#!/usr/bin/env python3
"""Host driver and command-line control for foxsinad_fw (docs/API.md).

The firmware speaks SCPI. This driver sends each command followed by
"SYST:ERR?" and "*OPC?" on lines of their own, in one write, so every
call is one round trip that both waits for the change to be made and
reports any error, and it leaves the firmware's error queue empty after
every call.

As a library:
    from foxctl import PicoFox
    with PicoFox() as fox:              # auto-detects the port, checks *IDN?
        fox.set_call("N0CALL")
        fox.set_freq(145.55)            # MHz
        fox.set_att(64)                 # 0.25 dB steps: 64 = 16 dB
        fox.tone(1000, 3000)
        ...
        fox.off()                       # sends the Morse ID first
    fox.write("FM:DEV 2.5kHz")          # any SCPI command
    fox.query("FREQ?")                  # any SCPI query

Over a link of your own (an Android USB connection, a socket, a test
double), pass it as transport=; then pyserial isn't needed:
    fox = PicoFox(transport=link)
The transport needs four methods, as pyserial's Serial has:
    write(data: bytes)
    readline() -> bytes     a line with its b"\n", or whatever arrived
                            before its own read timeout (b"" if nothing);
                            keep that timeout short (pyserial's is 0.5 s)
    reset_input_buffer()    discard anything unread
    close()

From the command line (./run picks a suitable Python; see README):
    ./run tools/foxctl.py --call N0CALL --freq 145.55 --att 64 --tone 1000 3000
    ./run tools/foxctl.py --off
    ./run tools/foxctl.py --status
    ./run tools/foxctl.py --cabled on --freq 145.55 --att 64 --tone 1000 3000
        # cabled mode: output into a load or test equipment only; no
        # callsign needed and no Morse ID (docs/API.md)
    ./run tools/foxctl.py --scpi "FREQ?" --scpi "FM:DEV 2.5kHz"

RF stays on after the command exits, but only for the firmware's host
timeout (60 s by default): use --hold to keep it on until Ctrl-C, or
--timeout 0 to leave it on. Turn it off with --off.
Requires pyserial, except as a library with a transport of your own.
"""

import argparse
import os
import re
import sys
import time

try:
    import serial
    from serial.tools import list_ports
except ImportError:  # fine with transport=, as on Android: only opening a port needs it
    serial = list_ports = None

RPI_VID = 0x2E8A  # Raspberry Pi; the Arduino-Pico rpipico board's USB vendor ID

FREQ_MIN_MHZ, FREQ_MAX_MHZ = 144.02, 147.98
TONE_MIN_HZ, TONE_MAX_HZ = 100, 2400
DEV_MIN_HZ, DEV_MAX_HZ = 100, 10000
CALLSIGN = re.compile(r"[A-Za-z0-9/]{1,11}")
ERROR_REPLY = re.compile(r'[+-]?\d+,"')  # a SYST:ERR? reply: <code>,"<text>"
OPER_ID_BIT = 1 << 8       # STATus:OPERation bit 8: Morse ID in progress
OPER_TIMEOUT_BIT = 1 << 9  # STATus:OPERation bit 9: output off by the host timeout


class PicoFoxError(RuntimeError):
    """An error from the firmware, a timeout, or an argument rejected
    before sending."""


def find_port():
    """The serial port with the Raspberry Pi USB vendor ID. Other RP2040
    boards (a Pico, a Debug Probe) have the same ID, so with more than one
    this refuses to guess."""
    if list_ports is None:
        raise PicoFoxError("finding the port needs pyserial: pip install pyserial")
    ports = sorted(p.device for p in list_ports.comports() if p.vid == RPI_VID)
    if len(ports) == 1:
        return ports[0]
    if ports:
        raise PicoFoxError(f"several Raspberry Pi USB serial devices ({', '.join(ports)}); "
                           "say which is the PicoFox with --port")
    raise PicoFoxError(
        "no Raspberry Pi USB serial device found - is the PicoFox plugged in "
        "and its power switch (SW3) on?")


def split_response(text):
    """Splits a SCPI response message at the ';' between query results,
    leaving ';' inside quoted strings alone."""
    parts, cur, quote = [], "", None
    for ch in text:
        if quote:
            quote = None if ch == quote else quote
        elif ch in "\"'":
            quote = ch
        elif ch == ";":
            parts.append(cur)
            cur = ""
            continue
        cur += ch
    parts.append(cur)
    return parts


class PicoFox:
    """Synchronous driver. Each method returns once the firmware has made
    the change, and raises PicoFoxError if the firmware reported an error.

    .events collects "*ID START" / "*ID DONE" (and "*TIMEOUT" when the
    firmware's host timeout turned the output off) strings, built from the
    STATus:OPERation registers each time status() runs, so callers can tell
    whether a Morse ID overlapped a measurement."""

    # A reply can wait for two Morse IDs back to back: one already being sent
    # when the command arrives, then one the command sends (up to 16 s each).
    def __init__(self, port=None, timeout_s=40.0, check=True, transport=None):
        self.port = port or ("transport" if transport is not None else find_port())
        self.timeout_s = timeout_s
        self.events = []
        self.idn = None
        self.revision = None
        self._id_open = False
        self._partial = b""
        if transport is not None:
            self._ser = transport
        else:
            if serial is None:
                raise PicoFoxError("opening a serial port needs pyserial: pip install pyserial")
            # Exclusive (POSIX only in pyserial): a second program on the port
            # would read this driver's replies.
            extra = {"exclusive": True} if os.name == "posix" else {}
            self._ser = serial.Serial(self.port, 115200, timeout=0.5, **extra)
            time.sleep(0.3)
        self._ser.reset_input_buffer()
        if check:
            try:
                self.hello()
                self.write("*CLS")       # an empty error queue, and no stale events
            except PicoFoxError:
                self.close()
                raise

    def close(self):
        self._ser.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    # -- SCPI ---------------------------------------------------------------
    def _readline(self, deadline):
        """The next non-empty line, or None at the deadline. pyserial's
        readline() returns what it has when its own timeout ends, so a line
        is only taken once its newline has arrived."""
        while time.time() < deadline:
            self._partial += self._ser.readline()
            if self._partial.endswith(b"\n"):
                raw, self._partial = self._partial.decode(errors="replace").strip(), b""
                if raw:
                    return raw
        return None

    def _exchange(self, line):
        """Sends one line and returns the response line."""
        return self._transact([line], lambda got: len(got) == 1)[0]

    def _transact(self, lines, done):
        """Sends lines in one write, then reads response lines until
        done(those read so far) is true, and returns them."""
        self._ser.reset_input_buffer()
        self._partial = b""
        self._ser.write("".join(f"{line}\n" for line in lines).encode())
        deadline = time.time() + self.timeout_s
        got = []
        while (raw := self._readline(deadline)) is not None:
            got.append(raw)
            if done(got):
                return got
        # The reply may still come, and would then be read as the answer to
        # the next command. *IDN? is answered after it, with a reply nothing
        # else gives: read up to that, and the replies line up again.
        self._ser.write(b"*IDN?\n")
        deadline = time.time() + self.timeout_s
        while (raw := self._readline(deadline)) is not None:
            if raw.startswith("FOXSINAD,"):
                raise PicoFoxError(f"{lines[0]!r}: no reply within {self.timeout_s} s. It may "
                                   "or may not have taken effect; check with status().")
        raise PicoFoxError(f"{lines[0]!r}: no reply, and the PicoFox doesn't answer *IDN? - "
                           "reconnect it")

    def _check(self, line, error):
        """Raises if error (a SYST:ERR? result) isn't 0, after reading the
        rest of the queue so it's left empty."""
        code, _, text = error.partition(",")
        try:
            code = int(code)
        except ValueError:
            raise PicoFoxError(f"{line!r}: unexpected reply {error!r}")
        if code == 0:
            return
        errors = [f"{code} {text.strip(chr(34))}"]
        for _ in range(10):
            more = self._exchange("SYST:ERR?")
            if more.startswith("0,"):
                break
            c, _, t = more.partition(",")
            errors.append(f"{c} {t.strip(chr(34))}")
        raise PicoFoxError(f"{line!r}: " + "; ".join(errors))

    def _call(self, message):
        """Sends message, then SYST:ERR? and *OPC? on lines of their own,
        and returns (its response line or None, the error reply). After a
        command error the firmware skips the rest of a line, so they can't
        share the message's; the reply to *OPC?, "1", after an error reply
        marks the end, whether or not the message replied."""
        got = self._transact([message, "SYST:ERR?", "*OPC?"],
                             lambda g: len(g) >= 2 and g[-1] == "1" and ERROR_REPLY.match(g[-2]))
        if len(got) > 3:
            raise PicoFoxError(f"{message!r}: unexpected replies {got[:-2]!r}")
        return (got[0] if len(got) == 3 else None), got[-2]

    def write(self, command):
        """Sends a SCPI command (or several, joined by ';') and checks it."""
        self._check(command, self._call(command)[1])

    def query(self, query):
        """Sends a SCPI query and returns its response, checked."""
        response, error = self._call(query)
        self._check(query, error)
        return response or ""

    # The firmware rejects bad values too, but checking here gives clearer
    # messages and catches mistakes before anything is sent.
    @staticmethod
    def _range(name, value, lo, hi):
        if not lo <= value <= hi:
            raise PicoFoxError(f"{name} {value} outside {lo}-{hi}")

    # -- the instrument -----------------------------------------------------
    def hello(self):
        """*IDN?: "FOXSINAD,PicoFox r3,0,<version>"."""
        idn = self._exchange("*IDN?")
        if idn.startswith(("OK ", "ERR ")):
            raise PicoFoxError(
                f"{self.port} runs foxsinad_fw older than 0.4.0 (text protocol, not SCPI). "
                "Flash the current firmware with tools/flash.py.")
        if not idn.startswith("FOXSINAD,"):
            raise PicoFoxError(f"{self.port} answered {idn!r}, not foxsinad_fw")
        self.idn = idn
        model = idn.split(",")[1]
        self.revision = model[-1] if model[-1].isdigit() else "0"
        return idn

    def set_call(self, callsign):
        if not CALLSIGN.fullmatch(callsign or ""):
            raise PicoFoxError(f"callsign {callsign!r}: 1-11 letters, digits or /")
        self.write(f'IDEN:CALL "{callsign.upper()}"')
        return callsign.upper()

    def set_cabled(self, on):
        """Cabled mode: output only into a load or test equipment, so no
        callsign is needed and no Morse ID is sent. The output must be off.
        The firmware clears it at every power-up."""
        if not isinstance(on, bool):
            raise PicoFoxError(f"cabled must be True or False, got {on!r}")
        self.write(f"OUTP:CABL {'ON' if on else 'OFF'}")

    def set_timeout(self, seconds):
        """The firmware's host timeout: the output turns off after this many
        seconds without a command. 0 = never. Resets to 60 s at power-up."""
        self._range("timeout (s)", int(seconds), 0, 3600)
        self.write(f"OUTP:TIM {int(seconds)}")

    def set_freq(self, mhz):
        self._range("FREQ (MHz)", float(mhz), FREQ_MIN_MHZ, FREQ_MAX_MHZ)
        self.write(f"FREQ {float(mhz):.6f}MHZ")

    def set_att(self, att):
        """Attenuator in 0.25 dB steps, 0-127; 0 is maximum output."""
        if int(att) != att:
            raise PicoFoxError(f"ATT must be a whole number of steps, got {att}")
        self._range("ATT", int(att), 0, 127)
        self.write(f"POW:ATT {int(att) * 0.25:.2f}")

    def tone(self, tone_hz, dev_hz):
        """FM tone on; dev_hz is the commanded peak deviation (docs/THEORY.md §4)."""
        self._range("tone (Hz)", int(tone_hz), TONE_MIN_HZ, TONE_MAX_HZ)
        self._range("deviation (Hz)", int(dev_hz), DEV_MIN_HZ, DEV_MAX_HZ)
        # Settings first, and RF on only once they've all succeeded: in SCPI a
        # failed command doesn't stop the rest of the line.
        self.write(f"FM:DEV {int(dev_hz)};:FM:INT:FREQ {int(tone_hz)};:FM:STAT ON")
        self.write("OUTP ON")

    def carrier(self):
        self.write("FM:STAT OFF")
        self.write("OUTP ON")

    def off(self):
        """Output off. Sends the Morse ID first if due, so may take several seconds."""
        self.write("OUTP OFF")

    def send_id(self):
        self.write("IDEN")

    STATUS_QUERY = ("OUTP?;FM:STAT?;:FREQ?;:FM:INT:FREQ?;:FM:DEV?;:IDEN:CALL?;"
                    ":OUTP:CABL?;:IDEN:TIME?;:SYST:UPT?;:STAT:OPER?;:STAT:OPER:COND?;"
                    ":OUTP:TIM?")

    def status(self):
        """The current state as a dict of strings: mode (OFF, CARRIER or
        TONE), freq (MHz), att (steps), tone, dev, call, rev, since_id, up,
        cabled, id_active, timeout. Also updates .events."""
        q = self.STATUS_QUERY + (";:POW:ATT?" if self.revision == "3" else "")
        v = split_response(self.query(q))
        if len(v) < 12:
            raise PicoFoxError(f"unexpected status reply {';'.join(v)!r}")
        outp, fm, freq, tone, dev, call, cabled, since_id, up, ev, cond, timeout = v[:12]
        active = bool(int(cond) & OPER_ID_BIT)
        if int(ev) & OPER_TIMEOUT_BIT:
            self.events.append("*TIMEOUT")
        if int(ev) & OPER_ID_BIT:                    # an ID started since the last read
            if self._id_open:
                self.events.append("*ID DONE")
            self.events.append("*ID START")
            self._id_open = True
        if self._id_open and not active:
            self.events.append("*ID DONE")
            self._id_open = False
        return dict(
            mode="OFF" if outp == "0" else ("TONE" if fm == "1" else "CARRIER"),
            freq=f"{float(freq) / 1e6:.6f}",
            att=str(round(float(v[12]) * 4)) if len(v) > 12 else "-",
            tone=tone, dev=dev, call=call.strip('"') or "-", rev=self.revision or "?",
            since_id=since_id, up=up, cabled=cabled, id_active="1" if active else "0",
            timeout=timeout)


def main():
    p = argparse.ArgumentParser(description="Control a PicoFox running foxsinad_fw.")
    p.add_argument("--port", help="serial port (default: auto-detect)")
    p.add_argument("--call", help="callsign (required before RF can turn on, unless cabled)")
    p.add_argument("--cabled", choices=["on", "off"],
                   help="cabled mode: output into a load or test equipment only "
                        "(no callsign needed, no Morse ID); RF must be off")
    p.add_argument("--freq", type=float, help=f"carrier, MHz ({FREQ_MIN_MHZ}-{FREQ_MAX_MHZ})")
    p.add_argument("--att", type=int, help="attenuator 0-127 (0.25 dB steps; 0 = max output)")
    p.add_argument("--tone", nargs=2, type=int, metavar=("HZ", "DEV_HZ"),
                   help="FM tone: audio frequency and peak deviation, Hz")
    p.add_argument("--carrier", action="store_true", help="unmodulated carrier on")
    p.add_argument("--off", action="store_true", help="RF off (sends the ID first)")
    p.add_argument("--timeout", type=int, metavar="S",
                   help="firmware host timeout: RF off after S s without a command "
                        "(default 60; 0 = never)")
    p.add_argument("--hold", action="store_true",
                   help="stay connected, keeping RF on, until Ctrl-C; then RF off")
    p.add_argument("--scpi", action="append", metavar="CMD",
                   help="send a SCPI command or query (repeatable; runs last)")
    p.add_argument("--status", action="store_true", help="print the state")
    args = p.parse_args()

    acted = False
    try:
        with PicoFox(args.port) as fox:
            if args.call:
                print("callsign", fox.set_call(args.call)); acted = True
            if args.cabled:
                fox.set_cabled(args.cabled == "on"); acted = True
                print("cabled", args.cabled)
            if args.timeout is not None:
                fox.set_timeout(args.timeout); acted = True
                print(f"host timeout {args.timeout} s" if args.timeout else "host timeout off")
            if args.freq is not None:
                fox.set_freq(args.freq); acted = True
                print(f"frequency {args.freq} MHz")
            if args.att is not None:
                fox.set_att(args.att); acted = True
                print(f"attenuator {args.att} ({args.att * 0.25:g} dB)")
            if args.tone:
                fox.tone(*args.tone); acted = True
                print(f"tone {args.tone[0]} Hz, deviation {args.tone[1]} Hz")
            if args.carrier:
                fox.carrier(); acted = True
                print("carrier on")
            if args.off:
                fox.off(); acted = True
                print("RF off")
            for cmd in args.scpi or []:
                acted = True
                if "?" in cmd:
                    print(fox.query(cmd))
                else:
                    fox.write(cmd)
                    print(f"{cmd}: ok")
            if args.status or not acted:
                for k, v in fox.status().items():
                    print(f"{k}={v}")
            if args.hold:
                print("holding: RF stays on until Ctrl-C")
                try:
                    while True:
                        st = fox.status()            # also keeps the host timeout from expiring
                        if st["mode"] == "OFF":
                            print("RF is off; done holding")
                            break
                        time.sleep(5)
                except KeyboardInterrupt:
                    print("\nRF off")
                    fox.off()
            elif fox.status()["mode"] != "OFF":
                t = fox.query("OUTP:TIM?")
                if t != "0":
                    print(f"note: RF turns off after {t} s without a command (firmware host "
                          "timeout); use --hold, or --timeout 0")
    except PicoFoxError as e:
        sys.exit(f"error: {e}")


if __name__ == "__main__":
    main()

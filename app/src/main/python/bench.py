"""The fox side of the sanity check, called from the Android audio thread:
foxctl (copied unchanged from foxsinad-firmware) over the Kotlin USB link."""

import foxctl

CALLSIGN = "KM6AM"  # the Morse ID; the firmware won't transmit without one (or cabled mode)

_fox = None


class Link:
    """foxctl's transport (pyserial's four methods) over the Kotlin UsbLink."""

    def __init__(self, usb):
        self._usb = usb

    def write(self, data):
        self._usb.write(data)

    def readline(self):
        return bytes(self._usb.readLine())

    def reset_input_buffer(self):
        self._usb.resetInput()

    def close(self):
        self._usb.close()


def _line(st):
    return (f"{st['mode']} {st['freq']} MHz att {st['att']} tone {st['tone']}/{st['dev']} "
            f"call {st['call']} since ID {st['since_id']} s")


def start(usb):
    """Opens the fox, sets the test signal and turns the output on."""
    global _fox
    _fox = foxctl.PicoFox(transport=Link(usb))
    _fox.set_call(CALLSIGN)
    _fox.set_freq(145.55)
    _fox.set_att(127)          # maximum attenuation, 31.75 dB
    _fox.tone(1000, 3000)
    return f"fox {_fox.idn}: " + _line(_fox.status())


def poll():
    """A status round trip: keeps the host timeout from firing, and reports
    any Morse ID that started since the last poll."""
    st = _fox.status()
    events, _fox.events[:] = list(_fox.events), []
    return "fox " + _line(st) + (f"  {' '.join(events)}" if events else "")


def stop():
    """Output off (the firmware sends the ID owed first, 4-10 s), then close."""
    global _fox
    if _fox is None:
        return "fox not open"
    try:
        _fox.off()
        return "fox " + _line(_fox.status())
    finally:
        _fox.close()
        _fox = None

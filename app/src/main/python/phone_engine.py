"""The bench Engine with the two things a phone does differently: the
PicoFox comes over Android's USB link (foxctl's transport=), and the audio
comes from AudioRecord, fed to a SinadAnalyzer(None). Everything else, the
modes, the safety rules and the messages, is foxsinad's, unchanged."""

import json
import os

import numpy as np

from foxsinad import picofox
from foxsinad.analyzer import SinadAnalyzer
from foxsinad.app.engine import CommandError, Engine


DUMP_BLOCKS = 8   # 2 s of 0.25 s blocks


class _Link:
    """foxctl's transport (pyserial's four methods) over Kotlin's UsbLink."""

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


class _FedAnalyzer:
    """A SinadAnalyzer(None) that Kotlin's AudioCapture feeds while it's
    open: recording runs only while a mode is measuring."""

    def __init__(self, capture, log, dump_path):
        self._an = SinadAnalyzer(None)
        self._capture = capture
        self._log = log
        self._dump_path = dump_path
        self._dump = []
        self._open = False

    def __enter__(self):
        self._an.__enter__()
        self._dump = []
        self._open = True
        self._log(str(self._capture.start(self)))   # raises if there's no USB audio input
        return self

    def __exit__(self, *exc):
        self._open = False
        self._capture.stop()       # doesn't wait: the capture thread may be inside feed()
        return self._an.__exit__(*exc)

    def feed(self, pcm, channels):
        """From the capture thread: 16-bit interleaved PCM; channel 0 is measured."""
        if self._open:
            raw = bytes(pcm)
            frames = np.frombuffer(raw, dtype="<i2").reshape(-1, channels)
            if len(self._dump) < DUMP_BLOCKS:
                self._first_blocks(raw, frames)
            self._an.feed(frames[:, 0] / 32768.0)

    def _first_blocks(self, raw, frames):
        """Diagnostics: the first block's level per channel on the page, and
        the first 2 s as raw PCM (48 kHz, 16-bit LE, interleaved) in files/."""
        if not self._dump:
            f = frames.astype(float) / 32768.0
            peak = 20 * np.log10(np.maximum(np.abs(f).max(axis=0), 1e-6))
            rms = 20 * np.log10(np.maximum(np.sqrt((f ** 2).mean(axis=0)), 1e-6))
            self._log("first audio block: " + ", ".join(
                f"ch{c} peak {p:.1f} rms {r:.1f} dBFS" for c, (p, r) in enumerate(zip(peak, rms))))
        self._dump.append(raw)
        if len(self._dump) == DUMP_BLOCKS:
            with open(self._dump_path, "wb") as fh:
                fh.write(b"".join(self._dump))

    def ended(self, why):
        """From the capture thread, when it stopped without being asked."""
        if self._open:
            self._log(f"audio capture ended: {why}", "error")

    def __getattr__(self, name):    # read(), fs, ... as the analyzer's
        return getattr(self._an, name)


class PhoneEngine(Engine):
    """Also keeps the Run setup across restarts (an app update, or Android
    stopping the app), all but cabled mode, which is confirmed each session."""

    def __init__(self, usb, capture, runs_dir):
        self._usb = usb
        self._capture = capture
        self._saved = None
        super().__init__(runs_dir=runs_dir)
        self._setup_file = os.path.join(os.path.dirname(str(self.runs_dir)), "setup.json")
        try:
            with open(self._setup_file) as fh:
                saved = json.load(fh)
            saved.pop("cabled", None)
            self.cmd_setup(**{k: v for k, v in saved.items() if k in self.setup})
        except FileNotFoundError:
            pass
        except Exception as e:      # a bad file costs the saved setup, nothing more
            self.log(f"saved setup not restored: {e}", "warn")

    def _state_changed(self):
        super()._state_changed()
        if getattr(self, "_setup_file", None) and self.setup != self._saved:
            self._saved = dict(self.setup)
            with open(self._setup_file, "w") as fh:
                json.dump(self.setup, fh)

    def _open_fox(self):
        link = self._usb.open()
        if link is None:
            raise CommandError("no PicoFox found on USB: plug it into the hub")
        return picofox.PicoFox(transport=_Link(link))

    def _open_analyzer(self):
        if self.setup["monitor"]:
            self.log("listening along isn't available on the phone yet", "warn")
        dump = os.path.join(os.path.dirname(str(self.runs_dir)), "last_capture.raw")
        return _FedAnalyzer(self._capture, self.log, dump), "USB audio (Android)"

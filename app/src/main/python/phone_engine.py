"""The bench Engine with the two things a phone does differently: the
PicoFox comes over Android's USB link (foxctl's transport=), and the audio
comes from AudioRecord, fed to a SinadAnalyzer(None). Everything else, the
modes, the safety rules and the messages, is foxsinad's, unchanged."""

import numpy as np

from foxsinad import picofox
from foxsinad.analyzer import SinadAnalyzer
from foxsinad.app.engine import CommandError, Engine


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

    def __init__(self, capture, log):
        self._an = SinadAnalyzer(None)
        self._capture = capture
        self._log = log
        self._open = False

    def __enter__(self):
        self._an.__enter__()
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
            x = np.frombuffer(bytes(pcm), dtype="<i2").reshape(-1, channels)[:, 0] / 32768.0
            self._an.feed(x)

    def __getattr__(self, name):    # read(), fs, ... as the analyzer's
        return getattr(self._an, name)


class PhoneEngine(Engine):
    def __init__(self, usb, capture, runs_dir):
        self._usb = usb
        self._capture = capture
        super().__init__(runs_dir=runs_dir)

    def _open_fox(self):
        link = self._usb.open()
        if link is None:
            raise CommandError("no PicoFox found on USB: plug it into the hub")
        return picofox.PicoFox(transport=_Link(link))

    def _open_analyzer(self):
        if self.setup["monitor"]:
            self.log("listening along isn't available on the phone yet", "warn")
        return _FedAnalyzer(self._capture, self.log), "USB audio (Android)"

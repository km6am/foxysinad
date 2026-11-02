# Foxy SINAD

Android field kit for the foxsinad desense measurements: a Pixel phone on a
USB hub drives the PicoFox (foxsinad_fw 0.4.0, SCPI over USB CDC) and reads
receiver audio from a USB sound card (Behringer UFO202).

Status: the app (`app/`) is foxsinad's bench page in a WebView, talking to
foxsinad's bench engine (Python, under Chaquopy: Python 3.10, numpy 1.23.3,
SciPy 1.8.1) through `window.FoxsinadBridge`, the transport the page already
has for Android. Build with `gradle assembleDebug`; it needs a Python 3.10 on
the Mac for Chaquopy (default `~/miniconda3/envs/chaq310`, or set
`FOXYSINAD_BUILD_PYTHON`).

No copies: the build takes the engine, analysis, calibration table and page
from the `~/foxsinad` checkout and the PicoFox driver (`tools/foxctl.py`) from
`~/foxsinad-firmware` (`-Pfoxsinad=` / `-PfoxsinadFirmware=` for others), and
records their commits in `foxsinad_build.py` (`+dirty` if uncommitted), which
the app logs at startup. The phone-only Python is `phone.py` (the bridge) and
`phone_engine.py`, an Engine subclass that opens the PicoFox over Android's
USB link and feeds the analyzer from AudioRecord.

- `spike/` - one-button test app: opens the PicoFox (`*IDN?`), sets it to
  145.550 MHz, maximum attenuation (POW:ATT 31.75) and a 1 kHz tone at 3 kHz
  deviation with callsign KM6AM, records 10 s from the USB audio input with
  the tone on, then turns the output off (which sends the Morse ID owed). `compare.py` scores the
  phone's recording against one made on the Mac (needs `foxsinad` importable).
- `icon/` - launcher icon source (fox with a radar dish), 108x108 adaptive canvas.

Depends on `~/foxsinad` (the Python engine, analyzer and UI page); see
foxsinad's CLAUDE.md for the API other repos rely on.

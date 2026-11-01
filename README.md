# Foxy SINAD

Android field kit for the foxsinad desense measurements: a Pixel phone on a
USB hub drives the PicoFox (foxsinad_fw 0.4.0, SCPI over USB CDC) and reads
receiver audio from a USB sound card (Behringer UFO202).

Status: feasibility spike only. The app design is in progress.

- `spike/` - one-button test app: opens the PicoFox (`*IDN?`), sets it to
  145.550 MHz, maximum attenuation (POW:ATT 31.75) and a 1 kHz tone at 3 kHz
  deviation with callsign KM6AM, records 10 s from the USB audio input with
  the tone on, then turns the output off (which sends the Morse ID owed). `compare.py` scores the
  phone's recording against one made on the Mac (needs `foxsinad` importable).
- `icon/` - launcher icon source (fox with a radar dish), 108x108 adaptive canvas.

Depends on `~/foxsinad` (the Python engine, analyzer and UI page); see
foxsinad's CLAUDE.md for the API other repos rely on.

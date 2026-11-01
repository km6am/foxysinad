"""The SINAD analysis, forked from foxsinad (foxsinad/analyzer.py at 19cc814)
with only its DSP: the sound-card capture stays behind, since on the phone the
audio comes from Android's AudioRecord. sinad_meter/ is a copy of
charlieh0tel/sinad_meter at 9a6fec3 (MIT, Christopher Hoover; its vendored
pysnr is MIT, Sambit Paul): filters.py, sinad.py and vendored/, unchanged.

Keep these functions the same as foxsinad's, so phone and bench readings
compare: SINAD is (S+N+D)/(N+D) after a 200-3000 Hz bandpass, per record.
"""

import math
import sys
from pathlib import Path

FORKED_FROM = "foxsinad 19cc814, sinad_meter 9a6fec3"

sys.path.insert(0, str(Path(__file__).resolve().parent / "sinad_meter"))
import filters  # noqa: E402  (sinad_meter's)
import sinad as _sinad  # noqa: E402  (sinad_meter's; returns radio-definition SINAD)

CLIP_LEVEL = 0.99
UNDER_RANGE_PEAK = 0.003  # below this the window is probably silence/cable trouble


def peak_dbfs(peak):
    return 20 * math.log10(max(peak, 1e-12))


def make_bandpass(fs, hpf_hz=200.0, lpf_hz=3000.0):
    return filters.make_audio_filter(fs, hpf_hz, lpf_hz)


def sinad_db(samples, fs, bandpass=None):
    """SINAD (dB, (S+N+D)/(N+D)) of one record; pure function for tests."""
    if bandpass is not None:
        bandpass.reset()
        samples = bandpass(samples)
    value, _ = _sinad.measure(samples, fs)
    return value

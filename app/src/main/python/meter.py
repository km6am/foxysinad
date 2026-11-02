"""Called from the Android audio thread: one 0.25 s block in, one text line
out. The sanity check that the analysis runs on the phone."""

import platform
import time

import numpy as np
import scipy

import analysis

_bandpass = {}


def versions():
    return (f"Python {platform.python_version()}, numpy {np.__version__}, "
            f"scipy {scipy.__version__}, {analysis.FORKED_FROM}")


def block(pcm, channels, fs):
    """pcm: 16-bit little-endian interleaved samples (a Java byte[]); channel 0 is measured."""
    t0 = time.perf_counter()
    x = np.frombuffer(bytes(pcm), dtype="<i2").reshape(-1, channels)[:, 0] / 32768.0
    if fs not in _bandpass:
        _bandpass[fs] = analysis.make_bandpass(fs)
    peak = float(np.abs(x).max())
    rms = float(np.sqrt(np.mean(x * x)))
    s = analysis.sinad_db(x, fs, _bandpass[fs])
    flags = ("  CLIPPED" if peak >= analysis.CLIP_LEVEL else "") + \
            ("  QUIET" if peak < analysis.UNDER_RANGE_PEAK else "")
    ms = (time.perf_counter() - t0) * 1000
    return (f"peak {analysis.peak_dbfs(peak):6.1f}  rms {analysis.peak_dbfs(rms):6.1f} dBFS  "
            f"SINAD {s:5.2f} dB  ({ms:.0f} ms){flags}")


def selftest(runs=20):
    """Times block() on a synthetic 0.25 s, 1 kHz tone with noise: the phone's
    own speed, without any hardware attached.

    Back to back, as here, the Pixel 10 Pro takes ~2 ms. At the live cadence,
    one call per 0.25 s, the same call took ~15 ms (measured 2026-11-01, same
    data, only the spacing changed): Android runs short periodic work on a
    slower, cheaper core. That is the phone saving power, not a cost in this
    code, and is still ~6% of the 250 ms budget."""
    t = np.arange(12000) / 48000
    x = 0.3 * np.sin(2 * np.pi * 1000 * t) + 0.003 * np.random.default_rng(0).normal(size=t.size)
    pcm = np.repeat((x * 32767).astype("<i2")[:, None], 2, axis=1).tobytes()
    times = []
    for _ in range(runs):
        t0 = time.perf_counter()
        line = block(pcm, 2, 48000)
        times.append((time.perf_counter() - t0) * 1000)
    times.sort()
    return (f"selftest: {line.split('(')[0].strip()}; block() median {times[len(times) // 2]:.1f} ms, "
            f"max {times[-1]:.1f} ms over {runs} (budget 250 ms)")

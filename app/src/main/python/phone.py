"""foxsinad on the phone: Kotlin's entry points into the bench engine.

foxsinad's own code (the engine, analyzer, sweep, cal table) is copied in
unchanged at build time (app/build.gradle.kts, recorded in foxsinad_build);
the page talks to the engine through window.FoxsinadBridge exactly as it
talks to the desktop server (docs/ui-protocol.md). phone_engine.py holds
the only phone-specific behavior.
"""

import json
import os
import platform
import queue
import threading
import time

_engine = None


def create(usb, capture, files_dir):
    """Makes the engine, once per process (it outlives the screen)."""
    global _engine
    if _engine is None:
        # foxsinad.picofox loads foxctl.py from here; Kotlin copied it out of the APK.
        os.environ["FOXSINAD_FIRMWARE"] = os.path.join(files_dir, "foxsinad-firmware")
        from phone_engine import PhoneEngine
        _engine = PhoneEngine(usb, capture, os.path.join(files_dir, "runs"))
        for line in (versions(), selftest()):
            print(line)             # logcat (python.stdout): no page is listening yet
            _engine.log(line)
    return _engine


def handle(cmd_json):
    """FoxsinadBridge.send(): one command as JSON in, the reply as JSON out."""
    try:
        cmd = json.loads(cmd_json)
        if not isinstance(cmd, dict):
            raise ValueError("a command is a JSON object")
    except ValueError as e:
        return json.dumps({"ok": False, "error": str(e)})
    return json.dumps(_engine.handle(cmd))


class _Pump:
    """Delivers the engine's events to one page as JSON (sink.event), from
    its own thread, until closed. Subscribing sends the snapshot first."""

    def __init__(self, sink):
        self._sink = sink
        self._q = _engine.subscribe()
        self._open = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self._open:
            try:
                msg = self._q.get(timeout=0.5)
            except queue.Empty:
                continue
            self._sink.event(json.dumps(msg))

    def close(self):
        self._open = False
        _engine.unsubscribe(self._q)


def attach(sink):
    return _Pump(sink)


def versions():
    import numpy as np
    import scipy
    import foxsinad_build as b
    return (f"phone: Python {platform.python_version()}, numpy {np.__version__}, "
            f"scipy {scipy.__version__}; foxsinad {b.FOXSINAD}, foxsinad-firmware {b.FIRMWARE}")


def selftest(runs=20):
    """Times foxsinad's SINAD of one synthetic 0.25 s window, back to back.
    At the live cadence (one window per 0.25 s) the Pixel 10 Pro measured
    ~7x slower (15 vs 2 ms, 2026-11-01): Android runs short periodic work on
    a slower core. Either is a few percent of the 250 ms budget."""
    import numpy as np
    from foxsinad.analyzer import make_bandpass, sinad_db
    t = np.arange(12000) / 48000
    x = (0.3 * np.sin(2 * np.pi * 1000 * t)
         + 0.003 * np.random.default_rng(0).normal(size=t.size)).astype(np.float32)
    bpf = make_bandpass(48000)
    times = []
    for _ in range(runs):
        t0 = time.perf_counter()
        s = sinad_db(x, 48000, bpf)
        times.append((time.perf_counter() - t0) * 1000)
    times.sort()
    return (f"phone selftest: SINAD {s:.2f} dB, {times[len(times) // 2]:.1f} ms median, "
            f"{times[-1]:.1f} ms max per window")

"""Judge the phone's capture against the Mac's, with foxsinad's own analyzer.

Feed the same steady 1 kHz test tone (a radio receiving the PicoFox, or any
clean source) into the UFO202, record it on the Mac, then on the phone, and
compare. Matching SINAD and level mean the phone passes the audio through
untouched; a phone SINAD well above the Mac's points to noise suppression,
a level that doesn't follow the source points to gain control.

  python compare.py --record mac.wav        # 10 s from the UFO202 on the Mac
  python compare.py mac.wav phone.wav       # SINAD and level of each

Windows, filter, clipping rule and median are those of
foxsinad.analyzer.SinadAnalyzer.read; the first 0.5 s is dropped as settling.
"""

import argparse
import wave

import numpy as np

from foxsinad.analyzer import CLIP_LEVEL, make_bandpass, pick_device, sinad_db


def load(path):
    with wave.open(str(path)) as w:
        fs, ch = w.getframerate(), w.getnchannels()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return fs, (x.astype(np.float32) / 32768).reshape(-1, ch)


def analyze(x, fs, settle_s=0.5, window_s=0.25):
    x = x[round(settle_s * fs):]
    n = round(window_s * fs)
    bpf = make_bandpass(fs)
    windows = [x[i:i + n] for i in range(0, len(x) - n + 1, n)]
    vals = [sinad_db(w, fs, bpf) for w in windows if np.abs(w).max() < CLIP_LEVEL]
    db = lambda v: 20 * np.log10(max(v, 1e-9))
    return {
        "sinad": float(np.median(vals)) if vals else float("nan"),
        "sd": float(np.std(vals)) if vals else float("nan"),
        "kept": f"{len(vals)}/{len(windows)}",
        "peak": db(float(np.abs(x).max())),
        "rms": db(float(np.sqrt(np.mean(x ** 2)))),
    }


def record(path, seconds, device):
    import sounddevice
    fs = 48000
    x = sounddevice.rec(seconds * fs, samplerate=fs, channels=2, dtype="int16",
                        device=pick_device(device), blocking=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(fs)
        w.writeframes(x.tobytes())


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("wavs", nargs="*")
    ap.add_argument("--record", metavar="WAV", help="record from the UFO202 first")
    ap.add_argument("--seconds", type=int, default=10)
    ap.add_argument("--device", help="input device name substring (default: auto)")
    args = ap.parse_args()
    if args.record:
        record(args.record, args.seconds, args.device)
        args.wavs.insert(0, args.record)
    for path in args.wavs:
        fs, x = load(path)
        for c in range(x.shape[1]):
            r = analyze(np.ascontiguousarray(x[:, c]), fs)
            print(f"{path} ch{c} {fs} Hz  SINAD {r['sinad']:.2f} dB (sd {r['sd']:.2f}, "
                  f"windows {r['kept']})  peak {r['peak']:.1f} dBFS  rms {r['rms']:.1f} dBFS")


if __name__ == "__main__":
    main()

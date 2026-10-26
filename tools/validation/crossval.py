"""Cross-validate the Sable engine against libebur128, FFmpeg's ebur128 filter and pyloudnorm. MATH §26.

Each file is decoded ONCE with FFmpeg to 32-bit float WAV (native rate/channels, gapless trims applied);
every meter then measures that same PCM, so differences come from the meters, not the decoders.

Usage: py -3 tools/validation/crossval.py [--per-album N] [--out report.csv] [corpus_dir]
Requires: sable_cli built at engine/build-host, ffmpeg on PATH, libebur128 DLL (see --ebur128).
"""
import argparse
import csv
import ctypes
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time

import numpy as np
import pyloudnorm
import soundfile as sf

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLI = os.path.join(REPO, "engine", "build-host", "sable_cli.exe" if os.name == "nt" else "sable_cli")

# libebur128 constants (ebur128.h): M=1, S=(1<<1)|M, I=(1<<2)|M, LRA=(1<<3)|S, SAMPLE_PEAK=(1<<4)|M, TRUE_PEAK=(1<<5)|M|SAMPLE_PEAK
EBUR128_MODE_I = (1 << 2) | 1
EBUR128_MODE_LRA = (1 << 3) | (1 << 1) | 1
EBUR128_MODE_TRUE_PEAK = (1 << 5) | (1 << 4) | 1
EBUR128_DUAL_MONO = 6  # enum channel: ..., Mm110 = 5, DUAL_MONO


def load_ebur128(path):
    lib = ctypes.CDLL(path)
    lib.ebur128_init.restype = ctypes.c_void_p
    lib.ebur128_init.argtypes = [ctypes.c_uint, ctypes.c_ulong, ctypes.c_int]
    lib.ebur128_add_frames_float.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float), ctypes.c_size_t]
    lib.ebur128_loudness_global.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_double)]
    lib.ebur128_loudness_range.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_double)]
    lib.ebur128_true_peak.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.POINTER(ctypes.c_double)]
    lib.ebur128_set_channel.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int]
    lib.ebur128_destroy.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
    return lib


def meter_libebur128(lib, wav):
    info = sf.info(wav)
    st = ctypes.c_void_p(lib.ebur128_init(info.channels, info.samplerate, EBUR128_MODE_I | EBUR128_MODE_LRA | EBUR128_MODE_TRUE_PEAK))
    if info.channels == 1:
        lib.ebur128_set_channel(st, 0, EBUR128_DUAL_MONO)
    for block in sf.blocks(wav, blocksize=65536, dtype="float32", always_2d=True):
        buf = np.ascontiguousarray(block)
        lib.ebur128_add_frames_float(st, buf.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), buf.shape[0])
    i, lra = ctypes.c_double(), ctypes.c_double()
    lib.ebur128_loudness_global(st, ctypes.byref(i))
    lib.ebur128_loudness_range(st, ctypes.byref(lra))
    tp = 0.0
    for c in range(info.channels):
        v = ctypes.c_double()
        lib.ebur128_true_peak(st, c, ctypes.byref(v))
        tp = max(tp, v.value)
    lib.ebur128_destroy(ctypes.byref(st))
    return i.value, lra.value, (20 * np.log10(tp) if tp > 0 else None)


def meter_ffmpeg(wav, channels):
    af = "ebur128=peak=true" + (":dualmono=true" if channels == 1 else "")
    p = subprocess.run(["ffmpeg", "-nostats", "-hide_banner", "-i", wav, "-af", af, "-f", "null", "-"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    summary = p.stderr[p.stderr.rfind("Summary:"):]
    i = float(re.search(r"I:\s+(-?[\d.]+) LUFS", summary).group(1))
    lra = float(re.search(r"LRA:\s+(-?[\d.]+) LU", summary).group(1))
    m = re.search(r"Peak:\s+(-?[\d.inf]+) dBFS", summary)
    tp = float(m.group(1)) if m and "inf" not in m.group(1) else None
    return i, lra, tp


def meter_pyloudnorm(wav, channels):
    data, fs = sf.read(wav, dtype="float64", always_2d=True)
    v = pyloudnorm.Meter(fs).integrated_loudness(data if channels > 1 else data[:, 0])
    return v + (10 * np.log10(2.0) if channels == 1 else 0.0)  # pyloudnorm: mono = single channel (MATH §1.2)


def meter_sable(wav):
    t0 = time.perf_counter()
    out = subprocess.run([CLI, wav], capture_output=True, text=True, check=True).stdout
    return json.loads(out), time.perf_counter() - t0


def pick_files(root, per_album):
    picked = []
    for dirpath, _, files in os.walk(root):
        audio = sorted(f for f in files if f.lower().endswith((".flac", ".mp3", ".m4a", ".ogg", ".opus", ".wav")))
        if not audio or os.sep + "ebu" in dirpath:
            continue
        take = audio if "free" in dirpath.replace("\\", "/").split("/") else audio[:per_album]
        picked += [os.path.join(dirpath, f) for f in take]
    return sorted(picked)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus", nargs="?", default=os.path.expanduser("~/dev/sable-corpus"))
    ap.add_argument("--per-album", type=int, default=2)
    ap.add_argument("--ebur128", default=os.path.expanduser("~/dev/sable-refs/libebur128/ebur128.dll"))
    ap.add_argument("--out", default=os.path.expanduser("~/dev/sable-corpus/reports/crossval.csv"))
    a = ap.parse_args()
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    lib = load_ebur128(a.ebur128)
    files = pick_files(a.corpus, a.per_album)
    rows = []
    tmpdir = tempfile.mkdtemp(prefix="sable-xv-")
    for n, path in enumerate(files, 1):
        wav = os.path.join(tmpdir, "x.wav")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-map", "0:a:0", "-c:a", "pcm_f32le", wav], check=True)
        info = sf.info(wav)
        s, secs = meter_sable(wav)
        li, llra, ltp = meter_libebur128(lib, wav)
        fi, flra, ftp = meter_ffmpeg(wav, info.channels)
        pi = meter_pyloudnorm(wav, info.channels)
        row = {
            "file": os.path.relpath(path, a.corpus).replace("\\", "/"), "fs": info.samplerate, "ch": info.channels,
            "dur_s": round(info.duration, 2), "sable_I": s["integratedLufs"], "sable_LRA": s["lraLu"], "sable_TP": s["truePeakDbtp"],
            "lib_I": li, "lib_LRA": llra, "lib_TP": ltp, "ff_I": fi, "ff_LRA": flra, "ff_TP": ftp, "pyln_I": pi,
            "sable_sec": round(secs, 3),
        }
        rows.append(row)
        print(f"[{n}/{len(files)}] dI(lib)={row['sable_I'] - li:+.4f} dI(ff)={row['sable_I'] - fi:+.3f} "
              f"dI(pyln)={row['sable_I'] - pi:+.4f} dLRA(lib)={row['sable_LRA'] - llra:+.2f} "
              f"dTP(lib)={(row['sable_TP'] - ltp) if ltp else 0:+.3f}  {row['file']}", flush=True)
    os.remove(os.path.join(tmpdir, "x.wav"))
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    def stats(key_a, key_b, tol):
        d = [r[key_a] - r[key_b] for r in rows if r[key_a] is not None and r[key_b] is not None]
        over = sum(abs(x) > tol for x in d)
        return f"mean {statistics.mean(d):+.4f}, max |d| {max(abs(x) for x in d):.4f}, over {tol}: {over}/{len(d)}"

    print("\nSUMMARY over", len(rows), "files,", round(sum(r["dur_s"] for r in rows) / 3600, 2), "h")
    print("I    vs libebur128:", stats("sable_I", "lib_I", 0.1))
    print("I    vs ffmpeg    :", stats("sable_I", "ff_I", 0.1))
    print("I    vs pyloudnorm:", stats("sable_I", "pyln_I", 0.1))
    print("LRA  vs libebur128:", stats("sable_LRA", "lib_LRA", 0.2))
    print("LRA  vs ffmpeg    :", stats("sable_LRA", "ff_LRA", 0.2))
    print("TP   vs libebur128:", stats("sable_TP", "lib_TP", 0.3))
    print("TP   vs ffmpeg    :", stats("sable_TP", "ff_TP", 0.3))
    speed = sum(r["dur_s"] for r in rows) / sum(r["sable_sec"] for r in rows)
    print(f"Sable host speed: {speed:.0f}x realtime (incl. process start + WAV read)")


if __name__ == "__main__":
    main()

"""MATH §10.2 / §11.1: validate Sable's C++ TVL reference against the authors' MATLAB TVL 2018 code (run in
GNU Octave), and report the fast model's error against the reference.

12 signals at 32 kHz stereo (so no resampler differences): tones, noises, AM tone, dichotic sounds and
three music excerpts. Criteria: reference vs Octave |dLTL|/LTL <= 1% frame-wise after the first 100 ms.

Usage: py -3 tools/perceptual/validate_reference.py [--octave PATH] [--corpus DIR]
"""
import argparse
import glob
import os
import subprocess
import tempfile

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TVL = os.path.expanduser("~/dev/sable-refs/tvl2018/src/TVL 2018/tv2018")
FIR = os.path.expanduser("~/dev/sable-refs/tvl2018/ff_32000.f64")
TOOL = os.path.join(REPO, "engine", "build-host", "sable_perceptual.exe")
FS = 32000
DCAL = 89.0


def spl_amp(spl):  # peak amplitude of a sine with RMS SPL `spl` under D_cal
    return 10 ** ((spl - DCAL) / 20)


def signals(corpus):
    t = np.arange(int(1.2 * FS)) / FS
    rng = np.random.default_rng(1234)
    sig = {}
    for name, f, spl in [("tone1k_40", 1000, 40), ("tone1k_70", 1000, 70), ("tone100_70", 100, 70), ("tone3k_60", 3000, 60)]:
        x = spl_amp(spl) * np.sin(2 * np.pi * f * t)
        sig[name] = np.stack([x, x], 1)
    w = rng.standard_normal(len(t)) * 10 ** ((65 - DCAL) / 20) / np.sqrt(2)
    sig["white_65"] = np.stack([w, w], 1)
    spec = np.fft.rfft(rng.standard_normal(len(t)))
    spec[1:] /= np.sqrt(np.arange(1, len(spec)))
    p = np.fft.irfft(spec, len(t))
    p *= 10 ** ((60 - DCAL) / 20) / np.sqrt(2) / np.sqrt(np.mean(p ** 2))
    sig["pink_60"] = np.stack([p, p], 1)
    am = spl_amp(70) * (1 + np.sin(2 * np.pi * 4 * t)) / 2 * np.sin(2 * np.pi * 1000 * t)
    sig["am4hz_70"] = np.stack([am, am], 1)
    x = spl_amp(60) * np.sin(2 * np.pi * 1000 * t)
    sig["left_only_60"] = np.stack([x, 0 * x], 1)
    sig["dichotic_1k_2k"] = np.stack([x, spl_amp(60) * np.sin(2 * np.pi * 2000 * t)], 1)
    picks = [
        "free/nine_inch_nails_the_slip/04_discipline.flac",
        "free/OpenGoldbergVariations/*01 Aria.flac",
        "free/MusopenCollectionAsFlac/*Vltava.flac",
    ]
    for i, pat in enumerate(picks):
        src = glob.glob(os.path.join(corpus, pat))[0]
        raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", "60", "-t", "1.5", "-i", src, "-ac", "2", "-ar", str(FS),
                              "-f", "f32le", "-"], capture_output=True, check=True).stdout
        m = np.frombuffer(raw, dtype="<f4").reshape(-1, 2).astype(np.float64)
        m *= 10 ** ((-23 - (-14)) / 20)  # play the excerpt ~9 dB below its mastering level (typical listening)
        sig[f"music{i + 1}"] = m
    return sig


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--octave", default=os.path.expanduser("~/dev/sable-toolchain/octave/octave-9.2.0-w64/mingw64/bin/octave-cli.exe"))
    ap.add_argument("--corpus", default=os.path.expanduser("~/dev/sable-corpus"))
    a = ap.parse_args()
    tmp = tempfile.mkdtemp(prefix="sable-tvl-")
    shim = os.path.join(REPO, "tools", "perceptual", "octave")
    print(f"{'signal':16s} {'frames':>6s} {'ref/oct max|d|':>14s} {'ref/oct mean':>12s} {'fast/ref max':>12s} {'fast/ref mean':>13s}")
    worst = 0.0
    for name, x in signals(a.corpus).items():
        f32 = os.path.join(tmp, name + ".f32")
        x.astype("<f4").tofile(f32)
        oct_out, ref_out, fast_out = (os.path.join(tmp, f"{name}.{k}.txt") for k in ("oct", "ref", "fast"))
        cmd = f"addpath('{shim.replace(os.sep, '/')}'); run_tvl2018('{TVL.replace(os.sep, '/')}', '{f32.replace(os.sep, '/')}', '{oct_out.replace(os.sep, '/')}', {DCAL});"
        subprocess.run([a.octave, "--no-gui", "--quiet", "--eval", cmd], check=True)
        subprocess.run([TOOL, f32, str(FS), FIR, ref_out, fast_out], check=True)
        o, r, fz = (np.loadtxt(p) for p in (oct_out, ref_out, fast_out))
        n = min(len(o), len(r))
        sl = slice(100, n)  # after the first 100 ms
        rel = np.abs(r[sl] - o[sl]) / np.maximum(o[sl], 1e-3)
        # fast frames are 4 ms: compare with the reference sampled at the same instants
        rf = r[::4][:len(fz)]
        k = min(len(rf), len(fz))
        relf = np.abs(fz[25:k] - rf[25:k]) / np.maximum(rf[25:k], 1e-3)
        worst = max(worst, float(rel.max()))
        print(f"{name:16s} {n:6d} {rel.max():14.5f} {rel.mean():12.5f} {relf.max():12.4f} {relf.mean():13.4f}", flush=True)
    print(f"\nreference vs authors' code: worst frame-wise relative error {worst:.5f} -> {'PASS' if worst <= 0.01 else 'FAIL'} (<= 1%)")


if __name__ == "__main__":
    main()

"""On-device verification for Milestone 2 (MATH §25, §26):
  1. run the engine test suite on the phone (ARM64),
  2. analyze real tracks on host and phone and require byte-identical JSON (with full series),
  3. benchmark a ~4-minute track on the big (A78) and little (A55) cores.

Usage: py -3 tools/validation/device_check.py [--corpus DIR]
Requires: adb on PATH with one device attached; engine built in engine/build-host and engine/build-android.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HOST_CLI = os.path.join(REPO, "engine", "build-host", "sable_cli.exe" if os.name == "nt" else "sable_cli")
DEV_DIR = "/data/local/tmp/sable"

FIXTURES = [  # (corpus-relative path pattern, why)
    ("free/nine_inch_nails_the_slip/04_discipline.flac", "44.1k/16 FLAC, dense"),
    ("free/OpenGoldbergVariations", "96k/24 FLAC, quiet piano"),
    ("free/stories_006_librivox", "mono MP3 speech"),
    ("free/NineInchNailsGhostsI-Iv24bit48khz", "48k/24 ALAC"),
    ("free/MusopenCollectionAsFlac", "48k/24 orchestral, wide dynamics"),
]


def adb(*args, check=True, text=True):
    return subprocess.run(["adb", *args], capture_output=True, text=text, check=check)


def first_audio(path):
    if os.path.isfile(path):
        return path
    for f in sorted(os.listdir(path)):
        if f.lower().endswith((".flac", ".mp3", ".m4a")):
            return os.path.join(path, f)
    raise FileNotFoundError(path)


def decode(src, dst):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-map", "0:a:0", "-c:a", "pcm_f32le", dst], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default=os.path.expanduser("~/dev/sable-corpus"))
    a = ap.parse_args()

    devs = [l for l in adb("devices").stdout.splitlines()[1:] if l.strip().endswith("device")]
    if len(devs) != 1:
        sys.exit(f"need exactly one authorized device, found: {devs}")
    model = adb("shell", "getprop", "ro.product.model").stdout.strip()
    sdk = adb("shell", "getprop", "ro.build.version.sdk").stdout.strip()
    print(f"device: {model}, SDK {sdk}")

    adb("shell", "mkdir", "-p", DEV_DIR)
    for b in ("sable_tests", "sable_cli"):
        adb("push", os.path.join(REPO, "engine", "build-android", b), f"{DEV_DIR}/{b}")
        adb("shell", "chmod", "755", f"{DEV_DIR}/{b}")

    print("\n== 1. test suite on device")
    t = adb("shell", f"cd {DEV_DIR} && ./sable_tests", check=False)
    tail = [l for l in t.stdout.splitlines() if "test cases" in l or "assertions" in l or "ERROR" in l]
    print("\n".join(tail))
    suite_ok = t.returncode == 0

    print("\n== 2. host vs device byte-identical analysis")
    tmp = tempfile.mkdtemp(prefix="sable-dev-")
    identical = True
    bench_wav = None
    for rel, why in FIXTURES:
        src = first_audio(os.path.join(a.corpus, rel))
        wav = os.path.join(tmp, "fx.wav")
        decode(src, wav)
        host = subprocess.run([HOST_CLI, "--series", wav], capture_output=True, text=True, check=True).stdout.strip()
        adb("push", wav, f"{DEV_DIR}/fx.wav")
        dev = adb("shell", f"{DEV_DIR}/sable_cli --series {DEV_DIR}/fx.wav").stdout.strip()
        same = host == dev
        identical &= same
        r = json.loads(host)
        print(f"{'IDENTICAL' if same else 'DIFFERENT'}  I={r['integratedLufs']:.3f} LRA={r['lraLu']:.2f} TP={r['truePeakDbtp']:.3f}  ({why})")
        if not same:
            hd, dd = json.loads(host), json.loads(dev)
            for k in hd:
                if hd[k] != dd.get(k):
                    print(f"   {k}: host={str(hd[k])[:80]} device={str(dd.get(k))[:80]}")
        if "the_slip" in rel:
            bench_wav = src

    print("\n== 3. benchmark (engine analysis of a decoded track; decode is benchmarked in M4)")
    wav = os.path.join(tmp, "bench.wav")
    decode(bench_wav, wav)
    dur = json.loads(subprocess.run([HOST_CLI, wav], capture_output=True, text=True, check=True).stdout)["frames"] / 44100
    adb("push", wav, f"{DEV_DIR}/bench.wav")
    ncpu = int(adb("shell", "nproc").stdout.strip())
    caps = [int(adb("shell", f"cat /sys/devices/system/cpu/cpu{c}/cpu_capacity", check=False).stdout.strip() or 0) for c in range(ncpu)]
    big = max(range(ncpu), key=lambda c: caps[c])
    little = min(range(ncpu), key=lambda c: caps[c])
    for label, cpu in (("big core", big), ("little core", little)):
        mask = format(1 << cpu, "x")
        times = []
        for _ in range(3):
            # Shell `time` (toybox/mksh) is reliable on device; `date +%s%N` is not.
            out = adb("shell", f"time taskset {mask} {DEV_DIR}/sable_cli {DEV_DIR}/bench.wav >/dev/null", check=False)
            m = re.search(r"(\d+)m([\d.]+)s real", out.stdout + out.stderr)
            times.append(int(m.group(1)) * 60 + float(m.group(2)))
        best = min(times)
        print(f"{label} (cpu{cpu}, capacity {caps[cpu]}): {dur:.0f} s track in {best:.2f} s  ({dur / best:.0f}x realtime; runs {times})")
    adb("shell", "rm", "-f", f"{DEV_DIR}/fx.wav", f"{DEV_DIR}/bench.wav")

    print(f"\nRESULT: suite {'PASS' if suite_ok else 'FAIL'}, host/device {'IDENTICAL' if identical else 'DIFFERENT'}")
    sys.exit(0 if suite_ok and identical else 1)


if __name__ == "__main__":
    main()

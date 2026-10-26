"""M3 corpus report: run the full two-pass pipeline (sable_cli --decide) over the corpus and summarize anchors,
characters, PLE - L_anchor deviations and gains vs plain -14 LUFS integrated normalization.

Usage: py -3 tools/validation/corpus_report.py [--per-album N] [--out CSV] [corpus_dir]
"""
import argparse
import csv
import json
import os
import statistics
import subprocess
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLI = os.path.join(REPO, "engine", "build-host", "sable_cli.exe" if os.name == "nt" else "sable_cli")


def pick(root, per_album):
    out = []
    for dirpath, _, files in os.walk(root):
        audio = sorted(f for f in files if f.lower().endswith((".flac", ".mp3", ".m4a")))
        if not audio or "golden" in dirpath or os.sep + "ebu" in dirpath:
            continue
        free = "free" in dirpath.replace("\\", "/").split("/")
        out += [os.path.join(dirpath, f) for f in (audio[:3] if free else audio[:per_album])]
    return sorted(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus", nargs="?", default=os.path.expanduser("~/dev/sable-corpus"))
    ap.add_argument("--per-album", type=int, default=1)
    ap.add_argument("--out", default=os.path.expanduser("~/dev/sable-corpus/reports/m3_corpus.csv"))
    a = ap.parse_args()
    rows = []
    wav = os.path.join(tempfile.mkdtemp(prefix="sable-m3-"), "x.wav")
    files = pick(a.corpus, a.per_album)
    for i, path in enumerate(files, 1):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-map", "0:a:0", "-c:a", "pcm_f32le", wav], check=True)
        d = json.loads(subprocess.run([CLI, "--decide", wav], capture_output=True, text=True, check=True).stdout)
        if d["integratedLufs"] is None:
            continue
        plain = -14.0 - d["integratedLufs"]
        row = {
            "file": os.path.relpath(path, a.corpus).replace("\\", "/"),
            "I": d["integratedLufs"], "anchor": d["anchorLufs"], "ple": d["anchorPle"],
            "ple_minus_anchor": (d["anchorPle"] - d["anchorLufs"]) if d["anchorPle"] is not None else None,
            "anchor_frac": d["anchorSec"] / max(d["nonSilentSec"], 1e-9), "fallback": d["anchorFallback"],
            "character": d["character"], "lra": d["lraLu"], "tp": d["truePeakDbtp"],
            "gain_sable": d["standard"]["gainDb"], "gain_plain14": min(plain, -1.0 - d["truePeakDbtp"]),
            "rules": "+".join(r["id"] for r in d["standard"]["rules"]), "refined": d["refined"],
        }
        rows.append(row)
        print(f"[{i}/{len(files)}] {row['character']:20s} I={row['I']:7.2f} anchor={row['anchor']:7.2f} "
              f"PLE-anchor={row['ple_minus_anchor'] if row['ple_minus_anchor'] is None else round(row['ple_minus_anchor'], 2)!s:>6} "
              f"gain {row['gain_sable']:+6.2f} vs plain {row['gain_plain14']:+6.2f}  {row['file'][-60:]}", flush=True)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    dev = [r["ple_minus_anchor"] for r in rows if r["ple_minus_anchor"] is not None]
    print(f"\n{len(rows)} tracks")
    print(f"PLE - L_anchor: median {statistics.median(dev):+.2f}, mean {statistics.mean(dev):+.2f}, "
          f"sd {statistics.pstdev(dev):.2f}, min {min(dev):+.2f}, max {max(dev):+.2f}")
    print("characters:", {c: sum(r["character"] == c for r in rows) for c in sorted(set(r["character"] for r in rows))})
    print("anchor fraction: median", round(statistics.median(r["anchor_frac"] for r in rows), 2),
          "fallbacks", sum(r["fallback"] for r in rows), " refined", sum(r["refined"] for r in rows))


if __name__ == "__main__":
    main()

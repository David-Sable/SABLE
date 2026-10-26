"""Catalog the test corpus with ffprobe.

Writes <corpus>/manifest.json (one entry per audio file: path, codec, sample rate,
bit depth, channels, duration, tags, content hash) and prints a summary table.
The corpus itself lives outside the repo; only this script is versioned.

Usage: py -3 tools/validation/catalog_corpus.py [corpus_dir]
"""
import hashlib
import json
import os
import subprocess
import sys

AUDIO_EXT = (".flac", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav")


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path],
        capture_output=True, text=True, encoding="utf-8", check=True).stdout
    info = json.loads(out)
    a = next(s for s in info["streams"] if s["codec_type"] == "audio")
    tags = {k.lower(): v for k, v in info["format"].get("tags", {}).items()}
    bits = a.get("bits_per_raw_sample") or a.get("bits_per_sample") or 0
    return {
        "codec": a["codec_name"],
        "sampleRateHz": int(a["sample_rate"]),
        "bitDepth": int(bits) if str(bits).isdigit() else 0,
        "channels": int(a["channels"]),
        "channelLayout": a.get("channel_layout", ""),
        "durationSec": float(info["format"].get("duration", 0.0)),
        "artist": tags.get("artist", ""),
        "album": tags.get("album", ""),
        "title": tags.get("title", ""),
        "track": tags.get("track", ""),
    }


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/dev/sable-corpus"))
    entries = []
    for dirpath, _, files in os.walk(root):
        for name in sorted(files):
            if not name.lower().endswith(AUDIO_EXT):
                continue
            path = os.path.join(dirpath, name)
            e = {"path": os.path.relpath(path, root).replace("\\", "/"), "bytes": os.path.getsize(path)}
            try:
                e.update(probe(path))
            except (subprocess.CalledProcessError, StopIteration) as err:
                e["error"] = str(err)
            e["sha256"] = sha256(path)
            entries.append(e)
    entries.sort(key=lambda e: e["path"])
    with open(os.path.join(root, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump({"root": root, "files": entries}, f, indent=1, ensure_ascii=False)

    groups = {}
    for e in entries:
        key = e["path"].split("/")[0] + "/" + e["path"].split("/")[1] if e["path"].count("/") >= 2 else e["path"].split("/")[0]
        g = groups.setdefault(key, {"n": 0, "sec": 0.0, "fmt": set()})
        g["n"] += 1
        g["sec"] += e.get("durationSec", 0.0)
        g["fmt"].add(f"{e.get('codec', '?')} {e.get('sampleRateHz', 0) / 1000:g}k/{e.get('bitDepth', 0)}b/{e.get('channels', 0)}ch")
    for k in sorted(groups):
        g = groups[k]
        print(f"{g['n']:3d} files {g['sec'] / 60:6.1f} min  {', '.join(sorted(g['fmt']))}  {k}")
    print(f"TOTAL {len(entries)} files, {sum(e.get('durationSec', 0) for e in entries) / 3600:.2f} h")


if __name__ == "__main__":
    main()

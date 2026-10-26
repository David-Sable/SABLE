# Milestone 4 report: library, cache, JIT (2026-10-26)

Device: Galaxy A17 5G (Exynos 1330, Android 16). Engine MATH 1.10, release-optimized native build.

## Exit criteria
| Criterion | Result | |
|---|---|---|
| Content-hash cache: rename/move doesn't trigger a rescan | Renamed file reused its analysis in 2 ms (vs 4.4 s to analyse) | ✅ |
| WorkManager scan, charging-only by default | Foreground worker (`mediaProcessing`); "While charging" is the default, "Analyse now" is the override; `cancel` command works | ✅ |
| Measured JIT latency (budget 1.5 s, MATH §22.2) | FLAC 0.31 s, MP3 0.37 s (minimp3; was 4.4 s via MediaCodec) | ✅ |
| Analysis budget ≤ 10 s per 4 min (MATH §22.3) | Library average **5.75 s per 4 min** including decode | ✅ |

## Full library scan
- 395 tracks (392 FLAC, 3 MP3), 1226 min of audio. All 395 analysed, 395 unique content hashes.
- The last 382 tracks took 36 min of wall time in the background worker. A typical FLAC took hash 0.5–1.4 s plus
  analysis 3.3–9.0 s; the slowest was a 6:56 track at 9.0 s. Tracks ran back to back on a big core (CPU 6/7).
- `decodeClipped` = 0: every file was decoded in-engine as floats (FLAC with dr_flac, MP3 with minimp3), so no
  analysis is affected by 16-bit decoder clipping.
- Character labels: sustained and dense 198, dynamic arc 97, steady 90, build and release 10.
- The 3 MP3s were first analysed through MediaCodec, which clips overs. They were re-analysed with minimp3
  (`ensure --es mime audio/mpeg --ez force true`). *Brainrot*: I = −8.0954 LUFS, matching the host.

## Notes
- The "worst" figure in `stats` (31 s per 4 min) comes from the forced MP3 re-runs. These ran from the debug screen
  without the ADPF boost and landed on little cores (CPU 2/3), taking ~17 s each. Two overlapping debug benches took
  30–87 s. Analysis that matters to the listener (the scan worker, and in M5 the playback-time JIT) runs with the
  boost on a big core. M5 must keep it that way.
- The earlier "18 s per track wall time" was not reproduced in the full scan (about 5 s per track back to back).

## Bugs found and fixed in M4
1. The A17's default FLAC decoder labels 16-bit output as float. The decoder now measures the sample width and
   prefers `c2.android.*`.
2. Android's FLAC pipeline stamps buffers with pts 0 after 25.6 s on some files, losing 17% of the audio. Fixed by
   decoding FLAC in-engine with dr_flac (bit-identical to FFmpeg).
3. The debug build compiled the engine at -O0 (about 6x slower). The engine is now always built optimized.
4. MediaCodec MP3 was slow (68 s for 2:17) and clipped overs. Fixed by decoding MP3 in-engine with minimp3 (3.5 s;
   host and device byte-identical).

## Carried to M5
- M_clipTP for formats still decoded by MediaCodec (AAC/Opus/Vorbis/ALAC) whose decoder outputs int16 (MATH §1.1).

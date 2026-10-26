# Milestone 4: progress (2026-10-26, paused overnight)

## Done and verified on the Galaxy A17 (Android 16)
- **Library:** MediaStore sync finds all 395 tracks (392 FLAC, 3 MP3).
- **FLAC analysis in-engine (dr_flac):** *Super Shy* (2:35): 3.95 s including decode (about 6.1 s per 4 min). The
  value -8.193058492695096 LUFS is identical to the host and to FFmpeg.
- **JIT:** 0.31 s (FLAC) against a 1.5 s budget. MP3 via MediaCodec: 4.4 s (over budget; only 3 MP3s on the phone).
- **Cache identity:** renaming a file keeps its content hash, so the analysis is reused (2 ms instead of 4.4 s).
- **Partial scan (12/395, paused):** average 7.2 s per 4 min of audio; worst 37 s (an MP3, MediaCodec decode).

## Bugs found and fixed
1. The A17's default FLAC decoder labels 16-bit output as float. The decoder now measures the sample width.
2. Android's FLAC pipeline stamps buffers with pts 0 after 25.6 s on some files, and the decoder drops them (17% of
   audio lost). FLAC now uses dr_flac.
3. The debug build compiled the engine at -O0 (about 6x slower). The engine is now always built optimized.

## Open
- Finish the full-library scan (resume command: `adb shell am start -n app.sable/.ui.MainActivity --es cmd scan`).
- The scan ran slower in wall time than its analysis time suggests (hashing, or the worker thread not boosted): investigate.
- MP3 true peak under 16-bit decoders (MATH 1.9 open item, settled in M5 with the playback decode path).
- MP3 JIT latency (MediaCodec).

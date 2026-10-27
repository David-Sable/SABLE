# SABLE — Plan, decisions, risks

Owners: David (product) and Claude (co-owner, architecture and engineering). Last updated 2026-10-26.

## Milestones and exit criteria
Each milestone ends with a test report. The next one does not start until it is green, or until an exception is explicitly accepted in writing here.

| # | Milestone | Exit criteria | Status |
|---|---|---|---|
| M0 | Toolchain | Host clang + ASan/UBSan works, NDK r30, SDK 36/37, JDK 21, FFmpeg, Python validation stack | ✅ done 2026-10-25 |
| M1 | MATH.md + independent review | Reviewer findings resolved or answered in writing | ✅ done 2026-10-26 (MATH-1.2, two reviews) |
| M2 | Engine baseline (BS.1770, LRA, TP), host + NDK | EBU 3341/3342 pass; corpus agrees with libebur128 / FFmpeg / pyloudnorm within 0.1 LU (differences explained); property + golden tests; sanitizers + clang-tidy clean; golden files bit-identical on the A17; A17 benchmark reported | ✅ done 2026-10-26 (docs/reports/M2-report.md); perf fix carried to M3 |
| M3 | Intent layers | Reference vs Octave-run TVL18 code; fast-model error within budget; structure/anchor/labels/gain rules unit-tested; corpus report | ✅ done 2026-10-26 (docs/reports/M3-corpus.md; MATH-1.8) |
| M4 | Library, cache, JIT | Content-hash cache (rename/move → no rescan); WorkManager scan (charging by default); measured JIT latency | ✅ done 2026-10-26 (docs/reports/M4-report.md) |
| M5 | Playback | Gain processor null test; float path; gapless; album/seam; MediaSession + foreground service | ✅ done 2026-10-26 (docs/reports/M5-report.md); preview APK 0.5.0-m5 |
| M6 | UI, Why sheet, A/B | Token theme; all screens; 90 Hz frame timing | ✅ done 2026-10-26 (docs/reports/M6-report.md); preview APK 0.6.0-m6 |
| M7 | Context, battery, install guide | Contour, output awareness, learning; Samsung battery walkthrough; APK + install steps | ✅ done 2026-10-26 (docs/reports/M7-report.md, docs/INSTALL.md); APK 0.7.0-m7 |

## Decisions log
| Date | Decision | Why |
|---|---|---|
| 2026-10-25 | Repo: `craigdavid209-design/SABLE` (public), local `C:\Users\Dana\Projects\SABLE`; toolchain in `~/dev/sable-toolchain`, test corpus in `~/dev/sable-corpus` (outside git) | David's choice; copyrighted audio must never be committed |
| 2026-10-25 | Host compiler: **llvm-mingw** (clang 23, ASan/UBSan, clang-tidy) | Portable, no system changes; same compiler family as the NDK |
| 2026-10-25 | **Bit-identical x86/ARM** engine: no FMA, `-ffp-contract=off`, own deterministic math library (no libm in the engine), fixed reduction order | The spec asks for identical results on ARM; libm differs between bionic and UCRT |
| 2026-10-25 | Our own FFT and resampler (no pffft) | pffft's SSE and NEON paths reduce in different orders, which breaks bit-identity; zero third-party code in the shipped engine |
| 2026-10-25 | Perceptual model = Moore–Glasberg–Schlittenlacher TVL 2018 (ISO 532-3 basis); host reference in double + fast phone model with measured error | The spec asks for ISO 532-3 or a justified approximation with measured error |
| 2026-10-25 | Perceptual results on the LUFS scale via **pink-noise equivalence** at a nominal 75 dB SPL for −14 LUFS (D_cal = 89 dB) | Perceived loudness is level-dependent; this gives one documented convention |
| 2026-10-25 | ISO 226: **2003 tables, marked provisional** (2023 tables are paywalled; the published difference is ≤ 0.3 dB in our range) | Honesty about sources |
| 2026-10-25 | Multichannel files are downmixed (BS.775) by Sable for both analysis and playback | Measure what is actually played on a stereo phone |
| 2026-10-25 | Blind A/B compares level-matched **sequences across transitions** | A single track can't reveal a gain engine; louder always wins |
| 2026-10-25 | Unscanned tracks: wait ≤ 1.5 s for JIT, else start at T + 8 dB (−6 dB in Standard) and ease in at ≤ 1 dB/s | "Never play un-normalized audio at full level" plus a responsive start |
| 2026-10-25 | Accent: **warm ivory `#E8E1D3`** on true black; brushed brass `#B5975A` kept as an alternate token | Timeless, readable on AMOLED, not gimmicky |
| 2026-10-26 | **Quiet mode target = −19 LUFS** (spec said −18) | Owner decision |
| 2026-10-26 | **FLAC decoded in-engine with dr_flac 0.13.3** (public domain); MediaCodec for other formats and playback | Owner decision. A17: MediaCodec FLAC ~48x realtime and buggy on some files (pts 0 after 25.6 s, 17% audio lost; Samsung decoder mislabels 16-bit as float). dr_flac: analysis 6.1 s / 4 min incl. decode, JIT 0.31 s, bit-identical to FFmpeg |
| 2026-10-26 | **MP3 decoded in-engine with minimp3** @ea99364f (CC0, scalar float build) | Owner decision. A17: 3.5 s vs 68.5 s via MediaCodec for a 2:17 MP3; keeps overs (+3.29 dBTP vs clipped +1.19); matches FFmpeg within 3e-6 LU; host == device byte-identical |
| 2026-10-26 | **Gain applied in an AudioSink wrapper**, not an AudioProcessor; one queue planner in the engine (JSON over JNI) | Media3 1.11 runs AudioProcessors only on its 16-bit path; the sink sees each item's first sample, so per-item gain is exact and float end to end |
| 2026-10-26 | Blind A/B = level-matched excerpt sequences, math in the engine (MATH §30); preview APKs are R8-optimized release builds signed with the debug key | Fair comparison by construction; R8 needed for smooth 90 Hz Compose |
| 2026-10-26 | Learning measures volume presses with AOSP's default media curve where One UI's getStreamVolumeDb is flat (MATH-1.13 §19.1) | Measured: One UI returns ~0 dB at every index |
| 2026-10-26 | Playhead redraws once per pixel of movement, not every 90 Hz frame | Measured: UI CPU 80% → 24–33% of a core; screen-off playback ~2% |
| 2026-10-26 | **Logo: the white lion** (David's AI-generated artwork, traced to vector; `tools/branding/`). App icon, and sparingly in the app | Owner choice; calm, regal, reads at small sizes |
| 2026-10-26 | **Quiet contour on by default in Quiet** (Easy always; Advanced has an off switch). MATH-1.14 | Owner decision: Easy-mode listeners at low volume lose the bass; the lift is subtle (+2 dB < 200 Hz) and peak-protected |
| 2026-10-26 | **Easy mode by default**: Settings shows Normal/Quiet only; Advanced (one switch) reveals albums, intimacy, per-output, contour, learning. Easy uses Sable's defaults for those; stored advanced values are kept | Owner request: people confused by sliders; the engine already decides everything |
| 2026-10-26 | Native engine always built optimized, even in debug APKs | -O0 was ~6x slower; results are identical (deterministic flags) |
| 2026-10-25 | Test-only dependencies: doctest, libebur128 (built from source), FFmpeg CLI, pyloudnorm/numpy/scipy/soundfile, GNU Octave (to run the TVL18 reference) | Validation only, never shipped |
| 2026-10-25 | App dependencies (pending M4–M6): Media3, Compose, Room, WorkManager, Coil, kotlinx-coroutines; detekt (dev). No Hilt, no FFmpeg in the app | Minimal surface; platform decoders cover all 7 formats |
| 2026-10-26 | **Blind A/B removed** (MATH-1.19 §30) | ~90 rounds were too subtle to judge in short excerpts; real listening (whole songs, the car) clearly favoured Sable as designed, perceptual estimate included. Direction: refine the engine (more understanding, fewer changes) and the app | David |
| 2026-10-26 | Song-world tracks that **flow** into the album's next track share the album gain (MATH-1.20 §17.3) | Continuous albums (segues, live, mixtapes) must not move level at a join with no pause; uses existing analysis (no rescan). Swipe the Now Playing cover to change songs (owner request) |
| 2026-10-27 | Learning only from songs that start from their beginning, were not resumed, and play with their full-analysis gain (MATH-1.25 §19.1) | Found in the owner's log: a song resumed at 86 s taught +1.92 dB from a volume press that set the room level |
| 2026-10-27 | Play button wakes Sable (Media3 playback resumption + MediaButtonReceiver), home-screen widget (RemoteViews, no new dependency), Clear / remove in the queue. Bluetooth ceiling stays the "codec unknown" −2.0 dBTP: reading the codec needs a Bluetooth permission prompt, not worth ≤ 0.5 dB | Owner requests (widget, clear queue) and the open "car play button" item |
| 2026-10-27 | Convenience round (owner request): mini player swipe, resume where you left off (paused, never autoplays), Play next / Add to queue (song world), tap title/artist on Now Playing, Shuffle all. Environment filter ignores the previous press when the volume was put back in between (MATH-1.24) | Owner's third JBL session: a put-back volume showed both turn-downs were about their songs |
| 2026-10-27 | Learning: a song's own lesson wins over its artist's; the artist's applies only when ≥ 2 taught songs agree; the environment filter only looks back 10 min (MATH-1.23 §19) | Found in the owner's second JBL session: a turn-down made a song louder (artist sum), and a press 30 min after the previous session was down-weighted |
| 2026-10-27 | **Library search** and **repeat** (queue / this song); CI keeps only the **newest three releases** | Owner request. With repeat all the planning window is circular so the wrap is seamed like any change. Old releases (incl. build-3 with the wrong key) deleted by CI; three remain to roll back to |
| 2026-10-26 | Listener learning is kept **per place** = output device (type + device name), seeded from the earlier shared state (MATH-1.21 §19.3) | The car and headphones are both Bluetooth but need different corrections; earlier learning must not be lost |
| 2026-10-27 | Learning audit (MATH-1.27): a press is measured against the section it was made in; mutes, jumps over 12 dB and presses while paused do not teach; songs with no artist share no artist state | Found by measuring the 392 FLACs: the first 20 s sit ≥ 3 LU under the song for 33 % of songs, so turning up in a quiet intro taught the whole song and artist ("$ (Money)"). Owner asked for a blind-spot hunt. |
| 2026-10-27 | Very short quiet interludes (Tinashe's 6 s "You", lifted +7.7 dB): **leave as is for now**. Both owners want artist intent kept and no volume fiddling; it is 1 song in 392, 6 s long, safe (true peak −11 dBTP). If driving shows it matters, the idea is a bounded lift (about +3 dB) for tracks too short to have an anchor. Gain rules are decided at play time, so this needs no rescan | Owner and Claude, 2026-10-27. |

## Known risks / blind spots
1. Perceived loudness depends on real playback SPL, which the phone doesn't know. Mitigated by the D_cal convention and per-output calibration (MATH §12, §20).
2. Full ISO 532-3 is far too slow for the phone budget. The fast model is mandatory and its error is measured (MATH §11).
3. Samsung post-processing (Dolby Atmos, Adapt Sound, UHQ upscaler, EQ, volume limiter, Bluetooth absolute volume) happens after Sable. Sable detects what it can; the walkthrough tells you how to turn it off for fair tests.
4. Some Android decoders may only output 16-bit, which clips lossy overs. Flagged per file, with a +1 dB TP margin (MATH §1.1). To be measured on the A17 in M4.
5. Media3 float output vs custom AudioProcessors: verified in M5. Processors are skipped on the float path, so Sable applies gain in a sink wrapper (MATH §23.1).
6. Volume-key learning is ambiguous. There is an environment filter, small rates, a clamp and decay (MATH §19).
7. The TVL reference code's licence is "free for research; commercial use contact the author". Fine for this personal project; it would block commercial distribution without permission.
8. Corpus (2026-10-26): 434 files, 24.1 h. 29 albums from David's collection (hip-hop, pop, K-pop, hyperpop, rock; FLAC 44.1/48/88.2/96 kHz, 16/24-bit), plus free-licensed recordings: solo piano (Open Goldberg, 96k), orchestral/chamber (Musopen), industrial (NIN The Slip), ambient at 48k ALAC and 96k FLAC (NIN Ghosts), acoustic (Josh Woodward MP3), jazz/electronic (Kevin MacLeod MP3), mono speech MP3 (LibriVox). Gaps: native Opus/Vorbis/AAC/WAV files (generated locally by transcoding in M2), the EBU official test set (needs a browser download), and multichannel material.

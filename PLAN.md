# SABLE — Plan, decisions, risks

Owners: Dana (product) and Claude (co-owner, architecture and engineering). Last updated 2026-10-25.

## Milestones and exit criteria
Each milestone ends with a test report. The next one does not start until it is green, or until an exception is explicitly accepted in writing here.

| # | Milestone | Exit criteria | Status |
|---|---|---|---|
| M0 | Toolchain | Host clang + ASan/UBSan works, NDK r30, SDK 36/37, JDK 21, FFmpeg, Python validation stack | ✅ done 2026-10-25 |
| M1 | MATH.md + independent review | Reviewer findings resolved or answered in writing | ✅ done 2026-10-26 (MATH-1.2, two reviews) |
| M2 | Engine baseline (BS.1770, LRA, TP), host + NDK | EBU 3341/3342 pass; corpus agrees with libebur128 / FFmpeg / pyloudnorm within 0.1 LU (differences explained); property + golden tests; sanitizers + clang-tidy clean; golden files bit-identical on the A17; A17 benchmark reported | ✅ done 2026-10-26 (docs/reports/M2-report.md); perf fix carried to M3 |
| M3 | Intent layers | Reference vs Octave-run TVL18 code; fast-model error within budget; structure/anchor/labels/gain rules unit-tested; corpus report | ✅ done 2026-10-26 (docs/reports/M3-corpus.md; MATH-1.8) |
| M4 | Library, cache, JIT | Content-hash cache (rename/move → no rescan); WorkManager scan (charging by default); measured JIT latency | |
| M5 | Playback | Gain processor null test; float path; gapless; album/seam; MediaSession + foreground service | |
| M6 | UI, Why sheet, A/B | Token theme; all screens; 90 Hz frame timing | |
| M7 | Context, battery, install guide | Contour, output awareness, learning; Samsung battery walkthrough; APK + install steps | |

## Decisions log
| Date | Decision | Why |
|---|---|---|
| 2026-10-25 | Repo: `craigdavid209-design/SABLE` (public), local `C:\Users\Dana\Projects\SABLE`; toolchain in `~/dev/sable-toolchain`, test corpus in `~/dev/sable-corpus` (outside git) | Dana's choice; copyrighted audio must never be committed |
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
| 2026-10-26 | Native engine always built optimized, even in debug APKs | -O0 was ~6x slower; results are identical (deterministic flags) |
| 2026-10-25 | Test-only dependencies: doctest, libebur128 (built from source), FFmpeg CLI, pyloudnorm/numpy/scipy/soundfile, GNU Octave (to run the TVL18 reference) | Validation only, never shipped |
| 2026-10-25 | App dependencies (pending M4–M6): Media3, Compose, Room, WorkManager, Coil, kotlinx-coroutines; detekt (dev). No Hilt, no FFmpeg in the app | Minimal surface; platform decoders cover all 7 formats |

## Known risks / blind spots
1. Perceived loudness depends on real playback SPL, which the phone doesn't know. Mitigated by the D_cal convention and per-output calibration (MATH §12, §20).
2. Full ISO 532-3 is far too slow for the phone budget. The fast model is mandatory and its error is measured (MATH §11).
3. Samsung post-processing (Dolby Atmos, Adapt Sound, UHQ upscaler, EQ, volume limiter, Bluetooth absolute volume) happens after Sable. Sable detects what it can; the walkthrough tells you how to turn it off for fair tests.
4. Some Android decoders may only output 16-bit, which clips lossy overs. Flagged per file, with a +1 dB TP margin (MATH §1.1). To be measured on the A17 in M4.
5. Media3 float output vs custom AudioProcessors: to be verified on the current Media3 version (M5).
6. Volume-key learning is ambiguous. There is an environment filter, small rates, a clamp and decay (MATH §19).
7. The TVL reference code's licence is "free for research; commercial use contact the author". Fine for this personal project; it would block commercial distribution without permission.
8. Corpus (2026-10-26): 434 files, 24.1 h. 29 albums from Dana's collection (hip-hop, pop, K-pop, hyperpop, rock; FLAC 44.1/48/88.2/96 kHz, 16/24-bit), plus free-licensed recordings: solo piano (Open Goldberg, 96k), orchestral/chamber (Musopen), industrial (NIN The Slip), ambient at 48k ALAC and 96k FLAC (NIN Ghosts), acoustic (Josh Woodward MP3), jazz/electronic (Kevin MacLeod MP3), mono speech MP3 (LibriVox). Gaps: native Opus/Vorbis/AAC/WAV files (generated locally by transcoding in M2), the EBU official test set (needs a browser download), and multichannel material.

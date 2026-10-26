# Working on SABLE (for Claude sessions, on the PC or in the cloud)

SABLE is a local Android music player built around the Sable Intent Engine. Owners: Dana (product) and Claude
(co-owner: architecture and engineering). Read README.md (plain language), PLAN.md (milestones, decisions log, risks)
and MATH.md (the math, single source of truth) before changing behaviour.

## Rules that do not bend
- **MATH.md is the source of truth.** Every engine function cites its section. If code and MATH.md disagree, fix the
  wrong one and bump the MATH version. New numeric decisions go into MATH.md first.
- **Correctness before features and design.** Engine changes come with tests (`engine/tests`, doctest).
- **Deterministic engine:** clang only, `-ffp-contract=off`, no libm in the engine (use `sable::dm`), fixed reduction
  order. Host and phone must produce identical results.
- **All gain math lives in the C++ engine** (planner: `engine/src/plan.cpp`, JSON over JNI). Kotlin gathers inputs and
  applies outputs; it does not re-implement rules.
- **Never commit audio or licensed references.** The test corpus (Dana's copyrighted albums) and the standards PDFs stay
  on the PC (`~/dev/sable-corpus`, `~/dev/sable-refs`). `.gitignore` blocks audio files and signing keys.
- **Never commit a signing key.** CI signs with the repository secret `SABLE_KEYSTORE_B64`.
- Commit as the repo's noreply identity: `craigdavid209-design <327186142+craigdavid209-design@users.noreply.github.com>`.
- Ask Dana before adding a major dependency or changing the product's philosophy (one static gain per song by default;
  the Quiet contour is the only opt-in non-flat processing). Keep README in plain language.

## Owner decisions to respect (details in PLAN.md)
- Targets: Normal (Standard) −14 LUFS, Quiet **−19** LUFS.
- Album world vs song world by intent: Play album = one album gain; tapping a song = that song and what follows,
  each in its own world.
- In-engine decoding for analysis: FLAC with dr_flac, MP3 with minimp3. Playback uses Media3 + platform decoders,
  gain applied in `SableAudioSink` (float).
- Phone first (Galaxy A17, Android 16, 90 Hz). Easy mode is the default UI; Advanced is one switch.

## Build and test
- Engine (host): `cmake -S engine -B build -DCMAKE_CXX_COMPILER=clang++ [-DSABLE_SANITIZE=ON] && cmake --build build -j && ./build/sable_tests`
- App: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease` (JDK 21, Android SDK 37, NDK 30.0.16248370, CMake 4.1.2).
- GitHub Actions (`.github/workflows/build.yml`) runs both on every push to `main` and publishes a signed APK as a
  release. Dana installs it on the phone from the repo's **Releases** page; it updates in place and keeps the library.

## What needs the PC session (not possible from the cloud)
- Anything on the phone over USB: installing by adb, on-device benchmarks, frame timing, battery/CPU, screenshots,
  volume-key learning tests. Debug automation commands are listed at the top of `ui/MainActivity.kt`.
- Corpus cross-validation and the corpus reports (copyrighted audio stays on the PC).
- Work that needs the standards texts or the TVL reference code.
From the cloud: code, math, UI, tests, docs; push to `main`, check the Actions run, then tell Dana the release to install.

# Working on SABLE (session guide for Claude: PC, cloud, or a fresh chat)

SABLE is a local Android music player built around the Sable Intent Engine. Owners: **David** (product; this PC used to belong to David's sister Dana, hence the `C:\Users\Dana` paths) and **Claude**
(co-owner: architecture and engineering; David delegates most technical decisions).
**Decision authority (David, 2026-10-28):** Claude decides technical and engineering matters alone when sure it is better, and tells David afterwards in plain words; David and Claude work together and no other AI works on SABLE. The "Ask David first" items in section 3 still apply. You start with no memory of earlier
chats, so:

## 1. Start of every session
1. Read **docs/NOTES.md** ("Now" = current state and open items, then the newest log entries).
2. Skim **PLAN.md** (milestones, decisions log, risks). Read the **MATH.md** sections for anything you will touch.
3. `git pull`, then check the latest CI run (section 5). If `main` is red, fixing it comes first.

## 2. End of every session (and whenever the chat is getting long)
David may need to start a new chat when the context fills up, so never leave state only in the conversation:
1. Rewrite "Now" in docs/NOTES.md and add a short log entry at the top (what changed, how it was verified, what is left).
2. Update whatever the change touched (table below). Commit and push. Check that CI is green and tell David which
   release to install, if the app changed.
3. If a better way of working emerged, update this file too. It is the guide for the next session.

| If you changed… | Also update |
|---|---|
| Any number, rule or threshold in the engine | MATH.md first (bump the version in its header and `MATH_DOC_VERSION` in `ui/SableApp.kt`; note it in NOTES) |
| An owner decision or architecture choice | PLAN.md decisions log |
| What David sees or does | README.md (plain language) and, for installing, docs/INSTALL.md |
| The app's version | `versionName` in `android/app/build.gradle.kts` (versionCode is automatic) |
| Analysis output (the cached JSON or anything that changes per-track results) | `SableDatabase.MATH_VERSION` (forces a full rescan of the library, ~40 min on the phone; only when results really change). Gain rules and planner changes do NOT need it: decisions are made at play time |
| Planner JSON (inputs/outputs) | docs/plan-json.md |

## 3. Rules that do not bend
- **MATH.md is the source of truth.** Every engine function cites its section. If code and MATH.md disagree, fix the
  wrong one and bump the MATH version. **Correctness before features and design.** Engine changes come with tests.
- **Deterministic engine:** clang only, `-ffp-contract=off`, no libm in the engine (use `sable::dm`), fixed reduction
  order. Host (x86) and phone (ARM64) must give identical results.
- **All gain math lives in the C++ engine** (planner `engine/src/plan.cpp`, JSON over JNI). Kotlin gathers inputs and
  applies outputs; it never re-implements a rule.
- **Portable C++:** it must build with libc++ (Android, llvm-mingw) **and** libstdc++ (Linux CI). Include what you use.
- **Never commit audio, licensed references or keys.** David's corpus (copyrighted) and the standards PDFs stay on the
  PC (`~/dev/sable-corpus`, `~/dev/sable-refs`). `.gitignore` blocks audio and keystores. The signing key exists only
  on the PC (`~/.android/debug.keystore`) and in the GitHub secret `SABLE_KEYSTORE_B64`. Never print, copy or move it.
- Commit identity: `craigdavid209-design <327186142+craigdavid209-design@users.noreply.github.com>`.
- **Ask David first** before a major new dependency or a change to the product's philosophy: one static gain per song by
  default; the Quiet contour (on by default in Quiet, switchable in Advanced) is the only non-flat processing; files are never modified.

## 4. Owner decisions to respect (details in PLAN.md)
- Targets: Normal (MATH "Standard") −14 LUFS, Quiet **−19** LUFS (not −18).
- Album world vs song world by intent: Play album = one album gain; tapping a song = that song and what follows, each
  in its own world.
- Analysis decoding in the engine: FLAC with dr_flac, MP3 with minimp3. Playback: Media3 + platform decoders, gain
  applied in float in `SableAudioSink`.
- Phone first (Galaxy A17 5G, Android 16, One UI, 90 Hz). **Easy mode is the default UI**; Advanced is one switch.
- Quiet mode includes the gentle contour by default (MATH-1.14 §24); Advanced can turn it off.
- Look: true black, warm ivory accent `#E8E1D3` (brass `#B5975A` alternate), tokens in `ui/theme/Tokens.kt`.
- Logo: the white lion (David's choice). Regenerate icon and mark only with `tools/branding/trace_logo.py`; Android caps a resource string at 32,767 chars, so keep one `<path>` per shape. Keep the app minimal: the mark appears only beside the title, in About and on empty states.

## 5. Build, test, release
- **Line endings are mixed** (some files LF, some CRLF). Python text mode on Windows rewrites a whole file's endings: edit with the Edit tool, or keep the file's own endings, and check `git diff --stat` for churn before committing.
- Engine: `cmake -S engine -B build -DCMAKE_CXX_COMPILER=clang++ -DSABLE_SANITIZE=ON && cmake --build build -j && ./build/sable_tests`
  (on the PC: `source ~/dev/sable-toolchain/env.sh` first; existing build dirs `engine/build-host`, `engine/build-asan`).
- App: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease` (JDK 21, SDK 37, NDK 30.0.16248370, CMake 4.1.2).
- **CI** (`.github/workflows/build.yml`), on every push to `main` that touches more than `*.md` or `docs/`: engine tests (Linux clang, ASan/UBSan) → app tests →
  signed APK → certificate check → GitHub release `build-N` ("Latest"); then older releases are deleted (the newest three stay). David installs from the phone:
  `https://github.com/craigdavid209-design/SABLE/releases/latest` → tap the APK → Update.
- **Checking CI without signing in** (no `gh` here; logs need sign-in, annotations are public):
  - latest run: `curl -s https://api.github.com/repos/craigdavid209-design/SABLE/actions/runs?per_page=1`
  - jobs: `…/actions/runs/<id>/jobs`; errors: `…/check-runs/<job id>/annotations` (the workflow turns compiler and
    Gradle errors into annotations).
  - Validate workflow YAML before pushing (`python -c "import yaml; yaml.safe_load(open('.github/workflows/build.yml'))"`):
    a `run:` line containing `: ` must be a block scalar (`run: |`).

## 6. What needs the PC session (cloud sessions cannot)
- The phone over USB: `adb install`, on-device benchmarks, frame timing (`dumpsys gfxinfo`), CPU (`top`), screenshots,
  volume-key learning tests. The debug commands are listed at the top of `ui/MainActivity.kt`
  (`adb shell am start -n app.sable/.ui.MainActivity --es cmd …`). Use `MSYS_NO_PATHCONV=1` with adb in Git Bash. A value with a space needs the whole device command in single quotes: `adb shell 'am start … --es match "two words"'`.
  Animation smoothness: `tools/perf/anim_frames.sh` (pauses music first; see the note in it). Library-wide engine checks: the PC holds the same 392 FLACs (`~/dev/sable-corpus/drive`); `sable_cli --decide` on each (decode with the toolchain's ffmpeg) gives every figure the phone has.
  Playback problems heard away from the PC: read the app's own playback log (`--es cmd diag`, tag `SableDiag`); logcat rolls over within hours. What learning holds: `--es cmd learned` (tag SablePlay). Whether it is working: `--es cmd scoreboard` (tag SablePlay, lines start with SCOREBOARD; its counters live in their own file, so `forget` does not clear them). **Never `adb logcat -c` before reading what David's session left.**
  Don't change David's phone settings or volume without putting them back; erase test learning (`--es cmd forget`).
- Corpus cross-validation and corpus reports (copyrighted audio stays on the PC); work needing the standards or TVL code.
From the cloud: code, math, UI, tests, docs → push → check CI → tell David the release number to install.

## 7. Working with David
- Plain language, short updates, honest about failures (say what failed and what you did). David is often on the phone.
- David watches usage: be efficient, don't redo finished work, don't spawn helpers unless asked.
- Measure before claiming (on the phone when it matters) and record numbers in the reports/NOTES.
- **Release routine (David asked for this):** when the work is done and pushed, first tell David he can unplug the phone (say so every time), then stay and watch CI until the build is green (a background loop on the check-runs API, not repeated chatter). If it fails, read the annotations, fix, push, and keep watching. Only then say which release to install.

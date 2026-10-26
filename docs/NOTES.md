# Session notes (handoff log)

The shared memory between Claude sessions: on the PC, in the cloud, and across new chats (a new chat remembers
nothing except what is in this repo). **Read "Now" first. Before a session ends, or when the chat is getting long,
rewrite "Now" and add one entry at the top of the log, then commit and push.** Keep entries short and factual: what
changed, what was verified (and how), what is left. Dana reads this too, so write in plain language.

## Starting a new chat
Dana can open a new chat (PC or phone) and say:
> Continue SABLE. Read CLAUDE.md and docs/NOTES.md first.

That is all the next session needs: CLAUDE.md has the rules and workflow, this file has the current state.

---

## Now (keep this current)
- **State:** all milestones M1-M7 done, plus Easy mode (default), swipe-down Now Playing and the app icon. MATH-1.13.
  Latest GitHub release: build 6 (`0.7.0-m7`), signed with the phone's key (certificate SHA-256 `0786a932…3be2`).
- **On Dana's phone:** 0.7.0-m7 (installed from the PC; same key, same data). Library 396 songs, all analysed.
  Battery shows "Optimized"; Dana may tap "Allow unrestricted" in Settings.
- **Open items (none blocking):**
  - Bluetooth and USB-C outputs untested on the device (per-route settings covered by code and tests only).
  - AAC/M4A, ALAC, Opus, Vorbis and WAV are not on the phone: their MediaCodec analysis path and the 16-bit
    `decodeClipped` margin (MATH §1.1) are untested on the A17. FLAC and MP3 (decoded in the engine) are verified.
  - EBU official test files (Tech 3341/3342) still need a browser download; compliance tests use generated signals.
  - Release `build-3` on GitHub has the wrong key; Dana can delete it (Releases → build-3 → Delete). Harmless.
  - Dana's listening check of character labels and the largest gain moves (from M3) is still open.
- **Ideas pending:** none.

---

## Log (newest first)

### 2026-10-26 (PC) GitHub builds and handoff setup
- GitHub Actions (`.github/workflows/build.yml`): engine tests with ASan/UBSan on Linux clang, app JVM tests, signed
  release APK published per push to `main`, only after the engine tests pass and only if the certificate matches.
- Fixed for Linux: JSON reader in `plan.cpp` (libstdc++ rejects `pair<string, J>` inside `J`).
- Signing explicit (`signingConfigs.sable`, env `SABLE_KEYSTORE` on CI); versionCode = minutes since 2026-01-01, so PC
  and GitHub builds always install over each other. Secret `SABLE_KEYSTORE_B64` added by Dana.
- Wrote CLAUDE.md (session guide) and this file.

### 2026-10-26 (PC) Easy mode, icon, swipe-down
- Easy mode default: Settings shows Normal/Quiet, battery, library; the Advanced switch reveals the rest. Easy uses
  `Settings.effective()` (Sable's defaults); stored advanced values are kept. Why sheet folds its details.
- Adaptive black icon (vector wordmark + anchor line); swipe-down closes Now Playing. Both verified on the phone.

### 2026-10-26 (PC) Milestones M1-M7
- See `docs/reports/M2-report.md` … `M7-report.md` and the decisions log in PLAN.md.

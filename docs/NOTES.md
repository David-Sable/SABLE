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
- **State:** all milestones M1-M7 done, plus Easy mode (default), swipe-down Now Playing, the Quiet contour on by default in Quiet (0.7.1), the white lion logo (0.8.0), the A/B verdict line (0.8.1) and the song-changes A/B round (0.9.0). MATH-1.16.
  Latest GitHub release: see Releases (0.9.0 pushed 2026-10-26), signed with the phone's key (certificate SHA-256 `0786a932…3be2`).
- **On Dana's phone:** 0.8.1 (installed from the PC over USB, 2026-10-26). A/B tally (17:18): Sable 15, Plain 12, Off 6 of 33 (luck ≈ 1 in 10). Off clearly loses; Sable vs Plain is close. Expected: A/B excerpts leave out seams and album world (§30.1), where Sable differs most from plain −14. 0.7.1 had been installed from GitHub build 8: the release path works end to end. Library 396 songs, all analysed.
  Battery shows "Optimized"; Dana may tap "Allow unrestricted" in Settings.
- **Open items (none blocking):**
  - Bluetooth and USB-C outputs untested on the device (per-route settings covered by code and tests only).
  - AAC/M4A, ALAC, Opus, Vorbis and WAV are not on the phone: their MediaCodec analysis path and the 16-bit
    `decodeClipped` margin (MATH §1.1) are untested on the A17. FLAC and MP3 (decoded in the engine) are verified.
  - EBU official test files (Tech 3341/3342) still need a browser download; compliance tests use generated signals.
  - Release `build-3` on GitHub has the wrong key; Dana can delete it (Releases → build-3 → Delete). Harmless.
  - Dana's listening check of character labels and the largest gain moves (from M3) is still open.
- **Next:** Dana tries the Song changes A/B (0.9.0). It was built while the phone was unplugged: **not yet verified on the device**. Next USB session: play a Song changes round, check in logcat (`SablePlay` ITEM lines) that the second song of each change starts natural (`manual=false`) with SEAM or album gain, that clip edges do not fade at the real transition, and that switching labels works.
- **Ideas pending (agreed with Dana, 2026-10-26):** test Bluetooth/USB-C outputs and the other formats on the phone (needs USB; formats need non-copyrighted test files, e.g. generated with ffmpeg); a sleep timer that stops at the end of a song (no fades, keeps one static gain per song).

---

## Log (newest first)

### 2026-10-26 (PC) Song changes A/B (0.9.0)
- Why: Dana's clip rounds stood at Sable 15, Plain 12, Off 6 of 33. Clips (§30.1) leave out seams and album world, where Sable differs most from plain −14, so Sable vs Plain is expected to be close there.
- New round kind (MATH-1.16 §30.6): 4 song changes, the last 20 s of one song straight into the first 20 s of the next; 2 inside complete albums (tracks really in a row, album world) and 2 between albums (song world, seams). Sable plays as normal playback (first song manual, second natural); plain and off as before; level match unchanged. Own tally (`abflow_*` prefs), same verdict line.
- Engine: `abSeams` planner input + test. App: `GainController.Excerpt` (length and start), no edge fade across the real transition (`setEdges` fadeIn/fadeOut), `pickFlowChanges` + `FlowSequenceTest`. Engine 53 cases, app tests green. Not verified on the phone (unplugged).

### 2026-10-26 (PC) A/B verdict (0.8.1)
- Under the A/B tally, one plain sentence: the chance that random picking would make Sable win this often (binomial tail, chance 1/3 per round), shown after 10 rounds; under 5% reads "You can hear the engine". MATH-1.15 §30.5 (display rule only). Unit tests in `AbVerdictTest`.
- Verified on the phone: Dana's 13 of 26 shows "about 1 time in 17, so it could still be luck". One more Sable win (14 of 27) crosses 5%.

### 2026-10-26 (PC) White lion logo (0.8.0)
- Dana chose a white lion (their AI-generated artwork, `tools/branding/lion_source.webp`) after my own drawn attempts (S mark, face lions, heraldic lions) didn't reach the quality bar. Traced with vtracer (99.6% pixel match) by `tools/branding/trace_logo.py` into `drawable/lion_mark.xml` (in-app `SableMark`) and the launcher foreground (56 dp wide in the 108 dp canvas, inside the safe circle). Monochrome themed layer reuses the foreground.
- Gotcha: Android rejects resource strings over 32,767 characters (`STRING_TOO_LARGE`; the icon falls back to the robot). Keep one `<path>` per traced shape. Debug command `--es cmd icontest` loads the icon drawables and logs any error.
- Verified on the phone: icon in App info, mark beside the title and on the empty Now Playing screen.

### 2026-10-26 (PC) Logo (0.7.2)
- The SABLE mark: an S sitting on a level line inside a fine ring (warm ivory on black). Launcher icon (adaptive + monochrome themed layer) and `ui/Logo.kt` (`SableMark`, same geometry) beside the title, in About and on the empty Now Playing screen. Chosen from 4 rendered options; verified on the phone.
- Checked on the phone before this: 0.7.1 from GitHub build 8 installed over the PC build; Quiet switched the contour on automatically.

### 2026-10-26 (PC) Contour automatic in Quiet (0.7.1)
- Owner decision: the Quiet contour is on by default in Quiet (Easy mode always; Advanced has an off switch). MATH-1.14 §24.
- Stored under a new preference key (`contourOn`, default true) so the "off" left by an earlier device test does not carry over.

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

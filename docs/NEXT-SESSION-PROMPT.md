# Prompt for the next (PC) session

> Continue SABLE. Read CLAUDE.md and docs/NOTES.md first. This is a PC session: David's phone (Galaxy A17) is plugged in over USB with USB debugging on, so you can use `adb`. Release build-54 (0.22.1) is the latest; the phone may still have 0.22.0.

## Do first
1. `git pull`, check CI (CLAUDE.md section 5). Install release 0.22.1 on the phone (same code as the release, then `adb shell cmd package compile -m speed -f app.sable`) and check the new file-name titles with a tag-less file (David's "Tinashe - Brainrot [Official Audio]" style). Put the phone back as found (volume, paused, no test lessons).
2. David said "go ahead" on both of these (he agreed in chat on 2026-10-28; you may decide the details yourself, tell him in plain words afterwards):
   - **The full rescan for segued albums** (NOTES "Tomorrow" item 1): record leading and trailing silence separately in the analysis so albums like The Life of Pablo flow (MATH §17.3, a MATH version bump, `SableDatabase.MATH_VERSION`, tests, README/PLAN note). The rescan is ~40 min with the phone plugged in and unlocked. Verify on the phone that TLOP then flows.
   - **An opt-in "quieter / louder for this song" tap** for the car (the car's volume knob is invisible to Android, so nothing teaches there; Hell For It is the example). A small control on Now Playing / the notification that records a deliberate correction as a lesson at the current place (it goes through the same MATH-1.30 rules: a hint first, a lesson on repeat). It acts only when David taps; no new always-on processing. Decide the exact design, write MATH first.
3. Items still open: restore `phone_seams_vs_pc.py` helper `lt`, fix the stale taps in `tools/perf/anim_frames.sh`, read what 0.19-0.22 learned (`scoreboard`, `learned`, `diag`) once David has listened for a few days.

## New idea from David: the "mood map" (try it; cancel if it is too much)
David wants each song to show, in colour, what it is doing right now, without breaking the minimal look. If it looks overpowering, remove it.

Design I suggest (display only, never touches a gain, files untouched):
- **Where:** Now Playing only, as a thin tint in the existing playhead/waveform strip (and optionally a faint edge glow on the mini player). Nowhere else; no new screen.
- **Colour:** stay inside the palette: start from the warm ivory `#E8E1D3` and drift only slightly towards two neighbours (a cooler grey-blue for calm/dark, brass `#B5975A` for warm/intense). No rainbow, no saturation above about 35 %, true black stays black.
- **What it shows, per moment:** (a) energy = short-term loudness relative to the song's own range (brighter and slightly longer bar when the song is at its peak, dimmer in quiet parts); (b) timbre = spectral tilt / brightness (bassy and dark leans cool, bright leans warm). Use data the engine already has (the waveform data behind the playhead, short-term loudness, tilt, the section structure of MATH §17/structure layer). Check first what is cached per track; if the per-second values are not stored, derive them from the waveform that is already drawn. Only bump `MATH_VERSION` (forces the rescan) if truly needed; since the segued-albums rescan is happening anyway, you can fold any new per-second numbers into that one rescan.
- **Motion:** colour changes smoothly with the playhead (no flashing, no per-frame work: reuse the once-per-pixel playhead redraw, keep idle = 0 frames).
- **Control:** a switch in Advanced ("Mood colours"), on in the trial build so David sees it; decide to keep or drop after he looks. Easy mode stays untouched unless he likes it.
- **Verify on the phone:** screenshots at a few points in 3 very different songs (a quiet piano piece, a bassy hip-hop song such as Hell For It, a loud pop song), CPU and `tools/perf/anim_frames.sh` before/after (must not add late frames), battery unchanged. Show David the screenshots/what you saw and ask only if you are not sure it is calm enough.

## Rules reminder
You (Claude) are co-owner and decide technical matters yourself; you may merge to `main` for a release. Ask David only before a major new dependency or a change to the product philosophy (one static gain per song, contour the only non-flat processing, files never modified). Tell him he can unplug the phone when you are done (every time), watch CI to green, then name the release. Update NOTES "Now" + a log entry, and the other docs in CLAUDE.md's table.

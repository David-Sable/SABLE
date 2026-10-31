# The test battery (engine and phone)

What was run in the 2026-10-27 blind-spot pass, so a later session can repeat it. Nothing here commits audio.

**Engine, on the PC** (`source ~/dev/sable-toolchain/env.sh`; the corpus is in `~/dev/sable-corpus/drive`)
1. `engine/build-host/sable_tests.exe` (58 cases): the unit tests, including the learning rules, the planner bounds and the unknown-peak cap.
2. `tools/validation/decide_corpus.py`: the engine's figure for every song (writes `%TEMP%/aud/decide.jsonl`).
3. Build the planner driver: `clang++ -std=c++20 -O1 -I engine/include tools/validation/plan_tool.cpp engine/build-host/libsable_engine.a -o %TEMP%/aud/tool2.exe`.
4. `fuzz_planner.py <seed> <cases>`: malformed and extreme inputs (expect only unreachable hostile-analysis complaints, no crash), `props_planner.py`: properties on real queues (expect none violated).
5. `gen_test_audio.py <folder>`: known-loudness and odd test audio; `native_analyze.cpp` runs the engine's own FLAC path on them (same answers as the WAV path).

**On the phone** (USB, `adb`; speaker route; volume muted or low and put back; the phone must stay unlocked)
- `phone_vs_pc.py 60`: gains of real songs, phone against PC (expect identical to 0.01 dB). `phone_seams_vs_pc.py 14`: natural transitions. `--es cmd flows`: which tracks flow, against the PC planner.
- `phone_learning.py` (`run(title, song, volume, [(seconds, "DOWN"|"UP"|"PAUSE"|"NP"|"SEEK:0.6")], wait=27)`): the volume-button scenarios: a plain press, the 0.25 weight of a repeated press, the artist offset applying only when two taught songs agree, a press in a quiet intro ignored and one against it counted, the 20 s window edge, press then undo, accumulated presses, a 16 dB jump ignored and the next press weighted 0.25, a seek into the body, Quiet mode, a screen-off press.
- `phone_bt_vs_pc.py 40` (JBL connected, run after `source ~/dev/sable-toolchain/env.sh`; turn the speaker down first and put it back): the same on the Bluetooth route in Quiet mode (ceiling -2.0, contour headroom 2.6 from the CONTOUR log line). 2026-10-27: 36 of 36 identical.
- Push audio with `adb push` (Windows-style path), index with `adb shell content call --uri content://media/ --method scan_volume --arg external_primary`, then `--es cmd sync` / `scan`.
- Cleanup: `--es cmd purgeplace --es place speaker --ei hours 3` removes only what that place learned in the last 3 hours; `forgetsong` / `forgetartist` act on every place, so never use them on a real artist who has lessons elsewhere. Delete the test folder, rescan, `sync`, check the song count.
- **Is learning working?** `--es cmd scoreboard [--ei weeks N]` (tag `SablePlay`, lines start with `SCOREBOARD`). Per week and place: plays that could teach, corrections (up/down) and what became of them, plays that could not teach. Read the correction rate of songs already taught against new songs: the first should be lower over time. Counters start at 0.22.0 and survive `forget`; touches outside the 20 s window are approximate (Android does not always report volume presses; the car's knob is never seen).

**Phone know-how** (learned the hard way; CLAUDE.md section 6 has the rules)
- `adb` needs `source ~/dev/sable-toolchain/env.sh`. With `MSYS_NO_PATHCONV=1`, `adb install` needs a Windows-style path (`C:/Users/...`).
- After `adb install` the app is only "verify"-compiled: run `adb shell cmd package compile -m speed -f app.sable` before measuring.
- versionCode is minutes-based, so a local build is older than the CI build made from the same code and David can still update from the release. Never leave an experimental build on the phone.
- Compare a screen's layout across gestures by finding text rows in screenshots, not by eye: `adb exec-out screencap -p`.
- The phone sleeps after at most 10 minutes: a Shift key press every 2 minutes keeps it awake (the wake-up key alone does not reset the timer).
- Frame numbers: GPU ms is a poor measure on the A17 (the Mali sits at a low clock under light load). Judge by recordings at 60 fps (extract with `fps=60`, not passthrough) and `tools/perf/anim_frames.sh`. Five rounds is too few to see small changes.

## Frame rate: measure what David runs
A normal install is `verify` until the phone compiles the profile when idle. `cmd package compile -m speed` hides that. Measure right after `adb install`, then again after `cmd package compile -m speed-profile -f app.sable`. Use `tools/perf/tab_frames.py` on one framestats file per gesture: the slowest frame is what the eye catches, and a late-frame share over repeats hides it.

**Now Playing swipe:** the app logs every release and settle under tag `SableAnim` (progress, finger speed, speed handed to the spring). Swipe with `adb shell input swipe 540 500 540 1700 <ms>` (1000 slow .. 50 flick); film with `adb shell screenrecord --time-limit 3` and tile frames with `ffmpeg -vf fps=60,scale=150:-1,tile=11x4`. The speed limit is live-tunable: `--es cmd animflag --es name vmax --ei value 300` (hundredths of progress per second).

**Test lessons:** erase them with `purgeplace --es place speaker --ei hours N`, never `forgetsong` (it clears the song in every place, including David's) and never `forget` (everything). Check the taught-song count before and after (`learned`, lines `LEARNED speaker · song`).

**Muted playback tests:** `media volume` is not on this phone's shell path. Read the volume back as 0 from `dumpsys audio` before any play command, and stop the script if it is not (2026-10-30: a failed mute let 4 s of a song play aloud).

**Testing lessons without a trace:** press Save backup in Settings first; test inside David's queue (seek near a song's end for natural transitions, `prev` back, `seek`, `pause`); finish with Restore, which puts learning, history and the scoreboard back; then `am force-stop app.sable` so nothing stays frozen for the loaded song. Set the volume with `adb shell cmd media_session volume --stream 3 --set N`. Debug commands do not reach Sable while Android's file picker is open: leave it with Back, and it may take two. Find the playing queue index in `dumpsys media_session` (`active item id`).

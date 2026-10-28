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

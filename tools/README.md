# Tools

Run these from the PC after `source ~/dev/sable-toolchain/env.sh`. Most need the corpus or the phone, so none run in CI. `docs/TESTING.md` says how they fit together.

| Folder | What is in it |
|---|---|
| `validation/` | Engine checks. Corpus: `crossval`, `corpus_report`, `catalog_corpus`, `decide_corpus`, `target_sweep`. Planner: `fuzz_planner`, `props_planner`, `plan_tool.cpp`. Test audio: `gen_test_audio`, `native_analyze.cpp`. Phone against PC: `phone_vs_pc`, `phone_seams_vs_pc`, `phone_bt_vs_pc`, `phone_learning`, `device_check`. |
| `blindspots/` | The blind-spot pass: `gen_cases` and `run_cases` (constructed cases), `run_all` (whole library), `mono_fold`, `peaks`, `lessons_vs_features` (re-run after more lessons). |
| `perf/` | Animation on the phone: `anim_frames.sh` (gestures and frame shares), `late_frames.py`, `tap_xy.py` (find a control by its words). |
| `perceptual/` | Loudness model: `gen_tables` makes `perceptual_tables.inc`, `validate_reference` checks against the TVL 2018 code in `octave/`. |
| `branding/` | `trace_logo.py` regenerates the icon and mark from `lion_source.webp`. |
| `setup/` | `bootstrap-windows.ps1` sets up the toolchain. |
- `perf/tab_frames.py`: per-gesture frame figures from framestats files (late share and slowest frame).

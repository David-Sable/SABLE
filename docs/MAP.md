# Code map

Where to look. Read the file itself for detail; keep this to one line per area.

## Engine (`engine/`, C++, deterministic)
- `src/plan.cpp`: the queue planner: every gain rule, learning rules, JSON over JNI (`docs/plan-json.md`).
- `src/pipeline.cpp`, `analyzer.cpp`, `intent.cpp`: analysis of one song (loudness, structure, anchor, character).
- `src/perceptual_*.cpp`: perceived loudness (`_ref` host reference, `_fast` phone model).
- `src/contour.cpp`: the Quiet contour. `src/dmath.cpp`: deterministic math, used instead of libm. `src/dsp.*`: FFT, filters, resampling.
- `src/audio_source.cpp`: FLAC and MP3 decoding for analysis.
- `tests/`: doctest cases. `tools/`: `sable_cli` and table generators.

## App (`android/app/src/main/kotlin/app/sable/`)
- `playback/`: `PlaybackService` (Media3, car browsing via `LibraryTree`), `GainController` (asks the planner, applies the plan), `SableAudioSink` + `GainKernel` (gain in float), `ListenerLearning`, `LearningScoreboard`, `PlayCounter`, `PlaybackDiag` (the `diag` log), `SleepTimer`, `SableWidget` + `WidgetLook`, `ArtProvider`.
- `library/`: `LibraryScanner`, `TrackAnalyzer`, `ContentHasher` (cache key), `GainTags`, `FileText` (names from file names), `Organizer`.
- `data/`: `SableDatabase` (Room; schemas in `android/app/schemas/`), `Backup`.
- `decode/`: `AudioDecoder`, `PerfBoost`. `engine/`: `SableEngine` (JNI). `work/`: background scan (`ScanWorker`), weekly backup (`BackupWorker`).
- `ui/`: `SableApp` (root, layers, cover flight), `AppState`, `MainActivity` (debug commands listed at its top), `Player` (Now Playing, mini player), `Home`, `Library` (tabs, `CreditLine`, tiles), `WhySheet` + `WhyText`, `Sheet` (`SableSheet`), `Reveal`, `SongMenu`, `Playlists`, `Favorites`, `Organize`, `Search*`, `SleepSheet`, `WidgetSettingsActivity`, `Art`, `AlbumFlight`, `Icons`, `Logo`, `LoudnessShape`, `Touch`, `theme/Tokens.kt`.

## Docs (`docs/`)
- `NOTES.md`: current state, read first. `ROADMAP.md`: what is next. `HISTORY.md`: every past session in detail; search it, do not read it through.
- `TESTING.md`: test battery and phone know-how. `plan-json.md`: planner JSON. `INSTALL.md`: for David.
- `reports/`: measurements and milestone reports. `reviews/`: MATH reviews. `archive/`: finished plans.

## Tools
See `tools/README.md`.

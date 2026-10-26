# Milestone 6 report: UI, Why sheet, blind A/B (2026-10-26)

Device: Galaxy A17 5G (Android 16, 90 Hz display), app 0.6.0-m6 (optimized release build, R8), MATH-1.12.

## What was built
- **Design tokens** (`ui/theme/Tokens.kt`): every colour, type style, spacing step, radius and motion curve.
  - Colour: true black base, near-black matte surfaces, one accent (warm ivory `#E8E1D3`, brass `#B5975A` kept as the alternate).
  - Type: serif display, sans text.
  - Motion: one easing curve (0.2, 0, 0, 1) at 480/300 ms.
  - Surface: a faint static grain on matte surfaces.
  - Dark only.
- **Icons:** hand-drawn line icons (no emoji or font fallbacks; Samsung's font rendered ⏸ as an emoji).
- **Screens:**
  - Library: Albums grid with art, Artists, Songs, Folders.
  - Album, Artist and Folder detail.
  - Now Playing: album art as the hero, plus the engine at work, the song's loudness shape with the anchor glowing and a seekable playhead drawn at the display rate.
  - Queue, with the planned gain per item.
  - Why sheet, A/B, Settings.
  - A mini player on every library screen.
- **Why sheet:**
  - Shows applied gain, world and output ceiling, plus a plain-English line (e.g. "Part of the album: one gain for every song keeps its contrast, lowered 1.3 dB, 1.5 dB short of target so its peaks stay clean.").
  - Shows the loudness shape with the anchor intervals in words.
  - Shows measured loudness (BS.1770), perceived loudness of the body, body loudness, LRA, true peak and character.
  - Lists every rule with its dB, plus notes (16-bit decode margin, Quiet mode, ceilings, effects after Sable).
  - For a song that isn't playing, it shows the cached decision "on its own" in the current mode.
- **Blind A/B (MATH §30, new):**
  - Six analysed songs from different albums, 25 s excerpts centred on each anchor, with 20 ms edge fades.
  - Sable vs plain −14 LUFS vs off, all level-matched in the engine to Sable's sequence loudness, peak cap kept.
  - Random label mapping, switch freely, pick the most even, reveal, tally.
  - Gains are hidden everywhere during a round.

## Exit criteria
| Criterion | Evidence | |
|---|---|---|
| Token theme | `SableColors`, `SableType`, `Space`, `Radius`, `Motion` used by every screen; no literal colours in screens | ✅ |
| All screens | Library (albums, artists, songs, folders), Now Playing, Queue, Why sheet, A/B, Settings; checked on the phone by screenshot | ✅ |
| 90 Hz frame timing | Display reports `renderFrameRate 90`. Library fast scroll (12 flings, 831 frames): 1.56% janky (13 deadline misses), 0 missed vsync, median 11 ms. Now Playing while playing (10 s): 912 frames (≈ 91 fps), **0 janky, 0 deadline misses** | ✅ |

## Tests
- Engine: 46 test cases. A/B level match is exact to 1e-9 LU, off is one constant gain, plain keeps its L_I differences, and the cap holds under level match. Sanitizers clean.
- App JVM: 10 tests.
  - Gain stage: null test, boundaries, ramps, partial consumption, dither, and A/B edge fades (monotone 20 ms, silent ends).
  - Why text: the spec's example sentence, album/cap/seam, JIT/provisional.
- On device: A/B round started, label switch changed the playing song's gain −5.51 → −1.99 dB (20 ms crossfade), nothing revealed until the pick.

## Known limits
- Label switches reach the speaker about 0.5 s after the tap (AudioTrack + Samsung mixer buffer). The A/B screen says so.
- The few janky frames happen during fast flings while album art loads for the first time (thumbnails are cached afterwards).
- The excerpt loudness for level matching uses the anchor loudness (MATH §30.3), not a measurement of the exact excerpt.

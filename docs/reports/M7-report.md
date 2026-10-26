# Milestone 7 report: context, edge cases, battery, install (2026-10-26)

Device: Galaxy A17 5G (One UI, Android 16), app 0.7.0-m7 (optimized release build), MATH-1.13.

## Built
- **Quiet-mode contour (MATH §24):**
  - Engine: ISO 226:2003 contours (tables cross-checked against two independent copies), target `H(f)` between 75 and 70 phon, and an exhaustive grid fit of one low shelf plus one high shelf (RBJ, S = 1).
  - Result at calibration 0: low shelf 200 Hz **+2.0 dB**, high shelf 8 kHz **+0.6 dB**, rms fit error **0.24 dB**, headroom 2.6 dB added to the peak cap.
  - Playback: biquads in double per channel after the gain, 20 ms wet/dry crossfade on/off, bit-exact when off, off during A/B. It only acts in Quiet mode.
- **Output awareness (MATH §20):** each route (speaker, wired, USB-C, Bluetooth, Bluetooth LE) has its own level offset (−3…+3 dB) and peak ceiling (−3…−0.5 dBTP), from the defaults of §20.1. The planner uses them; a route change re-plans with a 20 ms crossfade. Settings shows the current route's sliders.
- **Listener learning (MATH §19):**
  - The update and the decay are engine functions (`learnUpdateJson`, and the planner's `learn` input), all unit-tested: environment filter ω, ±2 dB clamp, 30-day half-life, artist offset only after 2 songs.
  - The app watches the media volume in the first 20 s of each song. A window closes 20 s after the start or 3 s after the last press, so an undone press nets out. It ignores route changes and A/B rounds.
  - Learned offsets apply from the next play, never mid-track.
  - Offsets are shown and resettable on the Why sheet, per song and per artist; Settings can forget everything.
- **Edge cases (MATH §28):** ReplayGain/R128 tags in FLAC (Vorbis comments) and MP3 (ID3v2 TXXX) are read and shown on the Why sheet for comparison, never used. The phone's sound effects (Dolby/SoundAlive/EQ) are listed in the Why notes.
- **Battery:**
  - Settings shows whether SABLE is Unrestricted and can request it, with the One UI steps.
  - The playhead now redraws once per pixel of movement instead of every 90 Hz frame.
- **Install guide:** [docs/INSTALL.md](../INSTALL.md) covers:
  - copying the APK to the phone, or installing over USB with adb (Developer options and USB debugging steps)
  - the One UI battery walkthrough (Unrestricted, Sleeping apps)
  - turning off effects for fair listening

## Measured on the phone
| What | Result |
|---|---|
| Learning, one volume press (−6.3 dB on One UI's curve) in the first 20 s | Logged event, weight 1.0 → track −2.00 dB (clamped), artist −1.58 dB. Next play of the song: −0.54 → **−2.54 dB** (artist not applied yet: 1 song). Test data then erased |
| Volume measurement | One UI's `getStreamVolumeDb()` returns ≈ 0 dB at every step, so Sable uses AOSP's default media curve (fetched from the AOSP source: (1,−58), (20,−40), (60,−17), (100,0)). Documented in MATH-1.13 §19.1 |
| Volume change delivery | One UI did not deliver the volume broadcast, so Sable samples the volume every 250 ms only while a 20 s window is open |
| Contour on (Quiet) | Logged design matches the engine test. No gain change for a song with room under the cap (as expected) |
| CPU while playing | Before the redraw change: ~80% of one core (UI at 90 Hz). After: library + mini player **24%**, Now Playing **33%**, screen off **≈ 1.6%** (audio path alone: ExoPlayer 0.4%, decoder ≈ 0) |

## Tests
- Engine: 52 test cases. New ones: ISO 226 1 kHz identity (±0.05 dB, 20–90 phon), contour target shape, shelf edge gains, bounded deterministic fit, contour headroom in the cap, learning decay/combination/environment filter/clamp. Sanitizers clean.
- App JVM: 13 tests. New ones: contour crossfade, exact wet path, bit-exact when off; ReplayGain/R128 parsing (FLAC and ID3v2.4).

## Also in this milestone (owner requests)
- **Swipe down to close Now Playing**: the screen follows the finger and fades slightly; past 140 dp (or a fast fling) it closes, otherwise it settles back. Verified on the phone.
- **App icon**: a black adaptive icon with "SABLE" in thin warm-ivory strokes and a loudness line whose middle (the anchor) glows. Vector, with a monochrome layer for themed icons. Verified on the phone.

## Not verified
- Bluetooth and USB outputs were not connected during testing, so their per-route values were exercised by code paths and tests only.

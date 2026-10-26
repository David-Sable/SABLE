# SABLE

A local Android music player built around one idea: **understand each song before touching it, change only its level, and keep its feeling.**

The player exists to serve the **Sable Intent Engine**. By default the engine makes exactly one change per track: one static gain. There is no compression, no dynamic processing and no EQ (the one exception is the opt-in Quiet-mode contour described below). Your files are never modified.

> Status: **Milestone 1 complete** (MATH-1.2, two independent reviews). Milestone 2 (engine baseline) next. No app code yet. See [PLAN.md](PLAN.md) for milestones and [MATH.md](MATH.md) for the precise math. If this README and MATH.md ever disagree, MATH.md wins and the README is wrong.

---

## How Sable decides how loud a song should be

Most players measure one average number for the whole song (ReplayGain, or Spotify/Apple-style −14 LUFS normalization) and shift the track until that number matches. Sable listens more closely first.

### 1. Listening
For every song Sable measures:
- **Standard loudness** (ITU-R BS.1770 / EBU R128): integrated loudness, momentary and short-term loudness over time, loudness range, and true peak. These are the same numbers professional meters show.
- **How loud it actually feels**: a model of human hearing (Moore–Glasberg, the basis of ISO 532-3) that notices what the standard meter misses, like heavy bass and dense, wall-of-sound mixes.
- **Its shape**: where the intro, body, builds, breakdowns and outro are.
- **Its character**: how peaky or squashed it is, its spectral tilt, how busy its transients are, and how much its sections contrast.

### 2. Understanding — the anchor
Every song has a part it is built around, usually the chorus or main body. Sable calls this the **anchor**. It is the loudest *sustained* part of the song: sections at least 8 seconds long, together at least 20 seconds (or 20% of the song). Intros, outros, fades, silence and brief spikes never count.

Sable also gives each song a plain-language **character**: *quiet and intimate*, *build and release*, *dynamic arc*, *sustained and dense*, or *steady*.

### 3. Balancing — the gain rules
Each rule below is in plain language. MATH.md has the exact version (section numbers in brackets).

1. **Anchor matching** (§16.2). Sable sets the gain so the song's anchor *feels* as loud as every other song's anchor, at the target: **−14** (Standard) or **−18** (Quiet), on the familiar LUFS scale. A song with a quiet intro won't get over-boosted, because the intro isn't the anchor. A bass-heavy or dense song that feels louder than its meter reading is matched by how it feels.
2. **Sanity bound** (§16.2). If the hearing model ever disagrees with the standard meter by more than 4 LU, Sable trusts it only up to that limit. This protects against strange signals.
3. **Intimacy allowance** (§16.3). Songs judged *quiet and intimate* are allowed to sit a little under the target (1 dB by default, adjustable from 0 to 2 dB), so they stay intimate.
4. **Peak integrity** (§16.4). Sable never pushes a song past the output's true-peak ceiling. If the ideal gain would clip, Sable accepts a slightly quieter song instead. A transparent limiter exists, but it is off unless you turn it on.
5. **Album continuity** (§17). When an album plays in order, the whole album gets *one* gain, so the artist's intended contrast between songs survives. Setting: Auto / Always track / Always album.
6. **Seam smoothing** (§18). In shuffle and mixed queues, Sable nudges each song by at most ±0.5 dB so the end of one song flows into the start of the next without a jump. It never applies inside an album, and never next to a fade or silence.
7. **Learning you** (§19). If you press the volume keys in the first 20 seconds of a song, Sable takes it as gentle feedback and learns a small offset for that song and artist. The offset is at most ±2 dB, fades over about a month, and is always visible and resettable.
8. **Your output** (§20). Phone speaker, USB-C and Bluetooth each get their own headroom and an optional calibration offset. Bluetooth gets extra headroom (−1.5 to −2 dBTP), because its lossy codecs can create new peaks when they re-encode.
9. **Never full level by accident** (§22). A song that hasn't been analysed yet gets a quick analysis (about a second) before it plays. If that isn't ready in time, it starts 6 dB down and then eases to the right level.

Every song has a **Why** sheet showing the measured numbers, where the anchor is, the gain applied, every rule that fired, and a one-line explanation, e.g. *"Intimate piano piece: raised 3.1 dB, held 1 dB under target to keep it quiet."*

### 4. Context (opt-in extras)
- **Quiet-mode contour** (§24): at low volume your ears lose bass and treble. If you turn this on, Quiet mode adds a gentle correction based on ISO 226 equal-loudness contours, at most +3 dB. It is the only non-flat processing in Sable.

### 5. Proof
- **Blind A/B**: compare Sable, plain −14 LUFS normalization and no normalization on the same shuffled sequence, unlabeled and level-matched, then see which was which.

---

## Repository layout (planned)
```
MATH.md            precise math (single source of truth)
README.md          this file
PLAN.md            milestones, decisions, risks
engine/            portable C++20 engine (host + Android NDK)
tools/             host CLI, validation scripts, toolchain setup
android/           Kotlin / Compose / Media3 app
```

## Building
Toolchain setup for Windows: `tools/setup/bootstrap-windows.ps1` installs JDK 21, the Android SDK/NDK and CMake, llvm-mingw (host clang with sanitizers) and FFmpeg into a user folder. Build instructions arrive with Milestone 2.

## Licensing notes
- The perceptual model follows the published Moore–Glasberg–Schlittenlacher papers. Some numeric tables (threshold, α and A tables, outer/middle-ear magnitude response) come from the authors' public reference code, which is "free for any research purposes; for commercial use please contact Brian C. J. Moore." Sable is a personal, non-commercial project. Any commercial use would need that permission first.
- Test music is never committed to this repository.

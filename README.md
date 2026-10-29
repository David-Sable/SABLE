# SABLE

A local Android music player built around one idea: **understand each song before touching it, change only its level, and keep its feeling.**

The player exists to serve the **Sable Intent Engine**. By default the engine makes exactly one change per track: one static gain. There is no compression, no dynamic processing and no EQ (the one exception is the gentle Quiet-mode contour described below, which you can turn off). Your files are never modified.

> Status: **all seven milestones complete** (2026-10-26): the engine (MATH-1.22), library analysis, playback with per-song gain, the designed app with the Why sheet, and the context features (Quiet contour, per-output settings, learning). Install it with [docs/INSTALL.md](docs/INSTALL.md). The app starts in **Easy mode** (just Normal or Quiet); one switch in Settings opens the advanced controls. See [PLAN.md](PLAN.md) for milestones and [MATH.md](MATH.md) for the precise math. If this README and MATH.md ever disagree, MATH.md wins and the README is wrong.
>
> **Look:** the app follows your phone's light or dark setting. Dark is true black with warm ivory; light matches Samsung's own light mode (soft grey page, near-white cards) with dark ink.

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

1. **Anchor matching** (§16.2). Sable sets the gain so the song's anchor *feels* as loud as every other song's anchor, at the target: **−14** (Standard) or **−19** (Quiet), on the familiar LUFS scale. A song with a quiet intro won't get over-boosted, because the intro isn't the anchor. A bass-heavy or dense song that feels louder than its meter reading is matched by how it feels.
2. **Sanity bound** (§16.2). The hearing model can move a song at most 3 dB away from where the standard meter would put it. Typical music lands exactly where the standard meter says; only songs that genuinely sound different (deep bass, very sparse or very dense mixes) are nudged.
3. **Intimacy allowance** (§16.3). Songs judged *quiet and intimate* are allowed to sit a little under the target (1 dB by default, adjustable from 0 to 2 dB), so they stay intimate.
4. **Peak integrity** (§16.4). Sable never pushes a song past the output's true-peak ceiling. If the ideal gain would clip, Sable accepts a slightly quieter song instead. A transparent limiter exists, but it is off unless you turn it on.
5. **Album world or song world** (§17). You choose by how you start playback. **Play album** gives the whole album *one* shared gain, so the artist's intended contrast between songs survives. **Tapping a song** puts it in its own world: it is balanced on its own, and so is every song after it. One exception: when the next track on the same album runs straight into it with no pause (live records, mixtapes, segued albums), those tracks share the album's level so nothing jumps at the join (only the join counts: a quiet moment in the middle of a track does not stop it). Setting: Auto (by how you start playback) / Always song / Always album.
6. **Seam smoothing** (§18). In shuffle and mixed queues, Sable nudges each song by at most ±0.5 dB so the end of one song flows into the start of the next without a jump. It never applies inside an album, and never next to a fade or silence.
7. **Learning you** (§19). If you press the volume keys in the first 20 seconds of a song, Sable takes it as gentle feedback and learns a small offset for that song and artist. The offset is at most ±2 dB, fades over about a month, and is always visible and resettable. A first press is only a hint (it counts up to 1 dB); it becomes a full lesson when you do the same to that song on another day, and an artist's lesson reaches their other songs only after two different days, so a passing mood does not stick. It ignores presses that are about the room or the moment rather than the song: a mute or a big jump, a press while paused, a turn-up during a quiet intro, and a press while the volume is still settling after a big change. Where the volume knob cannot be seen by the phone (a car stereo), two small **Quieter / Louder** buttons on Now Playing tell Sable the same thing for the playing song; they do nothing unless you tap them, follow the same one-hint-then-lesson rule, and can be switched off in Advanced.

   **Mood colours.** The loudness silhouette on Now Playing takes a faint colour from the song itself: ivory in the middle, a touch of brass where it is bright and intense, a cool grey-blue where it is dark and bassy. It is only a picture; it never changes the sound. Switch it off in Advanced.
8. **Your output** (§20). Phone speaker, USB-C and Bluetooth each get their own headroom and an optional calibration offset. Bluetooth gets extra headroom (−1.5 to −2 dBTP), because its lossy codecs can create new peaks when they re-encode.
9. **Never full level by accident** (§22). A song that hasn't been analysed yet gets a quick analysis (about a second) before it plays. If that isn't ready in time, it starts 6 dB down and then eases to the right level.

Every song has a **Why** sheet showing the measured numbers, where the anchor is, the gain applied, every rule that fired, and a one-line explanation, e.g. *"Intimate piano piece: raised 3.1 dB, held 1 dB under target to keep it quiet."*

### 4. Context (opt-in extras)
- **Quiet-mode contour** (§24): at low volume your ears lose bass and treble. In Quiet mode Sable adds a gentle correction based on ISO 226 equal-loudness contours (a +2 dB lift below 200 Hz and +0.6 dB of air, at most +3 dB). It is on by default in Quiet, can be turned off in Advanced settings, and is the only non-flat processing in Sable. Peaks stay protected.
- **Your outputs** (§20): each output (speaker, Bluetooth, USB-C, wired) keeps its own level offset and peak ceiling.
- **Learning** (§19): volume presses early in a song teach small offsets, shown and resettable on the Why sheet. Each place learns on its own (§19.3): your car, your headphones and the phone speaker are told apart by the device, so turning a song up in the car never changes it on headphones.
- **Home**: the first tab. It shows the albums you played recently, your most played songs this month and albums you have not heard in a while. It is built only from your own listening on this phone (a song counts after 30 seconds), never sent anywhere, and never changes how a song sounds.
- **Search**: the magnifier at the top of the library. Type part of a title, album or artist (accents and capitals don't matter); tapping a song plays it and the songs listed after it.
- **Sleep timer**: the moon on Now Playing (right of the controls). Choose "End of this song" or a time; when the time is up, Sable stops at the end of the song that is playing, never in the middle and never with a fade.
- **Swipe the mini player** (the bar at the bottom) left or right to change songs from any page; tap it to open Now Playing.
- **Picks up where you left off**: if the phone closes Sable, or you swipe it away, it comes back paused on the same song, at the same spot, with the same queue. A play button in the car, on headphones or in the phone's media controls wakes Sable and carries on, even when Sable was not running.
- **Home-screen widget**: long-press the home screen → Widgets → Sable. It shows the cover, title, artist and album, with previous, play/pause and next; tap the cover or title to open Sable. Resize it to taste: one row, a 2x2 square where the cover fills the card, or two rows and more with a big cover.
- **Car and lock screen**: the cover shows on the lock screen, in the notification and on cars that support cover art. **Android Auto**: browse Albums, Artists and Songs (with Shuffle all) on the car's screen. Because Sable is not from the Play Store, Android Auto hides it until you allow it once: open Android Auto's settings, tap **Version** about 10 times, open the ⋮ menu → **Developer settings**, and tick **Unknown sources**.
- **Queue**: **Clear** empties everything after the playing song (which keeps playing); the ✕ on a song removes just that one.
- **Long-press a song** for Play next, Add to queue, Go to album or Why. **Shuffle all** sits at the top of the Songs tab. On Now Playing, tap the title to open its album, the artist line to open the artist.
- **Repeat**: the loop left of the controls. Tap once to repeat the queue, again to repeat this song, again to turn it off. Sable plans the gain across the jump from the last song back to the first, so the wrap sounds as smooth as any other change.
- **Swipe the cover** on Now Playing to change songs: left for the next, right for the previous.

## Repository layout
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

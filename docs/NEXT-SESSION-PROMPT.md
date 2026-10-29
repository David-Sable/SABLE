# Next level: the brief for the next (PC) sessions

Written 2026-10-28 after reading the whole repo, at David's request ("where's the next level? premium is the goal").
David pastes the short prompt at the bottom; this file is the plan behind it. Work top to bottom; each part ends with
"done means". Tick parts off in NOTES "Now" as they land.

## Where Sable stands
The hard part is done and is rare: an engine that understands each song (anchor, character, perceived loudness),
changes only its level, explains itself (Why sheet) and learns per place. The app looks calm and premium (true black,
ivory, serif, the lion). What stops it feeling like a finished premium product is **everything around the music**:
the widget wastes space, the car shows only a Bluetooth logo, the library has no memory of you (no favourites, no
recently played, no playlists), and nothing protects what Sable has learned if the phone is reset. Premium means:
it looks deliberate everywhere you meet it (home screen, lock screen, car), it remembers you, and it never loses your
things. The engine's next step comes from data (the scoreboard), not guesses.

## Part 1: the widget, done properly (tonight; David's request)
David's screenshot (4x2 on One UI, 0.24.6): the content is an 84 dp cover and text centred in a ~180 dp tall card, so
there is a dead band above and below, the cover is under half the card's height, the controls sit centred under the
text column (the play button is not on any line with the cover) and the right half above them is empty.
Design (decide details yourself, judge by screenshots on the phone):
- **Cover fills the card's height**: square, flush with the card's top, left and bottom edges, the card's own corner
  radius on the outer corners (clip with `clipToOutline` on the art box; size it from the widget's real height, not
  a fixed 84 dp: use more `RemoteViews` size buckets, or `layout_height="match_parent"` + a square art box).
- Right column: title (serif, up to 2 lines) and artist **top-aligned**, the album as a third, faint line if there is
  room; the controls **pinned to the bottom** of that column, play button the visual anchor, prev/next close to it
  (not stretched by weights across the whole width).
- Optional, try and show David: the cover blurred and darkened as the whole card's background (pre-blur a small
  bitmap in Kotlin; keep the bitmap small, widget updates go over IPC and have a size limit). Keep it only if it still
  reads as calm black-and-ivory Sable.
- More sizes: 4x1 (row, already there), 4x2 (above), **2x2** (cover full-bleed with a play button and a thin scrim for
  the title), 4x3 and up (big cover, text and controls under it). Check the resize handles on One UI.
- A proper **widget preview** for the picker (`previewLayout`, and `previewImage` for older launchers).
- Idle state: lion tile, "Tap play to resume" and the last song's title if known.
Done means: screenshots on the phone of every size in dark and light, idle and playing, with a long title and with no
cover; no text cut; David says it is better. Put his widget back to the size he had (4x2) unless he says otherwise.

## Part 2: the car and the lock screen
David drives a lot, and the Toyota head unit showed only the Bluetooth logo once.
- **Artwork for the media session**: give every `MediaItem` an `artworkUri` (a small `ContentProvider` serving the
  cached cover) and/or `artworkData`, so the lock screen, the notification, Bluetooth AVRCP (cover art where the car
  supports it) and Android Auto all show the cover.
- **Android Auto browsing**: switch `PlaybackService` from `MediaSessionService` to Media3's `MediaLibraryService`
  (same library, no new dependency) with a small browse tree: Continue (the saved queue), Recently played, Albums,
  Artists, Shuffle all. Each start goes through the same album-world / song-world rules as the app.
- **Notification / lock screen custom buttons**: Quieter / Louder (MATH §19.4 already exists) as session custom
  commands, so the car's screen and the lock screen can teach without opening the app. Only if it stays tidy.
Done means: cover on the lock screen and notification (screenshot); Auto checked with the Desktop Head Unit (DHU) on
the PC or in the car by David; a drive with logs read afterwards.

## Part 3: it remembers you (ask David which of these he wants, then build in this order)
All local, in Room (a schema migration, never a wipe), no network.
1. **Listening history** (a play counts after ~30 s or half the song): it powers everything below.
2. **A Home view** above the Library tabs, quiet and short: Continue, Recently played albums, your most played this
   month, "Not heard in a while". Easy mode keeps it minimal (two rows at most).
3. **Favourites**: a heart on Now Playing and in the song menu; a Favourites row/tab.
4. **Playlists**: create, add from the song menu, reorder, play (song world by default, as with tapped songs).
5. Maybe later: lyrics from local `.lrc` files or embedded tags (no network), shown calmly on Now Playing.
Done means: each item checked on the phone with David's real library; history survives an app update.

## Part 4: never lose anything (trust)
- **Backup of what Sable learned** (open item): export/import of learning, scoreboard, settings and (Part 3) history
  and playlists to a single file in Downloads; Android Auto Backup rules so a new phone restores it.
- Keep the release rule: never leave an experimental build on the phone.
Done means: export, `--es cmd forget`, import, and the Why sheets show the same lessons again.

## Part 5: the premium polish pass (one sweep, measured)
- Screenshot every screen and state (dark and light, Easy and Advanced, empty library, scanning, long names) and
  write a punch list in NOTES before changing anything: spacing, alignment, type scale, icon weights, empty states,
  touch targets, haptics.
- The remaining Now Playing first-frame hitch (~19 ms, one late frame): try pre-composing Now Playing off-screen
  while the mini player shows (measure memory and frames with Perfetto before/after; revert if not clearly better).
- Mood colours: David wants it minimal or gone; ask his verdict and act on it.
Done means: punch list cleared, before/after frame numbers recorded.

## Part 6: the engine's next step (only from data)
- Read the scoreboard and learned state after the car drives (`--es cmd scoreboard`, `learned`, `diag`). Build
  place-level learning (MATH idea 1: a place that leans one way across many songs, possibly keyed to spectral tilt
  for the car's bass) **only** if a place shows a few dozen corrections leaning ~80% one way.
- Open questions still in NOTES: does a lesson on one track of a Play-album unbalance the album (`plan.cpp`, test on
  the phone); 5.1 files are up to ~3 dB too loud (front two channels only; the library is stereo, low priority);
  ALAC from iTunes/XLD.
- MATH.md first for any rule change, version bumps per CLAUDE.md.

## Rules for every part
CLAUDE.md applies in full: MATH.md first for the engine, tests with engine changes, deterministic C++, all gain math
in the engine, check David's route and listening before touching playback, put the phone back as found, no stray
plays. Ask David first only for a major new dependency or a change to the philosophy (Parts 3 and 4 add features,
not processing; still tell him what you built). Commit and push each part on its own, watch CI to green, tell David he
can unplug, then name the release.

---

## The prompt David pastes into a new chat
> Continue SABLE. Read CLAUDE.md, docs/NOTES.md and docs/NEXT-SESSION-PROMPT.md first. My phone is plugged in over
> USB. Start with Part 1 (the widget), show me screenshots of each size, then carry on with Part 2. Ask me before
> Part 3 which of those I want.

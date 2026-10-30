# Roadmap

What comes next, most useful first. Written 2026-10-30 by Claude; David picks the order. When something lands, remove it here and record it in NOTES. The finished plan before this one is in `docs/archive/next-level-2026-10-28.md`.

## 1. Sable in the car
Android Auto browsing is built (0.25.0) but has never been run. David turns on Android Auto settings -> tap Version ~10 times -> developer settings -> Unknown sources, then drives once; afterwards read `diag`.
Done means: browsing and playing from the car's screen works, or what fails is known.

## 2. Is learning really working?
Wait until David says he has listened enough (2026-10-30).
Read `scoreboard` and `learned` after a week of real listening: are songs Sable already learned corrected less than new ones? Place-level learning is built only if a place has a few dozen corrections leaning ~80 % one way across different songs. Also test on the phone whether a lesson on one track of a Play-album unbalances the album (`plan.cpp`).
Done means: numbers in NOTES and a clear yes or no on each question.

## 3. Premium motion, what is left
Batch 1 landed in 0.42.0. Still to do, most noticeable first:
- Tapping an album: its cover flies from the tile into the album page, as Now Playing's does from the mini player.
- The back gesture on ordinary pages (it follows the thumb only on Now Playing so far).
- The mini player: the next cover beside it on a swipe, as on Now Playing.
- A pass over Search, the queue, Settings and the sheets, filmed frame by frame.
Done means: David finds nothing that cuts instead of moves.

## 4. Smaller polish
- The Why sheet's details still have a few 44-56 ms gaps while opening (render side).
- Organize leaves non-audio files (cover images) in the old folders.
- ALAC from iTunes/XLD is untested; ffmpeg-made ALAC plays but cannot be analysed.
- The mid-song output switch (0.17.4) was never confirmed by David on the JBL.
- USB-C output is untested on the phone.

## Parked
- Lyrics from the files themselves: David cancelled it for now (2026-10-30). Found before stopping: 17 lyric tags across the 392 FLACs on the PC and no `.lrc` files; Android does not let Sable read `.lrc` files without a folder permission. No code was written.

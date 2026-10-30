# Roadmap

What comes next, most useful first. Written 2026-10-30 by Claude; David picks the order. When something lands, remove it here and record it in NOTES. The finished plan before this one is in `docs/archive/next-level-2026-10-28.md`.

## 1. Sable in the car
Android Auto browsing is built (0.25.0) but has never been run. David turns on Android Auto settings -> tap Version ~10 times -> developer settings -> Unknown sources, then drives once; afterwards read `diag`.
Done means: browsing and playing from the car's screen works, or what fails is known.

## 2. Quieter and Louder without opening the app
The car's knob never reaches Android, so the car teaches only through the Now Playing buttons, which are awkward to reach while driving. Add them as media-session buttons (notification, lock screen, Android Auto), through the same learning rules (MATH §19.4).
Done means: a tap on the lock screen and on the car's screen teaches like the app's button, and the notification still looks calm.

## 3. Is learning really working?
Read `scoreboard` and `learned` after a week of real listening: are songs Sable already learned corrected less than new ones? Place-level learning is built only if a place has a few dozen corrections leaning ~80 % one way across different songs. Also test on the phone whether a lesson on one track of a Play-album unbalances the album (`plan.cpp`).
Done means: numbers in NOTES and a clear yes or no on each question.

## 4. Never lose what Sable learned, automatically
Backup is manual, and learning takes months to earn. Keep the newest few backups in Downloads/Sable on their own (about weekly, in a quiet worker), with "last saved" under Settings -> Backup.
Done means: the file appears without being asked, and restoring it brings every lesson back.

## 5. Lyrics from your own files
Local `.lrc` files and embedded tags only, no network, shown calmly on Now Playing. Ask David first: this is a new thing on his favourite screen.
Done means: David likes it on songs he knows, and it adds no frame cost.

## 6. Smaller polish
- The Why sheet's details still have a few 44-56 ms gaps while opening (render side).
- Organize leaves non-audio files (cover images) in the old folders.
- ALAC from iTunes/XLD is untested; ffmpeg-made ALAC plays but cannot be analysed.
- The mid-song output switch (0.17.4) was never confirmed by David on the JBL.
- USB-C output is untested on the phone.

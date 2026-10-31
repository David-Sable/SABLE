# Roadmap

What comes next, most useful first. Written 2026-10-30 by Claude; David picks the order. When something lands, remove it here and record it in NOTES. The finished plan before this one is in `docs/archive/next-level-2026-10-28.md`.

## 0. Frame rate (batch 1 done in 0.45.2, David to judge)
Measured per gesture with `tools/perf/tab_frames.py` (slowest frame per gesture). After the phone optimises Sable: tab switches 20 to 47 ms, were up to 166. Left: tab slides still spend 20 to 37 ms in a frame where a page boundary is crossed; album back has one 60 ms frame while the Library is rebuilt under it (keeping the Library alive under other pages would remove it); a fresh install stays near 100 ms until the phone compiles the profile, overnight on charge.
Done means: David finds it smooth.

## 1. Sable in the car (Android Auto, parked 2026-10-31)
Android Auto browsing is built (0.25.0) but has never been run. David's car is too old for Android Auto, so there is no hurry. The test that needs no car is Google's Desktop Head Unit on the PC over the USB cable (needs Android Auto's developer settings -> Unknown sources on the phone). Do it when convenient; David said we will get to it.
Done means: browsing and playing from the Android Auto screen works, or what fails is known.

## 1b. Keeping Sable safe (David asked Claude to own this, 2026-10-31)
- The signing key lives only on the PC (`~/.android/debug.keystore`) and in a GitHub secret. Lose both and installed copies can never be updated. Needs a safe second copy: David copies the file himself (Claude never prints, copies or moves it); waiting on David (2026-10-31 he plans Google Drive; advised 2-step verification on and a second copy in a password manager).
- Known-good versions: done 2026-10-31. A commit with `[keep]` in its message gets a permanent `keep-<version>` release (CI never deletes it). First one: 0.48.1. Add `[keep]` to the build of each version David has judged good.
- History check: done 2026-10-31. No key, audio or large file was ever committed; the signing password in `build.gradle.kts` is Android's public debug default, so the key file is the only secret. Repeat before a public release.
- Before a public release: a licence decision and a clean-install test.

## 2. Is learning really working?
Wait until David says he has listened enough (2026-10-30).
Read `scoreboard` and `learned` after a week of real listening: are songs Sable already learned corrected less than new ones? Place-level learning is built only if a place has a few dozen corrections leaning ~80 % one way across different songs. Also test on the phone whether a lesson on one track of a Play-album unbalances the album (`plan.cpp`).
Done means: numbers in NOTES and a clear yes or no on each question.

## 3. Smaller polish
- The Why sheet's details still have a few 44-56 ms gaps while opening (render side).
- Organize leaves non-audio files (cover images) in the old folders.
- ALAC from iTunes/XLD is untested; ffmpeg-made ALAC plays but cannot be analysed.
- USB-C output is untested on the phone.

## Parked
- Lyrics from the files themselves: David cancelled it for now (2026-10-30). Found before stopping: 17 lyric tags across the 392 FLACs on the PC and no `.lrc` files; Android does not let Sable read `.lrc` files without a folder permission. No code was written.

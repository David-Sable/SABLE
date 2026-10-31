# Session notes (handoff log)

The shared memory between Claude sessions: on the PC, in the cloud, and across new chats (a new chat remembers
nothing except what is in this repo). **Read "Now" first. Before a session ends, or when the chat is getting long,
rewrite "Now", add one line to the log here and the full entry at the top of `docs/HISTORY.md`, then commit and push.**
Keep entries factual: what changed, what was verified (and how), what is left. David reads this too, so write in plain language.

## Starting a new chat
David can open a new chat (PC or phone) and say:
> Continue SABLE. Read CLAUDE.md and docs/NOTES.md first.

That is all the next session needs: CLAUDE.md has the rules and workflow, this file has the current state.

---

## Now (keep this short: under 30 lines)
- **Version:** 0.50.1, MATH-1.39. Library: 419 songs on the phone, all analysed, tidied into Music/Artist/Album. Dark only.
- **Phone:** Galaxy A17 5G. Animator scale 1x. Install from `https://github.com/David-Sable/SABLE/releases/latest`.
- **Next:** David judges the tabs and the centred widget controls on 0.45.4; Android Auto when David drives, then `docs/ROADMAP.md`. The learning deep dive waits until David says he has listened enough. Lyrics are parked.
- **Backups:** by themselves weekly into Downloads/Sable (`sable-auto-…json`, newest 4 kept); first one saved 2026-10-30 02:37, restore proved on the phone.
- **Waiting on David:** allow Nearby devices when 0.48.1 asks, then listen on the JBL and the car; Android Auto's Unknown sources setting (needed before the car test).
- **Keep as they are (David approved):** Now Playing's framed cover (344 dp on black under the NOW PLAYING bar), its pause shrink and skip bounce, its open and close, targets -14 and -19.
- **Learning so far (2026-10-29):** corrected plays: JBL 19 %, car 7 %, speaker 7 %. The JBL figure is largely David riding the volume in the garage on purpose. Too few taught songs replayed to judge yet.
- **Cleanup (0.48.0):** Home is rows of covers; no info icon on song rows (Why is in the long-press menu and on Now Playing); the song count only shows while Sable is working. David to judge.
- **Type (0.47.0):** names are serif, facts are sans, across the app. David to judge it.
- **Mood bar (0.46.2):** Patina colours (teal, sea glass, copper, sand) and calmer words; David to judge Patina on the phone. Dusk was the second choice. The numbers for tuning: `docs/HISTORY.md`, MATH §8.5.
- **Taps (0.49.0, replaces 0.45.5's 1 dB hold):** a tap counts in full at once (1 dB a tap, ceiling +-2); at the ceiling the app says "As far as it goes". Ceiling widened to +-4 in 0.50.0 (31 of 84 taught songs were at +-2). Was 0.45.5: every Quieter or Louder tap now counts in full (1 dB), held at ±1 until repeated 12 h or more later, and tapped plays count as corrected on the scoreboard from now on. David to judge in the car. The car's 3 % predates this, so read it as new weeks build up.
- **Known limits:** ALAC from iTunes/XLD untested; USB-C output untested; 5.1 files up to ~3 dB too loud (the library is stereo); EBU's official test files never downloaded (compliance uses generated signals).

---

## Log (newest first; keep about 10 lines, older sessions move to `docs/HISTORY.md`)
- **2026-10-31 0.50.1:** Now Playing's swipe is capped at 3 screens a second: a flick can no longer outrun the cover; slow swipes unchanged. David to feel it; tune with `animflag vmax`.
- **2026-10-31 0.50.0, MATH-1.39:** the Quieter and Louder buttons go to +-4 dB per song; volume keys and the artist stay at 2. Traced every place the 2 dB lived, tested on the phone muted. David to listen.
- **2026-10-31 0.49.1:** album mode's line says "album gain, your -1.0 dB" when a song carries a tap lesson; the numbers were right, the words were not.
- **2026-10-31 0.49.0, MATH-1.38:** Quieter and Louder taps need no probation: a song takes a tap in full up to the +-2 dB ceiling. 31 of 84 taught songs already sit at that ceiling. David to test.
- **2026-10-31 0.48.1 (David confirmed the mid-song output switch works on the JBL):** Sable now asks for Nearby devices (Bluetooth), as Spotify does; a candidate fix for the Bluetooth trouble, untested on the phone.
- **2026-10-30 0.48.0:** cleanup: Home in one layout, 50-odd info icons gone, header status only when it has news, headings 18.
- **2026-10-30 0.47.0:** Now Playing's serif on every page for names, tabs and Home's headings; no row moved.
- **2026-10-30 0.46.2:** David picked Patina from six packages drawn on real songs; the Advanced note no longer mentions brass.
- **2026-10-30 0.46.1:** the highlighted sections glow again, in the song's own colours, while the song plays through them; the first 0.46.0 veil was too faint.
- **2026-10-30 0.46.0, MATH-1.37:** mood bar: four muted colours around ivory, the ivory glow no longer covers them, words last at least 6 s and can say rising, easing, hushed, warm, deep. Measured on all 420 songs.
- **2026-10-30 0.45.5, MATH-1.36:** taps are never the room: full weight, a clear limit during probation, counted on the scoreboard. Verified on the phone. GitHub tidied: `main` only.
- **2026-10-30 car check (no code):** read diag, learned and scoreboard after David's drive. Taps and sound fine; quarter-weight taps and the scoreboard's blind spot found, see HISTORY.
- **2026-10-30 0.45.4:** widget (two rows and up): previous, play and next centred under the text; they sat 61 px left of the middle. Measured on the phone: play at 770.5, column middle 770.5.
- **2026-10-30 0.45.3:** swiping tabs: the name lights at once again and an up or down scroll works straight after a swipe; slowest swipe frame 25 ms.
- **2026-10-30 0.45.2:** frame rate, batch 1: tabs stay built, library lists worked out once in the background, far tabs one slide, own code in the baseline profile; launch screen holds until Home is ready. Slowest tab frame 166 ms to 47 ms once the phone has optimised Sable; filmed and measured.
- **2026-10-30 0.45.1:** light mode residue gone: black launch screen with the phone in light mode, the phone switching theme no longer rebuilds the screen, light palette deleted from the code; filmed on the phone.
- **2026-10-30 0.45.0:** brass is the signature: the play button on Now Playing, the mini player and the widget, no colour option; the mini player lost its skip button (swipe does that).
- **2026-10-30 0.44.4:** Why sheet: Hide details is one slide into place; it used to drop off the screen and climb back.
- **2026-10-30 0.44.3:** David chose the framed look from his own screenshot: NOW PLAYING bar back, cover 344 dp with rounded corners on black; edge-to-edge dropped.
- **2026-10-30 0.44.2:** cover back at the very top, all black again (album colour removed, which also removes the black square behind a paused cover), larger title and taller shape.

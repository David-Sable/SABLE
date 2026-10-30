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
- **Version:** 0.45.0, MATH-1.35. Library: 419 songs on the phone, all analysed, tidied into Music/Artist/Album. Dark only.
- **Phone:** Galaxy A17 5G. Animator scale 1x. Install from `https://github.com/David-Sable/SABLE/releases/latest`.
- **Next:** Android Auto when David drives, then `docs/ROADMAP.md`. The learning deep dive waits until David says he has listened enough. Lyrics are parked.
- **Backups:** by themselves weekly into Downloads/Sable (`sable-auto-…json`, newest 4 kept); first one saved 2026-10-30 02:37, restore proved on the phone.
- **Waiting on David:** Android Auto's Unknown sources setting (needed before the car test); his word on the mid-song output switch on the JBL.
- **Keep as they are (David approved):** Now Playing's framed cover (344 dp on black under the NOW PLAYING bar), its pause shrink and skip bounce, its open and close, the mood colours, targets -14 and -19.
- **Learning so far (2026-10-29):** corrected plays: JBL 19 %, car 7 %, speaker 7 %. The JBL figure is largely David riding the volume in the garage on purpose. Too few taught songs replayed to judge yet.
- **Known limits:** ALAC from iTunes/XLD untested; USB-C output untested; 5.1 files up to ~3 dB too loud (the library is stereo); EBU's official test files never downloaded (compliance uses generated signals).

---

## Log (newest first; keep about 10 lines, older sessions move to `docs/HISTORY.md`)
- **2026-10-30 0.45.0:** brass is the signature: the play button on Now Playing, the mini player and the widget, no colour option; the mini player lost its skip button (swipe does that).
- **2026-10-30 0.44.4:** Why sheet: Hide details is one slide into place; it used to drop off the screen and climb back.
- **2026-10-30 0.44.3:** David chose the framed look from his own screenshot: NOW PLAYING bar back, cover 344 dp with rounded corners on black; edge-to-edge dropped.
- **2026-10-30 0.44.2:** cover back at the very top, all black again (album colour removed, which also removes the black square behind a paused cover), larger title and taller shape.
- **2026-10-30 0.44.1:** cover moved just under the clock; pause shrink (94 %, soft spring, corners rounding) and the song-change bounce are back and stronger.
- **2026-10-30 0.44.0:** Now Playing edge to edge from the top of the screen with a faint album colour beneath; sheets slide away on every action; all sheets, Search and Settings filmed.
- **2026-10-30 0.43.0:** premium pass, batch 2: Now Playing cover 360 dp, album cover flight, back gesture on pages, mini player neighbours (fixes the stuck bar), wrong-tab bug, Home top, solid page arrival, queue opening; filmed and measured.
- **2026-10-30 0.42.1:** Now Playing's cover at 28 dp margins with air above it (David to judge the size); the signature landing back on every song change; song changes after the cover lands.
- **2026-10-30 0.42.0:** premium pass, batch 1: every song change moves as one, Library tabs swipe, back gesture on Now Playing, quiet press, steady Now Playing layout; filmed and measured on the phone.
- **2026-10-30 0.41.0:** Now Playing's swipe shows the next and previous album covers beside the playing one (`CoverDeck`); checked on the phone, 5.0 % late frames in cover swipes.
- **2026-10-30 0.40.0:** Quieter / Louder on the notification, lock screen and Android Auto; automatic weekly backups; the lion as the notification icon; checked on the phone.
- **2026-10-30 0.39.2:** Recently played keeps a steady height, so the page below stays still while swiping; measured on the phone.
- **2026-10-30 0.39.1:** mini player bar stays solid while its contents fade (page words no longer show through on open/close).
- **2026-10-30 0.39.0:** Organize (Folders tab): moves songs into Music/Artist/Album after Sable's question and Android's permission, with Undo; done on the phone, nothing lost.
- **2026-10-30 0.38.0:** widget redesign and widget Settings (clear-to-solid, cover glow, brass, album line); checked on the phone.
- **2026-10-29 0.37.0:** premium pass: 8 polish fixes (credits, covers on Artists/Folders, dates, device name, queue, track numbers, shorter Advanced text) and one spring family for sheets, sections and pages; measured and recorded.
- **2026-10-29 0.36.1:** opening cover flies diagonally from the mini player again; closing rides the sheet.

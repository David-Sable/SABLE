# Session notes (handoff log)

The shared memory between Claude sessions: on the PC, in the cloud, and across new chats (a new chat remembers
nothing except what is in this repo). **Read "Now" first. Before a session ends, or when the chat is getting long,
rewrite "Now" and add one entry at the top of the log, then commit and push.** Keep entries short and factual: what
changed, what was verified (and how), what is left. David reads this too, so write in plain language.

## Starting a new chat
David can open a new chat (PC or phone) and say:
> Continue SABLE. Read CLAUDE.md and docs/NOTES.md first.

That is all the next session needs: CLAUDE.md has the rules and workflow, this file has the current state.

---

## Now (keep this current)
- **State:** all milestones M1-M7 done, plus Easy mode (default), swipe-down Now Playing, the Quiet contour on by default in Quiet (0.7.1), the white lion logo (0.8.0), the A/B verdict line (0.8.1) and the song-changes A/B round (0.9.0) the playback log (0.9.1), separate Song changes / Albums rounds (0.9.2) and (0.10.0) the blind A/B removed by owner decision, (0.10.1) faster queue starts, and (0.11.0) flowing album tracks kept together plus swipe-to-change-song, (0.12.0) the sleep timer, (0.13.0) learning per place, (0.14.0) smoother animations plus the fragment fix, (0.14.1) a Baseline Profile, and (0.15.0) library search and repeat, (0.15.1) learning fixes, (0.15.2) learning rules MATH-1.23 and room for the mini player, (0.16.0) convenience round + MATH-1.24, (0.17.0) play-button resume, widget, queue clear, crash fixes. MATH-1.24.
  Latest GitHub release: build-28 = 0.15.2 (2026-10-27; CI keeps the newest three), signed with the phone's key (certificate SHA-256 `0786a932…3be2`).
- **On David's phone:** 0.17.0 installed over USB 2026-10-27 (speed-profile compiled). Per-place learning verified live (JBL Charge 5, see log). Library 396 songs, all analysed.
  Battery shows "Optimized"; David may tap "Allow unrestricted" in Settings.
- **Open items (none blocking):**
  - USB-C output untested on the device. Bluetooth verified in real use (JBL Charge 5, car); per-place learning live on the JBL.
  - Formats verified on the A17 (2026-10-27, generated signals): AAC, Opus, Vorbis, WAV 16/24 match the PC within 0.05 LU. **ALAC** (ffmpeg-made) is "audio/unknown" to the platform extractor: it plays (Media3's own extractor) but cannot be analysed, so it stays at the provisional gain. Untested: ALAC from iTunes/XLD (may differ).
  - EBU official test files (Tech 3341/3342) still need a browser download; compliance tests use generated signals.
  - David's listening check of character labels and the largest gain moves (from M3) is still open.
- **Skips in the car (open):** David heard skips on a drive (car Bluetooth "CAR MULTIMEDIA", Android Auto present, Maps prompts) on 0.8.1, none on the way back. Phone logs had rolled off; nothing left pointed at Sable (no crash; Samsung's freezer tried but skipped freezing it). 0.9.1 keeps a playback log on the phone: next time read it with `--es cmd diag` (tag `SableDiag`): UNDERRUN lines mean audio starvation (then raise the AudioTrack buffer), COMMAND next from a car package means the car sent the skips, PLAY_WHEN_READY/SUPPRESSED show interruptions, ROUTE shows Bluetooth drops.
- **Direction (David, 2026-10-26):** no more ear tests (A/B removed, MATH §30 retired). Real listening, whole songs in the car, "amazing" with the perceptual estimate. Work now: check the math and code, give the engine more understanding so it changes even less, refine the app, optimize.
- **Done 2026-10-27 (0.16.0):** the agreed convenience round (see log).
- **Ideas pending:** test USB-C output and the other formats on the phone (formats need non-copyrighted test files, e.g. generated with ffmpeg). David's pronouns: he/him.

---

## Log (newest first)

### 2026-10-27 (PC) Big pass: play button, widget, queue clear, formats, crash fixes (0.17.0)
- **Play button wakes Sable:** Media3 `MediaButtonReceiver` in the manifest + `onPlaybackResumption` (the saved queue, resolved and planned). Verified: Sable killed (`am kill`, as the system does), `KEYCODE_MEDIA_PLAY` → RESUMPTION asked → playing from the saved spot. (A force-stopped app cannot be woken; that is Android, not Sable.)
- **Restore race fixed:** on a cold start the automatic restore could replace a queue David had just asked for, and it planned the old queue (dropping the new queue's gains until the next replan). Now any controller request (`queueRequested`) or resumption wins, and the restore plans only if still wanted. QUEUE log line per request.
- **Queue:** Clear (everything after the playing song) and ✕ per upcoming song; the next song is replanned (its predecessor changed).
- **Formats on the phone** (`~/dev/sable-corpus/formats`, generated pink noise + chord, "master" −23 LUFS and "hot" −7.9 LUFS with peaks at full scale; report `reports/formats_pc.txt`): AAC, Opus, Vorbis, WAV16, WAV24 analysed on the A17 match `sable_cli` within 0.05 LU (integrated, anchor, PLE). Lossy decoders give 16-bit for analysis (`decodeClipped` = true, as M4 found); the +1.0 dB `M_clipTP` margin covers the measured overs (Opus true peak +1.17 on the PC vs +0.40 measured on the phone → 1.40 used). Test files removed from the phone afterwards (library back to 396).
- **Crashes found and fixed:** (1) ALAC: the platform reports "audio/unknown" without sample rate → `ContentHasher` NPE (now defaults to 0); analysis then fails cleanly. (2) **Native crash**: with no readable duration the quick estimate decoded nothing and called `jitResult` on a null handle (SIGSEGV in `JitPipeline::resultJson`); playing such a song killed Sable. Now `JitAnalyzer` refuses without audio and every JNI handle function returns early on 0. ALAC now plays at the provisional gain.
- **Widget** (`playback/SableWidget.kt`, `res/layout/widget_player.xml`): cover, title, artist, previous / play-pause / next as media-button presses (so play also resumes when Sable is not running); updated by PlaybackService on transitions and play/pause. Registered (dumpsys appwidget); **not yet placed on the home screen** (needs David's long-press).
- Bluetooth codec-aware ceiling (§20.1): left at "codec unknown" −2.0 (needs a Bluetooth permission prompt).
- Volume muted for the tests and restored to 5; no learning written.

### 2026-10-27 (PC) Third JBL session, convenience round (0.16.0, MATH-1.24)
- Session: no UNDERRUN/errors; gains sensible (Atrocity-style album runs at one gain, a FLOW pair, nothing provisional or capped). 4 new lessons at `bt:JBL Charge 5`: Steppa Pig −1.92 and Hustle Bones −2.00 at full weight (the 10 min window of 1.23 worked: previous events were 18+ min old); JRJRJR 0.25 weight; Star people pressed and undone (ignored, correct).
- **Blind spot 3 (§19.1, MATH-1.24):** JRJRJR was turned down 3.5 min after Hustle Bones, but David had put the volume back up in between (Hustle Bones ended at −32.33 dB, JRJRJR started at −24.67): the room had not changed, the songs were loud. The environment filter now needs the volume left where the previous press put it (`prevEndDb` vs `startDb`, 0.5 dB). Existing JRJRJR lesson (−0.48) left as is. Test from the real numbers.
- Noted, not changed: collaborations ("JPEGMAFIA x Danny Brown") count as their own artist, so they rarely generalise (safe direction).
- **Phone-only songs** (4 MP3s, pulled to `~/dev/sable-corpus/phone-extra`, never committed) through `sable_cli --decide`: two Tinashe MP3s with true peaks +2/+3 dBTP → −5.1/−5.6 dB Normal; "Like That" −2.2; AcidJazz test file PLE 5 LU under its meter → bounded +2.0, peak-capped. No blind spot.
- **Convenience (David):** `songSwipe()` shared by the Now Playing cover and the mini player (draw-phase, spring with finger speed); resume (`sable_resume` prefs: request ids, index, position; saved on transitions/pause/every 10 s; restored paused at service start if nothing is loaded; RESUME log line); long-press `SongMenu` (Play next, Add to queue, Go to album, Why; song world); Now Playing title → album, artist line → artist; Shuffle all on Songs.
- Verified on the phone (muted, volume restored to 5, no learning written): mini swipe next/previous/short drag, long-press menu, Play next lands after the playing song with its gain planned, force-stop → RESUME 16 songs at song 1, 41 s paused, title → album. Engine 54 cases, app tests green. Not done: system media resumption (car play button with Sable not running) needs Media3's playback-resumption callback; only in-app resume for now.

### 2026-10-27 (PC) Second JBL session: two learning blind spots fixed (0.15.2, MATH-1.23)
- Session clean again (no UNDERRUN/errors). Two new lessons at `bt:JBL Charge 5`: Rina Sawayama "STFU!" and JPEGMAFIA "its dark and hell is hot", each one step down (−3.83 dB), both weighted 0.25.
- **Blind spot 1 (§19.2):** JPEGMAFIA now had 2 taught songs, so the artist offset (+1.68, mostly from "$ (Money)" turned up 2 steps) was added: "its dark and hell is hot", just turned **down**, would have played +1.2 dB **louder**. Now a taught song keeps its own lesson; the artist's applies only to untaught songs, from ≥ 2 taught songs that agree in sign (JPEGMAFIA's disagree: nothing applied). Planner input `trackTaught`, `artistTrackDbs`. Why sheet says when the artist's offset is not used and why.
- **Blind spot 2 (§19.1):** "STFU!" was compared with the previous event from the earlier session (30 min before) and weighted 0.25. The environment filter now looks back only `T_env` = 10 min (`prevDays` stored per place). The two existing 0.25-weighted lessons are left as they are (small, and they fade).
- **Settings cut off (David):** with a song loaded, the mini player covered the end of Settings (no bottom room) and the Queue; `bottomBarHeight()` also left out the gesture bar. Fixed (`endPad`), About text reworded. Verified on the phone with the mini player showing (muted; volume restored to 5; Advanced put back to Off).
- Engine 54 cases (new: own lesson vs artist, disagreeing songs, 10 min window), app tests green.

### 2026-10-27 (PC) First real listening session; learning checked (0.15.1)
- David's session on the **JBL Charge 5** (Bluetooth): playback log clean (no UNDERRUN, no errors, skips/taps all from the app). Learned state (`--es cmd learned`, new): 4 songs, all under place `bt:JBL Charge 5`; every value matches §19 (e.g. Injury Reserve Δv −3.83 dB → track −1.92, artist −0.96; Ice Cube +3.83 with the environment filter ω 0.25 → +0.48; two steps (±7.7 dB) clamp at ±2). Artist offsets wait for a second song (n_a = 1 for all).
- **Bug fixed:** "05. Lost" (Danny Brown) was taught before its content hash existed (new file, played before analysis), so it was stored as `t:id8477` while planning looks up `t:<hash>`: its −2 dB would never apply. Track offsets now fall back to the id key and move to the hash key at the next event.
- Learning events (window, ignored, LEARN) now also go to the persistent playback log (`--es cmd diag`): logcat lost this session's lines (Claude cleared logcat before reading them; don't).
- **Skip on the JBL at an unlock** (David): no UNDERRUN or error in Sable's log at any point, so Sable's buffer stayed fed; the dropout was after the app, in the Bluetooth link (unlock is a known moment for Samsung radio scans). Open; if it repeats, note the time so the log can be lined up.
- Verified on the phone (muted, volume restored to 5 after the 20 s window, nothing learned, repeat put back to off): search ("atro" → album + 15 songs), repeat icons (off/all/one), repeat-all wrap Hell For It → Get Hi as a natural start at its planned −8.00 dB, no ramp.

### 2026-10-27 (PC) Search and repeat (0.15.0)
- **Search** (`ui/Search.kt` index + `ui/SearchScreen.kt`, route `Route.Search`, magnifier in the library header): artists, albums, songs. Accent/case-insensitive (`searchForm`: NFD, marks removed, punctuation as spaces); every typed word must match the start of a word, or from 3 letters anywhere; ranking: name starts with the query, then all words in the name, then words needing artist/album. Tapping a song = song world from there through the listed results (as in the Songs tab). Query kept while browsing from a result. Debug: `--es cmd open --es screen search --es q <text>`. Test: SearchTest.
- **Repeat** (off → queue → this song), left of the Now Playing controls; the moon moved to the right of the controls. Remembered between sessions (`sable_player` prefs), REPEAT lines in the playback log. GainController: with repeat all the planning window is circular (the last song precedes the first), so the wrap gets its seam and the first song keeps its predecessor after the wrap (otherwise a replan would change its gain mid-song as an in-track ramp). Repeat one replays the same item: same static gain. No engine change.
- Verified: app tests and release build on the PC. **Not yet on the phone** (it was not connected): check search typing/scrolling, the repeat icon states and a repeat-all wrap (ITEM line for the first song with SEAM, no gain ramp).
- **Old releases:** CI now keeps only the newest three releases (step "Keep only the newest releases"); this push deletes build-3 (wrong key) and the other old ones.

### 2026-10-27 (PC) Baseline Profile (0.14.1)
- `app/src/main/baseline-prof.txt`: all of Sable's code (`HSPLapp/sable/**->**(**)**`, `Lapp/sable/**;`) on top of the libraries' own profiles. androidx.profileinstaller was already in the APK (via Compose), so no new dependency was needed. Sideloaded installs are compiled at the next idle maintenance; for tests: `adb shell cmd package compile -f -m speed-profile app.sable`.
- Measured on the phone: cold launch (am start -W, 5 each) 306–339 ms not compiled → 238–270 ms with the profile. Animation late-frame shares unchanged (≈ 4–5 %): the remaining late frames are GPU/compositor-side (issue/swap stages), not code warm-up.

### 2026-10-27 (PC) Blind-spot review, smoother animations (0.14.0, MATH-1.22)
- **Library-wide check on the PC** (`~/dev/sable-corpus/reports/library_full.jsonl`, all 392 tracks through `sable_cli --decide`): characters 198 sustained/dense, 94 dynamic arc, 90 steady, 10 build-release, 0 quiet-intimate (INTIMACY never fires in this library); PERCEPTUAL_BOUND 9, PEAK_CAP 21, anchor fallback 2. PLE − L_anchor: median +0.4, 5–95 % −2.3…+2.0 LU.
- **Blind spot fixed (§16.2.3, A_ple = 3 s):** Tinashe "You" (6.1 s, anchor 0.6 s of sound) had PLE − L_anchor = −42 LU; the bound held it to +3 but it still pushed the gain to the peak cap (+10.1 dB). A PLE from an anchor under 3 s is now treated as absent (BS1770_ONLY) in track gain, album pooling and seams: "You" plays +7.7 dB. Every other track has an anchor ≥ 23 s; nothing else changes. Note: the cached `standard` gain in the phone's analysis JSON (shown in lists) is from analysis time; playback decides fresh.
- **Animations, measured on the phone** (`tools/perf/anim_frames.sh`, frames past their display deadline, music paused): library scroll 1–2 %, cover swipe 3 %, Now Playing open/close 4–6 %, Advanced settings 5 %. Changes: fades use ModulateAlpha (`ui/Reveal.kt`: `lightAlpha`, `Reveal`; route cross-fade; swipe layers) instead of offscreen buffers; gestures move in the draw phase (graphicsLayer translation), follow the finger undispatched and settle on a velocity spring; neighbours' covers decoded and GPU-uploaded ahead (`prepareToDraw`); Advanced section fades in place (no per-frame relayout); grain brush cached; progress drawings on their own layers. **Measurement lesson:** while music plays, the ~150 ms progress redraws are lone frames that nearly all count as late and swamped the first numbers (6–27 %); measure with music paused. **Clean before/after (2026-10-27, same script, music paused, two runs each):** 0.12.0 → 0.14.0: cover swipe 5.0/5.0 % → 4.2/4.2 %, Now Playing close/open 6.7/5.9 % → 7.5/5.8 %, Advanced 6.8/4.1 % → 6.5/5.1 %, library scroll 2.5/0.8 % → 0.8/1.7 %. I.e. within noise: with music paused the app was already close to smooth; the earlier 6–27 % figures were the progress-tick artefact. The gesture changes (spring with finger speed, prefetched covers) change feel, not frame counts. Baseline Profile added in 0.14.1 (see log).

### 2026-10-26 (PC) Learning per place (0.13.0, MATH-1.21 §19.3)
- ListenerLearning keys every state by place (`placeOf(type, productName)`: speaker, wired, `bt:<name>`, `ble:<name>`, `usb:<name>`); unprefixed keys = the pre-1.21 shared state, used as the seed when a place has none. The window remembers the place it opened in; the environment filter's previous event is per place. Why sheet: "Learned for Bluetooth · CAR MULTIMEDIA"; Forget removes the place's and the shared state. Test: LearningPlaceTest. Not yet on the phone.

### 2026-10-26 (PC) Sleep timer (0.12.0)
- `playback/SleepTimer.kt` (state) + PlaybackService: when due, `player.pauseAtEndOfMediaItems = true`, so playback stops at the end of the playing song (no cut, no fade); it clears itself when the song ends (PLAY_WHEN_READY false, reason end of item). Choices: end of this song, 15/30/45/60/90 min. UI: moon on Now Playing, `SleepSheet`, status line "Stops after this song" / "Stops at the end of the song playing at HH:MM". Diag log: SLEEP lines.
- Verified on the phone: "End of this song", seek to 2:30 of 2:37, stopped at the end (SLEEP stopped at the end of the song). Timed path tested with the debug command `--es cmd sleep --ei sec 5`: due mid-song (SLEEP due), stopped at the song's end 6 s later.

### 2026-10-26 (PC) Flowing album tracks, swipe the cover (0.11.0, MATH-1.20)
- §17.3: song-world neighbours that are the album's next track (same disc, track+1), with no fade at the join and ≤ 0.5 s of silence in each track (duration − nonSilentSec, existing analysis, no rescan), on a complete album, share the album gain (rule FLOW, no seams). Not in Always-track mode. §18 text corrected: the first item of an album/flow run is not seamed either (the code already did this). Planner input gains `disc`, `trackNo`; GainController sends the album JSON when a neighbour shares the album. Engine test with 6 negative cases; Why sentence and "flowing album tracks" label.
- `--es cmd flows` lists, per album, which tracks the planner joins. On David's library: segued records join (Atrocity Exhibition 5–6, 8–10; JPEGMAFIA albums; Death Certificate), pop albums with pauses join nothing (Charli, thank u next, SAWAYAMA). Verified playing "Pneumonia" by tap: ALBUM −5.78 + FLOW.
- Now Playing: swipe the cover left for the next song, right for the previous (or restart if first); the cover follows the finger and the new one slides in. Verified on the phone (COMMAND next / previous).

### 2026-10-26 (PC) Review pass, controller optimizations (0.10.1)
- Reviewed the playback path (SableAudioSink, GainKernel, GainController) after the A/B removal: no audio or math defects found.
- Fixed: a skipped-to / tapped song listed the SEAM rule (natural variant) although user starts play unseamed; the Why sheet would have said "nudged to flow". `publish` now drops SEAM for manual starts.
- Faster: `prepareStart` plans only the window (cur−1..cur+30), not the whole queue (Songs tab = up to 396 items before the first note); planning JSON without anchor blocks is cached per content hash; complete album JSON is cached per album while its members are unchanged; replan requests that arrive before the queued one runs are merged. Measured on the phone: tap → first plan 456 ms cold, later plans 12–50 ms (`PLAN n items in x ms`, logcat debug). The old whole-queue time was not measured.

### 2026-10-26 (PC) Blind A/B removed (0.10.0, MATH-1.19)
- David: the ear tests are too subtle in short excerpts; in the car Sable "with the feel estimate" was amazing. Removed AbScreen, AbSession, the round log, excerpt fades (GainKernel/SableAudioSink), the A/B button, `abCondition`/`abSeams`/`sablelufs`/`excerptSec` from the planner, tests and tools/ab. Planner output is now `{"items":[...]}`. Planner code equals the pre-0.9.0 version minus A/B. Final A/B tallies for the record: Clips S 15 / P 12 / O 6; Song changes S 8 / P 12 / O 4; Albums S 7 / P 6; Feel a few rounds.
- Engine 51 cases green, app tests green; on the phone: library without the A/B button, album playback logs ITEM with ALBUM gain.

### 2026-10-26 (PC) Feel test (0.9.5, MATH-1.18 §30.7)
- New A/B round "Feel": song changes between albums with S, **L = Sable with PLE_anchor := L_anchor** (planner `abCondition: "sablelufs"`, items built twice, L level-matched to real S) and P. Own tally `abfeel_`. Engine refactor: albums/items/seams built in a `build(noPle)` lambda; test "condition L is Sable without the PLE offset". Round buttons wrap (FlowRow).
- Verified on the phone with `abplans` (no pick): L sits between S and P; first change S stepped +5.0 dB, L +0.8, P −0.4.
- **Next:** when David has ~10 Feel rounds, run `tools/ab/analyse_rounds.py` and read the Feel tally. L preferred over S → revisit the PLE offset / B_p (§16.2) with David before changing normal playback (philosophy-level).

### 2026-10-26 (PC) First saved A/B rounds analysed (0.9.4)
- Song changes tally now S 8 / P 12 / O 4 of 24 (10 unlogged + 14 logged: S 6 / P 5 / O 3).
- `tools/ab/analyse_rounds.py`: per round, rms loudness step at the changes (next opening vs previous ending, after gain) and between the songs' main sections (anchor LUFS after gain). Seams do not separate S and P (step at the change ≈ equal). **Lead:** in rounds David gave to Plain, Sable's main-section steps were 0.87 dB rms larger than Plain's; in rounds Sable won, 0.26 dB. Sable's main-section steps differ from LUFS because it matches the perceptual estimate (anchor PLE), which differs from LUFS by up to ±3-5 dB on some songs. Only 11 rounds: a lead, not a result. Testing it with the Feel round (0.9.5, §30.7).
- Bug fixed: rounds saved by 0.9.3 could include the previous queue's items (a replan of the old queue after the reset). 0.9.4 resets in `prepareStart` and records only the round's queue ids (`AbSession.roundIds`). Verified with `abplans`: 8 items, ending/opening pairs.

### 2026-10-26 (PC) A/B round log (0.9.3)
- In A/B, GainController also plans the other conditions for the same queue (`recordAbPlans`) and keeps the first figures per item in `AbSession.plans`; a pick saves the round to files/ab-rounds.jsonl (`AbRoundLog`). Verified on the phone with `abplans` on a running Song changes round (no pick, tallies untouched).

### 2026-10-26 (PC) A/B rounds split (0.9.2)
- David's 22 mixed Song changes rounds: Sable 8, Plain 5, Off 9. Cause: after level matching, Off keeps an album's balance exactly as Sable's album gain does, so album changes split votes between two near-identical versions. MATH-1.17 §30.6: Song changes = 4 changes between albums (S, P, O); Albums = 4 album changes, S vs P only (labels 1, 2); verdict uses chance 1/m (§30.5).
- Verified on the phone: Albums round shows 2 labels, both tracks ALBUM −5.78 dB, second natural. Tests: FlowSequenceTest (both kinds), AbVerdictTest (m = 2).

### 2026-10-26 (PC) Playback log, Song changes verified (0.9.1)
- The owner is **David** (this PC used to be David's sister Dana's). Docs and memory updated.
- `playback/PlaybackDiag.kt`: a persistent log (files/playback-log.txt, newest ~256 KB) of underruns, player/sink errors, play/pause reasons (focus loss, unplugged), suppression, skips/seeks and which app sent them (MediaSession `onPlayerCommandRequest`), item changes, route changes, service start/stop, swipe-away. `--es cmd diag` prints it.
- Song changes round played on the phone: change 1 song world (`manual=false`, SEAM −0.50), change 2 album (ALBUM gain), labels switch. Installed 0.9.1 over USB.

### 2026-10-26 (PC) Song changes A/B (0.9.0)
- Why: David's clip rounds stood at Sable 15, Plain 12, Off 6 of 33. Clips (§30.1) leave out seams and album world, where Sable differs most from plain −14, so Sable vs Plain is expected to be close there.
- New round kind (MATH-1.16 §30.6): 4 song changes, the last 20 s of one song straight into the first 20 s of the next; 2 inside complete albums (tracks really in a row, album world) and 2 between albums (song world, seams). Sable plays as normal playback (first song manual, second natural); plain and off as before; level match unchanged. Own tally (`abflow_*` prefs), same verdict line.
- Engine: `abSeams` planner input + test. App: `GainController.Excerpt` (length and start), no edge fade across the real transition (`setEdges` fadeIn/fadeOut), `pickFlowChanges` + `FlowSequenceTest`. Engine 53 cases, app tests green. Not verified on the phone (unplugged).

### 2026-10-26 (PC) A/B verdict (0.8.1)
- Under the A/B tally, one plain sentence: the chance that random picking would make Sable win this often (binomial tail, chance 1/3 per round), shown after 10 rounds; under 5% reads "You can hear the engine". MATH-1.15 §30.5 (display rule only). Unit tests in `AbVerdictTest`.
- Verified on the phone: David's 13 of 26 shows "about 1 time in 17, so it could still be luck". One more Sable win (14 of 27) crosses 5%.

### 2026-10-26 (PC) White lion logo (0.8.0)
- David chose a white lion (their AI-generated artwork, `tools/branding/lion_source.webp`) after my own drawn attempts (S mark, face lions, heraldic lions) didn't reach the quality bar. Traced with vtracer (99.6% pixel match) by `tools/branding/trace_logo.py` into `drawable/lion_mark.xml` (in-app `SableMark`) and the launcher foreground (56 dp wide in the 108 dp canvas, inside the safe circle). Monochrome themed layer reuses the foreground.
- Gotcha: Android rejects resource strings over 32,767 characters (`STRING_TOO_LARGE`; the icon falls back to the robot). Keep one `<path>` per traced shape. Debug command `--es cmd icontest` loads the icon drawables and logs any error.
- Verified on the phone: icon in App info, mark beside the title and on the empty Now Playing screen.

### 2026-10-26 (PC) Logo (0.7.2)
- The SABLE mark: an S sitting on a level line inside a fine ring (warm ivory on black). Launcher icon (adaptive + monochrome themed layer) and `ui/Logo.kt` (`SableMark`, same geometry) beside the title, in About and on the empty Now Playing screen. Chosen from 4 rendered options; verified on the phone.
- Checked on the phone before this: 0.7.1 from GitHub build 8 installed over the PC build; Quiet switched the contour on automatically.

### 2026-10-26 (PC) Contour automatic in Quiet (0.7.1)
- Owner decision: the Quiet contour is on by default in Quiet (Easy mode always; Advanced has an off switch). MATH-1.14 §24.
- Stored under a new preference key (`contourOn`, default true) so the "off" left by an earlier device test does not carry over.

### 2026-10-26 (PC) GitHub builds and handoff setup
- GitHub Actions (`.github/workflows/build.yml`): engine tests with ASan/UBSan on Linux clang, app JVM tests, signed
  release APK published per push to `main`, only after the engine tests pass and only if the certificate matches.
- Fixed for Linux: JSON reader in `plan.cpp` (libstdc++ rejects `pair<string, J>` inside `J`).
- Signing explicit (`signingConfigs.sable`, env `SABLE_KEYSTORE` on CI); versionCode = minutes since 2026-01-01, so PC
  and GitHub builds always install over each other. Secret `SABLE_KEYSTORE_B64` added by David.
- Wrote CLAUDE.md (session guide) and this file.

### 2026-10-26 (PC) Easy mode, icon, swipe-down
- Easy mode default: Settings shows Normal/Quiet, battery, library; the Advanced switch reveals the rest. Easy uses
  `Settings.effective()` (Sable's defaults); stored advanced values are kept. Why sheet folds its details.
- Adaptive black icon (vector wordmark + anchor line); swipe-down closes Now Playing. Both verified on the phone.

### 2026-10-26 (PC) Milestones M1-M7
- See `docs/reports/M2-report.md` … `M7-report.md` and the decisions log in PLAN.md.

# Milestone 5 report: playback (2026-10-26)

Device: Galaxy A17 5G (Android 16), app 0.5.0-m5, MATH-1.11, Media3 1.11.1.

## Design (details: docs/M5-design.md, MATH §23.1)
- **Gain stage in the audio sink.** Media3 1.11 runs custom AudioProcessors only on its 16-bit path. So the gain is
  applied by `SableAudioSink`, which wraps `DefaultAudioSink` (float output enabled). Every PCM buffer is converted to
  float, multiplied by the item's gain, and handed on. If a device cannot take float, the stage writes 16-bit with
  TPDF dither (§23.4).
- **Per-item gain from the first sample.** The renderer reconfigures the sink at each item's first sample with that
  item's period. The sink looks the item up in a gain table, so no gain is ever early or late, gapless items included.
  A reconfiguration without a flush is a natural transition (seamed gain); after a flush it is a user start (§18).
- **One planner in the engine** (`sable::planQueueJson`, docs/plan-json.md) decides album world vs song world (§17.1),
  album gain (§17.2), seams (§18), JIT/provisional (§22) and the final composition (§21) for the 30 items around the
  playing one. The app re-plans when the queue changes, an item starts, analysis finishes, a setting changes, or the
  output route changes.
- **Not-analysed songs:** playback waits up to 1.5 s for JIT (§22.2), otherwise starts at the provisional gain. The
  full analysis runs in the background (one at a time, boosted), then the gain moves with the slow §23.3 ramp.
- **Decoders:** Android's own Codec2 decoders first (the A17's vendor FLAC decoder mislabels 16-bit as float), asked
  for float output. MediaSession + Media3's foreground media notification (`mediaPlayback`).

## Exit criteria
| Criterion | Evidence | |
|---|---|---|
| Gain processor null test | JVM test: at 0 dB, 16-bit input → `x/32768` exactly; float input (overs ±1.5 included) bit-identical. At -7.3 dB, every sample = `x · (float)10^(-7.3/20)` | ✅ |
| Float path | On device, Sable's AudioTrack is `AUDIO_FORMAT_PCM_FLOAT` (0x5). The 16-bit fallback is tested (TPDF: unbiased within 0.01 LSB, within ±1 LSB + dither) | ✅ |
| Gapless | Natural transitions reach the sink without a flush (no gap, logged `manual=false`). Media3 trims encoder delay/padding (LAME, FLAC exact). The boundary step lands exactly on the next item's first sample (JVM test) | ✅ (sample-exact test on JVM; no audio capture on device) |
| Album / seam | *Illmatic* via Play album: one album gain (-1.34 dB, album intent +0.21 capped by the loudest peak) on track 1 and, after a natural transition, track 2. Song world: each song its own gain. Seam math is tested exactly in the engine (±0.5 dB bound, fades, JIT break, manual starts) | ✅ |
| MediaSession + foreground service | `PlaybackService` foreground (type mediaPlayback, id 1001) with the media notification and lock-screen controls | ✅ |

## Measured on the phone
- Mode switch while playing (*The World Is Yours*): -1.35 → -6.35 dB → -1.35 dB (Quiet/Standard, 20 ms crossfade).
- New, unanalysed MP3 (*AcidJazz*, Kevin MacLeod, added for the test): JIT 344 ms, so playback started at the JIT
  gain (+1.42 dB). The full analysis finished in the background 4.6 s later on a big core. The gain then moved to the
  full decision (+2.41 dB) with the rate-limited ramp.
- Engine: 45 test cases / 2621 assertions pass (5 new planner tests, sanitizers on). App: 6 JVM tests pass.

## Known limits (carried forward)
- **Output latency is about 0.5 s** (AudioTrack buffer + Samsung mixer). The gain is applied before that buffer, so a
  mode switch is heard about 0.5 s after the tap. Fine for M5; to revisit for A/B in M6.
- Android mixes Sable's float track into a 16-bit HAL on the speaker. That is after Sable and outside its control.
- Bluetooth codec is not visible to apps, so A2DP uses the "unknown codec" ceiling (-2.0 dBTP, §20.1).
- Learning offsets, output calibration and the limiter are M7. The planner already takes them as inputs (0 / off).

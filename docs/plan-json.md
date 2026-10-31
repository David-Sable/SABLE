# Queue planner JSON (`sable::planQueueJson`, JNI `SableEngine.planQueue`)

The planner decides the gain of every item in a window of the play queue (MATH §17.1, §18, §21, §22). All the gain math
lives in the engine. The app only gathers inputs and applies the outputs.

## Input
```json
{
  "targetLufs": -14,          // §16.1: -14 Standard, -19 Quiet
  "intimacyLu": 1.0,          // §16.3
  "ceilingDbtp": -1.0,        // §20.1, from the output route
  "limiter": false,           // §23.5 (not in the app yet)
  "outputCalDb": 0.0,         // §20.2, clamped to [-3, 3]
  "albumMode": "auto",        // §17.1: "auto" | "track" | "album"
  "albums": {                 // only albums that an item may play in album world
    "<albumId>|<albumArtist>": {"complete": true, "tracks": [<analysis>, ...]}
  },
  "items": [
    {"analysis": <analysis> | absent, "jit": {"jitLufs": .., "jitTpDbtp": ..} | absent,
     "album": "<key>" | absent, "albumIntent": true, "manual": false,
     "learnedDb": 0.0 | "learn": {"trackDb", "trackDays", "artistDb", "artistDays", "artistTracks",
                                  "trackTaught": bool, "artistTrackDbs": [..]},
     "disc": 1, "trackNo": 4}
  ]
}
```
- `<analysis>` is the cached `TrackPipeline::resultJson` object. The planner reads `integratedLufs`, `truePeakDbtp`,
  `shortFile`, `decodeClipped`, `anchorLufs`, `anchorPle`, `anchorSec`, `character`, `openLufs`, `endLufs`, `fadeIn`,
  `fadeOut`, plus `anchorBlockPower` for album members.
- `complete` = every track of the album in the library is analysed. Otherwise the album's items use track mode and get
  the rule `ALBUM_PENDING`.
- `disc`, `trackNo` (§17.3): the item's place on its album. Song-world neighbours that are the next track and flow into
  each other (no fades; `trailSilenceSec` of the first + `leadSilenceSec` of the second ≤ 0.5 s, MATH-1.32; analyses without those two fields fall back to ≤ 0.5 s of silence anywhere in each, read from `frames`, `sampleRateHz`, `nonSilentSec`) share the album
  gain and get the rule `FLOW`; the album must be listed in `albums` and complete.
- `learn` (§19.2): stored offsets for the place playing now; the planner decays and combines them. `trackTaught` = the
  song has its own lesson (then the artist's is not used); `artistTrackDbs` = the stored offsets of the artist's taught
  songs (the artist's applies only when there are ≥ 2 and they share one sign). MATH-1.30: `trackConfirmed` (absent = stored
  before 1.30 = trusted; false = at most 1 dB applies) and `artistFirstDays` (the artist's offset applies only once its
  last event is ≥ 12 h after this). `learnUpdateJson` takes/returns `trackFirstDays`, `trackConfirmed`, `artistFirstDays`; it also takes `headroomDb` (MATH-1.31: room under the song's peak cap; under 0.5 dB a press up returns `{"ignored":"capped"}`) and `tap` (§19.4: no environment weight, no probation; `trackDb` goes to ±4 and a tap past it returns `{"ignored":"limit"}`, MATH-1.36, 1.38, 1.39).
  `learnedDb` instead = a value frozen
  when the item started.
- `seamDb` (§18, MATH-1.40): the item's seam, frozen when it started by a natural transition; the app sends back the `seamDb` of the output. Absent = work it out from the previous item.
- `manual` = this item began, or will begin, by a user action. The seam chain follows the gain each item actually
  plays with.

## Output
```json
{"items": [{"gainDb": .., "manualGainDb": .., "baseDb": .., "seamDb": .., "learnedDb": .., "capDb": .., "source": "full|jit|provisional",
            "albumRun": false, "rules": [{"id": "ANCHOR_MATCH", "deltaDb": ..}, ...]}]}
```
- `gainDb`: `g_final` after a natural (gapless) transition, i.e. seamed.
- `manualGainDb`: `g_final` when the user starts the item (no seam).
- `capDb`: the effective cap (`null` for digital silence; a provisional item assumes a full-scale peak, MATH-1.40).
- Malformed input returns `{"error":"bad input"}`.

Numbers are written with `std::to_chars` (shortest round trip) and read with `std::from_chars`, so a value cached by the
pipeline and re-read by the planner is the same double. Test: `engine/tests/test_plan.cpp`.

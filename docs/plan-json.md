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
     "album": "<key>" | absent, "albumIntent": true, "manual": false, "learnedDb": 0.0}
  ]
}
```
- `<analysis>` is the cached `TrackPipeline::resultJson` object. The planner reads `integratedLufs`, `truePeakDbtp`,
  `shortFile`, `decodeClipped`, `anchorLufs`, `anchorPle`, `anchorSec`, `character`, `openLufs`, `endLufs`, `fadeIn`,
  `fadeOut`, plus `anchorBlockPower` for album members.
- `complete` = every track of the album in the library is analysed. Otherwise the album's items use track mode and get
  the rule `ALBUM_PENDING`.
- `manual` = this item began, or will begin, by a user action. The seam chain follows the gain each item actually
  plays with.

## Output
```json
{"items": [{"gainDb": .., "manualGainDb": .., "baseDb": .., "capDb": .., "source": "full|jit|provisional",
            "albumRun": false, "rules": [{"id": "ANCHOR_MATCH", "deltaDb": ..}, ...]}]}
```
- `gainDb`: `g_final` after a natural (gapless) transition, i.e. seamed.
- `manualGainDb`: `g_final` when the user starts the item (no seam).
- `capDb`: the effective cap (`null` if unknown: silence or provisional).
- Malformed input returns `{"error":"bad input"}`.

Numbers are written with `std::to_chars` (shortest round trip) and read with `std::from_chars`, so a value cached by the
pipeline and re-read by the planner is the same double. Test: `engine/tests/test_plan.cpp`.

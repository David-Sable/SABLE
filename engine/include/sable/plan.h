// Queue planner (MATH §17.1, §18, §21, §22): the gain of every item in the play queue, from the cached analyses.
// JSON in, JSON out, so the app passes one string across JNI. Schema: docs/plan-json.md.
#pragma once

#include <string>

namespace sable {

std::string planQueueJson(const std::string& input);

// MATH §19: one volume-key feedback event. In: {"deltaDb", "prevDeltaDb"?, "prevDays"? (the previous event's time; it
// counts for the environment filter only within 10 min), "prevEndDb"? and "startDb"? (the volume after the previous
// event and at this window's start: if the listener moved it in between, the filter does not apply), "trackDb", "trackDays", "artistDb", "artistDays", "nowDays"}. Out: the updated track and artist offsets and the weight omega that was used.
std::string learnUpdateJson(const std::string& input);

}  // namespace sable

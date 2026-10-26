// Queue planner (MATH §17.1, §18, §21, §22): the gain of every item in the play queue, from the cached analyses.
// JSON in, JSON out, so the app passes one string across JNI. Schema: docs/plan-json.md.
#pragma once

#include <string>

namespace sable {

std::string planQueueJson(const std::string& input);

}  // namespace sable

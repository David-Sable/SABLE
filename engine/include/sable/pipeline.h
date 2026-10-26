// Full track pipeline (MATH §12.4, §13-§16, §22): the exact sequence the app runs, shared with sable_cli.
// The caller decodes the file and feeds float PCM; pass 2 needs a second decode of the same file.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace sable {

class TrackPipeline {
 public:
  TrackPipeline(int sampleRateHz, int channels, std::uint32_t channelMask, bool decodeClipped);
  ~TrackPipeline();
  TrackPipeline(const TrackPipeline&) = delete;
  TrackPipeline& operator=(const TrackPipeline&) = delete;

  void pushPass1(const float* interleaved, std::size_t frames);
  // Ends pass 1. Returns true if a perceptual pass is needed (call beginPass2 / pushPass2 / endPass2).
  bool endPass1();
  void beginPass2();
  void pushPass2(const float* interleaved, std::size_t frames);
  // Ends a perceptual pass. Returns true if one refinement pass is needed (MATH §12.4): run pass 2 again.
  bool endPass2();
  // Understanding + Standard/Quiet decisions as JSON (schema: docs/analysis-json.md).
  std::string resultJson() const;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

// Just-in-time analysis from excerpts (MATH §22.2). Excerpts are fed one at a time.
class JitPipeline {
 public:
  JitPipeline(int sampleRateHz, int channels, std::uint32_t channelMask);
  ~JitPipeline();
  JitPipeline(const JitPipeline&) = delete;
  JitPipeline& operator=(const JitPipeline&) = delete;
  void beginExcerpt(bool middle);
  void push(const float* interleaved, std::size_t frames);
  void endExcerpt();
  // {"gainStandardDb":..,"gainQuietDb":..,"jitLufs":..,"jitTpDbtp":..} for the given output ceiling.
  std::string resultJson(double ceilingDbtp) const;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace sable

// In-engine FLAC decoding for analysis (owner decision 2026-10-26, MATH §1.1): dr_flac (public domain) decodes to
// exact integers; Sable converts to float by an exact power-of-two scale, so results equal FFmpeg's float decode
// and do not depend on dr_flac's SIMD paths. Playback still uses MediaCodec.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>

namespace sable {

class FlacSource {
 public:
  static std::unique_ptr<FlacSource> openPath(const char* path);
  static std::unique_ptr<FlacSource> openFd(int fd);  // POSIX file descriptor (duplicated; caller keeps its own)
  ~FlacSource();
  FlacSource(const FlacSource&) = delete;
  FlacSource& operator=(const FlacSource&) = delete;

  int sampleRate() const;
  int channels() const;
  std::uint64_t totalFrames() const;
  // Read up to `frames` interleaved float frames; returns frames read (0 at end).
  std::size_t read(float* out, std::size_t frames);
  bool seek(std::uint64_t frame);

  struct Impl;  // dr_flac state (defined in flac_source.cpp)

 private:
  FlacSource() = default;
  std::unique_ptr<Impl> impl_;
};

// Full two-pass analysis of a FLAC source (same TrackPipeline as every other path). JSON as TrackPipeline::resultJson.
std::string analyzeFlac(FlacSource& src);
// Just-in-time excerpt analysis (MATH §22.2) of a FLAC source. JSON as JitPipeline::resultJson.
std::string jitFlac(FlacSource& src, double ceilingDbtp);

}  // namespace sable

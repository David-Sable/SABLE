// In-engine decoding for analysis (owner decisions 2026-10-26, MATH §1.1). Playback uses MediaCodec.
//  - FLAC: dr_flac 0.13.3 (public domain), exact integers scaled by 2^-31: bit-identical to FFmpeg's float decode.
//  - MP3:  minimp3 @ea99364f (CC0), float output (overs kept), scalar code (MINIMP3_NO_SIMD) so host and device
//          decode bit-identically; LAME/Xing encoder delay and padding trimmed (gapless).
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace sable {

class AudioSource {
 public:
  virtual ~AudioSource() = default;
  virtual int sampleRate() const = 0;
  virtual int channels() const = 0;
  virtual std::uint64_t totalFrames() const = 0;  // 0 if unknown
  // Read up to `frames` interleaved float frames; returns frames read (0 at end).
  virtual std::size_t read(float* out, std::size_t frames) = 0;
  virtual bool seek(std::uint64_t frame) = 0;
};

std::unique_ptr<AudioSource> openFlacPath(const char* path);
std::unique_ptr<AudioSource> openFlacFd(int fd);  // fd is duplicated; the caller keeps its own
std::unique_ptr<AudioSource> openMp3Path(const char* path);
std::unique_ptr<AudioSource> openMp3Fd(int fd);

// Full two-pass analysis (same TrackPipeline as every path). JSON as TrackPipeline::resultJson.
std::string analyzeSource(AudioSource& src);
// Just-in-time excerpt analysis (MATH §22.2). JSON as JitPipeline::resultJson.
std::string jitSource(AudioSource& src, double ceilingDbtp);

}  // namespace sable

// jitSource (MATH §22.2) on a source that does not know its length (a streamed FLAC reports total_samples 0).
#include <algorithm>
#include <charconv>
#include <string>

#include "doctest.h"
#include "sable/audio_source.h"
#include "signals.h"

namespace {

class SynthSource final : public sable::AudioSource {
 public:
  SynthSource(sigs::Audio a, bool knowsLength) : a_(std::move(a)), known_(knowsLength) {}
  int sampleRate() const override { return a_.fs; }
  int channels() const override { return a_.ch; }
  std::uint64_t totalFrames() const override { return known_ ? a_.frames() : 0; }
  std::size_t read(float* out, std::size_t frames) override {
    const std::size_t n = std::min(frames, a_.frames() - pos_);
    std::copy_n(a_.x.begin() + static_cast<std::ptrdiff_t>(pos_ * static_cast<std::size_t>(a_.ch)), n * static_cast<std::size_t>(a_.ch), out);
    pos_ += n;
    return n;
  }
  bool seek(std::uint64_t frame) override {
    if (frame > a_.frames()) return false;
    pos_ = static_cast<std::size_t>(frame);
    return true;
  }

 private:
  sigs::Audio a_;
  bool known_;
  std::size_t pos_ = 0;
};

double jitLufs(const std::string& js) {
  const std::size_t p = js.find("\"jitLufs\":");
  REQUIRE(p != std::string::npos);
  double v = 0.0;
  std::from_chars(js.data() + p + 10, js.data() + js.size(), v);
  return v;
}

}  // namespace

TEST_CASE("jit: a source of unknown length is measured in its middle, not at its start (MATH-1.29)") {
  // 20 s quiet intro, then 40 s at full level: the middle excerpts (30, 50 and 70 %) are all in the loud part.
  sigs::Audio a{44100, 2, {}};
  double phase = 0.0;
  sigs::appendSine(a, 440.0, -40.0, 20.0, phase);
  sigs::appendSine(a, 440.0, -12.0, 40.0, phase);
  SynthSource known(a, true), unknown(a, false);
  const double lKnown = jitLufs(sable::jitSource(known, -1.0));
  const double lUnknown = jitLufs(sable::jitSource(unknown, -1.0));
  CHECK(lUnknown == doctest::Approx(lKnown).epsilon(1e-12));
  CHECK(lKnown > -25.0);  // the loud part (the intro alone would read about -43 LUFS)
}

// Track and JIT pipelines. MATH §12.4, §13-§16, §22.
#include "sable/pipeline.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <optional>
#include <vector>

#include "sable/analyzer.h"
#include "sable/intent.h"
#include "sable/perceptual.h"

namespace sable {
namespace {

constexpr double kStandard = -14.0, kQuiet = -19.0;  // MATH §16.1
constexpr double kRefineDb = 6.0;                    // MATH §12.4 (1.8)
constexpr double kJitTpMargin = 1.0;                 // MATH §22.2

std::string num(const std::optional<double>& v) {
  if (!v || !std::isfinite(*v)) return "null";
  char b[40];
  const auto r = std::to_chars(b, b + sizeof b, *v);
  return std::string(b, r.ptr);
}

std::string esc(const char* s) { return std::string("\"") + s + "\""; }

std::string decisionJson(const GainDecision& d) {
  std::string o = "{\"gainDb\":" + num(d.gainDb) + ",\"intentDb\":" + num(d.intentDb) + ",\"capDb\":" + num(d.capDb) + ",\"rules\":[";
  for (std::size_t i = 0; i < d.rules.size(); ++i) {
    if (i) o += ',';
    o += "{\"id\":" + esc(d.rules[i].id.c_str()) + ",\"deltaDb\":" + num(d.rules[i].deltaDb) + "}";
  }
  return o + "]}";
}

}  // namespace

struct TrackPipeline::Impl {
  int fs = 0, ch = 0;
  bool decodeClipped = false;
  Analyzer analyzer;
  std::optional<LoudnessResult> r;
  std::unique_ptr<FastLoudness> fast;
  std::optional<PerceptualSeries> ps;
  double prescale = 0.0;
  bool refined = false;
  TrackUnderstanding u;
  Impl(int f, int c, std::uint32_t mask, bool clipped)
      : fs(f), ch(c), decodeClipped(clipped), analyzer(f, c, channelWeightsFor(c, mask)) {}
};

TrackPipeline::TrackPipeline(int fs, int ch, std::uint32_t mask, bool clipped) : impl_(std::make_unique<Impl>(fs, ch, mask, clipped)) {}
TrackPipeline::~TrackPipeline() = default;

void TrackPipeline::pushPass1(const float* x, std::size_t n) { impl_->analyzer.push(x, n); }

bool TrackPipeline::endPass1() {
  auto& s = *impl_;
  s.r = s.analyzer.finish();
  if (!s.r->integratedLufs) {  // silence: no perceptual pass (MATH §12.4)
    s.u = understand(*s.r, nullptr, 0.0);
    return false;
  }
  s.prescale = kStandard - *s.r->integratedLufs;
  return true;
}

void TrackPipeline::beginPass2() { impl_->fast = std::make_unique<FastLoudness>(impl_->fs, impl_->ch, impl_->prescale); }
void TrackPipeline::pushPass2(const float* x, std::size_t n) { impl_->fast->push(x, n); }

bool TrackPipeline::endPass2() {
  auto& s = *impl_;
  s.ps = s.fast->finish();
  s.fast.reset();
  s.u = understand(*s.r, &*s.ps, s.prescale);
  if (s.refined) return false;
  GainContext std;
  std.targetLufs = kStandard;
  const auto d = decideTrackGain(*s.r, s.u, std);
  double gAnchor = d.intentDb;
  for (const auto& rule : d.rules) if (rule.id == "ANCHOR_MATCH") gAnchor = rule.deltaDb;
  if (std::fabs(gAnchor - s.prescale) > kRefineDb) {
    s.prescale = gAnchor;
    s.refined = true;
    return true;
  }
  return false;
}

std::string TrackPipeline::resultJson() const {
  const auto& s = *impl_;
  const auto& r = *s.r;
  const auto& u = s.u;
  GainContext std, quiet;
  std.targetLufs = kStandard;
  std.decodeClipped = quiet.decodeClipped = s.decodeClipped;
  quiet.targetLufs = kQuiet;
  const auto dS = decideTrackGain(r, u, std), dQ = decideTrackGain(r, u, quiet);
  std::string o = "{\"engine\":\"sable-1\",\"math\":\"1.9\",\"sampleRateHz\":" + std::to_string(r.sampleRateHz) +
                  ",\"channels\":" + std::to_string(r.channels) + ",\"frames\":" + std::to_string(r.frames) +
                  ",\"integratedLufs\":" + num(r.integratedLufs) + ",\"lraLu\":" + num(r.lraLu) + ",\"truePeakDbtp\":" + num(r.truePeakDbtp) +
                  ",\"samplePeakDbfs\":" + num(r.samplePeakDbfs) + ",\"plrDb\":" + num(r.plrDb) + ",\"crestDb\":" + num(r.crestFactorDb) +
                  ",\"shortFile\":" + (r.shortFile ? "true" : "false") + ",\"decodeClipped\":" + (s.decodeClipped ? "true" : "false") +
                  ",\"anchorLufs\":" + num(u.anchorLufs) + ",\"anchorPle\":" + num(u.anchorPle) + ",\"anchorSec\":" + num(u.anchorSec) +
                  ",\"anchorCentreSec\":" + num(u.anchorCentreSec) + ",\"anchorFallback\":" + (u.anchorFallback ? "true" : "false") +
                  ",\"nonSilentSec\":" + num(u.nonSilentSec) + ",\"leadSilenceSec\":" + num(u.leadSilenceSec) + ",\"trailSilenceSec\":" + num(u.trailSilenceSec) + ",\"character\":" + esc(toString(u.character)) +
                  ",\"tilt\":" + num(u.tiltDbPerOct) + ",\"density\":" + num(u.density) + ",\"transientsPerSec\":" + num(u.transientsPerSec) +
                  ",\"plrAnchorDb\":" + num(u.plrAnchorDb) + ",\"sectionContrastLu\":" + num(u.sectionContrastLu) +
                  ",\"openLufs\":" + num(u.openLufs) + ",\"endLufs\":" + num(u.endLufs) +
                  ",\"fadeIn\":" + (u.fadeIn ? "true" : "false") + ",\"fadeOut\":" + (u.fadeOut ? "true" : "false") +
                  ",\"prescaleDb\":" + num(s.prescale) + ",\"refined\":" + (s.refined ? "true" : "false") + ",\"anchor\":[";
  for (std::size_t i = 0; i < u.anchor.size(); ++i) {
    if (i) o += ',';
    o += "[" + num(u.anchor[i].first) + "," + num(u.anchor[i].second) + "]";
  }
  o += "],\"sections\":[";
  for (std::size_t i = 0; i < u.sections.size(); ++i) {
    const auto& sec = u.sections[i];
    if (i) o += ',';
    o += "{\"type\":" + esc(toString(sec.type)) + ",\"start\":" + num(sec.startSec) + ",\"end\":" + num(sec.endSec) + ",\"lufs\":" + num(sec.loudnessLufs) + "}";
  }
  // Loudness shape for the UI (short-term LUFS every 0.5 s) and anchor block powers for album pooling (§17.2).
  o += "],\"shape\":[";
  for (std::size_t k = 0, n = 0; k < r.shortTermLufs.size(); k += 5, ++n) {
    if (n) o += ',';
    o += num(r.shortTermLufs[k]);
  }
  o += "],\"shapeTilt\":[";
  for (std::size_t i = 0; i < u.shapeTilt.size(); ++i) {
    if (i) o += ',';
    o += num(u.shapeTilt[i]);
  }
  o += "],\"anchorBlockPower\":[";
  for (std::size_t i = 0; i < u.anchorBlockPower.size(); ++i) {
    if (i) o += ',';
    o += num(u.anchorBlockPower[i]);
  }
  o += "],\"standard\":" + decisionJson(dS) + ",\"quiet\":" + decisionJson(dQ) + "}";
  return o;
}

// ---------------- JIT (MATH §22.2) ----------------

struct JitPipeline::Impl {
  int fs = 0, ch = 0;
  std::vector<double> w;
  std::unique_ptr<Analyzer> cur;
  bool curMiddle = false;
  std::optional<double> loudestMiddle, tpMax;
};

JitPipeline::JitPipeline(int fs, int ch, std::uint32_t mask) : impl_(std::make_unique<Impl>()) {
  impl_->fs = fs;
  impl_->ch = ch;
  impl_->w = channelWeightsFor(ch, mask);
}
JitPipeline::~JitPipeline() = default;

void JitPipeline::beginExcerpt(bool middle) {
  impl_->cur = std::make_unique<Analyzer>(impl_->fs, impl_->ch, impl_->w);
  impl_->curMiddle = middle;
}
void JitPipeline::push(const float* x, std::size_t n) { impl_->cur->push(x, n); }
void JitPipeline::endExcerpt() {
  auto& s = *impl_;
  const auto r = s.cur->finish();
  if (r.truePeakDbtp) s.tpMax = std::max(s.tpMax.value_or(-1e300), *r.truePeakDbtp);
  if (s.curMiddle && r.integratedLufs) s.loudestMiddle = std::max(s.loudestMiddle.value_or(-1e300), *r.integratedLufs);
  s.cur.reset();
}

std::string JitPipeline::resultJson(double ceiling) const {
  const auto& s = *impl_;
  const auto gain = [&](double target) -> std::optional<double> {
    if (!s.loudestMiddle) return std::nullopt;
    double g = target - *s.loudestMiddle;
    if (s.tpMax) g = std::min(g, ceiling - (*s.tpMax + kJitTpMargin));
    return g;
  };
  return "{\"gainStandardDb\":" + num(gain(kStandard)) + ",\"gainQuietDb\":" + num(gain(kQuiet)) + ",\"jitLufs\":" + num(s.loudestMiddle) +
         ",\"jitTpDbtp\":" + num(s.tpMax) + "}";
}

}  // namespace sable

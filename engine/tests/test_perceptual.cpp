// Perceptual lens tests. MATH §10-§12, §26.
#include <cmath>
#include <cstdlib>
#include <string>

#include "doctest.h"
#include "dsp.h"
#include "perceptual_tables.inc"
#include "pink_table.inc"
#include "sable/perceptual.h"
#include "signals.h"

namespace {
// Diotic 1 kHz sine whose RMS SPL is `spl` under D_cal = 89 dB (MATH §12.1).
sigs::Audio toneSpl(double spl, double seconds, double freq = 1000.0, int fs = 48000) {
  sigs::Audio a{fs, 2, {}};
  double ph = 0;
  sigs::appendSine(a, freq, spl - 89.0, seconds, ph);  // peak dBFS = SPL - D_cal for a sine
  return a;
}

double maxLtl(const sable::PerceptualSeries& s) {
  double m = 0;
  for (double v : s.ltlPerFrame) m = std::max(m, v);
  return m;
}

sable::PerceptualSeries fast(const sigs::Audio& a, double prescale = 0.0) {
  sable::FastLoudness m(a.fs, a.ch, prescale);
  m.push(a.x.data(), a.frames());
  return m.finish();
}

std::string firPath() {
  const char* p = std::getenv("SABLE_TVL_FIR");
  return p ? p : "";
}
}  // namespace

TEST_CASE("PCHIP matches MATLAB/SciPy pchip on the A table, including extrapolation") {
  const sable::dsp::Pchip p(std::vector<double>(std::begin(sable::tables::kAGdb), std::end(sable::tables::kAGdb)),
                            std::vector<double>(std::begin(sable::tables::kAValue), std::end(sable::tables::kAValue)));
  const double ref[][2] = {{-24.55, 8.853393212325257}, {-24.0, 8.692806310876051}, {-20.1, 7.71734133929442},
                           {-15.7, 6.907570961331189},  {-10.3, 5.94776112396703},  {-5.05, 5.259327871501997},
                           {-1.3, 4.855438212155491},   {-0.5, 4.772358648412735},  {-0.01, 4.721984516429382}};
  for (const auto& r : ref) CHECK(p(r[0]) == doctest::Approx(r[1]).epsilon(1e-12));
  for (int i = 0; i < 90; ++i) CHECK(p(sable::tables::kAGdb[i]) == doctest::Approx(sable::tables::kAValue[i]).epsilon(1e-14));
}

TEST_CASE("Resampler: 1 kHz sine 44.1 -> 32 kHz keeps amplitude and phase") {
  std::vector<float> x(44100), y;
  for (int i = 0; i < 44100; ++i) x[static_cast<std::size_t>(i)] = static_cast<float>(0.5 * std::sin(2 * sigs::kPi * 1000.0 * i / 44100.0));
  sable::dsp::Resampler<float> rs(44100, 32000);
  rs.push(x.data(), 20000, y);
  rs.push(x.data() + 20000, 24100, y);
  rs.finish(y);
  CHECK(y.size() == 32000);
  double maxErr = 0;
  for (std::size_t i = 2000; i < 30000; ++i)
    maxErr = std::max(maxErr, std::fabs(y[i] - 0.5 * std::sin(2 * sigs::kPi * 1000.0 * static_cast<double>(i) / 32000.0)));
  MESSAGE("resampler max error " << maxErr);
  CHECK(maxErr < 2e-4);
}

TEST_CASE("Fast model: 1 kHz diotic tone at 40 dB SPL is about 1 sone (model definition)") {
  const double l = maxLtl(fast(toneSpl(40.0, 2.0)));
  MESSAGE("fast max LTL @40 dB SPL = " << l);
  CHECK(l == doctest::Approx(1.0).epsilon(0.10));
}

TEST_CASE("Fast model: loudness grows with level, roughly doubling per 10 dB above 40 dB") {
  const double l60 = maxLtl(fast(toneSpl(60.0, 2.0))), l70 = maxLtl(fast(toneSpl(70.0, 2.0)));
  MESSAGE("60 dB: " << l60 << " sone, 70 dB: " << l70);
  CHECK(l70 > l60);
  CHECK(l70 / l60 == doctest::Approx(2.0).epsilon(0.25));
}

TEST_CASE("Fast model: prescale is equivalent to scaling the signal; deterministic") {
  auto a = sigs::multiSine(44100, 2, 3.0, 40.0, 15000.0, 40, -30.0);
  const auto r1 = fast(a, 6.0);
  for (auto& v : a.x) v = static_cast<float>(v * std::pow(10.0, 6.0 / 20.0));
  const auto r2 = fast(a, 0.0);
  CHECK(maxLtl(r1) == doctest::Approx(maxLtl(r2)).epsilon(1e-4));
  const auto r3 = fast(a, 0.0);
  CHECK(r2.ltlPerFrame == r3.ltlPerFrame);
}

TEST_CASE("PLE: the pink table is strictly increasing and PLE inverts it exactly at nodes (MATH 12.2-12.3)") {
  for (int i = 0; i + 1 < 147; ++i) CHECK(sable::tables::kPinkSone[i] < sable::tables::kPinkSone[i + 1]);
  for (int i = 1; i + 1 < 147; i += 7)
    CHECK(sable::pleFromSone(sable::tables::kPinkSone[i]) == doctest::Approx(-70.0 + 0.5 * i).epsilon(1e-9));
  bool clamped = false;
  sable::pleFromSone(1e-6, &clamped);
  CHECK(clamped);
}

TEST_CASE("PLE of independent pink-ish noise at a known LUFS reads back within 0.5 LU") {
  // A different noise (sum of sines, -3 dB/oct) than the table's: checks the scale is meaningful, not exact.
  for (double target : {-30.0, -14.0}) {
    // Log-spaced sines with equal power each => power density ~ 1/f (pink). tilt must be 0 here.
    auto a = sigs::multiSine(32000, 2, 12.0, 25.0, 14000.0, 200, -20.0, 99, 0.0);
    for (std::size_t i = 0; i + 1 < a.x.size(); i += 2) a.x[i + 1] = a.x[i];  // diotic
    const auto lr = sigs::analyze(a);
    const auto r = fast(a, target - *lr.integratedLufs);
    double sum = 0;
    std::size_t n = 0;
    for (std::size_t f = 0; f < r.ltlPerFrame.size(); ++f)
      if (static_cast<double>(f) * r.frameSeconds >= 2.0) { sum += r.ltlPerFrame[f]; ++n; }
    const double ple = sable::pleFromSone(sum / static_cast<double>(n));
    MESSAGE("target " << target << " LUFS -> PLE " << ple);
    CHECK(std::fabs(ple - target) <= 0.5);
  }
}

#ifdef SABLE_HAVE_REFERENCE
TEST_CASE("Reference vs fast model on steady signals (MATH §11.1)") {
  const std::string fir = firPath();
  if (fir.empty()) {
    MESSAGE("SABLE_TVL_FIR not set; skipping reference comparison");
    return;
  }
  struct Sig { const char* name; sigs::Audio a; };
  Sig sigsList[] = {
      {"1k tone 40 dB", toneSpl(40.0, 1.5)},
      {"1k tone 70 dB", toneSpl(70.0, 1.5)},
      {"100 Hz tone 70 dB", toneSpl(70.0, 1.5, 100.0)},
      {"3 kHz tone 60 dB", toneSpl(60.0, 1.5, 3000.0)},
      {"pink-ish 44.1k", sigs::multiSine(44100, 2, 1.5, 30.0, 15000.0, 60, -20.0)},
  };
  for (auto& s : sigsList) {
    const auto ref = sable::referenceLoudness(s.a.x, s.a.fs, s.a.ch, 0.0, fir);
    const auto fst = fast(s.a);
    const double lr = maxLtl(ref), lf = maxLtl(fst);
    MESSAGE(s.name << ": reference " << lr << " sone, fast " << lf << " sone, ratio " << lf / lr);
    if (std::string(s.name) == "1k tone 40 dB") CHECK(lr == doctest::Approx(1.0).epsilon(0.05));
    CHECK(lf / lr == doctest::Approx(1.0).epsilon(0.08));
  }
}
#endif

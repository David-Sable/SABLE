// EBU Tech 3341 (cases 1-6, 9, 10, 12, 13, 15-23) and Tech 3342 (cases 1-4) minimum requirements,
// synthesized per the documents. MATH §26. Cases 7-8 (3341) and 5-6 (3342) need the EBU's
// authentic programme files and run in the corpus validation when those files are present.
#include <cmath>

#include "doctest.h"
#include "signals.h"

using sigs::Audio;

namespace {
constexpr double kTol = 0.1;

double integrated(const Audio& a, std::vector<double> w = {}) {
  const auto r = sigs::analyze(a, std::move(w));
  REQUIRE(r.integratedLufs);
  return *r.integratedLufs;
}

Audio tones(std::initializer_list<std::pair<double, double>> segs, int fs = 48000) {  // (seconds, dBFS)
  Audio a{fs, 2, {}};
  double ph = 0;
  for (const auto& [sec, db] : segs) sigs::appendSine(a, 1000.0, db, sec, ph);
  return a;
}
}  // namespace

TEST_CASE("T3341 cases 1-5: integrated loudness") {
  CHECK(std::fabs(integrated(tones({{20, -23}})) + 23.0) <= kTol);
  CHECK(std::fabs(integrated(tones({{20, -33}})) + 33.0) <= kTol);
  CHECK(std::fabs(integrated(tones({{10, -36}, {60, -23}, {10, -36}})) + 23.0) <= kTol);
  CHECK(std::fabs(integrated(tones({{10, -72}, {10, -36}, {60, -23}, {10, -36}, {10, -72}})) + 23.0) <= kTol);
  CHECK(std::fabs(integrated(tones({{20, -26}, {20.1, -20}, {20, -26}})) + 23.0) <= kTol);
}

TEST_CASE("T3341 cases 1-2: momentary and short-term values") {
  for (double db : {-23.0, -33.0}) {
    const auto r = sigs::analyze(tones({{20, db}}));
    for (const auto& m : r.momentaryLufs) { REQUIRE(m); CHECK(std::fabs(*m - db) <= kTol); }
    for (const auto& s : r.shortTermLufs) { REQUIRE(s); CHECK(std::fabs(*s - db) <= kTol); }
  }
}

TEST_CASE("T3341 case 6: 5.0 channels, raw multichannel weights") {
  Audio a{48000, 5, {}};  // order L R C Ls Rs, mask FL|FR|FC|BL|BR (5.0 back = surround, 1.41)
  const double lv[5] = {-28, -28, -24, -30, -30};
  const auto n = static_cast<std::size_t>(20 * 48000);
  for (std::size_t i = 0; i < n; ++i) {
    const double s = std::sin(2 * sigs::kPi * 1000.0 * static_cast<double>(i) / 48000.0);
    for (int c = 0; c < 5; ++c) a.x.push_back(static_cast<float>(sigs::dbfs(lv[c]) * s));
  }
  const double l = integrated(a, sable::channelWeightsFor(5, 0x37));
  MESSAGE("case 6: " << l);
  CHECK(std::fabs(l + 23.0) <= kTol);
}

TEST_CASE("T3341 case 9: short-term constant after 3 s") {
  Audio a{48000, 2, {}};
  double ph = 0;
  for (int i = 0; i < 5; ++i) { sigs::appendSine(a, 1000, -20, 1.34, ph); sigs::appendSine(a, 1000, -30, 1.66, ph); }
  const auto r = sigs::analyze(a);
  REQUIRE(!r.shortTermLufs.empty());
  for (const auto& s : r.shortTermLufs) { REQUIRE(s); CHECK(std::fabs(*s + 23.0) <= kTol); }
}

TEST_CASE("T3341 case 10: max short-term per segment (file-based)") {
  for (int i = 0; i < 20; ++i) {
    Audio a{48000, 2, {}};
    double ph = 0;
    sigs::appendSilence(a, i * 0.15);
    sigs::appendSine(a, 1000, -23, 3.0, ph);
    sigs::appendSilence(a, 1.0);
    const auto r = sigs::analyze(a);
    REQUIRE(r.maxShortTermLufs);
    CHECK(std::fabs(*r.maxShortTermLufs + 23.0) <= kTol);
  }
}

TEST_CASE("T3341 case 12: momentary constant after 1 s") {
  Audio a{48000, 2, {}};
  double ph = 0;
  for (int i = 0; i < 25; ++i) { sigs::appendSine(a, 1000, -20, 0.18, ph); sigs::appendSine(a, 1000, -30, 0.22, ph); }
  const auto r = sigs::analyze(a);
  // M_j stamped at window end (j+4)*0.1 s (MATH §3.6); check from t >= 1 s.
  for (std::size_t j = 6; j < r.momentaryLufs.size(); ++j) {
    REQUIRE(r.momentaryLufs[j]);
    CHECK(std::fabs(*r.momentaryLufs[j] + 23.0) <= kTol);
  }
}

TEST_CASE("T3341 case 13: max momentary per segment (file-based, fine grid MATH 3.5)") {
  for (int i = 0; i < 20; ++i) {
    Audio a{48000, 2, {}};
    double ph = 0;
    sigs::appendSilence(a, i * 0.02);
    sigs::appendSine(a, 1000, -23, 0.4, ph);
    sigs::appendSilence(a, 1.0);
    const auto r = sigs::analyze(a);
    REQUIRE(r.maxMomentaryLufs);
    CHECK(std::fabs(*r.maxMomentaryLufs + 23.0) <= kTol);
  }
}

namespace {
// Cases 15-19: stereo sine at fs*ratio, amplitude amp, phase deg, 10 ms fades.
double tpSine(int fs, double ratio, double amp, double phaseDeg) {
  Audio a{fs, 2, {}};
  const auto n = static_cast<std::size_t>(fs);  // 1 s
  const auto fade = static_cast<std::size_t>(0.01 * fs);
  for (std::size_t i = 0; i < n; ++i) {
    double g = 1.0;
    if (i < fade) g = static_cast<double>(i) / static_cast<double>(fade);
    if (i >= n - fade) g = static_cast<double>(n - 1 - i) / static_cast<double>(fade);
    const float v = static_cast<float>(g * amp * std::sin(2 * sigs::kPi * ratio * static_cast<double>(i) + phaseDeg * sigs::kPi / 180.0));
    a.x.push_back(v);
    a.x.push_back(v);
  }
  const auto r = sigs::analyze(a);
  REQUIRE(r.truePeakDbtp);
  return *r.truePeakDbtp;
}

// Cases 20-23: synthesized at 4*fs, low-pass filtered, downsampled with an offset.
double tpBurst(int fs, int offset) {
  const int fs4 = 4 * fs;
  const auto n = static_cast<std::size_t>(fs4);  // 1 s at 4 fs
  std::vector<double> s(n);
  const double fb = fs / 6.0, ft = fs / 4.0;
  // Base tone fs/6 (amp 0.5). One period of fs/4 (amp 1.0) inserted at an upward zero crossing of the base,
  // phase-continuous on both sides (both start and end at zero, rising).
  const double periodBase = fs4 / fb;  // 24 samples
  const auto start = static_cast<std::size_t>(std::llround(periodBase * std::floor((n / 2) / periodBase)));
  const auto len = static_cast<std::size_t>(std::llround(fs4 / ft));  // 16 samples
  for (std::size_t i = 0; i < n; ++i) {
    if (i >= start && i < start + len) {
      s[i] = 1.0 * std::sin(2 * sigs::kPi * ft * static_cast<double>(i - start) / fs4);
    } else {
      const double t = i < start ? static_cast<double>(i) : static_cast<double>(i - len);
      s[i] = 0.5 * std::sin(2 * sigs::kPi * fb * t / fs4);
    }
    const auto fade = static_cast<std::size_t>(0.01 * fs4);
    if (i < fade) s[i] *= static_cast<double>(i) / static_cast<double>(fade);
    if (i >= n - fade) s[i] *= static_cast<double>(n - 1 - i) / static_cast<double>(fade);
  }
  // Anti-aliasing low-pass at fs/2 (Kaiser-windowed sinc, 511 taps, beta 10) then decimate by 4.
  const int taps = 511, c = taps / 2;
  std::vector<double> h(taps);
  auto i0 = [](double x) { double sum = 1, t = 1; for (int k = 1; k < 200; ++k) { t *= (x * x / 4) / (k * k); sum += t; } return sum; };
  double hs = 0;
  for (int i = 0; i < taps; ++i) {
    const double m = i - c;
    const double sinc = m == 0 ? 1.0 : std::sin(sigs::kPi * m * 0.23) / (sigs::kPi * m * 0.23);
    const double u = m / c;
    h[i] = 0.23 * sinc * i0(10.0 * std::sqrt(1 - u * u)) / i0(10.0);
    hs += h[i];
  }
  for (auto& v : h) v /= hs;
  Audio a{fs, 2, {}};
  for (std::size_t k = static_cast<std::size_t>(offset); k + static_cast<std::size_t>(c) < n; k += 4) {
    double acc = 0;
    for (int t = 0; t < taps; ++t) {
      const long long idx = static_cast<long long>(k) + t - c;
      if (idx >= 0 && idx < static_cast<long long>(n)) acc += h[static_cast<std::size_t>(t)] * s[static_cast<std::size_t>(idx)];
    }
    a.x.push_back(static_cast<float>(acc));
    a.x.push_back(static_cast<float>(acc));
  }
  const auto r = sigs::analyze(a);
  REQUIRE(r.truePeakDbtp);
  return *r.truePeakDbtp;
}

void checkTp(double v, double expect) {
  CHECK(v <= expect + 0.2);
  CHECK(v >= expect - 0.4);
}
}  // namespace

TEST_CASE("T3341 cases 15-23: true peak at 44.1, 48 and 96 kHz") {
  for (int fs : {44100, 48000, 96000}) {
    const double c15 = tpSine(fs, 0.25, 0.5, 0), c16 = tpSine(fs, 0.25, 0.5, 45), c17 = tpSine(fs, 1.0 / 6, 0.5, 60),
                 c18 = tpSine(fs, 0.125, 0.5, 67.5), c19 = tpSine(fs, 0.25, 1.41, 45);
    MESSAGE("fs=" << fs << " 15:" << c15 << " 16:" << c16 << " 17:" << c17 << " 18:" << c18 << " 19:" << c19);
    checkTp(c15, -6.0); checkTp(c16, -6.0); checkTp(c17, -6.0); checkTp(c18, -6.0); checkTp(c19, 3.0);
    for (int off = 0; off < 4; ++off) {
      const double v = tpBurst(fs, off);
      MESSAGE("fs=" << fs << " case " << 20 + off << ": " << v);
      checkTp(v, 0.0);
    }
  }
}

TEST_CASE("T3342 cases 1-4: loudness range") {
  const auto lra = [](std::initializer_list<std::pair<double, double>> segs) {
    const auto r = sigs::analyze(tones(segs));
    REQUIRE(r.lraLu);
    return *r.lraLu;
  };
  const double l1 = lra({{20, -20}, {20, -30}}), l2 = lra({{20, -20}, {20, -15}}), l3 = lra({{20, -40}, {20, -20}}),
               l4 = lra({{20, -50}, {20, -35}, {20, -20}, {20, -35}, {20, -50}});
  MESSAGE("LRA 1:" << l1 << " 2:" << l2 << " 3:" << l3 << " 4:" << l4);
  CHECK(std::fabs(l1 - 10) <= 1.0);
  CHECK(std::fabs(l2 - 5) <= 1.0);
  CHECK(std::fabs(l3 - 20) <= 1.0);
  CHECK(std::fabs(l4 - 15) <= 1.0);
}

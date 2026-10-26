// Property, edge-case and determinism tests. MATH §26.
#include <cmath>
#include <string>

#include "doctest.h"
#include "signals.h"

TEST_CASE("Gain property: +x dB shifts integrated loudness by exactly x") {
  const auto base = sigs::multiSine(48000, 2, 8.0, 30.0, 16000.0, 50, -25.0);
  const auto r0 = sigs::analyze(base);
  REQUIRE(r0.integratedLufs);
  for (double x : {-20.0, -6.02, 3.7, 12.0}) {
    auto a = base;
    const double g = std::pow(10.0, x / 20.0);
    for (auto& v : a.x) v = static_cast<float>(v * g);
    const auto r = sigs::analyze(a);
    REQUIRE(r.integratedLufs);
    const double d = *r.integratedLufs - *r0.integratedLufs;
    MESSAGE("x=" << x << " shift=" << d);
    CHECK(std::fabs(d - x) <= 1e-5);
  }
}

TEST_CASE("44.1 kHz vs 48 kHz: same band-limited signal within 0.1 LU (I and LRA)") {
  const auto a = sigs::multiSine(44100, 2, 12.0, 20.0, 19000.0, 80, -18.0);
  const auto b = sigs::multiSine(48000, 2, 12.0, 20.0, 19000.0, 80, -18.0);
  const auto ra = sigs::analyze(a), rb = sigs::analyze(b);
  REQUIRE(ra.integratedLufs);
  REQUIRE(rb.integratedLufs);
  CHECK(std::fabs(*ra.integratedLufs - *rb.integratedLufs) <= 0.1);
  REQUIRE(ra.lraLu);
  REQUIRE(rb.lraLu);
  CHECK(std::fabs(*ra.lraLu - *rb.lraLu) <= 0.1);
}

TEST_CASE("Swapping L and R changes nothing (bit-identical)") {
  const auto a = sigs::multiSine(48000, 2, 6.0, 30.0, 18000.0, 40, -20.0);
  auto b = a;
  for (std::size_t i = 0; i + 1 < b.x.size(); i += 2) std::swap(b.x[i], b.x[i + 1]);
  // K-weighting sums channels in channel order, so swapping can change the last bit of the
  // per-sample sum only if addition were non-commutative; IEEE addition is commutative.
  CHECK(sable::toJson(sigs::analyze(a), true) == sable::toJson(sigs::analyze(b), true));
}

TEST_CASE("Determinism: same input -> identical result (golden-style)") {
  const auto a = sigs::multiSine(44100, 2, 5.0, 25.0, 20000.0, 30, -14.0);
  CHECK(sable::toJson(sigs::analyze(a), true) == sable::toJson(sigs::analyze(a), true));
}

TEST_CASE("Mono is measured as dual-mono") {
  auto st = sigs::multiSine(48000, 2, 6.0, 30.0, 18000.0, 40, -20.0);
  sigs::Audio mono{48000, 1, {}}, dual{48000, 2, {}};
  for (std::size_t i = 0; i < st.frames(); ++i) {
    mono.x.push_back(st.x[2 * i]);
    dual.x.push_back(st.x[2 * i]);
    dual.x.push_back(st.x[2 * i]);
  }
  const auto rm = sigs::analyze(mono), rd = sigs::analyze(dual);
  REQUIRE(rm.integratedLufs);
  CHECK(*rm.integratedLufs == *rd.integratedLufs);
  CHECK(rm.lraLu == rd.lraLu);
  CHECK(rm.truePeakDbtp == rd.truePeakDbtp);
}

TEST_CASE("Silence, tiny and short files: no NaN/inf anywhere; absent where defined") {
  for (double ms : {0.0, 1.0, 10.0, 49.0, 50.0, 100.0, 399.0, 400.0, 401.0, 3000.0}) {
    for (bool silent : {true, false}) {
      sigs::Audio a{48000, 2, {}};
      double ph = 0;
      if (silent) sigs::appendSilence(a, ms / 1000.0);
      else sigs::appendSine(a, 997.0, -20.0, ms / 1000.0, ph);
      const auto r = sigs::analyze(a);
      const std::string j = sable::toJson(r, true);
      CAPTURE(ms);
      CAPTURE(silent);
      CHECK(j.find("nan") == std::string::npos);
      CHECK(j.find("inf") == std::string::npos);
      if (silent) {
        CHECK(!r.integratedLufs);
        CHECK(!r.truePeakDbtp);
      } else if (ms >= 50.0) {
        CHECK(r.integratedLufs.has_value());
        CHECK(r.shortFile == (ms < 400.0));
      } else {
        CHECK(!r.integratedLufs);  // < 50 ms: absent (MATH §4.5)
      }
    }
  }
}

TEST_CASE("Subnormal samples and DC/impulse/full-scale square are handled") {
  sigs::Audio a{48000, 2, {}};
  for (int i = 0; i < 48000; ++i) {
    const float v = (i % 3 == 0) ? 1e-40f : ((i / 100) % 2 ? 1.0f : -1.0f);
    a.x.push_back(v);
    a.x.push_back(i == 24000 ? 1.5f : 0.25f);
  }
  const auto r = sigs::analyze(a);
  const std::string j = sable::toJson(r, true);
  CHECK(j.find("nan") == std::string::npos);
  CHECK(j.find("inf") == std::string::npos);
  REQUIRE(r.truePeakDbtp);
  REQUIRE(r.samplePeakDbfs);
  CHECK(*r.truePeakDbtp >= *r.samplePeakDbfs);  // MATH §6.2
  CHECK(*r.samplePeakDbfs == doctest::Approx(20 * std::log10(1.5)));  // overs kept (MATH §1.1)
}

TEST_CASE("Album pooling equals the gated loudness of all blocks together") {
  const auto a = sigs::multiSine(48000, 2, 6.0, 30.0, 18000.0, 30, -12.0, 1);
  const auto b = sigs::multiSine(48000, 2, 9.0, 30.0, 18000.0, 30, -26.0, 2);
  const auto ra = sigs::analyze(a), rb = sigs::analyze(b);
  std::vector<double> pool = ra.blockPower;
  pool.insert(pool.end(), rb.blockPower.begin(), rb.blockPower.end());
  const auto album = sable::gatedLoudness(pool);
  REQUIRE(album);
  // Not the mean of track values (MATH §4.4):
  CHECK(std::fabs(*album - 0.5 * (*ra.integratedLufs + *rb.integratedLufs)) > 0.5);
}

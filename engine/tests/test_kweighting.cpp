// MATH §2: K-weighting coefficients and rate behaviour.
#include <cmath>

#include "doctest.h"
#include "signals.h"

TEST_CASE("K-weighting at 48 kHz matches BS.1770 Tables 1-2 (|d| <= 1e-8)") {
  const auto k = sable::KWeighting::forRate(48000.0);
  CHECK(std::fabs(k.b1[0] - 1.53512485958697) <= 1e-8);
  CHECK(std::fabs(k.b1[1] - -2.69169618940638) <= 1e-8);
  CHECK(std::fabs(k.b1[2] - 1.19839281085285) <= 1e-8);
  CHECK(std::fabs(k.a1[1] - -1.69065929318241) <= 1e-8);
  CHECK(std::fabs(k.a1[2] - 0.73248077421585) <= 1e-8);
  CHECK(std::fabs(k.a2[1] - -1.99004745483398) <= 1e-8);
  CHECK(std::fabs(k.a2[2] - 0.99007225036621) <= 1e-8);
}

TEST_CASE("1 kHz stereo sine at -18 dBFS peak reads -18.0 LUFS (T3341 2.9 alignment)") {
  sigs::Audio a{48000, 2, {}};
  double ph = 0;
  sigs::appendSine(a, 1000.0, -18.0, 10.0, ph);
  const auto r = sigs::analyze(a);
  REQUIRE(r.integratedLufs);
  CHECK(std::fabs(*r.integratedLufs + 18.0) <= 0.05);
}

TEST_CASE("Rate behaviour: band-limited noise vs 48 kHz within MATH 2.4 table") {
  struct Row { int fs; double tol; };
  const Row rows[] = {{8000, 0.51}, {11025, 0.30}, {16000, 0.30}, {22050, 0.10}, {32000, 0.10},
                      {44100, 0.10}, {88200, 0.10}, {96000, 0.10}, {192000, 0.10}};
  for (const auto& row : rows) {
    const double fHi = 0.45 * std::min(row.fs, 48000);
    const double secs = row.fs >= 88200 ? 3.0 : 6.0;
    const auto a = sigs::multiSine(row.fs, 2, secs, 20.0, fHi, 60, -20.0);
    const auto b = sigs::multiSine(48000, 2, secs, 20.0, fHi, 60, -20.0);
    const auto ra = sigs::analyze(a), rb = sigs::analyze(b);
    REQUIRE(ra.integratedLufs);
    REQUIRE(rb.integratedLufs);
    const double d = *ra.integratedLufs - *rb.integratedLufs;
    MESSAGE("fs=" << row.fs << " delta=" << d);
    CHECK(std::fabs(d) <= row.tol);
  }
}

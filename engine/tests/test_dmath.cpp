// MATH §25.2: sable::dm accuracy vs std:: (test code may use libm as the reference).
#include <cmath>
#include <cstdint>
#include <cstring>

#include "doctest.h"
#include "sable/dmath.h"

namespace {
std::int64_t ulpDiff(double a, double b) {
  std::int64_t ia, ib;
  std::memcpy(&ia, &a, 8);
  std::memcpy(&ib, &b, 8);
  if (ia < 0) ia = INT64_MIN - ia;
  if (ib < 0) ib = INT64_MIN - ib;
  return ia > ib ? ia - ib : ib - ia;
}
struct Rng {
  std::uint64_t s = 0x9E3779B97F4A7C15ULL;
  double uni() { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return static_cast<double>(s >> 11) / 9007199254740992.0; }
};
}  // namespace

TEST_CASE("dm::exp/log/sin/cos within 2 ulp of std (1e6 args)") {
  Rng r;
  std::int64_t worstExp = 0, worstLog = 0, worstSin = 0, worstCos = 0;
  for (int i = 0; i < 1000000; ++i) {
    const double xe = -700.0 + 1400.0 * r.uni();
    worstExp = std::max(worstExp, ulpDiff(sable::dm::exp(xe), std::exp(xe)));
    const double xl = std::exp(-700.0 + 1400.0 * r.uni());
    worstLog = std::max(worstLog, ulpDiff(sable::dm::log(xl), std::log(xl)));
    const double xs = -1.0e4 + 2.0e4 * r.uni();
    // Compare sin/cos away from zeros where relative ulp error is ill-conditioned.
    if (std::fabs(std::sin(xs)) > 1e-3) worstSin = std::max(worstSin, ulpDiff(sable::dm::sin(xs), std::sin(xs)));
    if (std::fabs(std::cos(xs)) > 1e-3) worstCos = std::max(worstCos, ulpDiff(sable::dm::cos(xs), std::cos(xs)));
  }
  MESSAGE("worst ulp exp=" << worstExp << " log=" << worstLog << " sin=" << worstSin << " cos=" << worstCos);
  CHECK(worstExp <= 2);
  CHECK(worstLog <= 2);
  CHECK(worstSin <= 2);
  CHECK(worstCos <= 2);
}

TEST_CASE("dm::pow and dB conversions") {
  Rng r;
  double worstRel = 0;
  for (int i = 0; i < 200000; ++i) {
    const double db = -150.0 + 300.0 * r.uni();
    const double a = sable::dm::pow(10.0, db / 20.0), b = std::pow(10.0, db / 20.0);
    worstRel = std::max(worstRel, std::fabs(a - b) / b);
  }
  MESSAGE("worst pow relative error " << worstRel);
  CHECK(worstRel < 1e-14);
  CHECK(sable::dm::pow(10.0, 0.0) == 1.0);
  CHECK(sable::dm::log10(1000.0) == doctest::Approx(3.0).epsilon(1e-15));
  CHECK(sable::dm::tan(sable::dm::kPi / 4) == doctest::Approx(1.0).epsilon(1e-15));
}

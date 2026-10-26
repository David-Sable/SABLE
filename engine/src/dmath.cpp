// Deterministic math. MATH §25.2.
// Argument reduction + fixed polynomials; only + - * / and sqrt are used.
#include "sable/dmath.h"

#include <cmath>  // std::sqrt only (IEEE exactly rounded)
#include <cstring>
#include <limits>

namespace sable::dm {
namespace {

std::uint64_t bits(double x) {
  std::uint64_t u;
  std::memcpy(&u, &x, sizeof u);
  return u;
}
double fromBits(std::uint64_t u) {
  double x;
  std::memcpy(&x, &u, sizeof x);
  return x;
}

// ln 2 split for Cody-Waite reduction (hi has trailing zero bits so k*hi is exact for |k| < 2^11).
constexpr double kLn2Hi = 6.93147180369123816490e-01;
constexpr double kLn2Lo = 1.90821492927058770002e-10;
constexpr double kInvLn2 = 1.44269504088896338700e+00;
constexpr double kInvLn10 = 0.43429448190325182765;

// pi/2 = p1 + p2 + p3 (fdlibm pio2_1/2/3: 33-bit pieces, so k*p1 and k*p2 are exact; residual ~8.5e-32).
constexpr double kPio2_1 = 1.57079632673412561417e+00;
constexpr double kPio2_2 = 6.07710050630396597660e-11;
constexpr double kPio2_3 = 2.02226624871116645580e-21;
constexpr double kTwoOverPi = 6.36619772367581382433e-01;

double roundHalfEven(double x) {
  // Values used here are |x| < 2^31; adding/subtracting 1.5*2^52 rounds to nearest-even exactly.
  constexpr double kMagic = 6755399441055744.0;
  return (x + kMagic) - kMagic;
}

// sin and cos on |r| <= pi/4 (Taylor, 13 and 14 terms: truncation < 1e-19).
double sinPoly(double r) {
  const double r2 = r * r;
  double p = -1.0 / 1307674368000.0;          // -1/15!
  p = p * r2 + 1.0 / 6227020800.0;            // 1/13!
  p = p * r2 - 1.0 / 39916800.0;              // -1/11!
  p = p * r2 + 1.0 / 362880.0;                // 1/9!
  p = p * r2 - 1.0 / 5040.0;                  // -1/7!
  p = p * r2 + 1.0 / 120.0;                   // 1/5!
  p = p * r2 - 1.0 / 6.0;                     // -1/3!
  return r + r * (r2 * p);
}
double cosPoly(double r) {
  const double r2 = r * r;
  double p = 1.0 / 20922789888000.0;          // 1/16!
  p = p * r2 - 1.0 / 87178291200.0;           // -1/14!
  p = p * r2 + 1.0 / 479001600.0;             // 1/12!
  p = p * r2 - 1.0 / 3628800.0;               // -1/10!
  p = p * r2 + 1.0 / 40320.0;                 // 1/8!
  p = p * r2 - 1.0 / 720.0;                   // -1/6!
  p = p * r2 + 1.0 / 24.0;                    // 1/4!
  const double hz = 0.5 * r2;
  const double w = 1.0 - hz;
  return w + (((1.0 - w) - hz) + r2 * r2 * p);
}

// Reduce x to r in [-pi/4, pi/4] with quadrant q. Valid for |x| < ~1e9 (more than the engine needs).
double reduce(double x, int& q) {
  const double k = roundHalfEven(x * kTwoOverPi);
  q = static_cast<int>(static_cast<unsigned long long>(static_cast<long long>(k)) & 3ULL);
  return ((x - k * kPio2_1) - k * kPio2_2) - k * kPio2_3;
}

}  // namespace

double fabs(double x) { return fromBits(bits(x) & 0x7FFFFFFFFFFFFFFFULL); }
double sqrt(double x) { return std::sqrt(x); }

double scalbn(double x, int k) {
  // Multiply by exact powers of two in steps that stay normal.
  while (k > 1000) { x *= fromBits(static_cast<std::uint64_t>(1023 + 1000) << 52U); k -= 1000; }
  while (k < -1000) { x *= fromBits(static_cast<std::uint64_t>(1023 - 1000) << 52U); k += 1000; }
  return x * fromBits(static_cast<std::uint64_t>(1023 + k) << 52U);
}

double exp(double x) {
  if (x != x) return x;
  if (x > 709.782712893384) return std::numeric_limits<double>::infinity();
  if (x < -745.1332191019411) return 0.0;
  const double k = roundHalfEven(x * kInvLn2);
  const double hi = x - k * kLn2Hi;
  const double lo = k * kLn2Lo;
  const double r = hi - lo;  // |r| <= 0.3466
  // Taylor to r^15/15!: truncation < 2e-18 relative.
  double p = 1.0 / 1307674368000.0;
  p = p * r + 1.0 / 87178291200.0;
  p = p * r + 1.0 / 6227020800.0;
  p = p * r + 1.0 / 479001600.0;
  p = p * r + 1.0 / 39916800.0;
  p = p * r + 1.0 / 3628800.0;
  p = p * r + 1.0 / 362880.0;
  p = p * r + 1.0 / 40320.0;
  p = p * r + 1.0 / 5040.0;
  p = p * r + 1.0 / 720.0;
  p = p * r + 1.0 / 120.0;
  p = p * r + 1.0 / 24.0;
  p = p * r + 1.0 / 6.0;
  p = p * r + 0.5;
  const double e = 1.0 + (r + r * r * p);
  return scalbn(e, static_cast<int>(k));
}

double log(double x) {
  if (!(x > 0.0)) return x == 0.0 ? -std::numeric_limits<double>::infinity() : std::numeric_limits<double>::quiet_NaN();
  if (x == std::numeric_limits<double>::infinity()) return x;
  int e = 0;
  std::uint64_t u = bits(x);
  if ((u >> 52U) == 0U) {  // subnormal: scale up
    x *= 18014398509481984.0;  // 2^54
    e -= 54;
    u = bits(x);
  }
  e += static_cast<int>((u >> 52U) & 0x7FFU) - 1023;
  double m = fromBits((u & 0x000FFFFFFFFFFFFFULL) | 0x3FF0000000000000ULL);  // [1,2)
  if (m > 1.4142135623730951) { m *= 0.5; e += 1; }  // m in [sqrt(1/2), sqrt(2)]
  const double s = (m - 1.0) / (m + 1.0);  // |s| <= 0.1716
  const double s2 = s * s;
  // atanh series: 2(s + s^3/3 + ... + s^23/23); truncation < 1e-19.
  double p = 1.0 / 23.0;
  p = p * s2 + 1.0 / 21.0;
  p = p * s2 + 1.0 / 19.0;
  p = p * s2 + 1.0 / 17.0;
  p = p * s2 + 1.0 / 15.0;
  p = p * s2 + 1.0 / 13.0;
  p = p * s2 + 1.0 / 11.0;
  p = p * s2 + 1.0 / 9.0;
  p = p * s2 + 1.0 / 7.0;
  p = p * s2 + 1.0 / 5.0;
  p = p * s2 + 1.0 / 3.0;
  const double logm = 2.0 * s + 2.0 * s * (s2 * p);
  const double de = static_cast<double>(e);
  return de * kLn2Hi + (logm + de * kLn2Lo);
}

double log10(double x) { return log(x) * kInvLn10; }

double pow(double x, double y) {
  if (y == 0.0) return 1.0;
  if (x == 1.0) return 1.0;
  if (!(x > 0.0)) return x == 0.0 ? (y > 0.0 ? 0.0 : std::numeric_limits<double>::infinity())
                                  : std::numeric_limits<double>::quiet_NaN();
  return exp(y * log(x));
}

double sin(double x) {
  int q;
  const double r = reduce(x, q);
  switch (q) {
    case 0: return sinPoly(r);
    case 1: return cosPoly(r);
    case 2: return -sinPoly(r);
    default: return -cosPoly(r);
  }
}

double cos(double x) {
  int q;
  const double r = reduce(x, q);
  switch (q) {
    case 0: return cosPoly(r);
    case 1: return -sinPoly(r);
    case 2: return -cosPoly(r);
    default: return sinPoly(r);
  }
}

double tan(double x) { return sin(x) / cos(x); }

}  // namespace sable::dm

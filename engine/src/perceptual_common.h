// Shared formulas of the Moore-Glasberg-Schlittenlacher time-varying loudness model (TVL 2018), MATH §10.1.
// Templated on the math backend so the shipped fast model uses sable::dm (deterministic) and the host-only
// reference may use <cmath>.
#pragma once

#include <vector>

#include "dsp.h"
#include "perceptual_tables.inc"

namespace sable::tvl {

inline constexpr double kC = 0.0631;             // MATH §10.1 step 6
inline constexpr double kStlAttack = 0.045;      // per 1 ms, TVL18
inline constexpr double kStlRelease = 0.033;
inline constexpr double kLtlAttack = 0.01;
inline constexpr double kLtlRelease = 0.00133;
inline constexpr double kInhibitionB = 0.08;     // MATH §10.1 step 8
inline constexpr double kInhibitionP = 1.5978;
inline constexpr double kCamLow = 1.75, kCamHigh = 39.0;

template <class M>
struct Formulas {
  static double cam(double f) { return 21.366 * M::log10(0.004368 * f + 1.0); }
  static double freq(double c) { return (M::pow(10.0, c / 21.366) - 1.0) / 0.004368; }
  static double erb(double f) { return 24.673 * (0.004368 * f + 1.0); }
  static double p(double f) { return 4.0 * f / erb(f); }
  static double pl(double fc, double x) { return p(fc) - 0.35 * (p(fc) / p(1000.0)) * (x - 51.0); }
  static double w(double p, double g) {  // roex, |g|
    const double a = g < 0.0 ? -g : g;
    return (1.0 + p * a) * M::exp(-p * a);
  }
  // E_THRQ(f) in dB: linear interpolation with constant ends, 3.63 dB for f >= 500 Hz.
  static double thresholdDb(double f) {
    if (f >= 500.0) return 3.63;
    const auto& x = tables::kThresholdHz;
    const auto& y = tables::kThresholdDb;
    if (f <= x[0]) return y[0];
    for (int i = 0; i + 1 < 15; ++i)
      if (f <= x[i + 1]) return y[i] + (y[i + 1] - y[i]) * (f - x[i]) / (x[i + 1] - x[i]);
    return y[14];
  }
  static double gLinear(double f) { return M::pow(10.0, 3.63 / 10.0) / M::pow(10.0, thresholdDb(f) / 10.0); }
  static double alpha(double f) {  // linear in G_dB, constant ends
    const double g = 10.0 * M::log10(gLinear(f));
    const auto& x = tables::kAlphaGdb;
    const auto& y = tables::kAlphaValue;
    if (g <= x[0]) return y[0];
    for (int i = 0; i + 1 < 6; ++i)
      if (g <= x[i + 1]) return y[i] + (y[i + 1] - y[i]) * (g - x[i]) / (x[i + 1] - x[i]);
    return y[5];
  }
};

// Per-CF constants of the specific-loudness transform. MATH §10.1 step 6.
struct CfParams {
  double fc, g, alpha, a, aPowAlpha, eThr, pu;
};

template <class M>
std::vector<CfParams> makeCfParams(double camStep) {
  static const dsp::Pchip aOfG(std::vector<double>(std::begin(tables::kAGdb), std::end(tables::kAGdb)),
                               std::vector<double>(std::begin(tables::kAValue), std::end(tables::kAValue)));
  std::vector<CfParams> out;
  const int n = static_cast<int>((kCamHigh - kCamLow) / camStep + 0.5) + 1;
  for (int i = 0; i < n; ++i) {
    const double c = kCamLow + camStep * i;
    if (c > kCamHigh + 1e-9) break;
    CfParams q{};
    q.fc = Formulas<M>::freq(c);
    q.g = Formulas<M>::gLinear(q.fc);
    q.alpha = Formulas<M>::alpha(q.fc);
    q.a = aOfG(10.0 * M::log10(q.g));
    q.aPowAlpha = M::pow(q.a, q.alpha);
    q.eThr = M::pow(10.0, Formulas<M>::thresholdDb(q.fc) / 10.0);
    q.pu = Formulas<M>::p(q.fc);
    out.push_back(q);
  }
  return out;
}

// Specific loudness from linear excitation E. MATH §10.1 step 6 (three branches).
template <class M>
double specificLoudness(const CfParams& q, double e) {
  if (e >= 1e10) return kC * M::pow(e / 1.0707, 0.2);
  const double core = M::pow(q.g * e + q.a, q.alpha) - q.aPowAlpha;
  if (e > q.eThr) return kC * core;
  const double r = 2.0 * e / (e + q.eThr);
  return kC * M::pow(r, 1.5) * core;
}

}  // namespace sable::tvl

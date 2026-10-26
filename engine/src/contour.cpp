// Quiet-mode loudness contour. MATH §24.
#include "sable/contour.h"

#include <charconv>
#include <cmath>

#include "sable/dmath.h"

namespace sable {
namespace {
#include "iso226_tables.inc"

constexpr double kRefPhon = 75.0;    // §24.2 L_ref (Standard calibration, §12.1)
constexpr double kQuietDelta = -5.0; // T_quiet - T_std
constexpr double kDesignFs = 48000.0;
constexpr double kLowCorners[] = {60, 80, 100, 125, 160, 200, 250};
constexpr double kHighCorners[] = {4000, 5000, 6300, 8000};

std::string num(double v) {
  char b[40];
  const auto r = std::to_chars(b, b + sizeof b, v);
  return std::string(b, r.ptr);
}

std::array<double, 5> shelf(double fs, double f0, double gainDb, bool low) {
  const double A = dm::pow(10.0, gainDb / 40.0);
  const double w0 = 2.0 * dm::kPi * f0 / fs;
  const double c = dm::cos(w0);
  const double alpha = dm::sin(w0) / 2.0 * dm::sqrt(2.0);  // S = 1
  const double k = 2.0 * dm::sqrt(A) * alpha;
  double b0, b1, b2, a0, a1, a2;
  if (low) {
    b0 = A * ((A + 1) - (A - 1) * c + k);
    b1 = 2 * A * ((A - 1) - (A + 1) * c);
    b2 = A * ((A + 1) - (A - 1) * c - k);
    a0 = (A + 1) + (A - 1) * c + k;
    a1 = -2 * ((A - 1) + (A + 1) * c);
    a2 = (A + 1) + (A - 1) * c - k;
  } else {
    b0 = A * ((A + 1) + (A - 1) * c + k);
    b1 = -2 * A * ((A - 1) + (A + 1) * c);
    b2 = A * ((A + 1) + (A - 1) * c - k);
    a0 = (A + 1) - (A - 1) * c + k;
    a1 = 2 * ((A - 1) - (A + 1) * c);
    a2 = (A + 1) - (A - 1) * c - k;
  }
  return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

}  // namespace

double iso226Freq(int i) { return kIsoF[i]; }

double iso226Spl(int i, double phon) {  // §24.1
  const double af = kIsoAf[i];
  const double A = 4.47e-3 * (dm::pow(10.0, 0.025 * phon) - 1.15) + dm::pow(0.4 * dm::pow(10.0, (kIsoTf[i] + kIsoLu[i]) / 10.0 - 9.0), af);
  return (10.0 / af) * dm::log10(A) - kIsoLu[i] + 94.0;
}

double contourTarget(int i, double calDb) {  // §24.2
  const double ref = kRefPhon + calDb, q = kRefPhon + kQuietDelta + calDb;
  return (iso226Spl(i, q) - iso226Spl(i, ref)) - (q - ref);
}

std::array<double, 5> lowShelf(double fs, double f0, double g) { return shelf(fs, f0, g, true); }
std::array<double, 5> highShelf(double fs, double f0, double g) { return shelf(fs, f0, g, false); }

double biquadResponseDb(const std::array<double, 5>& c, double fs, double f) {
  const double w = 2.0 * dm::kPi * f / fs;
  const double c1 = dm::cos(w), s1 = dm::sin(w), c2 = dm::cos(2 * w), s2 = dm::sin(2 * w);
  const double nr = c[0] + c[1] * c1 + c[2] * c2, ni = -(c[1] * s1 + c[2] * s2);
  const double dr = 1.0 + c[3] * c1 + c[4] * c2, di = -(c[3] * s1 + c[4] * s2);
  return 10.0 * dm::log10((nr * nr + ni * ni) / (dr * dr + di * di));
}

ContourDesign designQuietContour(double calDb) {  // §24.3
  constexpr int kF0 = 1, kF1 = 28;  // 25 Hz .. 12.5 kHz
  constexpr int nF = kF1 - kF0 + 1;
  double target[nF];
  for (int i = 0; i < nF; ++i) target[i] = contourTarget(kF0 + i, calDb);
  // Responses of each candidate shelf on the grid (the cascade's response in dB is the sum).
  constexpr int nL = 31, nH = 31;  // gains 0.0 .. 3.0 and -1.0 .. 2.0 dB in 0.1 dB steps
  static thread_local double low[7][nL][nF], high[4][nH][nF];
  for (int c = 0; c < 7; ++c)
    for (int g = 0; g < nL; ++g) {
      const auto bq = lowShelf(kDesignFs, kLowCorners[c], 0.1 * g);
      for (int i = 0; i < nF; ++i) low[c][g][i] = g == 0 ? 0.0 : biquadResponseDb(bq, kDesignFs, kIsoF[kF0 + i]);
    }
  for (int c = 0; c < 4; ++c)
    for (int g = 0; g < nH; ++g) {
      const double gain = -1.0 + 0.1 * g;
      const auto bq = highShelf(kDesignFs, kHighCorners[c], gain);
      for (int i = 0; i < nF; ++i) high[c][g][i] = g == 10 ? 0.0 : biquadResponseDb(bq, kDesignFs, kIsoF[kF0 + i]);
    }
  ContourDesign best;
  double bestCost = 1e300, bestMag = 1e300;
  for (int lc = 0; lc < 7; ++lc)
    for (int lg = 0; lg < nL; ++lg)
      for (int hc = 0; hc < 4; ++hc)
        for (int hg = 0; hg < nH; ++hg) {
          double cost = 0.0;
          for (int i = 0; i < nF; ++i) {
            const double e = low[lc][lg][i] + high[hc][hg][i] - target[i];
            cost += e * e;
          }
          const double gl = 0.1 * lg, gh = -1.0 + 0.1 * hg;
          const double mag = dm::fabs(gl) + dm::fabs(gh);
          if (cost < bestCost - 1e-12 || (cost <= bestCost + 1e-12 && mag < bestMag)) {  // ties: smaller |gain|
            bestCost = cost;
            bestMag = mag;
            best.lowHz = kLowCorners[lc];
            best.lowGainDb = gl;
            best.highHz = kHighCorners[hc];
            best.highGainDb = gh;
          }
        }
  best.headroomDb = (best.lowGainDb > 0 ? best.lowGainDb : 0.0) + (best.highGainDb > 0 ? best.highGainDb : 0.0);  // §24.4
  best.rmsErrorDb = dm::sqrt(bestCost / nF);
  return best;
}

std::string contourJson(double calDb, double fs) {
  const ContourDesign d = designQuietContour(calDb);
  const auto lo = lowShelf(fs, d.lowHz, d.lowGainDb), hi = highShelf(fs, d.highHz, d.highGainDb);
  auto arr = [](const std::array<double, 5>& c) {
    std::string o = "[";
    for (int i = 0; i < 5; ++i) o += (i ? "," : "") + num(c[static_cast<std::size_t>(i)]);
    return o + "]";
  };
  return "{\"lowHz\":" + num(d.lowHz) + ",\"lowGainDb\":" + num(d.lowGainDb) + ",\"highHz\":" + num(d.highHz) +
         ",\"highGainDb\":" + num(d.highGainDb) + ",\"headroomDb\":" + num(d.headroomDb) + ",\"rmsErrorDb\":" + num(d.rmsErrorDb) +
         ",\"low\":" + arr(lo) + ",\"high\":" + arr(hi) + "}";
}

}  // namespace sable

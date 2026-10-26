// Fast perceptual model shipped on the phone. MATH §11 (F1-F7), sharing formulas with the reference (§10).
// Deterministic: sable::dm only, fixed loop order, float spectra / double accumulators.
#include <algorithm>
#include <cstring>
#include <memory>

#include "dsp.h"
#include "fpenv.h"
#include "perceptual_common.h"
#include "pink_table.inc"
#include "sable/dmath.h"
#include "sable/perceptual.h"

namespace sable {
namespace {

struct DmMath {
  static double log10(double x) { return dm::log10(x); }
  static double pow(double x, double y) { return dm::pow(x, y); }
  static double exp(double x) { return dm::exp(x); }
};
using F = tvl::Formulas<DmMath>;

constexpr int kFs = 32000;
#ifndef SABLE_FAST_FRAME_MS
#define SABLE_FAST_FRAME_MS 8
#endif
constexpr int kFrameMs = SABLE_FAST_FRAME_MS;  // F1 frame period (ms); see MATH 11.2 for the measured choice
constexpr int kFrameHop32 = 32 * kFrameMs;
constexpr int kFrameHop8 = 8 * kFrameMs;
constexpr int kNa = 512, kNb = 512, kNc = 128;  // F3 window lengths (A at 8 kHz, B and C at 32 kHz)
constexpr double kKappa = 1.6108742; // 0.75 * 10^0.332 (MATH §11.0)
constexpr double kCompCamLow = 0.75;  // component bands from 0.75 Cam (covers 20 Hz) to 39 Cam
constexpr int kComp = 154;            // (39 - 0.75) / 0.25 + 1
constexpr double kCfStep = 0.5;       // F5
constexpr float kSampleFlush = 7.888609052210118e-31f;  // 2^-100 (MATH §25.3)
constexpr int kXLo = -30, kXHi = 130; // F5 lower-slope table range (dB), 1 dB grid
constexpr double kWFloor = 1e-8;      // filter-shape weights below this are dropped (relative contribution < 1e-8)

double agcFrame(double a1) {  // F1: per-frame coefficient 1 - (1 - a1)^kFrameMs, exact for constant input
  double r = 1.0;
  for (int i = 0; i < kFrameMs; ++i) r *= 1.0 - a1;
  return 1.0 - r;
}

struct BinMap {  // one analysis band: bins -> component index, with ear power weight
  int n = 0, kLo = 0, kHi = 0;  // bins [kLo, kHi] used
  std::vector<int> comp;        // per bin in [kLo, kHi]: component index or -1
  std::vector<float> weight;    // per bin: kappa * 2/(N*sum w^2) * |H|^2 * 10^(Dcal/10)
  std::vector<float> window;
};

}  // namespace

struct FastLoudness::Impl {
  int fsIn = 0, ch = 0, hopIn = 0;
  float gain = 1.0f;
  // Streaming signal per ear at 32 kHz and 8 kHz with absolute base indices.
  std::vector<float> s32[2], s8[2];
  long long base32 = 0, base8 = 0;
  std::vector<std::unique_ptr<dsp::Resampler<float>>> rsIn, rs8;
  std::vector<float> tmpIn, tmpOut;
  long long nextFrame = 0;
  long long inFrames = 0;  // input frames received
  bool finishing = false;

  BinMap bandA, bandB, bandC;
  dsp::RealPowerFft<float> fftA{kNa}, fftC{kNc};
  std::vector<float> buf, pw;
  std::vector<double> compF;                  // component centre frequencies
  std::vector<float> wx;                      // [i * kComp + k]: W(p(f_i), g_ik) for X (source i spreads)
  std::vector<int> wxLo, wxHi;                // per source i: k range with W >= kWFloor
  std::vector<int> upLo, upHi;                // per component k: CF range with wUp >= kWFloor
  std::vector<float> compBuf[2], xBuf, eBuf;  // per-frame work buffers (no allocation per frame)
  std::vector<double> smBuf[2];
  std::vector<tvl::CfParams> cfs;             // 75 CFs
  std::vector<float> wUp;                     // [k * ncf + c] for f_k >= fc (0 otherwise)
  std::vector<int> lowPairsStart;             // per component k: offset into lowPairs
  struct LowPair { int c; int tableOffset; };
  std::vector<LowPair> lowPairs;
  std::vector<float> lowTable;                // per pair: W at X = kXLo..kXHi
  std::vector<double> kern;                   // binaural kernel at 0.5 Cam (73 taps, normalized)
  double aStlA = 0, aStlR = 0, aLtlA = 0, aLtlR = 0;
  std::vector<double> stl[2];
  double ltl[2] = {0.0, 0.0};
  std::vector<double> frameStl, frameLtl;

  void setupBand(BinMap& b, int n, double fsBand, double fLo, double fHi, bool includeTop,
                 const double* earPower, double cal) {
    b.n = n;
    const auto w = dsp::hannSymmetric(static_cast<std::size_t>(n));
    double sw2 = 0.0;
    for (double v : w) sw2 += v * v;
    b.window.assign(w.begin(), w.end());
    const double df = fsBand / n;
    b.kLo = static_cast<int>(fLo / df);
    while (b.kLo * df < fLo) ++b.kLo;
    b.kHi = static_cast<int>(fHi / df);
    while (b.kHi * df > fHi || (!includeTop && b.kHi * df >= fHi)) --b.kHi;
    for (int k = b.kLo; k <= b.kHi; ++k) {
      const double f = k * df;
      const double cam = F::cam(f);
      const double pos = (cam - kCompCamLow) / 0.25;
      int idx = static_cast<int>(pos + 0.5);
      if (pos < -0.5 || idx >= kComp) idx = -1;
      b.comp.push_back(idx);
      b.weight.push_back(static_cast<float>(kKappa * 2.0 / (n * sw2) * earPower[k] * cal));
    }
  }

  void init(double dcalDb) {
    const double cal = dm::pow(10.0, dcalDb / 10.0);
    setupBand(bandA, kNa, 8000.0, 20.0, 500.0, false, tables::kEarPowerBandA, cal);
    setupBand(bandB, kNb, kFs, 500.0, 2540.0, false, tables::kEarPowerBandB, cal);
    setupBand(bandC, kNc, kFs, 2540.0, 15000.0, true, tables::kEarPowerBandC, cal);
    buf.resize(kNa); pw.resize(kNa / 2 + 1);
    compF.resize(kComp);
    for (int i = 0; i < kComp; ++i) compF[static_cast<std::size_t>(i)] = F::freq(kCompCamLow + 0.25 * i);
    wx.assign(static_cast<std::size_t>(kComp) * kComp, 0.0f);
    for (int i = 0; i < kComp; ++i) {
      const double pi = F::p(compF[static_cast<std::size_t>(i)]);
      for (int k = 0; k < kComp; ++k) {
        const double g = (compF[static_cast<std::size_t>(k)] - compF[static_cast<std::size_t>(i)]) / compF[static_cast<std::size_t>(i)];
        if (g <= 4.0) wx[static_cast<std::size_t>(i * kComp + k)] = static_cast<float>(F::w(pi, g));
      }
    }
    cfs = tvl::makeCfParams<DmMath>(kCfStep);
    const int ncf = static_cast<int>(cfs.size());
    wUp.assign(static_cast<std::size_t>(kComp) * static_cast<std::size_t>(ncf), 0.0f);
    lowPairsStart.assign(static_cast<std::size_t>(kComp) + 1, 0);
    for (int k = 0; k < kComp; ++k) {
      lowPairsStart[static_cast<std::size_t>(k)] = static_cast<int>(lowPairs.size());
      const double fk = compF[static_cast<std::size_t>(k)];
      for (int c = 0; c < ncf; ++c) {
        const auto& q = cfs[static_cast<std::size_t>(c)];
        const double g = (fk - q.fc) / q.fc;
        if (g > 4.0) continue;
        if (fk >= q.fc) {
          wUp[static_cast<std::size_t>(k * ncf + c)] = static_cast<float>(F::w(q.pu, g));
        } else {
          // Lower slope depends on the component's input level X: tabulate on a 1 dB grid (F5).
          if (F::w(F::pl(q.fc, kXHi), g) < 1e-9) continue;  // negligible even at the shallowest slope
          LowPair lp{c, static_cast<int>(lowTable.size())};
          for (int x = kXLo; x <= kXHi; ++x) lowTable.push_back(static_cast<float>(F::w(F::pl(q.fc, x), g)));
          lowPairs.push_back(lp);
        }
      }
    }
    lowPairsStart[static_cast<std::size_t>(kComp)] = static_cast<int>(lowPairs.size());
    wxLo.assign(kComp, 0);
    wxHi.assign(kComp, -1);
    for (int i = 0; i < kComp; ++i)
      for (int k = 0; k < kComp; ++k) {
        float& w = wx[static_cast<std::size_t>(i * kComp + k)];
        if (w >= kWFloor) {
          if (wxHi[static_cast<std::size_t>(i)] < 0) wxLo[static_cast<std::size_t>(i)] = k;
          wxHi[static_cast<std::size_t>(i)] = k;
        } else {
          w = 0.0f;
        }
      }
    upLo.assign(kComp, 0);
    upHi.assign(kComp, -1);
    for (int k = 0; k < kComp; ++k)
      for (int c = 0; c < ncf; ++c) {
        float& w = wUp[static_cast<std::size_t>(k * ncf + c)];
        if (w >= kWFloor) {
          if (upHi[static_cast<std::size_t>(k)] < 0) upLo[static_cast<std::size_t>(k)] = c;
          upHi[static_cast<std::size_t>(k)] = c;
        } else {
          w = 0.0f;
        }
      }
    compBuf[0].assign(kComp, 0.0f);
    compBuf[1].assign(kComp, 0.0f);
    xBuf.assign(kComp, 0.0f);
    eBuf.assign(static_cast<std::size_t>(ncf), 0.0f);
    smBuf[0].assign(static_cast<std::size_t>(ncf), 0.0);
    smBuf[1].assign(static_cast<std::size_t>(ncf), 0.0);
    kern.resize(73);
    double ks = 0.0;
    for (int k = 0; k < 73; ++k) {
      const double d = -18.0 + 0.5 * k;
      kern[static_cast<std::size_t>(k)] = dm::exp(-(tvl::kInhibitionB * d) * (tvl::kInhibitionB * d));
      ks += kern[static_cast<std::size_t>(k)];
    }
    for (auto& v : kern) v /= ks;
    aStlA = agcFrame(tvl::kStlAttack); aStlR = agcFrame(tvl::kStlRelease);
    aLtlA = agcFrame(tvl::kLtlAttack); aLtlR = agcFrame(tvl::kLtlRelease);
    stl[0].assign(cfs.size(), 0.0);
    stl[1].assign(cfs.size(), 0.0);
  }

  // Accumulate one band's component powers for ear e from signal sig (absolute index base) centred at c.
  void bandPowers(const BinMap& b, dsp::RealPowerFft<float>& fft, const std::vector<float>& sig, long long base,
                  long long centre, float* comp) {
    const long long start = centre - b.n / 2;
    for (int i = 0; i < b.n; ++i) {
      const long long idx = start + i - base;
      const float v = (idx >= 0 && idx < static_cast<long long>(sig.size())) ? sig[static_cast<std::size_t>(idx)] : 0.0f;
      buf[static_cast<std::size_t>(i)] = v * b.window[static_cast<std::size_t>(i)];
    }
    fft.power(buf.data(), pw.data());
    for (int k = b.kLo; k <= b.kHi; ++k) {
      const int ci = b.comp[static_cast<std::size_t>(k - b.kLo)];
      if (ci >= 0) comp[ci] += pw[static_cast<std::size_t>(k)] * b.weight[static_cast<std::size_t>(k - b.kLo)];
    }
  }

  void processFrame(long long n) {
    const int ncf = static_cast<int>(cfs.size());
    float imax = 0.0f;
    for (int e = 0; e < 2; ++e) {
      float* cmp = compBuf[e].data();
      std::fill(cmp, cmp + kComp, 0.0f);
      bandPowers(bandA, fftA, s8[e], base8, n * kFrameHop8, cmp);
      bandPowers(bandB, fftA, s32[e], base32, n * kFrameHop32, cmp);
      bandPowers(bandC, fftC, s32[e], base32, n * kFrameHop32, cmp);
      for (int k = 0; k < kComp; ++k) imax = std::max(imax, cmp[k]);
    }
    if (imax < 1000.0f) imax = 1000.0f;
    const float thr = imax * 1e-6f;
    float* X = xBuf.data();
    float* E = eBuf.data();
    for (int e = 0; e < 2; ++e) {
      float* cmp = compBuf[e].data();
      for (int k = 0; k < kComp; ++k)
        if (!(cmp[k] > thr)) cmp[k] = 0.0f;  // pruning (MATH 10.1 step 4)
      // Input level per ERB: X[k] = sum_i I_i W(p_i, g_ik). Source-outer, k-inner: vectorizable, fixed order.
      std::fill(X, X + kComp, 0.0f);
      for (int i = 0; i < kComp; ++i) {
        const float ii = cmp[i];
        if (ii == 0.0f) continue;
        const float* row = &wx[static_cast<std::size_t>(i * kComp)];
        for (int k = wxLo[static_cast<std::size_t>(i)]; k <= wxHi[static_cast<std::size_t>(i)]; ++k) X[k] = X[k] + ii * row[k];
      }
      std::fill(E, E + ncf, 0.0f);
      for (int k = 0; k < kComp; ++k) {
        const float ik = cmp[k];
        if (ik == 0.0f) continue;
        const float* up = &wUp[static_cast<std::size_t>(k * ncf)];
        for (int c = upLo[static_cast<std::size_t>(k)]; c <= upHi[static_cast<std::size_t>(k)]; ++c) E[c] = E[c] + ik * up[c];
        double xc = X[k] > 0.0f ? 10.0 * dm::log10(static_cast<double>(X[k])) : static_cast<double>(kXLo);
        if (xc < kXLo) xc = kXLo;
        if (xc > kXHi) xc = kXHi;
        const double pos = xc - kXLo;
        int i0 = static_cast<int>(pos);
        if (i0 >= kXHi - kXLo) i0 = kXHi - kXLo - 1;
        const float fr = static_cast<float>(pos - i0);
        for (int p = lowPairsStart[static_cast<std::size_t>(k)]; p < lowPairsStart[static_cast<std::size_t>(k) + 1]; ++p) {
          const auto& lp = lowPairs[static_cast<std::size_t>(p)];
          const float* t = &lowTable[static_cast<std::size_t>(lp.tableOffset + i0)];
          E[lp.c] = E[lp.c] + ik * (t[0] + fr * (t[1] - t[0]));
        }
      }
      for (int c = 0; c < ncf; ++c) {  // specific loudness + short-term AGC (F1)
        const double nsp = tvl::specificLoudness<DmMath>(cfs[static_cast<std::size_t>(c)], static_cast<double>(E[c]));
        double& st = stl[e][static_cast<std::size_t>(c)];
        const double a = nsp > st ? aStlA : aStlR;
        st = a * nsp + (1.0 - a) * st;
      }
    }
    // Binaural inhibition (F6) on short-term patterns
    for (int e = 0; e < 2; ++e) {
      double* sm = smBuf[e].data();
      const double* st = stl[e].data();
      for (int c = 0; c < ncf; ++c) {
        double acc = 0.0;
        const int kLo = std::max(0, c + 36 - (ncf - 1)), kHi = std::min(72, c + 36);
        for (int k = kLo; k <= kHi; ++k) acc += kern[static_cast<std::size_t>(k)] * st[c + 36 - k];
        sm[c] = acc + 1e-13;
      }
    }
    double stlTot = 0.0, ltlTot = 0.0;
    for (int e = 0; e < 2; ++e) {
      double sum = 0.0;
      for (int c = 0; c < ncf; ++c) {
        const double r = smBuf[1 - e][static_cast<std::size_t>(c)] / smBuf[e][static_cast<std::size_t>(c)];
        double inh = 2.0;
        if (r < 700.0) {
          const double q = dm::exp(-r);  // sech r = 2 e^-r / (1 + e^-2r): one exp
          const double sechR = 2.0 * q / (1.0 + q * q);
          inh = 2.0 / (1.0 + dm::pow(sechR, tvl::kInhibitionP));
        }
        sum += stl[e][static_cast<std::size_t>(c)] / inh;
      }
      const double st = sum * kCfStep;  // F7
      const double a = st > ltl[e] ? aLtlA : aLtlR;
      ltl[e] = a * st + (1.0 - a) * ltl[e];
      stlTot += st;
      ltlTot += ltl[e];
    }
    frameStl.push_back(stlTot);
    frameLtl.push_back(ltlTot);
  }

  void pump() {
    for (;;) {
      const long long need32 = nextFrame * kFrameHop32 + kNb / 2;
      const long long need8 = nextFrame * kFrameHop8 + kNa / 2;
      const long long have32 = base32 + static_cast<long long>(s32[0].size());
      const long long have8 = base8 + static_cast<long long>(s8[0].size());
      if (!finishing && (need32 > have32 || need8 > have8)) break;
      if (finishing && nextFrame * kFrameHop32 >= have32) break;
      processFrame(nextFrame);
      ++nextFrame;
    }
    // Trim samples no future frame needs.
    const long long keep32 = nextFrame * kFrameHop32 - kNb / 2, keep8 = nextFrame * kFrameHop8 - kNa / 2;
    for (int e = 0; e < 2; ++e) {
      if (keep32 - base32 > 65536) {
        const auto d = static_cast<std::ptrdiff_t>(keep32 - base32);
        s32[e].erase(s32[e].begin(), s32[e].begin() + d);
      }
      if (keep8 - base8 > 16384) {
        const auto d = static_cast<std::ptrdiff_t>(keep8 - base8);
        s8[e].erase(s8[e].begin(), s8[e].begin() + d);
      }
    }
    if (keep32 - base32 > 65536) base32 = keep32;
    if (keep8 - base8 > 16384) base8 = keep8;
  }

  void feedEar(int e, const float* x, std::size_t n, bool flush) {
    tmpOut.clear();
    auto& r = *rsIn[static_cast<std::size_t>(e)];
    if (fsIn == kFs) tmpOut.assign(x, x + n);
    else if (flush) r.finish(tmpOut);
    else r.push(x, n, tmpOut);
    s32[e].insert(s32[e].end(), tmpOut.begin(), tmpOut.end());
    std::vector<float> o8;
    if (flush) rs8[static_cast<std::size_t>(e)]->push(tmpOut.data(), tmpOut.size(), o8), rs8[static_cast<std::size_t>(e)]->finish(o8);
    else rs8[static_cast<std::size_t>(e)]->push(tmpOut.data(), tmpOut.size(), o8);
    s8[e].insert(s8[e].end(), o8.begin(), o8.end());
  }
};

FastLoudness::FastLoudness(int fs, int channels, double prescaleDb, double dcalDb) : impl_(new Impl) {
  auto& s = *impl_;
  s.fsIn = fs;
  s.ch = channels;
  s.hopIn = (fs + 5) / 10;
  s.gain = static_cast<float>(dm::pow(10.0, prescaleDb / 20.0));
  for (int e = 0; e < 2; ++e) {
    s.rsIn.push_back(std::make_unique<dsp::Resampler<float>>(fs == kFs ? 48000 : fs, kFs, 32, 10.0));
    s.rs8.push_back(std::make_unique<dsp::Resampler<float>>(kFs, 8000, 16, 8.0));  // band A uses <= 500 Hz only
  }
  s.init(dcalDb);
}

FastLoudness::~FastLoudness() { delete impl_; }

void FastLoudness::push(const float* in, std::size_t n) {
  FpEnvGuard fp;
  auto& s = *impl_;
  s.inFrames += static_cast<long long>(n);
  for (int e = 0; e < 2; ++e) {
    const int c = std::min(e, s.ch - 1);  // mono -> both ears
    s.tmpIn.resize(n);
    for (std::size_t i = 0; i < n; ++i) {
      float v = in[i * static_cast<std::size_t>(s.ch) + static_cast<std::size_t>(c)] * s.gain;
      if (v < kSampleFlush && v > -kSampleFlush) v = 0.0f;
      s.tmpIn[i] = v;
    }
    s.feedEar(e, s.tmpIn.data(), n, false);
  }
  s.pump();
}

PerceptualSeries FastLoudness::finish() {
  FpEnvGuard fp;
  auto& s = *impl_;
  for (int e = 0; e < 2; ++e) s.feedEar(e, nullptr, 0, true);
  s.finishing = true;
  s.pump();
  PerceptualSeries out;
  out.frameSeconds = kFrameMs / 1000.0;
  // Sub-block m holds the frames whose time lies in [0.1m, 0.1(m+1)) s (MATH §10.1 step 11).
  const std::size_t nsub = static_cast<std::size_t>(s.inFrames / s.hopIn);
  out.stlSone.assign(nsub, std::nullopt);
  out.ltlSone.assign(nsub, std::nullopt);
  for (std::size_t m = 0; m < nsub; ++m) {
    double a = 0.0, b = 0.0;
    int cnt = 0;
    // frames with t = n*kFrameMs ms in [100m, 100m + 100) ms
    for (std::size_t n = (m * 100 + kFrameMs - 1) / kFrameMs; n * kFrameMs < (m + 1) * 100 && n < s.frameStl.size(); ++n) { a += s.frameStl[n]; b += s.frameLtl[n]; ++cnt; }
    if (cnt) { out.stlSone[m] = a / cnt; out.ltlSone[m] = b / cnt; }
  }
  out.ltlPerFrame = s.frameLtl;
  return out;
}

double pleFromSone(double sone, bool* clamped) {  // MATH §12.3
  const auto& t = tables::kPinkSone;
  constexpr int n = 147;
  if (clamped) *clamped = false;
  if (!(sone > t[0])) {
    if (clamped) *clamped = true;
    return tables::kPinkLufsStart;
  }
  if (sone >= t[n - 1]) {
    if (clamped) *clamped = true;
    return tables::kPinkLufsStart + tables::kPinkLufsStep * (n - 1);
  }
  int i = 0;
  while (i + 1 < n && t[i + 1] <= sone) ++i;
  const double a = dm::log(t[i]), b = dm::log(t[i + 1]);
  return tables::kPinkLufsStart + tables::kPinkLufsStep * (i + (dm::log(sone) - a) / (b - a));
}

}  // namespace sable

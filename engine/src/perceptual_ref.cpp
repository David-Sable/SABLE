// Host-only reference port of TVL 2018 (MATH §10.1). Follows tv2018.m step by step; double precision.
// Not shipped: uses <cmath> for speed. Validated against the authors' MATLAB code in Octave (MATH §10.2).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <stdexcept>

#include "dsp.h"
#include "perceptual_common.h"
#include "sable/perceptual.h"

namespace sable {
namespace {

struct StdMath {
  static double log10(double x) { return std::log10(x); }
  static double pow(double x, double y) { return std::pow(x, y); }
  static double exp(double x) { return std::exp(x); }
};
using F = tvl::Formulas<StdMath>;

constexpr int kFs = 32000;
constexpr int kNpts = 2048;
constexpr int kHop = 32;  // 1 ms

std::vector<double> loadFir(const std::string& path) {
  std::FILE* f = std::fopen(path.c_str(), "rb");
  if (!f) throw std::runtime_error("cannot open FIR file " + path);
  std::vector<double> h(4097);
  const std::size_t got = std::fread(h.data(), sizeof(double), h.size(), f);
  std::fclose(f);
  if (got != h.size()) throw std::runtime_error("short FIR file");
  return h;
}

// Full linear convolution via overlap-add FFT (exact up to rounding).
std::vector<double> convolve(const std::vector<double>& x, const std::vector<double>& h) {
  const std::size_t n = 16384, block = n - h.size() + 1;
  dsp::Fft<double> fft(n);
  std::vector<double> hr(n, 0.0), hi(n, 0.0);
  std::copy(h.begin(), h.end(), hr.begin());
  fft.forward(hr.data(), hi.data());
  std::vector<double> y(x.size() + h.size() - 1, 0.0), re(n), im(n);
  for (std::size_t s = 0; s < x.size(); s += block) {
    std::fill(re.begin(), re.end(), 0.0);
    std::fill(im.begin(), im.end(), 0.0);
    const std::size_t len = std::min(block, x.size() - s);
    std::copy(x.begin() + static_cast<std::ptrdiff_t>(s), x.begin() + static_cast<std::ptrdiff_t>(s + len), re.begin());
    fft.forward(re.data(), im.data());
    for (std::size_t k = 0; k < n; ++k) {  // multiply, then inverse via conj-FFT-conj
      const double r = re[k] * hr[k] - im[k] * hi[k], i = re[k] * hi[k] + im[k] * hr[k];
      re[k] = r;
      im[k] = -i;
    }
    fft.forward(re.data(), im.data());
    for (std::size_t k = 0; k < len + h.size() - 1 && s + k < y.size(); ++k) y[s + k] += re[k] / static_cast<double>(n);
  }
  return y;
}

}  // namespace

PerceptualSeries referenceLoudness(const std::vector<float>& in, int fsIn, int channels, double prescaleDb,
                                   const std::string& firPath, double dcalDb) {
  const double gain = std::pow(10.0, prescaleDb / 20.0);
  const std::size_t frames = in.size() / static_cast<std::size_t>(channels);
  // Ears: ch0 -> left, ch1 -> right; mono -> both (diotic). MATH §10.1.
  std::vector<double> ear[2];
  for (int e = 0; e < 2; ++e) {
    const int c = std::min(e, channels - 1);
    std::vector<double> x(frames);
    for (std::size_t i = 0; i < frames; ++i) x[i] = gain * static_cast<double>(in[i * static_cast<std::size_t>(channels) + static_cast<std::size_t>(c)]);
    std::vector<double> y;
    if (fsIn == kFs) {
      y = std::move(x);
    } else {
      dsp::Resampler<double> rs(fsIn, kFs, 32, 10.0);
      rs.push(x.data(), x.size(), y);
      rs.finish(y);
    }
    // SoundField2Cochlea: full convolution, keep (1-based) 1025 .. end-1024.
    const std::vector<double> h = loadFir(firPath);
    std::vector<double> full = convolve(y, h);
    ear[e].assign(full.begin() + 1024, full.end() - 1024);
  }

  const std::size_t len = ear[0].size();
  PerceptualSeries out;
  out.frameSeconds = 0.001;
  if (len < static_cast<std::size_t>(kNpts)) return out;
  const std::size_t nSeg = (len - kNpts) / kHop;  // frames 0..nSeg

  // Windows (symmetric Hann, centred, zero-padded to 2048) and band limits (TVL18 code).
  std::vector<std::vector<double>> win(6, std::vector<double>(kNpts, 0.0));
  for (int i = 0; i < 6; ++i) {
    const std::size_t wl = static_cast<std::size_t>(kNpts >> i);
    const std::size_t off = (kNpts - wl) / 2;
    const auto hw = dsp::hannSymmetric(wl);
    for (std::size_t k = 0; k < wl; ++k) win[static_cast<std::size_t>(i)][off + k] = hw[k];
  }
  const double limF[7] = {20, 80, 500, 1250, 2540, 4050, 15000};
  std::size_t lim[7];
  for (int i = 0; i < 7; ++i) lim[i] = static_cast<std::size_t>(std::floor(limF[i] / (static_cast<double>(kFs) / kNpts)));  // 0-based
  const double hannCorr = std::pow(10.0, 3.32 / 10.0), cal = std::pow(10.0, dcalDb / 10.0);

  const auto cfs = tvl::makeCfParams<StdMath>(0.25);  // 150 CFs
  const std::size_t ncf = cfs.size();
  dsp::Fft<double> fft(kNpts);
  std::vector<double> buf(kNpts), pw(kNpts / 2 + 1), sre(kNpts), sim(kNpts);
  std::vector<double> I[2] = {std::vector<double>(kNpts / 2 + 1), std::vector<double>(kNpts / 2 + 1)};
  std::vector<double> stl[2] = {std::vector<double>(ncf, 0.0), std::vector<double>(ncf, 0.0)};
  double ltl[2] = {0.0, 0.0};
  std::vector<double> stlTotal, ltlTotal;

  // Binaural kernel: Delta = -18..18 step 0.25 (145 taps), normalized by its sum.
  std::vector<double> kern(145);
  double ksum = 0.0;
  for (int k = 0; k < 145; ++k) { const double d = -18.0 + 0.25 * k; kern[static_cast<std::size_t>(k)] = std::exp(-(tvl::kInhibitionB * d) * (tvl::kInhibitionB * d)); ksum += kern[static_cast<std::size_t>(k)]; }

  std::vector<double> fComp, lComp, iComp, X, inst(ncf);
  for (std::size_t seg = 0; seg <= nSeg; ++seg) {
    double imax = 0.0;
    for (int e = 0; e < 2; ++e) {
      std::fill(I[e].begin(), I[e].end(), 0.0);
      const double* s = ear[e].data() + seg * kHop;
      for (int w = 0; w < 6; ++w) {
        for (int k = 0; k < kNpts; ++k) buf[static_cast<std::size_t>(k)] = s[k] * win[static_cast<std::size_t>(w)][static_cast<std::size_t>(k)];
        fft.powerOfReal(buf.data(), pw.data(), sre.data(), sim.data());
        for (std::size_t k = lim[w]; k <= lim[w + 1]; ++k) {
          double a = std::sqrt(pw[k]) / kNpts;
          if (k != 0 && k != kNpts / 2) a *= 2.0;
          I[e][k] = a * a * hannCorr * std::pow(2.0, w) * cal;
        }
      }
      for (double v : I[e]) imax = std::max(imax, v);
    }
    if (imax < 1000.0) imax = 1000.0;
    const double thr = imax / 1e6;

    std::vector<double> spec[2];
    for (int e = 0; e < 2; ++e) {
      fComp.clear(); iComp.clear();
      for (std::size_t k = 1; k <= kNpts / 2; ++k)
        if (I[e][k] > thr) { fComp.push_back(static_cast<double>(k) * kFs / kNpts); iComp.push_back(I[e][k]); }
      spec[e].assign(ncf, 0.0);
      if (!fComp.empty()) {
        const std::size_t nc = fComp.size();
        X.assign(nc, 0.0);  // InputLevelPerERB: each source i spreads with its own p
        for (std::size_t i = 0; i < nc; ++i) {
          const double pi = F::p(fComp[i]);
          for (std::size_t k = 0; k < nc; ++k) {
            const double g = (fComp[k] - fComp[i]) / fComp[i];
            if (g <= 4.0) X[k] += iComp[i] * F::w(pi, g);
          }
        }
        for (auto& v : X) v = 10.0 * std::log10(v);
        for (std::size_t c = 0; c < ncf; ++c) {
          const auto& q = cfs[c];
          double e2 = 0.0;
          for (std::size_t k = 0; k < nc; ++k) {
            const double g = (fComp[k] - q.fc) / q.fc;
            if (g > 4.0) continue;
            const double p = fComp[k] >= q.fc ? q.pu : F::pl(q.fc, X[k]);
            e2 += F::w(p, g) * iComp[k];
          }
          spec[e][c] = tvl::specificLoudness<StdMath>(q, e2);
        }
      }
      // Short-term specific loudness AGC (MATH §10.1 step 7)
      for (std::size_t c = 0; c < ncf; ++c) {
        const double a = spec[e][c] > stl[e][c] ? tvl::kStlAttack : tvl::kStlRelease;
        stl[e][c] = a * spec[e][c] + (1.0 - a) * stl[e][c];
      }
    }
    // Binaural inhibition on the short-term patterns (MATH §10.1 step 8)
    std::vector<double> sm[2] = {std::vector<double>(ncf), std::vector<double>(ncf)};
    for (int e = 0; e < 2; ++e)
      for (std::size_t n = 0; n < ncf; ++n) {
        double acc = 0.0;
        for (int k = 0; k < 145; ++k) {
          const long long idx = static_cast<long long>(n) + 72 - k;
          if (idx >= 0 && idx < static_cast<long long>(ncf)) acc += kern[static_cast<std::size_t>(k)] * stl[e][static_cast<std::size_t>(idx)];
        }
        sm[e][n] = acc / ksum + 1e-13;
      }
    double stlEar[2] = {0.0, 0.0};
    for (int e = 0; e < 2; ++e) {
      for (std::size_t n = 0; n < ncf; ++n) {
        const double r = sm[1 - e][n] / sm[e][n];
        const double inh = 2.0 / (1.0 + std::pow(1.0 / std::cosh(r), tvl::kInhibitionP));
        stlEar[e] += stl[e][n] / inh;
      }
      stlEar[e] /= 4.0;
      const double a = stlEar[e] > ltl[e] ? tvl::kLtlAttack : tvl::kLtlRelease;
      ltl[e] = a * stlEar[e] + (1.0 - a) * ltl[e];
    }
    stlTotal.push_back(stlEar[0] + stlEar[1]);
    ltlTotal.push_back(ltl[0] + ltl[1]);
  }

  // Reduce to the input's 100 ms sub-blocks: frame i is at t = i ms (MATH §10.1 step 11).
  const std::size_t nsub = static_cast<std::size_t>(std::floor(static_cast<double>(frames) / (std::floor((fsIn + 5) / 10.0))));
  out.stlSone.assign(nsub, std::nullopt);
  out.ltlSone.assign(nsub, std::nullopt);
  for (std::size_t m = 0; m < nsub; ++m) {
    double s1 = 0.0, s2 = 0.0;
    int cnt = 0;
    for (std::size_t i = m * 100; i < (m + 1) * 100 && i < stlTotal.size(); ++i) { s1 += stlTotal[i]; s2 += ltlTotal[i]; ++cnt; }
    if (cnt) { out.stlSone[m] = s1 / cnt; out.ltlSone[m] = s2 / cnt; }
  }
  out.ltlPerFrame = std::move(ltlTotal);
  return out;
}

}  // namespace sable

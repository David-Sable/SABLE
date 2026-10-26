// Layer 1 baseline analyzer. Each block cites the MATH.md section it implements.
#include "sable/analyzer.h"

#include <algorithm>
#include <charconv>
#include <cstring>

#include "fpenv.h"
#include "sable/dmath.h"

namespace sable {
namespace {

constexpr double kPowerFloor = 1e-20;          // MATH §0.3
constexpr double kStateFlush = 1e-30;          // MATH §2.3, §25.3
constexpr float kSampleFlush = 7.888609052210118e-31f;  // 2^-100, MATH §25.3
constexpr double kSquareFlush = 3.054936363499605e-151; // 2^-500: y^2 >= 2^-1000, MATH §25.3
constexpr float kTapFlush = 9.5367431640625e-07f;       // 2^-20: taps below are zeroed so products stay normal
constexpr int kTpHalfLength = 16;              // Mh, MATH §6.2
constexpr double kTpBeta = 8.0;                // MATH §6.2

std::optional<double> powerToLufs(double p) {  // MATH §3.2 / eq. 2
  if (!(p > kPowerFloor)) return std::nullopt;
  return -0.691 + 10.0 * dm::log10(p);
}

double besselI0(double x) {  // MATH §6.2: power series until term < 1e-17 * sum
  double sum = 1.0, term = 1.0;
  const double q = (x * x) / 4.0;
  for (int k = 1; k < 500; ++k) {
    term *= q / (static_cast<double>(k) * static_cast<double>(k));
    sum += term;
    if (term < 1e-17 * sum) break;
  }
  return sum;
}

int roundHalfUpDiv(int fs, int d) { return (fs + d / 2) / d; }

// Polyphase true-peak interpolator taps for phases 1..L-1, each reversed for a forward dot product
// over the history window. MATH §6.2 (phase 0 is exactly delta: the samples themselves).
std::vector<std::vector<float>> designTruePeak(int L) {
  const int mh = kTpHalfLength;
  const int n = 2 * mh * L + 1;
  const double c = static_cast<double>(mh * L);
  std::vector<double> h(static_cast<std::size_t>(n));
  const double i0b = besselI0(kTpBeta);
  for (int i = 0; i < n; ++i) {
    const double t = (static_cast<double>(i) - c) / static_cast<double>(L);
    const double sinc = (i == static_cast<int>(c)) ? 1.0 : dm::sin(dm::kPi * t) / (dm::kPi * t);
    const double u = (static_cast<double>(i) - c) / c;
    const double w = besselI0(kTpBeta * dm::sqrt(std::max(0.0, 1.0 - u * u))) / i0b;
    h[static_cast<std::size_t>(i)] = sinc * w;
  }
  std::vector<std::vector<float>> phases(static_cast<std::size_t>(L));
  for (int phi = 1; phi < L; ++phi) {
    const int taps = 2 * mh;  // k = 0 .. 2Mh-1
    double sum = 0.0;
    for (int k = 0; k < taps; ++k) sum += h[static_cast<std::size_t>(phi) + static_cast<std::size_t>(L) * static_cast<std::size_t>(k)];
    std::vector<float> rev(static_cast<std::size_t>(taps));
    for (int j = 0; j < taps; ++j) {
      // y = sum_k h[phi+Lk] x[n-k]; window w[j] = x[n-(taps-1-j)] -> coefficient index k = taps-1-j.
      const double v = h[static_cast<std::size_t>(phi) + static_cast<std::size_t>(L) * static_cast<std::size_t>(taps - 1 - j)] / sum;  // unit DC gain
      float f = static_cast<float>(v);
      if (f < kTapFlush && f > -kTapFlush) f = 0.0f;
      rev[static_cast<std::size_t>(j)] = f;
    }
    phases[static_cast<std::size_t>(phi)] = std::move(rev);
  }
  return phases;
}

// Dot product with 4 fixed lanes, reduced as (l0+l1)+(l2+l3). MATH §25.4.
inline float dot4(const float* a, const float* b, int n) {
  float l0 = 0.f, l1 = 0.f, l2 = 0.f, l3 = 0.f;
  for (int i = 0; i < n; i += 4) {
    l0 = l0 + a[i] * b[i];
    l1 = l1 + a[i + 1] * b[i + 1];
    l2 = l2 + a[i + 2] * b[i + 2];
    l3 = l3 + a[i + 3] * b[i + 3];
  }
  return (l0 + l1) + (l2 + l3);
}

}  // namespace

// MATH §1.2: position-dependent BS.1770-5 weights from a WAVEFORMATEXTENSIBLE-style mask.
std::vector<double> channelWeightsFor(int channels, std::uint32_t mask) {
  if (channels == 1) return {2.0};  // dual mono: sum G z = 2 z_mono
  if (channels == 2 && (mask == 0 || mask == 0x3)) return {1.0, 1.0};
  if (mask == 0) {
    switch (channels) {
      case 3: mask = 0x7; break;
      case 4: mask = 0x33; break;
      case 5: mask = 0x37; break;
      case 6: mask = 0x3F; break;
      case 7: mask = 0x70F; break;
      case 8: mask = 0x63F; break;
      default: break;
    }
  }
  std::vector<double> w;
  const bool hasSide = (mask & 0x600u) != 0;
  for (int bit = 0; bit < 32 && static_cast<int>(w.size()) < channels; ++bit) {
    const std::uint32_t b = 1u << static_cast<unsigned>(bit);
    if (!(mask & b)) continue;
    double g = 1.0;
    const bool side = b == 0x200u || b == 0x400u;                // side +-90
    const bool backAsSurround = (b == 0x10u || b == 0x20u) && !hasSide;  // 5.1 back = surround +-110
    if (b == 0x8u) g = 0.0;                                      // LFE
    else if (side || backAsSurround) g = 1.41;
    w.push_back(g);
  }
  while (static_cast<int>(w.size()) < channels) w.push_back(1.0);  // unknown extra channels
  return w;
}

KWeighting KWeighting::forRate(double fs) {  // MATH §2.1, §2.2 (libebur128 derivation)
  KWeighting k{};
  {
    const double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
    const double kk = dm::tan(dm::kPi * f0 / fs);
    const double vh = dm::pow(10.0, g / 20.0);
    const double vb = dm::pow(vh, 0.4996667741545416);
    const double a0 = 1.0 + kk / q + kk * kk;
    k.b1[0] = (vh + vb * kk / q + kk * kk) / a0;
    k.b1[1] = 2.0 * (kk * kk - vh) / a0;
    k.b1[2] = (vh - vb * kk / q + kk * kk) / a0;
    k.a1[0] = 1.0;
    k.a1[1] = 2.0 * (kk * kk - 1.0) / a0;
    k.a1[2] = (1.0 - kk / q + kk * kk) / a0;
  }
  {
    const double f0 = 38.13547087602444, q = 0.5003270373238773;
    const double kk = dm::tan(dm::kPi * f0 / fs);
    const double d = 1.0 + kk / q + kk * kk;
    k.b2[0] = 1.0; k.b2[1] = -2.0; k.b2[2] = 1.0;
    k.a2[0] = 1.0;
    k.a2[1] = 2.0 * (kk * kk - 1.0) / d;
    k.a2[2] = (1.0 - kk / q + kk * kk) / d;
  }
  return k;
}

std::optional<double> gatedLoudness(const std::vector<double>& p) {  // MATH §4.1
  double sumA = 0.0;
  std::size_t nA = 0;
  for (double v : p) {
    const auto l = powerToLufs(v);
    if (l && *l > -70.0) { sumA += v; ++nA; }
  }
  if (nA == 0) return std::nullopt;
  const double gammaR = -0.691 + 10.0 * dm::log10(sumA / static_cast<double>(nA)) - 10.0;  // sumA > 0: every term passed -70
  double sumG = 0.0;
  std::size_t nG = 0;
  for (double v : p) {
    const auto l = powerToLufs(v);
    if (l && *l > -70.0 && *l > gammaR) { sumG += v; ++nG; }
  }
  return powerToLufs(sumG / static_cast<double>(nG));
}

std::optional<double> loudnessRange(const std::vector<std::optional<double>>& st) {  // MATH §5
  std::vector<double> abs;
  for (const auto& s : st) if (s && *s >= -70.0) abs.push_back(*s);
  if (abs.empty()) return std::nullopt;
  double e = 0.0;
  for (double s : abs) e += dm::pow(10.0, s / 10.0);
  const double sInt = 10.0 * dm::log10(e / static_cast<double>(abs.size()));
  std::vector<double> v;
  for (double s : abs) if (s >= sInt - 20.0) v.push_back(s);
  if (v.empty()) return std::nullopt;
  std::sort(v.begin(), v.end());
  const auto idx = [&](double pr) {
    const double x = static_cast<double>(v.size() - 1) * pr + 0.5;
    return static_cast<std::size_t>(x);  // floor for x >= 0
  };
  return v[idx(0.95)] - v[idx(0.10)];
}

struct Analyzer::Impl {
  int fs = 0, ch = 0, hop = 0, fineHop = 0, tpL = 0;
  std::vector<double> g;
  KWeighting kw{};
  std::vector<double> s1, s2;  // TDF-II states, 2 per channel per stage
  double curSub = 0.0, curRaw = 0.0, curFine = 0.0;
  int subCount = 0, fineCount = 0;
  std::vector<double> sub, rawSub, fine;
  double totalWeighted = 0.0;
  std::uint64_t frames = 0;
  float samplePeak = 0.0f, truePeak = 0.0f;
  std::vector<std::vector<float>> tpPhases;
  // Per channel doubled ring of length 2*win: slot p and p+win hold the same sample, so the
  // window [p+1, p+win] (oldest .. newest) is always contiguous. MATH §6.2.
  std::vector<std::vector<float>> tpHist;
  int tpPos = 0;
};

Analyzer::Analyzer(int sampleRateHz, int channels, std::vector<double> w) : impl_(new Impl) {
  auto& s = *impl_;
  s.fs = sampleRateHz;
  s.ch = channels;
  s.g = std::move(w);
  s.hop = roundHalfUpDiv(sampleRateHz, 10);      // MATH §3.1
  s.fineHop = roundHalfUpDiv(sampleRateHz, 100); // MATH §3.5
  s.kw = KWeighting::forRate(static_cast<double>(sampleRateHz));
  s.s1.assign(2 * static_cast<std::size_t>(channels), 0.0);
  s.s2.assign(2 * static_cast<std::size_t>(channels), 0.0);
  s.tpL = sampleRateHz < 96000 ? 8 : 4;          // MATH §6.1
  s.tpPhases = designTruePeak(s.tpL);
  s.tpHist.assign(static_cast<std::size_t>(channels), std::vector<float>(4 * static_cast<std::size_t>(kTpHalfLength), 0.0f));
}

Analyzer::~Analyzer() { delete impl_; }

void Analyzer::push(const float* in, std::size_t n) {
  FpEnvGuard fp;  // MATH §25.3
  auto& s = *impl_;
  const auto& k = s.kw;
  const int win = 2 * kTpHalfLength;
  for (std::size_t f = 0; f < n; ++f) {
    double weighted = 0.0, raw = 0.0;
    for (int c = 0; c < s.ch; ++c) {
      float xf = in[f * static_cast<std::size_t>(s.ch) + static_cast<std::size_t>(c)];
      if (xf < kSampleFlush && xf > -kSampleFlush) xf = 0.0f;  // MATH §25.3
      const float ax = xf < 0.0f ? -xf : xf;
      if (ax > s.samplePeak) s.samplePeak = ax;
      const double x = static_cast<double>(xf);
      raw += x * x;
      // Stage 1, TDF-II (MATH §2.3)
      double* z1 = &s.s1[2 * static_cast<std::size_t>(c)];
      const double y1 = k.b1[0] * x + z1[0];
      z1[0] = k.b1[1] * x - k.a1[1] * y1 + z1[1];
      z1[1] = k.b1[2] * x - k.a1[2] * y1;
      // Stage 2
      double* z2 = &s.s2[2 * static_cast<std::size_t>(c)];
      const double y = k.b2[0] * y1 + z2[0];
      z2[0] = k.b2[1] * y1 - k.a2[1] * y + z2[1];
      z2[1] = k.b2[2] * y1 - k.a2[2] * y;
      for (int i = 0; i < 2; ++i) {
        if (z1[i] < kStateFlush && z1[i] > -kStateFlush) z1[i] = 0.0;
        if (z2[i] < kStateFlush && z2[i] > -kStateFlush) z2[i] = 0.0;
      }
      const double y2 = (y < kSquareFlush && y > -kSquareFlush) ? 0.0 : y * y;
      weighted += s.g[static_cast<std::size_t>(c)] * y2;
    }
    // True peak per channel (MATH §6.2)
    for (int c = 0; c < s.ch; ++c) {
      float xf = in[f * static_cast<std::size_t>(s.ch) + static_cast<std::size_t>(c)];
      if (xf < kSampleFlush && xf > -kSampleFlush) xf = 0.0f;
      auto& hb = s.tpHist[static_cast<std::size_t>(c)];
      // Doubled ring of length win: positions p and p+win hold the same sample.
      hb[static_cast<std::size_t>(s.tpPos)] = xf;
      hb[static_cast<std::size_t>(s.tpPos) + static_cast<std::size_t>(win)] = xf;
      const float* w = hb.data() + s.tpPos + 1;  // oldest .. newest, newest at tpPos+win
      for (int phi = 1; phi < s.tpL; ++phi) {
        const float yv = dot4(s.tpPhases[static_cast<std::size_t>(phi)].data(), w, win);
        const float a = yv < 0.0f ? -yv : yv;
        if (a > s.truePeak) s.truePeak = a;
      }
    }
    s.tpPos = (s.tpPos + 1) % win;
    s.curSub += weighted;
    s.curRaw += raw;
    s.curFine += weighted;
    s.totalWeighted += weighted;
    ++s.frames;
    if (++s.subCount == s.hop) {  // MATH §3.1
      s.sub.push_back(s.curSub);
      s.rawSub.push_back(s.curRaw);
      s.curSub = 0.0;
      s.curRaw = 0.0;
      s.subCount = 0;
    }
    if (++s.fineCount == s.fineHop) {  // MATH §3.5
      s.fine.push_back(s.curFine);
      s.curFine = 0.0;
      s.fineCount = 0;
    }
  }
}

LoudnessResult Analyzer::finish() {
  auto& s = *impl_;
  {
    // Flush the true-peak interpolator with Mh zeros so the tail is measured (MATH §6.2).
    // Done through a dedicated loop, not push(), so loudness series are not extended.
    FpEnvGuard fp;
    const int win = 2 * kTpHalfLength;
    for (int z = 0; z < kTpHalfLength; ++z) {
      for (int c = 0; c < s.ch; ++c) {
        auto& hb = s.tpHist[static_cast<std::size_t>(c)];
        hb[static_cast<std::size_t>(s.tpPos)] = 0.0f;
        hb[static_cast<std::size_t>(s.tpPos) + static_cast<std::size_t>(win)] = 0.0f;
        const float* w = hb.data() + s.tpPos + 1;
        for (int phi = 1; phi < s.tpL; ++phi) {
          const float yv = dot4(s.tpPhases[static_cast<std::size_t>(phi)].data(), w, win);
          const float a = yv < 0.0f ? -yv : yv;
          if (a > s.truePeak) s.truePeak = a;
        }
      }
      s.tpPos = (s.tpPos + 1) % win;
    }
  }

  LoudnessResult r;
  r.sampleRateHz = s.fs;
  r.channels = s.ch;
  r.frames = s.frames;
  r.truePeakOversampling = s.tpL;
  r.lowRateKWeighting = s.fs < 32000;  // MATH §2.4

  const double hop = static_cast<double>(s.hop);
  const std::size_t m = s.sub.size();
  // Blocks (MATH §3.2)
  if (m >= 4) {
    r.blockPower.resize(m - 3);
    r.momentaryLufs.resize(m - 3);
    for (std::size_t j = 0; j + 3 < m; ++j) {
      const double p = (((s.sub[j] + s.sub[j + 1]) + s.sub[j + 2]) + s.sub[j + 3]) / (4.0 * hop);
      r.blockPower[j] = p;
      r.momentaryLufs[j] = powerToLufs(p);
    }
  }
  // Short-term (MATH §3.4)
  if (m >= 30) {
    r.shortTermLufs.resize(m - 29);
    for (std::size_t k = 0; k + 29 < m; ++k) {
      double acc = 0.0;
      for (std::size_t i = 0; i < 30; ++i) acc += s.sub[k + i];
      r.shortTermLufs[k] = powerToLufs(acc / (30.0 * hop));
    }
  }
  // Integrated (MATH §4.2) or short file (MATH §4.5)
  if (!r.blockPower.empty()) {
    r.integratedLufs = gatedLoudness(r.blockPower);
  } else if (s.frames > 0 && static_cast<double>(s.frames) >= 0.05 * s.fs) {
    r.shortFile = true;
    r.integratedLufs = powerToLufs(s.totalWeighted / static_cast<double>(s.frames));
  }
  r.lraLu = loudnessRange(r.shortTermLufs);  // MATH §5

  // Fine-grid maxima (MATH §3.5)
  {
    const double fh = static_cast<double>(s.fineHop);
    std::optional<double> mMax, sMax;
    for (std::size_t u = 0; u + 40 <= s.fine.size(); ++u) {
      double acc = 0.0;
      for (std::size_t i = 0; i < 40; ++i) acc += s.fine[u + i];
      const auto l = powerToLufs(acc / (40.0 * fh));
      if (l && (!mMax || *l > *mMax)) mMax = l;
    }
    for (std::size_t u = 0; u + 300 <= s.fine.size(); ++u) {
      double acc = 0.0;
      for (std::size_t i = 0; i < 300; ++i) acc += s.fine[u + i];
      const auto l = powerToLufs(acc / (300.0 * fh));
      if (l && (!sMax || *l > *sMax)) sMax = l;
    }
    r.maxMomentaryLufs = mMax;
    r.maxShortTermLufs = sMax;
  }

  // Peaks (MATH §6.3, §7). Phase 0 of the interpolator is the samples themselves.
  const float tp = std::max(s.truePeak, s.samplePeak);
  if (tp > 0.0f) r.truePeakDbtp = 20.0 * dm::log10(static_cast<double>(tp));
  if (s.samplePeak > 0.0f) r.samplePeakDbfs = 20.0 * dm::log10(static_cast<double>(s.samplePeak));

  // Gated RMS, crest factor, PLR (MATH §7)
  if (r.integratedLufs && !r.shortFile) {
    // Re-derive J_g (MATH §4.1) to sum the raw (unweighted) sub-blocks of gated blocks.
    double sumA = 0.0;
    std::size_t nA = 0;
    for (double p : r.blockPower) {
      const auto l = powerToLufs(p);
      if (l && *l > -70.0) { sumA += p; ++nA; }
    }
    const double gammaR = -0.691 + 10.0 * dm::log10(sumA / static_cast<double>(nA)) - 10.0;  // sumA > 0: every term passed -70
    double raw = 0.0;
    std::size_t nG = 0;
    for (std::size_t j = 0; j < r.blockPower.size(); ++j) {
      const auto l = powerToLufs(r.blockPower[j]);
      if (l && *l > -70.0 && *l > gammaR) {
        raw += ((s.rawSub[j] + s.rawSub[j + 1]) + s.rawSub[j + 2]) + s.rawSub[j + 3];
        ++nG;
      }
    }
    const double ms = raw / (static_cast<double>(nG) * 4.0 * hop * static_cast<double>(s.ch));
    if (ms > kPowerFloor) r.rmsGatedDbfs = 10.0 * dm::log10(ms);
  }
  if (r.samplePeakDbfs && r.rmsGatedDbfs) r.crestFactorDb = *r.samplePeakDbfs - *r.rmsGatedDbfs;
  if (r.truePeakDbtp && r.integratedLufs) r.plrDb = *r.truePeakDbtp - *r.integratedLufs;
  return r;
}

namespace {
void appendNumber(std::string& out, const std::optional<double>& v) {
  if (!v) { out += "null"; return; }
  char buf[64];
  const auto res = std::to_chars(buf, buf + sizeof buf, *v);
  out.append(buf, res.ptr);
}
void appendField(std::string& out, const char* name, const std::optional<double>& v) {
  out += '"'; out += name; out += "\":"; appendNumber(out, v); out += ',';
}
}  // namespace

std::string toJson(const LoudnessResult& r, bool series) {
  std::string o = "{";
  o += "\"sampleRateHz\":" + std::to_string(r.sampleRateHz) + ",";
  o += "\"channels\":" + std::to_string(r.channels) + ",";
  o += "\"frames\":" + std::to_string(r.frames) + ",";
  appendField(o, "integratedLufs", r.integratedLufs);
  o += std::string("\"shortFile\":") + (r.shortFile ? "true" : "false") + ",";
  appendField(o, "lraLu", r.lraLu);
  appendField(o, "truePeakDbtp", r.truePeakDbtp);
  appendField(o, "samplePeakDbfs", r.samplePeakDbfs);
  appendField(o, "rmsGatedDbfs", r.rmsGatedDbfs);
  appendField(o, "crestFactorDb", r.crestFactorDb);
  appendField(o, "plrDb", r.plrDb);
  appendField(o, "maxMomentaryLufs", r.maxMomentaryLufs);
  appendField(o, "maxShortTermLufs", r.maxShortTermLufs);
  o += std::string("\"lowRateKWeighting\":") + (r.lowRateKWeighting ? "true" : "false") + ",";
  o += "\"truePeakOversampling\":" + std::to_string(r.truePeakOversampling);
  if (series) {
    o += ",\"shortTermLufs\":[";
    for (std::size_t i = 0; i < r.shortTermLufs.size(); ++i) {
      if (i) o += ',';
      appendNumber(o, r.shortTermLufs[i]);
    }
    o += ']';
  }
  o += '}';
  return o;
}

}  // namespace sable

// Sable Intent Engine layers 2-3. MATH §7-§9, §13-§21.
#include "sable/intent.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <limits>

#include "sable/dmath.h"

namespace sable {
namespace {

constexpr double kSub = 0.1;  // sub-block seconds (MATH §3.1)
constexpr double kMusicCentreLu = 0.65;  // MATH §12.3 c_m: corpus median of PLE_p95 - L_anchor (52 tracks)

std::optional<double> lufsOfPower(double p) {
  if (!(p > 1e-20)) return std::nullopt;
  return -0.691 + 10.0 * dm::log10(p);
}

int roundNonNeg(double x) { return static_cast<int>(std::floor(x + 0.5)); }  // x >= 0 (times, counts); floor is exact
int toSub(double t) { return roundNonNeg(t / kSub); }

// §4.1 gated block set (indices into blockPower) for a set of candidate block indices.
std::vector<std::size_t> gatedSet(const std::vector<double>& p, const std::vector<std::size_t>& cand) {
  double sumA = 0.0;
  std::size_t nA = 0;
  for (std::size_t j : cand) {
    const auto l = lufsOfPower(p[j]);
    if (l && *l > -70.0) { sumA += p[j]; ++nA; }
  }
  std::vector<std::size_t> g;
  if (nA == 0) return g;
  const double gammaR = -0.691 + 10.0 * dm::log10(sumA / static_cast<double>(nA)) - 10.0;  // sumA > 0: terms passed -70
  for (std::size_t j : cand) {
    const auto l = lufsOfPower(p[j]);
    if (l && *l > -70.0 && *l > gammaR) g.push_back(j);
  }
  return g;
}

std::optional<double> loudnessOf(const std::vector<double>& p, const std::vector<std::size_t>& set) {
  if (set.empty()) return std::nullopt;
  double s = 0.0;
  for (std::size_t j : set) s += p[j];
  return lufsOfPower(s / static_cast<double>(set.size()));
}

// Blocks fully inside the sub-block interval [m0, m1) (§4.3).
std::vector<std::size_t> blocksIn(int m0, int m1, std::size_t nBlocks) {
  std::vector<std::size_t> b;
  for (int j = std::max(0, m0); j + 4 <= m1; ++j)
    if (static_cast<std::size_t>(j) < nBlocks) b.push_back(static_cast<std::size_t>(j));
  return b;
}

// Least-squares slope of (t, v).
double slope(const std::vector<double>& t, const std::vector<double>& v) {
  const double n = static_cast<double>(t.size());
  if (t.size() < 2) return 0.0;
  double st = 0, sv = 0;
  for (std::size_t i = 0; i < t.size(); ++i) { st += t[i]; sv += v[i]; }
  const double mt = st / n, mv = sv / n;
  double num = 0, den = 0;
  for (std::size_t i = 0; i < t.size(); ++i) { num += (t[i] - mt) * (v[i] - mv); den += (t[i] - mt) * (t[i] - mt); }
  return den > 0.0 ? num / den : 0.0;
}

// Three-valued logic for labels (§15): nullopt = unknown.
using Tri = std::optional<bool>;
Tri le(const std::optional<double>& a, double b) { return a ? Tri(*a <= b) : std::nullopt; }
Tri ge(const std::optional<double>& a, double b) { return a ? Tri(*a >= b) : std::nullopt; }
Tri triAnd(Tri a, Tri b) {
  if ((a && !*a) || (b && !*b)) return false;
  if (a && b) return true;
  return std::nullopt;
}
Tri triOr(Tri a, Tri b) {
  if ((a && *a) || (b && *b)) return true;
  if (a && b) return false;
  return std::nullopt;
}
bool isTrue(Tri t) { return t && *t; }

}  // namespace

const char* toString(SectionType t) {
  switch (t) {
    case SectionType::Silence: return "silence";
    case SectionType::FadeIn: return "fade-in";
    case SectionType::FadeOut: return "fade-out";
    case SectionType::Intro: return "intro";
    case SectionType::Outro: return "outro";
    case SectionType::Build: return "build";
    case SectionType::Body: return "body";
    case SectionType::Breakdown: return "breakdown";
  }
  return "?";
}

const char* toString(Character c) {
  switch (c) {
    case Character::QuietIntimate: return "quiet and intimate";
    case Character::BuildRelease: return "build and release";
    case Character::DynamicArc: return "dynamic arc";
    case Character::SustainedDense: return "sustained and dense";
    case Character::Steady: return "steady";
  }
  return "?";
}

TrackUnderstanding understand(const LoudnessResult& r, const PerceptualSeries* perc, double prescaleDb) {
  TrackUnderstanding u;
  const auto& sp = r.subPower;
  const int M = static_cast<int>(sp.size());
  const std::size_t nBlocks = r.blockPower.size();
  const double durSec = M * kSub;

  // Whole-track gated set J_g (§4.1) and gated sub-blocks (R_g, §8.3).
  std::vector<std::size_t> all(nBlocks);
  for (std::size_t j = 0; j < nBlocks; ++j) all[j] = j;
  const auto jg = gatedSet(r.blockPower, all);
  std::vector<char> gatedSub(static_cast<std::size_t>(M), 0);
  for (std::size_t j : jg)
    for (std::size_t m = j; m < j + 4 && m < static_cast<std::size_t>(M); ++m) gatedSub[m] = 1;

  // ---- §8.3 tilt, §8.4 density (whole track) ----
  const int B = LoudnessResult::kBands;
  const int nSpec = static_cast<int>(r.bandEnergy.size() / B);
  auto meanBands = [&](const std::vector<char>& use, std::vector<double>& eb) {
    eb.assign(B, 0.0);
    int cnt = 0;
    for (int f = 0; f < std::min(nSpec, M); ++f) {
      if (!use[static_cast<std::size_t>(f)]) continue;
      for (int b = 0; b < B; ++b) eb[static_cast<std::size_t>(b)] += r.bandEnergy[static_cast<std::size_t>(f * B + b)];
      ++cnt;
    }
    if (cnt == 0) return false;
    for (auto& v : eb) v /= cnt;
    return true;
  };
  auto densityOf = [&](const std::vector<double>& eb) -> std::optional<double> {
    double mx = -1e300;
    std::vector<double> db(B, -1e300);
    for (int b = 0; b < B; ++b)
      if (eb[static_cast<std::size_t>(b)] > 1e-20) { db[static_cast<std::size_t>(b)] = 10.0 * dm::log10(eb[static_cast<std::size_t>(b)]); mx = std::max(mx, db[static_cast<std::size_t>(b)]); }
    if (mx < -1e299) return std::nullopt;
    int n = 0;
    for (double v : db) if (v >= mx - 30.0) ++n;
    return n / 25.0;
  };
  {
    std::vector<double> eb;
    if (meanBands(gatedSub, eb)) {
      std::vector<double> t, v;
      for (int b = 1; b <= 22; ++b)  // b = -12 .. 9 (63 Hz .. 8 kHz)
        if (eb[static_cast<std::size_t>(b)] > 1e-20) { t.push_back((b - 13) / 3.0); v.push_back(10.0 * dm::log10(eb[static_cast<std::size_t>(b)])); }
      if (t.size() >= 6) u.tiltDbPerOct = slope(t, v);
      u.density = densityOf(eb);
    }
  }

  // ---- §9 transient density ----
  if (r.integratedLufs && !jg.empty()) {
    const auto& e = r.finePower;
    const std::size_t T = e.size();
    std::vector<double> d(T, 0.0);
    for (std::size_t t = 1; t < T; ++t)
      d[t] = std::max(0.0, 10.0 * dm::log10(e[t] + 1e-12) - 10.0 * dm::log10(e[t - 1] + 1e-12));
    long long last = -1000;
    int onsets = 0;
    for (std::size_t t = 1; t < T; ++t) {
      if (d[t] < 6.0) continue;
      bool peak = true;
      for (std::size_t k = (t >= 2 ? t - 2 : 0); k <= std::min(T - 1, t + 2); ++k) if (d[k] > d[t]) peak = false;
      const auto lt = lufsOfPower(e[t]);
      if (!peak || !lt || *lt < *r.integratedLufs - 20.0 || static_cast<long long>(t) - last < 5) continue;
      ++onsets;
      last = static_cast<long long>(t);
    }
    u.transientsPerSec = onsets / (static_cast<double>(jg.size()) * kSub);
  }

  // ---- §13.4.2 silent sub-blocks ----
  const double thetaSil = r.integratedLufs ? std::max(-60.0, *r.integratedLufs - 35.0) : -60.0;
  std::vector<char> silent(static_cast<std::size_t>(M), 0);
  for (int m = 0; m < M; ++m) {
    double s = 0.0;
    int n = 0;
    for (int k = m - 2; k <= m + 1; ++k) if (k >= 0 && k < M) { s += sp[static_cast<std::size_t>(k)]; ++n; }
    const auto l = lufsOfPower(n ? s / n : 0.0);
    silent[static_cast<std::size_t>(m)] = (!l || *l < thetaSil) ? 1 : 0;
  }
  int firstNs = -1, lastNs = -1, nNs = 0;
  for (int m = 0; m < M; ++m) if (!silent[static_cast<std::size_t>(m)]) { if (firstNs < 0) firstNs = m; lastNs = m; ++nNs; }
  u.nonSilentSec = nNs * kSub;
  {  // §17.3 leading and trailing silence (the tail counts the part of the file past the last whole sub-block)
    const double fileSec = r.sampleRateHz > 0 ? static_cast<double>(r.frames) / r.sampleRateHz : durSec;
    u.leadSilenceSec = firstNs < 0 ? fileSec : firstNs * kSub;
    u.trailSilenceSec = lastNs < 0 ? fileSec : std::max(0.0, fileSec - (lastNs + 1) * kSub);
  }
  // ---- §8.5 tilt over time (display only): the §8.3 slope over each 0.5 s (5 frames), rounded to 0.1 dB/oct ----
  for (int f0 = 0; f0 < std::min(nSpec, M); f0 += 5) {
    std::vector<double> eb(static_cast<std::size_t>(B), 0.0);
    const int f1 = std::min(f0 + 5, std::min(nSpec, M));
    for (int f = f0; f < f1; ++f)
      for (int b = 0; b < B; ++b) eb[static_cast<std::size_t>(b)] += r.bandEnergy[static_cast<std::size_t>(f * B + b)];
    std::vector<double> t, v;
    for (int b = 1; b <= 22; ++b)
      if (eb[static_cast<std::size_t>(b)] / (f1 - f0) > 1e-20) { t.push_back((b - 13) / 3.0); v.push_back(10.0 * dm::log10(eb[static_cast<std::size_t>(b)] / (f1 - f0))); }
    u.shapeTilt.push_back(t.size() >= 6 ? std::floor(slope(t, v) * 10.0 + 0.5) / 10.0 : std::numeric_limits<double>::quiet_NaN());
  }

  // ---- seams (§18): first / last 3 s of non-silent audio ----
  auto windowLufs = [&](int m0, int m1) {  // energy mean over sub-blocks [m0, m1)
    double s = 0.0;
    int n = 0;
    for (int m = std::max(0, m0); m < std::min(M, m1); ++m) { s += sp[static_cast<std::size_t>(m)]; ++n; }
    return lufsOfPower(n ? s / n : 0.0);
  };
  if (firstNs >= 0) {
    u.openLufs = windowLufs(firstNs, firstNs + 30);
    u.endLufs = windowLufs(lastNs + 1 - 30, lastNs + 1);
  }

  // ---- §13.5 fades on the 1 s series F (centre stamps) ----
  std::vector<double> fT, fV;
  for (int k = 0; k + 10 <= M; ++k) {
    double s = 0.0;
    for (int i = 0; i < 10; ++i) s += sp[static_cast<std::size_t>(k + i)];
    const auto l = lufsOfPower(s / 10.0);
    fT.push_back((k + 5) * kSub);
    fV.push_back(l ? *l : -70.0);
  }
  auto F = [&](double t) {
    std::size_t best = 0;
    for (std::size_t i = 1; i < fT.size(); ++i) if (dm::fabs(fT[i] - t) < dm::fabs(fT[best] - t)) best = i;
    return fV[best];
  };
  auto rangeSlope = [&](double a, double b) {
    std::vector<double> t, v;
    for (std::size_t i = 0; i < fT.size(); ++i) if (fT[i] >= a - 1e-9 && fT[i] <= b + 1e-9) { t.push_back(fT[i]); v.push_back(fV[i]); }
    return slope(t, v);
  };
  if (!fT.empty() && firstNs >= 0) {
    const double tFirst = std::clamp(firstNs * kSub, fT.front(), fT.back());
    const double tLast = std::clamp((lastNs + 1) * kSub, fT.front(), fT.back());
    std::optional<double> pass;
    for (int step = 0;; ++step) {  // fade-out: scan backwards in 0.5 s steps
      const double tf = tLast - 4.0 - 0.5 * step;
      if (tf < fT.front() - 1e-9) break;
      const bool ok = rangeSlope(tf, tLast) <= -1.5 && rangeSlope(tf, tf + 2.0) <= -0.75 && F(tf) - F(tLast) >= 10.0;
      if (ok) pass = tf;
      else if (pass) break;
    }
    if (pass) u.fadeOut = std::make_pair(*pass, (lastNs + 1) * kSub);
    pass.reset();
    for (int step = 0;; ++step) {  // fade-in: scan forwards in 0.5 s steps
      const double tf = tFirst + 4.0 + 0.5 * step;
      if (tf > fT.back() + 1e-9) break;
      const bool ok = rangeSlope(tFirst, tf) >= 1.5 && rangeSlope(tf - 2.0, tf) >= 0.75 && F(tf) - F(tFirst) >= 10.0;
      if (ok) pass = tf;
      else if (pass) break;
    }
    if (pass) u.fadeIn = std::make_pair(firstNs * kSub, *pass);
  }

  // ---- §13.1-13.3 features, self-similarity, novelty ----
  const double tfr = 0.5 * std::max(1.0, static_cast<double>(static_cast<long long>((durSec + 899.999999) / 900.0)));
  const int Fsub = roundNonNeg(tfr / kSub);
  const int R = M / Fsub;
  constexpr int kDim = 9;
  std::vector<double> x(static_cast<std::size_t>(R) * kDim, 0.0);
  std::vector<char> frameSilent(static_cast<std::size_t>(R), 1);
  static const int kOctMid[8] = {1, 4, 7, 10, 13, 16, 19, 22};  // band indices of 63 Hz .. 8 kHz
  for (int f = 0; f < R; ++f) {
    double s = 0.0;
    for (int i = 0; i < Fsub; ++i) {
      s += sp[static_cast<std::size_t>(f * Fsub + i)];
      if (!silent[static_cast<std::size_t>(f * Fsub + i)]) frameSilent[static_cast<std::size_t>(f)] = 0;
    }
    const auto l = lufsOfPower(s / Fsub);
    x[static_cast<std::size_t>(f * kDim)] = std::clamp(l ? *l : -70.0, -70.0, 0.0);
    double o[8] = {}, tot = 0.0;
    for (int i = 0; i < Fsub; ++i) {
      const int sf = f * Fsub + i;
      if (sf >= nSpec) continue;
      for (int q = 0; q < 8; ++q)
        for (int d = -1; d <= 1; ++d) o[q] += r.bandEnergy[static_cast<std::size_t>(sf * B + kOctMid[q] + d)];
    }
    for (double v : o) tot += v;
    for (int q = 0; q < 8; ++q) {
      const double v = (tot > 1e-20 && o[q] > 1e-20) ? 10.0 * dm::log10(o[q] / tot) : -60.0;
      x[static_cast<std::size_t>(f * kDim + 1 + q)] = std::max(v, -60.0);
    }
  }
  // z-score over non-silent frames (population SD), weights 1 and 1/sqrt(8)
  for (int d = 0; d < kDim; ++d) {
    double s = 0, s2 = 0;
    int n = 0;
    for (int f = 0; f < R; ++f) if (!frameSilent[static_cast<std::size_t>(f)]) { s += x[static_cast<std::size_t>(f * kDim + d)]; ++n; }
    const double mean = n ? s / n : 0.0;
    for (int f = 0; f < R; ++f) if (!frameSilent[static_cast<std::size_t>(f)]) { const double z = x[static_cast<std::size_t>(f * kDim + d)] - mean; s2 += z * z; }
    const double sd = n ? dm::sqrt(s2 / n) : 0.0;
    const double w = d == 0 ? 1.0 : 1.0 / dm::sqrt(8.0);
    for (int f = 0; f < R; ++f) {
      double& v = x[static_cast<std::size_t>(f * kDim + d)];
      v = sd < 1e-9 ? 0.0 : w * (v - mean) / sd;
    }
  }
  auto dist2 = [&](int a, int b) {
    double s = 0;
    for (int d = 0; d < kDim; ++d) { const double z = x[static_cast<std::size_t>(a * kDim + d)] - x[static_cast<std::size_t>(b * kDim + d)]; s += z * z; }
    return s;
  };
  double sigma2 = 1.0;
  {
    std::vector<double> dd;
    for (int a = 0; a < R; ++a)
      if (!frameSilent[static_cast<std::size_t>(a)])
        for (int b = a + 1; b < R; ++b) if (!frameSilent[static_cast<std::size_t>(b)]) dd.push_back(dist2(a, b));
    if (!dd.empty()) {
      const std::size_t mid = (dd.size() - 1) / 2;  // lower median
      std::nth_element(dd.begin(), dd.begin() + static_cast<std::ptrdiff_t>(mid), dd.end());
      if (dd[mid] > 0.0) sigma2 = dd[mid];
    }
  }
  auto S = [&](int a, int b) { return (a < 0 || b < 0 || a >= R || b >= R) ? 0.0 : dm::exp(-dist2(a, b) / (2.0 * sigma2)); };
  const int K = std::max(1, roundNonNeg(8.0 / tfr));
  std::vector<double> gam(static_cast<std::size_t>(2 * K));
  for (int a = -K; a < K; ++a) { const double z = (a + 0.5) / (K / 2.0); gam[static_cast<std::size_t>(a + K)] = dm::exp(-0.5 * z * z); }
  std::vector<double> nov(static_cast<std::size_t>(R), 0.0);
  double nmax = 0.0;
  for (int f = 0; f < R; ++f) {
    double acc = 0.0;
    for (int a = -K; a < K; ++a)
      for (int b = -K; b < K; ++b) {
        const double sg = ((a >= 0) == (b >= 0)) ? 1.0 : -1.0;
        acc += sg * gam[static_cast<std::size_t>(a + K)] * gam[static_cast<std::size_t>(b + K)] * S(f + a, f + b);
      }
    nov[static_cast<std::size_t>(f)] = std::max(acc, 0.0);
    nmax = std::max(nmax, nov[static_cast<std::size_t>(f)]);
  }
  std::vector<double> bounds = {0.0, durSec};
  if (nmax > 0.0 && R > 0) {
    double mean = 0, sd = 0;
    for (auto& v : nov) { v /= nmax; mean += v; }
    mean /= R;
    for (double v : nov) sd += (v - mean) * (v - mean);
    sd = dm::sqrt(sd / R);
    const double thr = std::max(0.1, mean + 0.5 * sd);
    std::vector<int> cand;
    for (int f = 0; f < R; ++f) {
      bool pk = nov[static_cast<std::size_t>(f)] >= thr;
      for (int k = std::max(0, f - 4); k <= std::min(R - 1, f + 4) && pk; ++k) if (nov[static_cast<std::size_t>(k)] > nov[static_cast<std::size_t>(f)]) pk = false;
      if (pk) cand.push_back(f);
    }
    std::stable_sort(cand.begin(), cand.end(), [&](int a, int b) { return nov[static_cast<std::size_t>(a)] > nov[static_cast<std::size_t>(b)]; });
    std::vector<int> chosen;
    for (int c : cand) {
      bool ok = true;
      for (int q : chosen) if (std::abs(q - c) < K) ok = false;
      if (ok) chosen.push_back(c);
    }
    for (int c : chosen) {
      const double t = c * tfr;
      const bool inFade = (u.fadeIn && t > u.fadeIn->first + 1e-9 && t < u.fadeIn->second - 1e-9) ||
                          (u.fadeOut && t > u.fadeOut->first + 1e-9 && t < u.fadeOut->second - 1e-9);
      if (!inFade) bounds.push_back(t);
    }
  }
  // Silence edges: runs of >= 2 s of silent sub-blocks
  for (int m = 0; m < M;) {
    if (!silent[static_cast<std::size_t>(m)]) { ++m; continue; }
    int e = m;
    while (e < M && silent[static_cast<std::size_t>(e)]) ++e;
    if (e - m >= 20) { bounds.push_back(m * kSub); bounds.push_back(e * kSub); }
    m = e;
  }
  if (u.fadeIn) { bounds.push_back(u.fadeIn->first); bounds.push_back(u.fadeIn->second); }
  if (u.fadeOut) { bounds.push_back(u.fadeOut->first); bounds.push_back(u.fadeOut->second); }
  for (auto& b : bounds) b = toSub(b) * kSub;  // snap to the sub-block grid
  std::sort(bounds.begin(), bounds.end());
  bounds.erase(std::unique(bounds.begin(), bounds.end(), [](double a, double b) { return dm::fabs(a - b) < 1e-6; }), bounds.end());

  // ---- §13.4.4 sections, typing before merging, merging ----
  struct Sec { int m0, m1; int kind; };  // kind: 0 non-silent, 1 silence, 2 fade
  std::vector<Sec> secs;
  for (std::size_t i = 0; i + 1 < bounds.size(); ++i) {
    const int m0 = toSub(bounds[i]), m1 = toSub(bounds[i + 1]);
    if (m1 <= m0) continue;
    int sil = 0;
    for (int m = m0; m < m1; ++m) sil += silent[static_cast<std::size_t>(m)];
    int kind = (sil * 10 >= (m1 - m0) * 9) ? 1 : 0;
    auto eq = [&](const std::optional<std::pair<double, double>>& f) { return f && toSub(f->first) == m0 && toSub(f->second) == m1; };
    if (kind == 0 && (eq(u.fadeIn) || eq(u.fadeOut))) kind = 2;
    if (kind == 1 && !secs.empty() && secs.back().kind == 1) secs.back().m1 = m1;  // adjacent silences coalesce
    else secs.push_back({m0, m1, kind});
  }
  auto meanVec = [&](const Sec& s, std::vector<double>& mv) {
    mv.assign(kDim, 0.0);
    int n = 0;
    for (int f = 0; f < R; ++f) {
      const double c = (f + 0.5) * tfr;
      if (c < s.m0 * kSub || c >= s.m1 * kSub || frameSilent[static_cast<std::size_t>(f)]) continue;
      for (int d = 0; d < kDim; ++d) mv[static_cast<std::size_t>(d)] += x[static_cast<std::size_t>(f * kDim + d)];
      ++n;
    }
    if (n == 0) return false;
    for (auto& v : mv) v /= n;
    return true;
  };
  std::vector<char> frozen(secs.size(), 0);
  for (;;) {
    int pick = -1;
    for (int i = 0; i < static_cast<int>(secs.size()); ++i) {
      const auto& s = secs[static_cast<std::size_t>(i)];
      if (s.kind != 0 || frozen[static_cast<std::size_t>(i)] || (s.m1 - s.m0) >= 80) continue;
      if (pick < 0 || (s.m1 - s.m0) < (secs[static_cast<std::size_t>(pick)].m1 - secs[static_cast<std::size_t>(pick)].m0)) pick = i;
    }
    if (pick < 0) break;
    std::vector<double> mp, mn;
    const bool hp = meanVec(secs[static_cast<std::size_t>(pick)], mp);
    double best = std::numeric_limits<double>::infinity();
    int target = -1;
    for (int nb : {pick - 1, pick + 1}) {
      if (nb < 0 || nb >= static_cast<int>(secs.size()) || secs[static_cast<std::size_t>(nb)].kind != 0) continue;
      double dd = std::numeric_limits<double>::max();
      if (hp && meanVec(secs[static_cast<std::size_t>(nb)], mn)) {
        dd = 0;
        for (int d = 0; d < kDim; ++d) dd += (mp[static_cast<std::size_t>(d)] - mn[static_cast<std::size_t>(d)]) * (mp[static_cast<std::size_t>(d)] - mn[static_cast<std::size_t>(d)]);
      }
      if (dd < best) { best = dd; target = nb; }  // ties keep the earlier neighbour (checked first)
    }
    if (target < 0) { frozen[static_cast<std::size_t>(pick)] = 1; continue; }
    auto& a = secs[static_cast<std::size_t>(std::min(pick, target))];
    const auto& b = secs[static_cast<std::size_t>(std::max(pick, target))];
    a.m1 = b.m1;
    const auto eraseAt = static_cast<std::ptrdiff_t>(std::max(pick, target));
    secs.erase(secs.begin() + eraseAt);
    frozen.erase(frozen.begin() + eraseAt);
    frozen[static_cast<std::size_t>(std::min(pick, target))] = 0;
  }

  // ---- §13.6 typing ----
  auto secLoud = [&](int m0, int m1) { return loudnessOf(r.blockPower, gatedSet(r.blockPower, blocksIn(m0, m1, nBlocks))); };
  const double dNs = u.nonSilentSec;
  for (const auto& s : secs) {
    Section out;
    out.startSec = s.m0 * kSub;
    out.endSec = s.m1 * kSub;
    out.loudnessLufs = secLoud(s.m0, s.m1);
    if (s.kind == 1) out.type = SectionType::Silence;
    else if (s.kind == 2) out.type = (u.fadeIn && toSub(u.fadeIn->first) == s.m0) ? SectionType::FadeIn : SectionType::FadeOut;
    else out.type = SectionType::Body;
    u.sections.push_back(out);
  }
  auto quieter = [&](const Section& s) {
    return s.loudnessLufs && r.integratedLufs && *s.loudnessLufs <= *r.integratedLufs - 3.0 && s.duration() <= 0.25 * dNs;
  };
  for (auto& s : u.sections) if (s.type == SectionType::Body) { if (quieter(s)) s.type = SectionType::Intro; break; }
  for (auto it = u.sections.rbegin(); it != u.sections.rend(); ++it)
    if (it->type == SectionType::Body) { if (quieter(*it)) it->type = SectionType::Outro; break; }
    else if (it->type == SectionType::Intro) break;
  for (std::size_t i = 0; i + 1 < u.sections.size(); ++i) {  // builds
    auto& s = u.sections[i];
    if (s.type != SectionType::Body || s.duration() < 8.0) continue;
    std::vector<double> t, v;
    for (std::size_t k = 0; k < r.shortTermLufs.size(); ++k) {
      const double ts = (static_cast<double>(k) + 30.0) * kSub;  // window-end stamp (§3.6)
      if (ts > s.startSec && ts <= s.endSec && r.shortTermLufs[k]) { t.push_back(ts); v.push_back(*r.shortTermLufs[k]); }
    }
    const double sl = slope(t, v);
    const auto last3 = windowLufs(toSub(s.endSec) - 30, toSub(s.endSec));
    const auto& next = u.sections[i + 1].loudnessLufs;
    if (sl >= 0.3 && sl * s.duration() >= 4.0 && last3 && next && *next >= *last3) s.type = SectionType::Build;
  }

  // ---- §14 anchor ----
  std::vector<const Section*> q;
  for (const auto& s : u.sections) if (s.type == SectionType::Body && s.duration() >= 8.0 && s.loudnessLufs) q.push_back(&s);
  const double dReq = std::min(dNs, std::max(20.0, 0.2 * dNs));
  std::vector<const Section*> chosen;
  if (!q.empty() && dNs >= 20.0) {
    double top = -1e300;
    for (auto* s : q) top = std::max(top, *s->loudnessLufs);
    for (double delta : {3.0, 4.0, 5.0, 6.0}) {
      chosen.clear();
      double tot = 0.0;
      for (auto* s : q) if (*s->loudnessLufs >= top - delta) { chosen.push_back(s); tot += s->duration(); }
      if (tot >= dReq - 1e-9) break;
      chosen.clear();
    }
  }
  if (chosen.empty()) {
    u.anchorFallback = true;
    for (const auto& s : u.sections)
      if (s.type != SectionType::Silence && s.type != SectionType::FadeIn && s.type != SectionType::FadeOut) chosen.push_back(&s);
    if (chosen.empty()) for (const auto& s : u.sections) chosen.push_back(&s);
  }
  std::vector<std::size_t> aBlocks;
  double wsum = 0.0;
  for (auto* s : chosen) {
    u.anchor.emplace_back(s->startSec, s->endSec);
    u.anchorSec += s->duration();
    wsum += s->duration() * 0.5 * (s->startSec + s->endSec);
    for (std::size_t j : blocksIn(toSub(s->startSec), toSub(s->endSec), nBlocks)) aBlocks.push_back(j);
  }
  if (u.anchorSec > 0) u.anchorCentreSec = wsum / u.anchorSec;
  const auto aGated = gatedSet(r.blockPower, aBlocks);
  u.anchorLufs = loudnessOf(r.blockPower, aGated);
  for (std::size_t j : aBlocks) u.anchorBlockPower.push_back(r.blockPower[j]);
  if (r.shortFile && r.integratedLufs) u.anchorLufs = r.integratedLufs;  // §16.6
  if (perc && !aGated.empty()) {
    for (std::size_t j : aGated)
      if (j < perc->ltlSone.size() && perc->ltlSone[j]) u.anchorLtlSone.push_back(*perc->ltlSone[j]);
    const std::size_t n = u.anchorLtlSone.size();
    if (n > 0) {  // §14.7: 95th percentile of the gated anchor LTL (nearest rank), music-centred PLE (§12.3)
      std::vector<double> v = u.anchorLtlSone;
      std::sort(v.begin(), v.end());
      const double p95 = v[static_cast<std::size_t>(std::floor(0.95 * static_cast<double>(v.size() - 1) + 0.5))];
      u.anchorPle = pleFromSone(p95) + kMusicCentreLu - prescaleDb;
    }
  }

  // ---- breakdowns (§13.6.7), section contrast, anchor descriptors ----
  for (std::size_t i = 1; i + 1 < u.sections.size(); ++i) {
    auto& s = u.sections[i];
    if (s.type == SectionType::Body && s.duration() >= 8.0 && s.loudnessLufs && u.anchorLufs && *s.loudnessLufs <= *u.anchorLufs - 6.0)
      s.type = SectionType::Breakdown;
  }
  {
    double mx = -1e300, mn = 1e300;
    int n = 0;
    for (const auto& s : u.sections)
      if (s.type != SectionType::Silence && s.type != SectionType::FadeIn && s.type != SectionType::FadeOut && s.duration() >= 8.0 && s.loudnessLufs) {
        mx = std::max(mx, *s.loudnessLufs); mn = std::min(mn, *s.loudnessLufs); ++n;
      }
    u.sectionContrastLu = n >= 2 ? mx - mn : 0.0;
  }
  if (r.truePeakDbtp && u.anchorLufs) u.plrAnchorDb = *r.truePeakDbtp - *u.anchorLufs;
  {
    std::vector<char> use(static_cast<std::size_t>(M), 0);
    for (auto* s : chosen)
      for (int m = toSub(s->startSec); m < toSub(s->endSec) && m < M; ++m) use[static_cast<std::size_t>(m)] = gatedSub[static_cast<std::size_t>(m)];
    std::vector<double> eb;
    if (meanBands(use, eb)) u.densityAnchor = densityOf(eb);
  }

  // ---- §15 character ----
  int builds = 0;
  double buildSec = 0.0;
  for (const auto& s : u.sections) if (s.type == SectionType::Build) { ++builds; buildSec += s.duration(); }
  const Tri r1 = triAnd(triAnd(le(u.anchorLufs, -20.0), ge(u.plrAnchorDb, 12.0)), triOr(le(u.densityAnchor, 0.6), le(u.transientsPerSec, 1.5)));
  const Tri r2 = Tri(builds >= 2 || (dNs > 0 && buildSec >= 0.1 * dNs));
  const Tri r3 = triOr(ge(r.lraLu, 9.0), ge(u.sectionContrastLu, 9.0));
  const Tri r4 = triAnd(triAnd(le(r.lraLu, 6.0), le(u.plrAnchorDb, 11.0)), ge(u.densityAnchor, 0.7));
  if (isTrue(r1)) u.character = Character::QuietIntimate;
  else if (isTrue(r2)) u.character = Character::BuildRelease;
  else if (isTrue(r3)) u.character = Character::DynamicArc;
  else if (isTrue(r4)) u.character = Character::SustainedDense;
  else u.character = Character::Steady;
  return u;
}

// ---------------- Layer 3 ----------------

namespace {
constexpr double kPerceptualBound = 3.0;  // §16.2 B_p
constexpr double kLimiterAllowance = 6.0; // §16.4
constexpr double kClipMargin = 1.0;       // §1.1 M_clipTP

double tpEff(const LoudnessResult& r, const GainContext& c) {
  return r.truePeakDbtp.value_or(-200.0) + (c.decodeClipped ? kClipMargin : 0.0) + c.contourHeadroomDb;  // callers check TP
}

// The peak that limits the gain (§16.4). A measured true peak; or, when the loudness is defined but the peak is not (a
// non-finite sample in the file), a full-scale peak (MATH-1.29: "no peak" used to mean "no cap", which is right only for
// digital silence).
std::optional<double> capPeak(const LoudnessResult& r, const GainContext& c) {
  if (r.truePeakDbtp) return tpEff(r, c);
  if (r.integratedLufs) return 0.0 + (c.decodeClipped ? kClipMargin : 0.0) + c.contourHeadroomDb;
  return std::nullopt;
}

void applyCap(GainDecision& d, std::optional<double> tp, const GainContext& c) {
  // §16.4 peak integrity (+ limiter allowance)
  if (!tp) { d.capDb = std::numeric_limits<double>::infinity(); d.gainDb = d.intentDb; return; }
  const double cap = c.ceilingDbtp - *tp;
  d.capDb = c.limiter ? cap + kLimiterAllowance : cap;
  d.gainDb = std::min(d.intentDb, d.capDb);
  if (c.limiter && d.intentDb > cap) d.rules.push_back({"LIMITER", std::min(d.intentDb, d.capDb) - cap});
  if (d.intentDb > d.capDb) d.rules.push_back({"PEAK_CAP", d.capDb - d.intentDb});
}

double boundedPle(std::optional<double> ple, double lAnchor, std::vector<Rule>& rules) {  // §16.2.2-3
  if (!ple) { rules.push_back({"BS1770_ONLY", 0.0}); return lAnchor; }
  const double c = std::clamp(*ple, lAnchor - kPerceptualBound, lAnchor + kPerceptualBound);
  if (c != *ple) rules.push_back({"PERCEPTUAL_BOUND", c - *ple});
  return c;
}
}  // namespace

std::optional<double> usablePle(const TrackUnderstanding& u) {  // §16.2.3
  constexpr double kPleMinAnchorSec = 3.0;                       // A_ple
  if (!u.anchorPle || u.anchorSec < kPleMinAnchorSec) return std::nullopt;
  return u.anchorPle;
}

GainDecision decideTrackGain(const LoudnessResult& r, const TrackUnderstanding& u, const GainContext& c) {
  GainDecision d;
  if (!r.integratedLufs) {  // §16.6 silence
    d.intentDb = 0.0;
    applyCap(d, capPeak(r, c), c);
    d.gainDb = std::min(0.0, d.capDb);
    d.rules.push_back({"UNDEFINED_LOUDNESS", 0.0});
    return d;
  }
  if (r.shortFile) d.rules.push_back({"SHORT_FILE", 0.0});
  double lAnchor;
  if (u.anchorLufs) lAnchor = *u.anchorLufs;
  else { lAnchor = *r.integratedLufs; d.rules.push_back({"ANCHOR_FALLBACK", 0.0}); }
  const double ple = r.shortFile ? lAnchor : boundedPle(usablePle(u), lAnchor, d.rules);
  const double gAnchor = c.targetLufs - ple;  // §16.2
  d.rules.insert(d.rules.begin(), {"ANCHOR_MATCH", gAnchor});
  d.intentDb = gAnchor;
  if (u.character == Character::QuietIntimate && c.intimacyLu > 0.0) {  // §16.3
    const double a = std::clamp(c.intimacyLu, 0.0, 2.0);
    d.intentDb -= a;
    d.rules.push_back({"INTIMACY", -a});
  }
  applyCap(d, capPeak(r, c), c);
  return d;
}

GainDecision decideAlbumGain(const std::vector<const LoudnessResult*>& tr, const std::vector<const TrackUnderstanding*>& und,
                             const GainContext& c) {
  GainDecision d;
  std::vector<double> pool;
  double wsum = 0.0, esum = 0.0, wIntimate = 0.0;
  std::optional<double> tpMax;
  for (std::size_t i = 0; i < tr.size(); ++i) {
    const auto& r = *tr[i];
    const auto& u = *und[i];
    if (const auto p = capPeak(r, c)) tpMax = std::max(tpMax.value_or(-1e300), *p);
    if (!r.integratedLufs || r.shortFile) continue;  // §17.2 members
    pool.insert(pool.end(), u.anchorBlockPower.begin(), u.anchorBlockPower.end());
    const auto uple = usablePle(u);
    double p = uple ? *uple : (u.anchorLufs ? *u.anchorLufs : *r.integratedLufs);
    if (uple && u.anchorLufs) p = std::clamp(p, *u.anchorLufs - kPerceptualBound, *u.anchorLufs + kPerceptualBound);
    const double w = u.anchorSec;
    wsum += w;
    esum += w * dm::pow(10.0, p / 10.0);
    if (u.character == Character::QuietIntimate) wIntimate += w;
  }
  if (wsum <= 0.0) {
    d.rules.push_back({"ALBUM_NO_MEMBERS", 0.0});
    d.gainDb = 0.0;
    return d;
  }
  const double ple = 10.0 * dm::log10(esum / wsum);
  const auto lAlbum = gatedLoudness(pool);
  double pleB = ple;
  if (lAlbum) pleB = std::clamp(ple, *lAlbum - kPerceptualBound, *lAlbum + kPerceptualBound);
  if (pleB != ple) d.rules.push_back({"PERCEPTUAL_BOUND", pleB - ple});
  d.intentDb = c.targetLufs - pleB;
  d.rules.insert(d.rules.begin(), {"ALBUM", d.intentDb});
  if (wIntimate >= 0.5 * wsum && c.intimacyLu > 0.0) {
    const double a = std::clamp(c.intimacyLu, 0.0, 2.0);
    d.intentDb -= a;
    d.rules.push_back({"INTIMACY", -a});
  }
  if (tpMax) {
    const double cap = c.ceilingDbtp - *tpMax;
    d.capDb = c.limiter ? cap + kLimiterAllowance : cap;
    d.gainDb = std::min(d.intentDb, d.capDb);
    if (c.limiter && d.intentDb > cap) d.rules.push_back({"LIMITER", std::min(d.intentDb, d.capDb) - cap});
    if (d.intentDb > d.capDb) d.rules.push_back({"PEAK_CAP", d.capDb - d.intentDb});
  } else {
    d.capDb = std::numeric_limits<double>::infinity();
    d.gainDb = d.intentDb;
  }
  return d;
}

double seamGain(const SeamInput& in, std::vector<Rule>* rules) {  // §18
  const double g = in.curTrackDb;
  if (!in.havePrev || in.prevFadeOut || in.curFadeIn || in.manualSkip || !in.prevEndLufs || !in.curOpenLufs) return g;
  const double prevEnd = *in.prevEndLufs + in.prevPleOffset.value_or(0.0);
  const double curOpen = *in.curOpenLufs + in.curPleOffset.value_or(0.0);
  if (prevEnd + in.prevSeamedDb < -40.0 || curOpen + g < -40.0) return g;
  const double ideal = prevEnd + in.prevSeamedDb - curOpen;
  const double out = std::min(std::clamp(ideal, g - 0.5, g + 0.5), in.curCapDb);
  if (rules && out != g) rules->push_back({"SEAM", out - g});
  return out;
}

double decayedOffset(const LearnedOffset& o, double nowDays) {  // §19.2: o * 2^(-(now - tau) / 30 d)
  const double dt = std::max(0.0, nowDays - o.updatedDays);
  return o.valueDb * dm::pow(2.0, -dt / 30.0);
}

void learnEvent(LearnedOffset& o, double eta, double weightedDeltaDb, double nowDays, double ceilingDb) {  // §19.2
  o.valueDb = std::clamp(decayedOffset(o, nowDays) + eta * weightedDeltaDb, -ceilingDb, ceilingDb);
  o.updatedDays = nowDays;
}

double sectionAdjustedDelta(double deltaDb, double sectionDevDb) {  // §19.1 (MATH-1.27)
  // The section playing at the press sat |dev| dB off the song's anchor in the direction the press moved the volume
  // (dev > 0: quieter than the anchor, so turning up fixes the section, not the song). That part is explained: only the
  // excess counts as a statement about the song, and a press that does not exceed it counts for nothing.
  if (deltaDb * sectionDevDb <= 0.0) return deltaDb;
  const double rest = std::fabs(deltaDb) - std::fabs(sectionDevDb);
  return rest <= 0.0 ? 0.0 : std::copysign(rest, deltaDb);
}

double appliedOffset(double trackDb, double artistDb, int artistTracks, bool trackTaught, bool artistAgrees) {  // §19.2
  if (trackTaught) return std::clamp(trackDb, -kTapCeilingDb, kTapCeilingDb);  // the song's own lesson is the most specific: it wins
  return std::clamp(trackDb + (artistTracks >= 2 && artistAgrees ? artistDb : 0.0), -kLearnCeilingDb, kLearnCeilingDb);  // no lesson of its own: the prior stays within 2 dB
}

double finalGain(double seamedDb, double learnedDb, double outputCalDb, double capEffDb) {  // §21
  return std::min(seamedDb + learnedDb + outputCalDb, capEffDb);
}

}  // namespace sable

// Test-signal generators (test code only; may use std:: math). Signals follow the EBU Tech 3341/3342
// minimum-requirement descriptions.
#pragma once

#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "sable/analyzer.h"

namespace sigs {

inline constexpr double kPi = 3.14159265358979323846;

struct Audio {
  int fs = 48000;
  int ch = 2;
  std::vector<float> x;  // interleaved
  std::size_t frames() const { return x.size() / static_cast<std::size_t>(ch); }
};

inline double dbfs(double db) { return std::pow(10.0, db / 20.0); }

// Append a sine at peak dBFS on all channels (in phase), continuous phase across calls.
inline void appendSine(Audio& a, double freq, double peakDbfs, double seconds, double& phase) {
  const auto n = static_cast<std::size_t>(std::llround(seconds * a.fs));
  const double amp = peakDbfs <= -200 ? 0.0 : dbfs(peakDbfs);
  const double dphi = 2.0 * kPi * freq / a.fs;
  for (std::size_t i = 0; i < n; ++i) {
    const float v = static_cast<float>(amp * std::sin(phase));
    for (int c = 0; c < a.ch; ++c) a.x.push_back(v);
    phase += dphi;
  }
}
inline void appendSilence(Audio& a, double seconds) {
  const auto n = static_cast<std::size_t>(std::llround(seconds * a.fs));
  a.x.insert(a.x.end(), n * static_cast<std::size_t>(a.ch), 0.0f);
}

inline sable::LoudnessResult analyze(const Audio& a, std::vector<double> w = {}) {
  if (w.empty()) w = sable::channelWeightsFor(a.ch);
  sable::Analyzer an(a.fs, a.ch, w);
  // Feed in uneven chunks to exercise streaming.
  std::size_t f = 0, step = 997;
  while (f < a.frames()) {
    const std::size_t n = std::min(step, a.frames() - f);
    an.push(a.x.data() + f * static_cast<std::size_t>(a.ch), n);
    f += n;
    step = step == 997 ? 4096 : 997;
  }
  return an.finish();
}

// Deterministic band-limited "noise": sum of many sines with fixed pseudo-random freqs/phases.
// Same continuous-time signal at any sample rate.
inline Audio multiSine(int fs, int ch, double seconds, double fLo, double fHi, int count, double rmsDb,
                       std::uint64_t seed = 12345, double tilt = -0.5) {
  Audio a{fs, ch, {}};
  std::uint64_t s = seed;
  auto rnd = [&]() {
    s ^= s << 13; s ^= s >> 7; s ^= s << 17;
    return static_cast<double>(s >> 11) / 9007199254740992.0;
  };
  std::vector<double> f(static_cast<std::size_t>(count)), ph(f.size()), amp(f.size());
  double pw = 0;
  for (std::size_t i = 0; i < f.size(); ++i) {
    f[i] = fLo * std::pow(fHi / fLo, rnd());
    ph[i] = 2 * kPi * rnd();
    amp[i] = std::pow(f[i] / 1000.0, tilt);  // tilt -0.5 => pink-ish
    pw += amp[i] * amp[i] / 2;
  }
  const double scale = dbfs(rmsDb) / std::sqrt(pw);
  const auto n = static_cast<std::size_t>(seconds * fs);
  a.x.assign(n * static_cast<std::size_t>(ch), 0.0f);
  for (std::size_t k = 0; k < n; ++k) {
    double v = 0;
    const double t = static_cast<double>(k) / fs;
    for (std::size_t i = 0; i < f.size(); ++i) v += amp[i] * std::sin(2 * kPi * f[i] * t + ph[i]);
    for (int c = 0; c < ch; ++c) a.x[k * static_cast<std::size_t>(ch) + static_cast<std::size_t>(c)] =
        static_cast<float>(scale * v * (c == 1 ? 0.9 : 1.0));
  }
  return a;
}

}  // namespace sigs

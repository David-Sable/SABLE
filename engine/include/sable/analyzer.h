// Sable engine — Layer 1 baseline analyzer (BS.1770 / R128 / LRA / true peak).
// Streaming: push() any number of interleaved float frames, then finish().
// Memory grows only with the per-100 ms / per-10 ms series (a few doubles each), never with PCM.
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace sable {

// Per-channel BS.1770 weight G_i (MATH §1.2). Use channelWeightsFor() for standard layouts.
std::vector<double> channelWeightsFor(int channels, std::uint32_t channelMask = 0);

struct KWeighting {  // MATH §2: two biquads, coefficients derived per sample rate.
  double b1[3], a1[3];  // stage 1 (high shelf), a1[0] == 1
  double b2[3], a2[3];  // stage 2 (RLB high-pass), a2[0] == 1
  static KWeighting forRate(double fs);
};

struct LoudnessResult {
  int sampleRateHz = 0;
  int channels = 0;
  std::uint64_t frames = 0;
  std::optional<double> integratedLufs;   // §4.2 (or §4.5 when shortFile)
  bool shortFile = false;                 // §4.5
  std::optional<double> lraLu;            // §5
  std::optional<double> truePeakDbtp;     // §6
  std::optional<double> samplePeakDbfs;   // §7
  std::optional<double> rmsGatedDbfs;     // §7
  std::optional<double> crestFactorDb;    // §7
  std::optional<double> plrDb;            // §7
  std::optional<double> maxMomentaryLufs; // §3.5 (fine grid)
  std::optional<double> maxShortTermLufs; // §3.5 (fine grid)
  bool lowRateKWeighting = false;         // §2.4
  int truePeakOversampling = 0;           // §6.1
  // Series on the 100 ms grid (MATH §3): block power P_j (weighted, linear) and S_k (LUFS or absent).
  std::vector<double> blockPower;                     // P_j, j = 0..J-1
  std::vector<std::optional<double>> shortTermLufs;   // S_k, k = 0..M-30
  std::vector<std::optional<double>> momentaryLufs;   // M_j = l_j
};

// Integrated loudness of a set of block powers (MATH §4.1). Exposed for anchors/albums.
std::optional<double> gatedLoudness(const std::vector<double>& blockPowers);

// LRA from short-term values (MATH §5).
std::optional<double> loudnessRange(const std::vector<std::optional<double>>& shortTerm);

class Analyzer {
 public:
  Analyzer(int sampleRateHz, int channels, std::vector<double> channelWeights);
  ~Analyzer();
  Analyzer(const Analyzer&) = delete;
  Analyzer& operator=(const Analyzer&) = delete;

  // Interleaved float samples, frames * channels values. No allocation after the first call per series growth.
  void push(const float* interleaved, std::size_t frames);
  LoudnessResult finish();

 private:
  struct Impl;
  Impl* impl_;
};

// Serialize as JSON (doubles via std::to_chars shortest round-trip; absent -> null). MATH §0.3, §25.6.
std::string toJson(const LoudnessResult& r, bool includeSeries = false);

}  // namespace sable

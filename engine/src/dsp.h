// DSP building blocks for the perceptual lens: in-tree FFT and resampler (MATH §25.5), PCHIP (MATH §10.1).
// Deterministic: fixed operation order, twiddles and filter taps designed with sable::dm.
#pragma once

#include <cstddef>
#include <vector>

namespace sable::dsp {

// Iterative radix-2 complex FFT, decimation in time, bit-reversed input. N is a power of two.
template <typename T>
class Fft {
 public:
  explicit Fft(std::size_t n);
  std::size_t size() const { return n_; }
  // In-place forward transform of (re, im), each of length N.
  void forward(T* re, T* im) const;
  // Power |X_k|^2 for k = 0..N/2 of a real input of length N (scratch buffers owned by the caller).
  void powerOfReal(const T* x, T* power, T* scratchRe, T* scratchIm) const;

 private:
  std::size_t n_;
  std::vector<std::size_t> rev_;
  std::vector<T> cos_, sin_;  // twiddles exp(-2 pi i k / N), k < N/2
};

// Power spectrum of a real signal of length N via one N/2-point complex FFT (half the work).
template <typename T>
class RealPowerFft {
 public:
  explicit RealPowerFft(std::size_t n);
  std::size_t size() const { return n_; }
  // power[k] = |X_k|^2, k = 0..N/2. Uses internal scratch; not thread-safe per instance.
  void power(const T* x, T* power);

 private:
  std::size_t n_;
  Fft<T> half_;
  std::vector<T> wr_, wi_, re_, im_;  // twiddles exp(-2 pi i k / N), k < N/2; scratch
};

// Symmetric Hann window (MATLAB `hann`): w[n] = 0.5 - 0.5 cos(2 pi n / (N - 1)).
std::vector<double> hannSymmetric(std::size_t n);

// Streaming rational resampler fsIn -> fsOut (polyphase Kaiser-windowed sinc). MATH §25.5.
// halfZeroCrossings: sinc zero crossings per side at the lower of the two rates; cutoff = 0.95 * min/2.
template <typename T>
class Resampler {
 public:
  Resampler(int fsIn, int fsOut, int halfZeroCrossings = 32, double beta = 10.0);
  // Feed input; appends every output sample that can be computed. Output time is aligned with input
  // time (zero-delay, non-causal filter centred on each output instant).
  void push(const T* in, std::size_t n, std::vector<T>& out);
  // Flush with zeros so every output up to the input end is produced.
  void finish(std::vector<T>& out);
  double ratio() const { return static_cast<double>(l_) / static_cast<double>(m_); }

 private:
  void produce(std::vector<T>& out, long long available);
  long long l_, m_;          // up L, down M
  long long half_;           // filter half-length in up-sampled samples
  int taps_ = 0;             // taps per phase (multiple of 4, zero padded)
  std::vector<T> poly_;      // [r * taps_ + j]: coefficient for x[n0 - (taps_-1) + j], r = (pos+half) mod L
  std::vector<T> hist_;      // input samples kept, starting at absolute index base_ (may be negative: zeros)
  long long base_ = 0;       // absolute index of hist_[0]
  long long inCount_ = 0;    // total input samples received
  long long nextOut_ = 0;    // next output index
  long long endInput_ = -1;  // set on finish
};

// MATLAB interp1(x, y, xi, 'pchip') including end-polynomial extrapolation (Fritsch-Carlson slopes with
// MATLAB's three-point end formula).
class Pchip {
 public:
  Pchip(std::vector<double> x, std::vector<double> y);
  double operator()(double xi) const;

 private:
  std::vector<double> x_, y_, d_;
};

}  // namespace sable::dsp

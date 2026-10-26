// DSP building blocks. See dsp.h.
#include "dsp.h"

#include <algorithm>
#include <stdexcept>

#include "sable/dmath.h"

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace sable::dsp {

template <typename T>
Fft<T>::Fft(std::size_t n) : n_(n), rev_(n), cos_(n / 2), sin_(n / 2) {
  if (n < 2 || (n & (n - 1)) != 0) throw std::invalid_argument("FFT size must be a power of two");
  std::size_t bits = 0;
  while ((static_cast<std::size_t>(1) << bits) < n) ++bits;
  for (std::size_t i = 0; i < n; ++i) {
    std::size_t r = 0;
    for (std::size_t b = 0; b < bits; ++b) r |= ((i >> b) & 1U) << (bits - 1 - b);
    rev_[i] = r;
  }
  for (std::size_t k = 0; k < n / 2; ++k) {
    const double a = -2.0 * dm::kPi * static_cast<double>(k) / static_cast<double>(n);
    cos_[k] = static_cast<T>(dm::cos(a));
    sin_[k] = static_cast<T>(dm::sin(a));
  }
}

template <typename T>
void Fft<T>::forward(T* re, T* im) const {
  for (std::size_t i = 0; i < n_; ++i) {
    const std::size_t r = rev_[i];
    if (r > i) { std::swap(re[i], re[r]); std::swap(im[i], im[r]); }
  }
  for (std::size_t len = 2; len <= n_; len <<= 1U) {
    const std::size_t half = len >> 1U, step = n_ / len;
    for (std::size_t s = 0; s < n_; s += len) {
      for (std::size_t j = 0; j < half; ++j) {
        const T wr = cos_[j * step], wi = sin_[j * step];
        const std::size_t a = s + j, b = a + half;
        const T tr = re[b] * wr - im[b] * wi;
        const T ti = re[b] * wi + im[b] * wr;
        re[b] = re[a] - tr; im[b] = im[a] - ti;
        re[a] = re[a] + tr; im[a] = im[a] + ti;
      }
    }
  }
}

template <typename T>
void Fft<T>::powerOfReal(const T* x, T* power, T* sre, T* sim) const {
  for (std::size_t i = 0; i < n_; ++i) { sre[i] = x[i]; sim[i] = T(0); }
  forward(sre, sim);
  for (std::size_t k = 0; k <= n_ / 2; ++k) power[k] = sre[k] * sre[k] + sim[k] * sim[k];
}

template class Fft<float>;
template class Fft<double>;

template <typename T>
RealPowerFft<T>::RealPowerFft(std::size_t n) : n_(n), half_(n / 2), wr_(n / 2), wi_(n / 2), re_(n / 2), im_(n / 2) {
  for (std::size_t k = 0; k < n / 2; ++k) {
    const double a = -2.0 * dm::kPi * static_cast<double>(k) / static_cast<double>(n);
    wr_[k] = static_cast<T>(dm::cos(a));
    wi_[k] = static_cast<T>(dm::sin(a));
  }
}

template <typename T>
void RealPowerFft<T>::power(const T* x, T* out) {
  const std::size_t h = n_ / 2;
  for (std::size_t i = 0; i < h; ++i) { re_[i] = x[2 * i]; im_[i] = x[2 * i + 1]; }
  half_.forward(re_.data(), im_.data());
  // X[k] = E[k] + W^k O[k], with E = (Z[k] + conj Z[h-k]) / 2 and O = (Z[k] - conj Z[h-k]) / (2i).
  out[0] = (re_[0] + im_[0]) * (re_[0] + im_[0]);
  out[h] = (re_[0] - im_[0]) * (re_[0] - im_[0]);
  for (std::size_t k = 1; k < h; ++k) {
    const T zr = re_[k], zi = im_[k], cr = re_[h - k], ci = -im_[h - k];
    const T er = T(0.5) * (zr + cr), ei = T(0.5) * (zi + ci);
    const T dr = T(0.5) * (zr - cr), di = T(0.5) * (zi - ci);
    const T or_ = di, oi = -dr;  // (d) / i
    const T xr = er + (wr_[k] * or_ - wi_[k] * oi);
    const T xi = ei + (wr_[k] * oi + wi_[k] * or_);
    out[k] = xr * xr + xi * xi;
  }
}

template class RealPowerFft<float>;
template class RealPowerFft<double>;

std::vector<double> hannSymmetric(std::size_t n) {
  std::vector<double> w(n);
  if (n == 1) { w[0] = 1.0; return w; }
  for (std::size_t i = 0; i < n; ++i)
    w[i] = 0.5 - 0.5 * dm::cos(2.0 * dm::kPi * static_cast<double>(i) / static_cast<double>(n - 1));
  return w;
}

namespace {
// Dot product with 4 fixed lanes (lane k sums j = k mod 4 in order), reduced as (l0+l1)+(l2+l3).
// MATH 25.4. The NEON float version performs exactly the same non-fused operations per lane.
template <typename T>
T dot4lanes(const T* c, const T* x, int n) {
  T l0 = T(0), l1 = T(0), l2 = T(0), l3 = T(0);
  for (int j = 0; j < n; j += 4) {
    l0 = l0 + c[j] * x[j];
    l1 = l1 + c[j + 1] * x[j + 1];
    l2 = l2 + c[j + 2] * x[j + 2];
    l3 = l3 + c[j + 3] * x[j + 3];
  }
  return (l0 + l1) + (l2 + l3);
}
#if defined(__aarch64__)
template <>
float dot4lanes<float>(const float* c, const float* x, int n) {
  float32x4_t acc = vdupq_n_f32(0.0f);
  for (int j = 0; j < n; j += 4) acc = vaddq_f32(acc, vmulq_f32(vld1q_f32(c + j), vld1q_f32(x + j)));
  const float l0 = vgetq_lane_f32(acc, 0), l1 = vgetq_lane_f32(acc, 1), l2 = vgetq_lane_f32(acc, 2), l3 = vgetq_lane_f32(acc, 3);
  return (l0 + l1) + (l2 + l3);
}
#endif
long long gcdLL(long long a, long long b) { while (b) { const long long t = a % b; a = b; b = t; } return a; }
double besselI0(double x) {
  double sum = 1.0, term = 1.0;
  const double q = (x * x) / 4.0;
  for (int k = 1; k < 500; ++k) {
    term *= q / (static_cast<double>(k) * static_cast<double>(k));
    sum += term;
    if (term < 1e-17 * sum) break;
  }
  return sum;
}
}  // namespace

template <typename T>
Resampler<T>::Resampler(int fsIn, int fsOut, int halfZc, double beta) {
  const long long g = gcdLL(fsIn, fsOut);
  l_ = fsOut / g;
  m_ = fsIn / g;
  // Work at the up-sampled rate fsIn * L. Cutoff in cycles per up-sampled sample:
  const double fc = 0.95 * 0.5 * static_cast<double>(std::min(fsIn, fsOut)) / (static_cast<double>(fsIn) * static_cast<double>(l_));
  half_ = static_cast<long long>(static_cast<double>(halfZc) / (2.0 * fc));  // zero crossings every 1/(2 fc)
  std::vector<double> h(static_cast<std::size_t>(2 * half_ + 1));
  const double i0b = besselI0(beta);
  for (long long n = -half_; n <= half_; ++n) {
    const double t = static_cast<double>(n);
    const double x = 2.0 * fc * t;
    const double sinc = n == 0 ? 1.0 : dm::sin(dm::kPi * x) / (dm::kPi * x);
    const double u = t / static_cast<double>(half_);
    const double w = besselI0(beta * dm::sqrt(std::max(0.0, 1.0 - u * u))) / i0b;
    h[static_cast<std::size_t>(n + half_)] = static_cast<double>(l_) * 2.0 * fc * sinc * w;  // gain L * passband
  }
  // Polyphase: output at up-sampled position pos uses n0 = floor((pos+half)/L), r = (pos+half) mod L and
  // taps h[r + jL] on x[n0 - j]. Stored reversed and zero padded to a multiple of 4 for a forward dot.
  const long long maxJ = (2 * half_) / l_ + 1;
  taps_ = static_cast<int>((maxJ + 3) / 4 * 4);
  poly_.assign(static_cast<std::size_t>(l_) * static_cast<std::size_t>(taps_), T(0));
  for (long long r = 0; r < l_; ++r)
    for (long long jj = 0; jj < taps_; ++jj) {
      const long long idx = r + jj * l_;
      if (idx <= 2 * half_) poly_[static_cast<std::size_t>(r * taps_ + (taps_ - 1 - jj))] = static_cast<T>(h[static_cast<std::size_t>(idx)]);
    }
  // Leading zeros stand for x[n < 0].
  hist_.assign(static_cast<std::size_t>(taps_), T(0));
  base_ = -static_cast<long long>(taps_);
}

template <typename T>
void Resampler<T>::produce(std::vector<T>& out, long long available) {
  for (;;) {
    const long long pos = nextOut_ * m_;
    const long long n0 = (pos + half_) / l_;
    if (n0 >= available) break;
    if (endInput_ >= 0 && pos > (endInput_ - 1) * l_) break;
    const long long r = (pos + half_) % l_;
    const T* c = poly_.data() + r * taps_;
    const T* x = hist_.data() + (n0 - (taps_ - 1) - base_);
    out.push_back(dot4lanes(c, x, taps_));
    ++nextOut_;
  }
  const long long need = (nextOut_ * m_ + half_) / l_ - taps_;  // oldest index still needed
  if (need - base_ > 4096) {
    const auto drop = static_cast<std::ptrdiff_t>(need - base_);
    hist_.erase(hist_.begin(), hist_.begin() + drop);
    base_ += drop;
  }
}

template <typename T>
void Resampler<T>::push(const T* in, std::size_t n, std::vector<T>& out) {
  hist_.insert(hist_.end(), in, in + n);
  inCount_ += static_cast<long long>(n);
  produce(out, inCount_);
}

template <typename T>
void Resampler<T>::finish(std::vector<T>& out) {
  endInput_ = inCount_;
  const long long pad = half_ / l_ + taps_ + 1;  // zeros after the end
  hist_.insert(hist_.end(), static_cast<std::size_t>(pad), T(0));
  produce(out, inCount_ + pad);
}

template class Resampler<float>;
template class Resampler<double>;

Pchip::Pchip(std::vector<double> x, std::vector<double> y) : x_(std::move(x)), y_(std::move(y)), d_(x_.size()) {
  const std::size_t n = x_.size();
  std::vector<double> h(n - 1), del(n - 1);
  for (std::size_t k = 0; k + 1 < n; ++k) { h[k] = x_[k + 1] - x_[k]; del[k] = (y_[k + 1] - y_[k]) / h[k]; }
  for (std::size_t k = 1; k + 1 < n; ++k) {  // interior: weighted harmonic mean (Fritsch-Carlson)
    if (del[k - 1] * del[k] > 0.0) {
      // Fritsch-Butland weighted harmonic mean (as MATLAB pchip / SciPy PchipInterpolator):
      // del_left is weighted by (h_left + 2 h_right), del_right by (2 h_left + h_right).
      const double w1 = h[k - 1] + 2.0 * h[k], w2 = 2.0 * h[k - 1] + h[k];
      d_[k] = (w1 + w2) / (w1 / del[k - 1] + w2 / del[k]);
    } else {
      d_[k] = 0.0;
    }
  }
  const auto endSlope = [](double h0, double h1, double d0, double d1) {  // MATLAB pchipend
    double d = ((2.0 * h0 + h1) * d0 - h0 * d1) / (h0 + h1);
    if ((d > 0) != (d0 > 0) || d == 0.0) d = 0.0;
    else if ((d0 > 0) != (d1 > 0) && dm::fabs(d) > dm::fabs(3.0 * d0)) d = 3.0 * d0;
    return d;
  };
  d_[0] = endSlope(h[0], h[1], del[0], del[1]);
  d_[n - 1] = endSlope(h[n - 2], h[n - 3], del[n - 2], del[n - 3]);
}

double Pchip::operator()(double xi) const {
  const std::size_t n = x_.size();
  std::size_t k = 0;  // interval index; extrapolate with the end polynomials
  if (xi >= x_[n - 1]) k = n - 2;
  else if (xi > x_[0]) k = static_cast<std::size_t>(std::upper_bound(x_.begin(), x_.end(), xi) - x_.begin()) - 1;
  const double h = x_[k + 1] - x_[k], t = xi - x_[k];
  const double del = (y_[k + 1] - y_[k]) / h;
  const double c = (3.0 * del - 2.0 * d_[k] - d_[k + 1]) / h;
  const double b = (d_[k] - 2.0 * del + d_[k + 1]) / (h * h);
  return y_[k] + t * (d_[k] + t * (c + t * b));
}

}  // namespace sable::dsp

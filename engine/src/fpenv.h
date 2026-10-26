// FP control state guard: FTZ/DAZ on for the duration of an analysis call. MATH §25.3.
#pragma once

#include <cstdint>

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__)
#include <immintrin.h>
#endif

namespace sable {

class FpEnvGuard {
 public:
  FpEnvGuard() {
#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__)
    saved_ = _mm_getcsr();
    _mm_setcsr(static_cast<unsigned>(saved_) | 0x8040u);  // FTZ (bit 15) | DAZ (bit 6)
#elif defined(__aarch64__)
    std::uint64_t v;
    __asm__ volatile("mrs %0, fpcr" : "=r"(v));
    saved_ = v;
    v |= (1ull << 24);  // FZ: flush inputs and outputs, single and double
    __asm__ volatile("msr fpcr, %0" : : "r"(v));
#endif
  }
  ~FpEnvGuard() {
#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__)
    _mm_setcsr(static_cast<unsigned>(saved_));
#elif defined(__aarch64__)
    __asm__ volatile("msr fpcr, %0" : : "r"(saved_));
#endif
  }
  FpEnvGuard(const FpEnvGuard&) = delete;
  FpEnvGuard& operator=(const FpEnvGuard&) = delete;

 private:
  std::uint64_t saved_ = 0;
};

}  // namespace sable

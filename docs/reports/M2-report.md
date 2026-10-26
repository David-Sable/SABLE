# Milestone 2 — Engine baseline: test report (2026-10-26)

Engine: `engine/` (C++20, clang). It implements MATH-1.2 §0–§7 and §25: K-weighting, blocks, gating, momentary/short-term (with the fine-grid maxima), integrated loudness, LRA, true peak, peaks/RMS/crest/PLR, and the deterministic math library.

## 1. Compliance (synthesized EBU signals, `engine/tests/test_compliance.cpp`)
| Test | Expected | Sable | Pass |
|---|---|---|---|
| T3341 cases 1–5 (I) | −23.0 / −33.0 ± 0.1 | all within ±0.02 | ✅ |
| T3341 cases 1–2 (M, S every value) | ± 0.1 | all | ✅ |
| T3341 case 6 (5.0 raw multichannel) | −23.0 ± 0.1 | −23.016 | ✅ |
| T3341 case 9 (S constant) | −23.0 ± 0.1 | all values | ✅ |
| T3341 case 10 (max S, 20 files) | −23.0 ± 0.1 | all 20 | ✅ |
| T3341 case 12 (M constant) | −23.0 ± 0.1 | all values | ✅ |
| T3341 case 13 (max M, 20 files, fine grid) | −23.0 ± 0.1 | all 20 | ✅ |
| T3341 cases 15–19 TP @ 44.1/48/96 kHz | −6.0 / +3.0, +0.2/−0.4 | −6.021…−6.019 / +2.985 | ✅ |
| T3341 cases 20–23 TP @ 44.1/48/96 kHz | 0.0, +0.2/−0.4 | −0.144…−0.139 | ✅ |
| T3342 cases 1–4 (LRA) | 10 / 5 / 20 / 15 ± 1 | 10 / 5 / 20 / 15 | ✅ |

Not yet run: T3341 cases 7–8 and T3342 cases 5–6 (authentic programmes). They need the official EBU file set, which sits behind a browser check and must be downloaded manually.

## 2. Properties and robustness (`engine/tests/test_properties.cpp`, `test_kweighting.cpp`, `test_dmath.cpp`)
- **Gain:** +x dB shifts I by x within 1e-5 for x ∈ {−20, −6.02, +3.7, +12} (measured: exact to display precision).
- **44.1 vs 48 kHz** band-limited signal: I and LRA within 0.1 LU. Low-rate table (MATH §2.4): measured deltas 0.21 (8 kHz), 0.16 (11.025), 0.09 (16), 0.05 (22.05), ≤ 0.03 (≥ 32 kHz), all inside the table.
- **L/R swap:** bit-identical JSON.
- **Mono** = dual-mono stereo: identical.
- **Silence and short files** (0–3000 ms): no NaN/∞; absent where MATH §0.3/§4.5 says so.
- **Subnormals, overs (> 0 dBFS kept), DC/square:** handled. TP ≥ sample peak.
- **Determinism:** same input gives identical JSON.
- **`sable::dm`:** exp ≤ 1 ulp, log/sin/cos ≤ 2 ulp, pow ≤ 3.5e-15 relative (1e6 random args).
- **K-weighting coefficients at 48 kHz** match BS.1770 Tables 1–2 to ≤ 1e-8.
- **Totals:** 22 test cases, 2,227 assertions, all passing.

## 3. Code hygiene
- AddressSanitizer + UndefinedBehaviorSanitizer build: full suite passes, no reports.
- clang-tidy (bugprone, performance, clang-analyzer, cert-flp30): clean.
- Compiler warnings (`-Wall -Wextra -Wpedantic -Wconversion -Wshadow`): none, on host and NDK.

## 4. Cross-validation on real music (`tools/validation/crossval.py`, data in `M2-crossval.csv`)
71 files, 5.06 h. All free-licence recordings, plus one track per album from the owner's collection. Formats: FLAC 16/24-bit 44.1–96 kHz, ALAC, MP3 (including mono). Every meter measures the same FFmpeg-decoded float PCM.

| Quantity | vs libebur128 | vs FFmpeg ebur128 | vs pyloudnorm |
|---|---|---|---|
| Integrated | **max \|Δ\| 0.0000 LU** | mean +0.003, max 0.054 | mean +0.040, max 0.047 |
| LRA | mean +0.025, max 0.58, 11/71 > 0.2 | max 0.061 | – |
| True peak | mean +0.03, max 0.39, 2/71 > 0.3 | mean +0.02, max 0.36, 1/71 > 0.3 | – |

Explanations (as predicted in MATH §5, §6):
- **LRA vs libebur128:** libebur128 takes short-term values once per second. EBU Tech 3342 v3+ requires ≥ 10 Hz, as used by Sable and FFmpeg, and Sable agrees with FFmpeg within 0.06 LU.
- **True peak:** Sable oversamples 8× below 96 kHz. libebur128 and FFmpeg use 4×, which under-reads by up to 0.55 dB at high normalized frequencies (BS.1770 Annex 2). Every larger difference has Sable reading *higher*, on transient-heavy tracks.
- **pyloudnorm:** a near-constant +0.04 LU (44.1 kHz) / +0.02 LU (96 kHz) offset. It comes from pyloudnorm's different K-weighting filter constants. Within 0.1 LU; libebur128 and FFmpeg agree with Sable.

**Requirement "agree within 0.1 LU, explain any difference": met.**

## 5. On-device (Galaxy A17 5G, SM-A176U, Android 16 / SDK 36) — `tools/validation/device_check.py`
- Test suite on device (ARM64, NDK r30): **22/22 cases, 2,227/2,227 assertions pass.**
- **Host vs device byte-identical** analysis JSON, including every short-term series value, on 5 real tracks: 44.1/16 FLAC, 96/24 FLAC, mono MP3, 48/24 ALAC and 48/24 orchestral. **All IDENTICAL.**

### Benchmark (engine analysis of a decoded 4 min 19 s 44.1 kHz stereo track; timed with the shell's `time`)
| Core | Time | Realtime factor |
|---|---|---|
| Cortex-A78 (big, cpu6) | 4.36 s | 59× |
| Cortex-A55 (little, cpu0) | 10.20 s | 25× |
| Host i7-4790K (reference) | 4.01 s | 65× |

**Honest assessment:** the total budget is ≤ 10 s for a 4-minute song, including decode (M4) and the perceptual model (M3). The baseline alone uses ≈ 4.0 s of that on a big core. That is too much. The 8× true-peak interpolator dominates (≈ 450 multiply-adds per stereo frame, in latency-bound dependency chains).

**Planned fix (first task of M3), without changing results:**
- compute several output frames per pass, so the 4 fixed lanes per phase stay identical but independent chains run in parallel (more ILP for NEON);
- an exact pruning bound: skip interpolation where `Σ|h|·max|window| · (1 + 1e-4)` cannot exceed the current maximum.

Both keep bit-identical results; the determinism tests enforce it. Target: ≤ 1.5 s on an A78.

(The first benchmark run used `date +%s%N` on the device, which gave impossible sub-100 ms timings. That method is discarded; the script now uses `time`.)

## Verdict
Milestone 2's correctness criteria are all met: compliance, cross-validation, properties, determinism, sanitizers, tidy, and on-device identity. Performance is measured and reported, and does not yet meet the overall budget. Its fix is scheduled as M3's first task.

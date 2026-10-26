# SABLE — MATH.md

**Version:** MATH-1.8 (2026-10-26)
**Status:** Milestone 1 complete. Two independent reviews (docs/reviews/MATH-1.0-draft-review.md and docs/reviews/MATH-1.1-review.md); all findings are resolved in 1.2. Nothing in the engine may contradict this file. If code and MATH.md disagree, that is a bug in one of them; fix the one that is wrong and bump the version.

Every engine function cites the section it implements, e.g. `// MATH §4.2`.
Constants are collected in §27. Sources are listed in §29 and cited inline as **[BS1770]**, **[T3341]**, **[T3342]**, **[TVL16]**, **[TVL18]**, **[ISO226]**, etc.

Status tags used below:
- **[STD]** taken directly from a standard or reference implementation; not ours to change.
- **[DESIGN]** a Sable design decision; justified in place; may be tuned only through a MATH.md version bump.
- **[TUNE]** an initial value expected to be tuned on the corpus in Milestone 3, then frozen and versioned.
- **[PROVISIONAL]** depends on a source we could not fully verify; see the note at that item.

---

## 0. Conventions

### 0.1 Units and naming
| Quantity | Unit | Code suffix | Example |
|---|---|---|---|
| Level ratio (amplitude) | dB | `Db` | `gainDb` |
| Linear amplitude gain | – | `Lin` | `gainLin = 10^(gainDb/20)` |
| Linear power / mean square | – (full-scale² ) | `Pow` | `blockPow` |
| Loudness (BS.1770) | LUFS (absolute), LU (relative) | `Lufs`, `Lu` | `integratedLufs`, `lraLu` |
| True peak | dBTP | `Dbtp` | `truePeakDbtp` |
| Sample peak | dBFS | `Dbfs` | `samplePeakDbfs` |
| Perceptual loudness | sone | `Sone` | `ltlSone` |
| Perceptual loudness equivalent | LUFS-equivalent (§12) | `Ple` | `anchorPle` |
| Time | s or samples | `Sec`, `Samples` | `hopSamples` |
| Frequency | Hz | `Hz` | `cutoffHz` |
| ERB_N-number | Cam | `Cam` | `cfCam` |

Conversions — the only ones allowed **[STD]**:
- `gainLin = 10^(gainDb / 20)`, `gainDb = 20·log10(gainLin)`
- `powLin = 10^(levelDb / 10)`, `levelDb = 10·log10(powLin)`

Applying amplitude gain `g` dB to a signal multiplies every mean-square (power) quantity by `10^(g/10)`.

### 0.2 Energy-domain rule [DESIGN, required by spec]
Every average, pool or aggregate of BS.1770 loudness (blocks, sections, anchors, albums, LRA intermediates) is computed on **linear power**, then converted to dB once at the end. Averaging dB values is forbidden.

Perceptual (sone) quantities are additive in sones by construction of the loudness model **[TVL16]**: specific loudness integrates across frequency, and the two ears sum. Their linear domain is therefore the sone. Perceptual averages are taken in sones and never in phons. LUFS-equivalent values derived from them (§12) pool in the power domain like any other LUFS value.

### 0.3 Undefined values [DESIGN]
A quantity that has no defined value is represented as **absent** (`std::optional` in C++, `null` in JSON). Examples: integrated loudness of pure silence, LRA with fewer than one short-term value, true peak of an all-zero signal. NaN and ±∞ never leave a function. A test asserts that every serialized field is either a finite number or `null` (§26).
- `log10(0)` is never evaluated. Callers check `pow > 0` first, otherwise the result is absent.
- **Floor:** a defined power below `1e-20` (−200 dB) is treated as zero for log purposes (absent). For scale: one ±1 LSB 16-bit sample (`(1/32768)² ≈ 9.3e-10`) inside an otherwise silent 0.4 s block at 192 kHz (76 800 samples) has mean square ≈ 1.2e-14, six orders of magnitude above the floor. The floor is also far below the −70 LUFS gate (≈ 1.2e-7), so it never changes a gated result.

### 0.4 Numeric types [DESIGN]
- Filter states, accumulators of squares, gating sums, coefficient design: `double`.
- Decoded audio samples and the playback path: `float` (IEEE-754 binary32).
- True-peak polyphase FIR: `float` inputs and coefficients, `float` accumulation in fixed lane order (§25), with the maximum held in `float`.
- Perceptual fast model: `float` with fixed lane order. Perceptual reference model (host only): `double`.

### 0.5 Rounding
The engine never rounds. Display layers round to 0.1 (LU/LUFS/dB/dBTP), per the precision limit in **[T3341 §2.8]**.

---

## 1. Signal input contract

### 1.1 Decoded PCM [DESIGN]
- The engine receives **decoded, interleaved float** PCM in nominal range [−1, 1]. It is never clipped: values beyond ±1.0 from lossy decodes are kept and measured, as the spec requires.
- If a platform decoder can only output 16-bit integer PCM (it cannot represent overs) **for a lossy source** (MP3, AAC, Vorbis, Opus), the result is flagged `decodeClipped = true`. Lossless sources (FLAC, ALAC, WAV) decoded to their native integer depth are exact and never flagged. The true-peak value used for the peak cap (§16.4) then gets a safety margin `M_clipTP = +1.0 dB` **[TUNE]**, and the Why sheet says so. Milestone 4 records which Android decoders deliver float on the A17.
- Encoder delay and padding are removed *before* the engine sees samples: LAME/Xing `enc_delay`/`enc_padding`, iTunSMPB for AAC/ALAC-in-MP4, Opus `pre_skip` and end trimming via granule position, Vorbis granule position. FLAC and WAV have none. Media3 extractors already parse these. The host CLI uses FFmpeg, which applies the same trims; cross-checked in §26.

### 1.2 Channel layouts [STD + DESIGN]
Channel weights `G_i` **[BS1770 Table 3]**:

| Channel (azimuth θ, elevation φ) | G_i |
|---|---|
| L, R, C (and any channel with \|θ\| < 60°) | 1.0 |
| Side surrounds Ls/Rs, Lss/Rss (60° ≤ \|θ\| ≤ 120°, \|φ\| < 30°) | 1.41 |
| Back surrounds Lb/Rb, Lrs/Rrs (120° < \|θ\| ≤ 180°) | 1.0 |
| Height channels (\|φ\| ≥ 30°) | 1.0 |
| LFE | 0 (excluded) |

These are the position-dependent weights of **[BS1770-5 Annex 3, Tables 4–5]**. Channel positions come from the container's channel mask (WAVE_FORMAT_EXTENSIBLE `dwChannelMask`, FLAC/Vorbis `WAVEFORMATEXTENSIBLE_CHANNEL_MASK`, MP4 channel layout). With no mask, the standard default order for the channel count is used (FLAC/Vorbis/WAV order: 6 ch = L R C LFE BL BR; 8 ch = L R C LFE BL BR SL SR). Mask bits map to azimuth as follows:

| Mask bits | Assumed azimuth | G |
|---|---|---|
| FRONT_LEFT / FRONT_RIGHT | ±30° | 1.0 |
| FRONT_CENTER | 0° | 1.0 |
| FRONT_LEFT_OF_CENTER / FRONT_RIGHT_OF_CENTER | ±15° | 1.0 |
| SIDE_LEFT / SIDE_RIGHT | ±90° | 1.41 |
| BACK_LEFT / BACK_RIGHT, **no SIDE bits present** (5.1 "back" = surround) | ±110° | 1.41 |
| BACK_LEFT / BACK_RIGHT, SIDE bits also present (7.1) | ±135° | 1.0 |
| BACK_CENTER | 180° | 1.0 |
| TOP_* | elevated | 1.0 |
| LOW_FREQUENCY | – | 0 |

This mapping gives T3341 case 6 = −23.02 LUFS. Treating 5.1 BACK as ±135° would give −23.40 LUFS and fail the test.

- **Mono (1 channel) [DESIGN]:** treated as **dual-mono**. The mono signal plays from both speakers / both earpieces, which BS.1770 would measure as a 2-channel signal with identical channels, so the measurement is `Σ G_i z_i = 2·z_mono`. That is +3.01 dB relative to measuring it as a single channel. This matches libebur128's `EBUR128_DUAL_MONO` and FFmpeg `ebur128=dualmono=true`. pyloudnorm treats mono as one channel, so pyloudnorm mono results are 3.01 LU lower by construction; the cross-validation corrects for this explicitly (§26).
- **More than 2 channels [DESIGN]:** Sable plays on a stereo device, so the measured signal must be what is played. Files with >2 channels are downmixed by Sable before *both* analysis and playback (the same code), using ITU-R BS.775 coefficients:
  `L' = L + 0.7071·C + 0.7071·Σ(left surrounds)`, `R' = R + 0.7071·C + 0.7071·Σ(right surrounds)`. The left surrounds are every side, back and top channel on the left, each with coefficient 0.7071. FRONT_LEFT_OF_CENTER is 1.0 into L. The right side mirrors this. BACK_CENTER and TOP_CENTER add 0.5 to both sides. LFE is dropped.
  The downmix is then measured as stereo (G = 1, 1). **True peak is also measured on the downmix**, the signal that is actually played: the un-normalized sum can exceed the raw channels' TP by `20·log10(1 + 0.7071·n_s)` dB, where `n_s` is the number of 0.7071-weighted channels feeding one side. That is +7.7 dB for 5.1 (C + 1 surround) and +9.9 dB for 7.1 (C + side + back). The raw multichannel BS.1770 loudness and TP (weights above) are *also* reported for compliance testing (§26, EBU 3341 case 6) but are not used for gain.

### 1.3 Sample rates [DESIGN]
Supported: 8 kHz to 384 kHz. Every rate-dependent constant is derived from `fs` (filters §2, hop §3, true-peak factor §6). None are hard-coded for 48 kHz.

---

## 2. K-weighting [STD]

Two biquads in cascade, coefficients derived for each `fs` from the analog prototype via the bilinear transform, exactly as libebur128 does **[LIBEBUR128]**. The 48 kHz values reproduce **[BS1770 Tables 1–2]**.

### 2.1 Stage 1 — high-shelf ("spherical head")
Constants (libebur128):
- `f0 = 1681.974450955533 Hz`
- `G = 3.999843853973347 dB`
- `Q = 0.7071752369554196`
- `K = tan(π·f0/fs)`, `Vh = 10^(G/20)`, `Vb = Vh^0.4996667741545416`
- `a0 = 1 + K/Q + K²`
- `b0 = (Vh + Vb·K/Q + K²)/a0`, `b1 = 2(K² − Vh)/a0`, `b2 = (Vh − Vb·K/Q + K²)/a0`
- `a1 = 2(K² − 1)/a0`, `a2 = (1 − K/Q + K²)/a0`

### 2.2 Stage 2 — RLB high-pass
- `f0 = 38.13547087602444 Hz`, `Q = 0.5003270373238773`, `K = tan(π·f0/fs)`
- `b = [1, −2, 1]` (unnormalized, as in **[BS1770 Table 2]** and libebur128)
- `a1 = 2(K² − 1)/(1 + K/Q + K²)`, `a2 = (1 − K/Q + K²)/(1 + K/Q + K²)`

### 2.3 Implementation
- Each stage runs as a biquad in **Transposed Direct Form II** with `double` state and coefficients, one state per channel. (libebur128 multiplies the two stages into one 4th-order direct-form filter. The cascade is mathematically identical and numerically better conditioned. Agreement is checked in §26.)
- The filter output `y_i[n]` is squared in `double`.
- Denormals: the state is flushed to zero when `|s| < 1e-30` (§25.3).
- **Test [§26]:** at `fs = 48000` the derived coefficients match **[BS1770]** Table 1 to `|Δ| ≤ 1e-8` and Table 2 `a1, a2` to `|Δ| ≤ 1e-8`.

### 2.4 Rate invariance (known limitation) [DESIGN]
The bilinear transform warps frequencies near Nyquist. Stage 2's unnormalized `b = [1, −2, 1]` also has a high-frequency gain `4/(1 − a1 + a2)` that depends slightly on fs. So the K-weighting response at other rates differs from the 48 kHz response, as follows (identical to libebur128):

| fs | max deviation of K-weighting vs 48 kHz (20 Hz – 0.45·fs) |
|---|---|
| ≥ 32 kHz (incl. 44.1, 88.2, 96, 192, 384) | ≤ 0.05 dB |
| 22.05 / 24 kHz | ≈ 0.08 dB |
| 11.025 / 12 / 16 kHz | ≤ 0.30 dB |
| 8 kHz | ≤ 0.51 dB (at ≈ 2.4 kHz) |

Music at ≥ 32 kHz, which is all of the supported formats in practice, meets the 0.1 LU cross-rate requirement. For fs < 32 kHz Sable keeps the libebur128 behaviour, for cross-validation parity, and **reports `lowRateKWeighting = true`**; the Why sheet notes a possible error of up to ±0.5 LU. **Test:** pink noise **band-limited to 20 Hz – 0.45·min(fs, 48 kHz)**, the same band at both rates, measures within the table's tolerance of the same band-limited noise at 48 kHz. Full-band noise would differ by construction, because low rates lose the upper octaves.

---

## 3. Blocks, sub-blocks, momentary and short-term [STD]

### 3.1 Sub-blocks
- Hop length `H = round_half_up(fs / 10)` samples (100 ms). `fs` divisible by 10 gives exactly 100 ms. Otherwise the hop deviates by < 0.5 sample: at 11025 Hz, H = 1103, which is +0.045%, and the block 4H = 4412 is 2 samples longer than "400 ms to the nearest sample" (4410). Sable accepts this deviation to match libebur128's `samples_in_100ms = (fs + 5)/10` and to keep blocks made of exactly 4 sub-blocks. It only affects fs not divisible by 10, which are rare and already flagged as low-rate in §2.4.
- For channel `i`, sub-block `m` covers samples `[m·H, (m+1)·H)`: `q_{i,m} = Σ_n y_i[n]²` (sum, not mean), in `double`.
- Only complete sub-blocks are formed. A trailing partial sub-block is kept only for the very-short-file rule (§4.5).

### 3.2 Gating blocks (400 ms, 75% overlap)
Block `j` = sub-blocks `j, j+1, j+2, j+3`, i.e. samples `[j·H, j·H + 4H)`. That is a block length `T_g = 4H` (400 ms to the nearest sample) and a step of `T_g·(1 − 0.75) = H` **[BS1770 eq. 3]**.
- Per-channel mean square: `z_{i,j} = (q_{i,j} + q_{i,j+1} + q_{i,j+2} + q_{i,j+3}) / (4H)`.
- Block power: `P_j = Σ_i G_i·z_{i,j}`.
- Block loudness: `l_j = −0.691 + 10·log10(P_j)` LUFS **[BS1770 eq. 4]**. Absent if `P_j ≤ 1e-20`; such a block can never pass the −70 LUFS gate anyway.
- Number of blocks: `J = M − 3` for `M ≥ 4` complete sub-blocks, otherwise 0. The last incomplete block is discarded **[BS1770; T3341 §2.3]**.

### 3.3 Momentary loudness M [STD]
`M_j = l_j` (400 ms rectangular window, ungated, updated every 100 ms) **[T3341 §2.2]**. The first value is at t = 0.4 s.

### 3.4 Short-term loudness S [STD]
`S_k = −0.691 + 10·log10( Σ_i G_i · (Σ_{m=k}^{k+29} q_{i,m}) / (30H) )` for `k = 0 … M−30` (3 s rectangular window, ungated, 10 Hz) **[T3341 §2.2]**. The first value covers [0, 3 s). If `M < 30`, there are no S values.

### 3.5 Maximum momentary / short-term (fine grid)
A 100 ms grid can miss the true maximum of a 400 ms window by up to 0.46 dB, for example on T3341 case 13, where tones start at 20 ms offsets. Maxima are therefore computed on a fine grid:
- Fine hop `h = round_half_up(fs/100)` samples (10 ms). Fine sub-block sums are `r_{i,u} = Σ y_i²` over `[u·h, (u+1)·h)`.
- `M^fine_u` uses 40 fine sub-blocks (window 40h samples) and `S^fine_u` uses 300 (300h samples). Otherwise both are computed as in §3.3/§3.4: mean square over the window, channel weights, −0.691. When fs is divisible by 100, 40h = 4H exactly. Otherwise the windows differ from 4H/30H by < 0.5% (e.g. 4400 vs 4412 samples at 11.025 kHz), which is accepted for these display and compliance maxima.
- `Mmax = max_u M^fine_u` and `Smax = max_u S^fine_u`, over defined values only.

Worst-case under-read with a 10 ms hop: a 400 ms window misses ≤ 5 ms in total, because the onset lies at most half a hop from a grid point. That is −0.05 dB on the steady tones of the T3341 cases. These maxima are reported for compliance tests (**[T3341]** cases 9–14) and the UI. Gating, LRA, structure and all gain decisions use the 100 ms grid only.

### 3.6 Timestamps
A value computed over the window `[a, b)` carries the timestamp `b`, its window end:
- `M_j` → `(j + 4)·H/fs`
- `S_k` → `(k + 30)·H/fs`
- **exception:** sub-block `m` is stamped at its centre, `(m + 0.5)·H/fs`, because it is used for interval membership, not as a windowed measurement

Any reference to "S at time t" means the `S_k` whose timestamp is the largest one ≤ t.

---

## 4. Gating and integrated loudness [STD]

### 4.1 Integrated loudness of a set of blocks
For any index set `B` of blocks (a whole track, a section, an anchor, an album pool):

1. Absolute gate **[BS1770 eq. 6]**: `J_a = { j ∈ B : l_j > −70 }`. The comparison is strict `>`.
2. If `J_a = ∅` → the result is **absent**.
3. `P_a = (1/|J_a|)·Σ_{j∈J_a} P_j`, then `Γ_r = −0.691 + 10·log10(P_a) − 10` LUFS. This is an energy-domain mean, never a mean of `l_j`.
4. Relative gate **[BS1770 eq. 7]**: `J_g = { j ∈ J_a : l_j > Γ_r }` (strict).
5. `L = −0.691 + 10·log10( (1/|J_g|)·Σ_{j∈J_g} P_j )`. `J_g` is never empty when `J_a` is non-empty, because the block with maximum `P_j` always passes. Proof: max ≥ mean > mean·0.1.

Note: BS.1770 sums `G_i·z_ij` inside the mean, and averaging `P_j` is algebraically identical to that, because the per-block channel sum and the mean over blocks commute.

### 4.2 Integrated loudness of a track
`L_I = Integrated(all blocks)` (§4.1).

### 4.3 Section / anchor loudness
A block `j` belongs to a time interval `[t0, t1)` iff its whole extent lies inside: `j·H ≥ t0·fs` and `j·H + 4H ≤ t1·fs`. Section and anchor loudness = §4.1 over the member blocks of the union of intervals. Gating is relative to that set (the gate is re-derived from the set). This is the same rule BS.1770 uses for a programme, applied to a sub-programme.

### 4.4 Album loudness [STD-consistent, required by spec]
`L_album = Integrated( ⋃_tracks blocks )`: all blocks of all tracks pooled into one set with one absolute and one relative gate. It is **not** the mean of track `L_I` values.
Implementation: each track stores its block powers `P_j` (one `double` per 100 ms, ≈ 19 KB for 4 minutes), so pooling is exact.

### 4.5 Very short files [DESIGN]
If a file has no complete gating block (< 400 ms) but has ≥ 50 ms of samples:
`L_short = −0.691 + 10·log10( Σ_i G_i · (Σ_n y_i[n]²)/N )` over all `N` samples, ungated, and flagged `shortFile = true`. If it has < 50 ms, or the power is ≤ 1e-20, loudness is absent. `L_short` is used *only* for the gain decision of that file (§16.6). It is never pooled into an album.

---

## 5. Loudness Range (LRA) [STD]

Exactly **[T3342 §3.1 and its MATLAB reference]**:
1. Input: all short-term values `S_k` (§3.4) at 10 Hz. That is a 3 s window with a 2.9 s overlap, the minimum overlap T3342 v3+ requires. No padding at the end of the file (see note).
2. Absolute gate: keep `S_k ≥ −70` LUFS. **Non-strict `≥`**, as in the T3342 MATLAB code. This differs from the strict `>` of the integrated gate, and the difference is intended.
3. `S_int = 10·log10( mean_k 10^(S_k/10) )` over the abs-gated values. This is the energy-domain mean, exactly as the MATLAB code computes it. The −0.691 offset cancels because it is inside both sides.
4. Relative gate: keep `S_k ≥ S_int − 20`.
5. Sort the remaining `n` values ascending into `v[0..n−1]`.
6. Percentile index (**exact method**, used everywhere in Sable): `idx(p) = floor((n − 1)·p + 0.5)` (0-based). This equals MATLAB `round((n−1)·p + 1)` (1-based, round half away from zero on non-negative arguments) and libebur128's `(size_t)((n−1)·p + 0.5)`.
7. `LRA = v[idx(0.95)] − v[idx(0.10)]` LU.
- `n = 0` → LRA absent. `n = 1` → LRA = 0.

**Note (end padding):** the T3342 MATLAB comment suggests ≥ 1.5 s of trailing silence for file-based measurement, so that the final windows are centered on the end of the programme. Sable does not pad. Padding would add short-term values that partly cover silence, and the relative gate and 10th percentile already exclude them. The effect is < 0.1 LU on the T3342 test signals, verified in §26.
**Known differences:**
- libebur128 computes LRA from short-term values every 1 s (it resets its counter to 20 × 100 ms). It uses a histogram only in `EBUR128_MODE_HISTOGRAM`, and its gates (integrated and LRA) use `>=`. Sable's integrated gate is strict `>`, per BS.1770.
- FFmpeg uses a 0.1 LU histogram.

Small LRA disagreements with them (≤ 0.2 LU typical) are expected and explained by this. Integrated-loudness differences from `>=` vs `>` only occur when a block lies exactly on a gate, which is measure-zero for real audio (§26).

---

## 6. True peak [STD + DESIGN]

### 6.1 Oversampling factor [DESIGN, satisfies "≥ 4×, more for lower rates"]
| fs | factor L |
|---|---|
| fs < 96 000 Hz | 8 |
| 96 000 ≤ fs < 192 000 | 4 |
| fs ≥ 192 000 | 4 |

Worst-case under-read of the oversampling grid at normalized frequency 0.45 is `20·log10(cos(π·0.45/L))`: 0.136 dB at L = 8 and 0.554 dB at L = 4 **[BS1770 Annex 2, Attachment]**. For fs ≥ 96 kHz, content near 0.45·fs (≥ 43 kHz) is negligible in music, and the musically relevant range (≤ 20 kHz, normalized ≤ 0.21) under-reads ≤ 0.12 dB at L = 4.

### 6.2 Interpolation filter [DESIGN]
A Kaiser-windowed sinc, linear phase, designed in `double` and stored in `float`:
- Half-length in input samples `Mh = 16` → total length `N = 2·Mh·L + 1`, center `c = Mh·L`.
- `h[n] = sinc((n − c)/L) · I0(β·sqrt(1 − ((n − c)/(Mh·L))²)) / I0(β)`, for `n = 0 … N−1`, with `sinc(x) = sin(πx)/(πx)`, `sinc(0) = 1`, and `β = 8.0`.
- Polyphase split: phase `φ ∈ [0, L)` uses taps `h[φ + L·k]`.
- **Phase 0 is set to exactly δ by construction:** tap `Mh` = 1.0, all other phase-0 taps = 0.0. Computed in floating point, `sin(πk)/(πk)` would be ~1e-17 rather than 0. So phase 0 reproduces the input samples bit-exactly, and **true peak ≥ sample peak always**; the test asserts it.
- Phases 1 … L−1 are each normalized to unit DC gain: `Σ_k h[φ + L·k] = 1`. This removes the per-phase DC gain error of the windowed sinc.
- Output: `x_up[m·L + φ] = Σ_k h[φ + L·k]·x[m − k + Mh]`. A latency of `Mh` input samples is accounted for, so no peak is lost at the end of the file: the input is padded with `Mh` zeros at the end and `Mh` zeros at the start.
- `I0` = modified Bessel function of the first kind, order 0, by its power series summed until the term is < 1e-17 × the sum.

### 6.3 Measure [STD]
`TP_ch = max_m |x_up,ch[m]|`, `TP = max_ch TP_ch`, and `truePeakDbtp = 20·log10(TP)` (absent if TP = 0). It is measured on the decoded float signal, before any gain, without attenuation. The 12.04 dB headroom of **[BS1770 Annex 2]** is unnecessary in floating point and is not applied.
The filter's passband ripple, under-read and overshoot must satisfy the **[T3341]** cases 15–23 tolerance `+0.2/−0.4 dB` (§26). If they don't, `Mh` or `β` is changed via a MATH.md version bump.
**Comparison note:** libebur128 uses a 49-tap Hann-windowed sinc at 4× below 96 kHz, 2× from 96 kHz up to 192 kHz, and **no oversampling at ≥ 192 kHz**. FFmpeg's true peak differs again. TP differences up to ≈ 0.3 dB on bright material are expected and explained by the filters.

---

## 7. Sample peak, RMS, crest factor, PLR [DESIGN]

- `samplePeakDbfs = 20·log10(max_{n,ch} |x|)` (absent if 0).
- `rmsGatedDbfs`: unweighted mean square over the samples of blocks in `J_g` (§4.1 of the whole track), averaged over channels in the power domain: `10·log10( Σ_{j∈J_g} Σ_ch Σ_{n∈block j} x² / (|J_g|·4H·C) )`. Overlapping blocks count samples up to 4×, consistently with the gating.
- **Crest factor** `CF = samplePeakDbfs − rmsGatedDbfs` (dB).
- **PLR** (peak-to-loudness ratio) `PLR = truePeakDbtp − L_I` (dB vs LU). This is the AES TD1004 convention. The anchor variant is `PLR_a = truePeakDbtp − L_anchor`.

---

## 8. Spectral analysis for features (analysis pass 1) [DESIGN]

### 8.1 Frames
- Mono-summed signal `u[n] = Σ_ch x_ch[n] / C` (unweighted). It is used only for spectral *shape* features, never for loudness.
- Frame `r` covers samples `[r·H, r·H + N_s)` with `N_s = 2^floor(log2(0.1·fs))`:

  | fs | N_s |
  |---|---|
  | 44.1 / 48 kHz | 4096 (≈ 93 / 85 ms) |
  | 88.2 / 96 kHz | 8192 |
  | 176.4 / 192 kHz | 16384 |
  | 22.05 / 24 kHz | 2048 |

  Frames whose window would extend past the end of the signal are zero-padded.
- Hop `H` (100 ms), so frames align with sub-blocks. Window: periodic Hann `w[n] = 0.5 − 0.5·cos(2πn/N_s)`.
- Power spectrum normalized per Parseval: `P[k] = 2·|X[k]|² / (N_s·Σ w²)` for `0 < k < N_s/2`.

### 8.2 One-third-octave bands
Band centers `f_b = 1000·2^(b/3)` for `b = −13 … 11` (50 Hz … 12.5 kHz, 25 bands). Edges at `f_b·2^(±1/6)`. `E_{r,b} = Σ_{k : edge_lo ≤ k·fs/N_s < edge_hi} P[k]`. Bands above `0.45·fs` are absent.
Octave bands for structure features (§13): `O_{r,o}` for centers 63, 125, 250, 500, 1k, 2k, 4k, 8k Hz, each the sum of its three 1/3-octave bands.

### 8.3 Spectral tilt
Over the gated frames `R_g` (frames whose sub-block lies in a gated block of §4.2): `Ē_b = mean_{r∈R_g} E_{r,b}` (power domain). Tilt = least-squares slope of `10·log10(Ē_b)` against `log2(f_b)` for bands 63 Hz … 8 kHz (b = −12 … 9, 22 bands), in **dB/octave**. Bands with `Ē_b ≤ 1e-20` are excluded; fewer than 6 bands → absent.

### 8.4 Spectral density
`D = (1/25)·|{ b : 10·log10(Ē_b) ≥ max_b 10·log10(Ē_b) − 30 }|`, a fraction in [0, 1] counting 1/3-octave bands within 30 dB of the strongest **[TUNE: 30 dB]**. Computed for the whole track (`D`) and the anchor (`D_a`, frames in anchor sections).

---

## 9. Transient density [DESIGN]

- K-weighted, channel-weighted power in 10 ms frames: `e_t = Σ_i G_i·Σ_{n∈frame t} y_i[n]² / N_10`, with `N_10 = round_half_up(fs/100)`.
- Log flux `d_t = max(0, 10·log10(e_t + ε) − 10·log10(e_{t−1} + ε))`, `ε = 1e-12`.
- Onset at `t` iff `d_t ≥ 6 dB` **[TUNE]**, `d_t` is a local maximum over `t ± 2`, frame loudness `−0.691 + 10·log10(e_t) ≥ L_I − 20`, and `t` is ≥ 5 frames (50 ms) after the previous onset.
- `ρ = onsets / (gated duration in s)`, where gated duration = `|J_g|·0.1 s` (each gated block contributes one hop). Unit: onsets per second.

---

## 10. Perceptual lens — reference model (host only) [STD]

The reference is the Moore–Glasberg–Schlittenlacher time-varying loudness model in its 2018 form (**[TVL18]**, the basis of ISO 532-3:2023), ported to C++ in `double` from the published description **[TVL16]** and the authors' public MATLAB reference **[TVL18-CODE]**. It is used to validate the fast model (§11) and is never shipped.
**[PROVISIONAL]** ISO 532-3:2023 itself could not be obtained, since it is paywalled. TVL18-CODE is the authors' implementation of the same method. Any change once the standard text is checked becomes a version bump.

### 10.1 Pipeline (per ear; channel 0 → left ear, channel 1 → right ear; mono → both)
1. Resample to `fs_m = 32 000 Hz` with the Sable resampler (§25.5: polyphase Kaiser-windowed sinc, half-length 32 input samples at the lower rate, `β = 10`, cutoff 0.95·min(fs, fs_m)/2). TVL18-CODE uses MATLAB `resample`; that difference is accepted and measured in §10.2.
2. Outer + middle ear: convolve with the free-field frontal FIR (4097 taps, `ff_32000`, centre tap index 2048) from TVL18-CODE **[DESIGN: free field]**. Music is mixed for loudspeakers or for free-field/diffuse-equalized headphones. The free-field condition is also the one ISO 226 contours are defined for, so it is the consistent choice.
   Trimming exactly as `SoundField2Cochlea`: full convolution of length `len + 4096`, keep samples `1025 … end−1024` (1-based). This leaves a 1024-sample lead-in, so that analysis segment `i` (samples `32i … 32i + 2047`, 0-based) is **centred on input time `t = i` ms**. Number of frames: `floor((len − 2048)/32) + 1`, with `len` the trimmed length.
3. Every 1 ms (32 samples), six Hann-windowed segments of 64, 32, 16, 8, 4, 2 ms, centred at the same instant, each zero-padded to 2048 points. `hann` is the MATLAB **symmetric** window `w[n] = 0.5 − 0.5·cos(2πn/(N−1))`, `n = 0 … N−1`, placed in the centre of the 2048 frame. FFT. Power per bin:
   `I_k = (2|X_k|/2048)² · 10^(3.32/10) · 2^(w−1) · 10^(D_cal/10)` (TVL18-CODE `SignalSegment2Spectrum`, with `w = 1 … 6` from 64 ms to 2 ms). Each window contributes bins only in its band: 20–80, 80–500, 500–1250, 1250–2540, 2540–4050, 4050–15000 Hz for 64, 32, 16, 8, 4, 2 ms respectively. The band-edge bin index is `floor(f/(32000/2048)) + 1` (1-based, as in the code). Each window's range is **inclusive at both ends**. Windows are written in order 64 ms → 2 ms, so a bin shared by two ranges takes the **shorter** window's value.
4. Keep components with `I_k > max(I_max, 1000) · 10^-6`, where **`I_max` is the maximum over both ears and all bins including DC** (TVL18-CODE `max(max(ICombinedFft))`). That means ≥ −30 dB SPL and within 60 dB of the binaural maximum. DC is discarded after the maximum is taken. Component levels `L_k = 10·log10(I_k)` dB SPL.
5. Excitation pattern at CFs `1.75 … 39 Cam` in steps of 0.25 (150 CFs) **[TVL16]**:
   - Cam ↔ Hz: `Cam(f) = 21.366·log10(0.004368·f + 1)`, `f(Cam) = (10^(Cam/21.366) − 1)/0.004368`
   - `ERB(f) = 24.673·(0.004368·f + 1)`, `p(f) = 4f/ERB(f)`
   - `W(p, g) = (1 + p|g|)·exp(−p|g|)`, `g = (f − f_c)/f_c`, and `W = 0` for `g > 4`
   - Input level per ERB at each component `k`, **exactly as `InputLevelPerERB.m`**, where each *source* component `i` spreads with its own filter: `X_k = 10·log10( Σ_i 10^(L_i/10) · W(p(f_i), g_ik)·[g_ik ≤ 4] )`, with `g_ik = (f_k − f_i)/f_i`. `g_ik` is signed in the `≤ 4` test and absolute inside W.
   - Lower slope: `p_l(f_c, X) = p(f_c) − 0.35·(p(f_c)/p(1000))·(X − 51)`
   - `E(f_c) = Σ_k 10^(L_k/10) · W(p_k, g_k)·[g_k ≤ 4]`, with `p_k = p(f_c)` if `f_k ≥ f_c`, else `p_l(f_c, X_k)`. Level `10·log10(E)`.
6. Specific loudness (binaural-model constant):
   - `C = 0.0631`
   - `E_THRQ(f)`: 28.18, 23.90, 19.20, 15.68, 12.67, 10.09, 8.08, 6.30, 5.30, 4.50, 3.63 dB at 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500 Hz. Linear interpolation in f (constant beyond the ends); 3.63 dB for f ≥ 500 Hz.
   - `G(f) = 10^(3.63/10) / 10^(E_THRQ(f)/10)`
   - `α(f)`: linear interpolation of `G_dB` → α over (−25, −20, −15, −10, −5, 0) → (0.26692, 0.25016, 0.23679, 0.22228, 0.21055, 0.20000)
   - `A(f)`: interpolation over the 90-point `G_dB → A` table of TVL18-CODE `get_A.m`, reproduced verbatim in `engine/src/perceptual_tables.inc` with attribution. The method is MATLAB `interp1(…,'cubic')`, which is **PCHIP**: Fritsch–Carlson monotone piecewise cubic Hermite, with MATLAB's three-point end-slope formula. Outside the table range MATLAB extrapolates with the end polynomial. That happens at CF 1.75 Cam, where `G_dB = −24.55 < −24.54531`. Sable implements exactly that: PCHIP with end-polynomial extrapolation. The Octave harness (§10.2) patches `get_A` to pass `'pchip','extrap'`, because Octave's `interp1` returns NA outside the range.
   - With `E` and `E_T = 10^(E_THRQ/10)` linear:
     - if `E_T < E < 1e10`: `N' = C·((G·E + A)^α − A^α)`
     - if `E ≤ E_T`: `N' = C·(2E/(E + E_T))^1.5 · ((G·E + A)^α − A^α)`
     - if `E ≥ 1e10`: `N' = C·(E/1.0707)^0.2`
7. Short-term specific loudness: AGC per CF with attack `a_A = 0.045` and release `a_R = 0.033` per 1 ms frame **[TVL18]**:
   `S'_n = a·S_n + (1 − a)·S'_{n−1}`, with `a = a_A` if `S_n > S'_{n−1}`, else `a_R`, and `S'_{−1} = 0`.
   (TVL16 used `a_R = 0.02`. TVL18 revised it to 0.033.)
8. Binaural inhibition **[TVL16 eqs. 5–6, TVL18-CODE]**:
   - Smoothing kernel `W_b(Δ) = exp(−(0.08·Δ)²)` for `Δ = −18 … 18` Cam in 0.25 steps, normalized by `Σ W_b`, convolved over the 150-CF pattern (zero outside 1.75 … 39 Cam). Then `+1e-13`.
   - `INH_L = 2/(1 + sech(N'_R,s/N'_L,s)^1.5978)`, and symmetrically for the right ear.
   - `N'_L,inh = N'_L/INH_L`
9. Short-term loudness per ear: `STL_e = Σ_CF N'_e,inh / 4` (0.25 Cam spacing). Binaural `STL = STL_L + STL_R`.
10. Long-term loudness per ear: AGC on `STL_e` with `a_A = 0.01`, `a_R = 0.00133` per 1 ms **[TVL18]**. `LTL = LTL_L + LTL_R`.
11. Outputs at 1 ms are reduced to the 10 Hz analysis grid by averaging in sones. `STL_m` and `LTL_m` are the means over the frames `n` whose time `t = n ms` lies in sub-block `m`'s interval `[0.1m, 0.1(m+1))` s. Every complete sub-block contains frames.

### 10.2 Reference validation [§26]
- A diotic 1 kHz steady tone at 40 dB SPL gives `max LTL = 1.00 ± 0.02` sone (the model's definition of C) **[TVL16]**.
- Our C++ reference vs TVL18-CODE run in GNU Octave (test-only dependency) on 12 signals (tones, noises, AM tones, 3 music excerpts): `|ΔLTL|/LTL ≤ 1%` frame-wise after the first 100 ms. Any difference must be explained (e.g. MATLAB `resample` vs our resampler).

---

## 11. Perceptual lens — fast model (ships on the phone) [DESIGN]

The same model as §10, with these approximations, chosen for the A17 budget (§11.3).

### 11.0 Intensity-scale invariant
For a stationary signal, the fast model's total component intensity should approximately equal the reference's. For a sine whose lobe lies inside one window's band, the reference (§10.1 step 3) sums to `2·(3/8)·10^0.332·MS = 1.61087·MS` for mean square MS, because of its amplitude-squared bins and empirical 3.32 dB Hann correction. The fast model's Parseval-normalized power sums to `MS`, so it carries `κ = 0.75·10^0.332 = 1.61087`.

The equality is **approximate**. The reference's band-assembled total varies with frequency, because main lobes leak across band edges: +0.51 dB at 50 Hz, −0.07 dB at 8 kHz. So residual low-frequency differences remain, and they are measured.

In the fast model each window's band is **half-open** `[f_lo, f_hi)` in bin-centre frequency, except the top band, which includes 15 000 Hz.

**Test:** total intensity, fast vs reference:
- white noise and sines at 1, 3, 8 kHz: ≤ 0.1 dB
- sines at 100, 200, 500, 2540 Hz: ≤ 0.25 dB
- 50 Hz: ≤ 0.6 dB

Without κ the fast model would read 2.07 dB quieter, a 12–15% loudness error.

| # | Reference (§10) | Fast model |
|---|---|---|
| F1 | 1 ms frame | **8 ms** frame (measured choice, §11.2). AGC coefficients converted per frame: `a_8 = 1 − (1 − a_1)^8`. This is exact for a constant input across the 8 steps. |
| F2 | Ear FIR convolution in time | Ear transfer applied as `|H_ff(f)|²` weighting of bin powers, using a 1/12-octave magnitude table derived from `ff_32000` (`engine/src/perceptual_tables.inc`). |
| F3 | 6 windows (64…2 ms), each zero-padded to 2048 | 3 windows, unpadded: 64 ms for 20–500 Hz (computed on a 4× decimated 8 kHz signal, 512-pt FFT), 16 ms for 500–2540 Hz (512-pt at 32 kHz), 4 ms for 2540–15000 Hz (128-pt at 32 kHz). Windows are symmetric Hann like the reference. Bin power is **scaled to match the reference's total intensity** (§11.0): `P_k = κ · 2|X_k|²/(N·Σw²) · 10^(D_cal/10)`, with `κ = 0.75·10^0.332 = 1.61087` (+2.07 dB). |
| F4 | Every FFT bin is a component | Bin powers are summed into 150 component bands of 0.25 Cam (edges at ±0.125 Cam around 1.75 … 39 Cam). Each component sits at its band centre. The same −30 dB SPL / −60 dB-re-max pruning applies. |
| F5 | Excitation at 150 CFs (0.25 Cam) | Excitation at 75 CFs (0.5 Cam: 1.75, 2.25, …, 38.75). `W` for the upper side precomputed per (component, CF). For the lower side, `W` is tabulated on a 1 dB grid of `X` in [−30, 130] dB, with linear interpolation. |
| F6 | Binaural kernel at 0.25 Cam | Same kernel sampled at 0.5 Cam (Δ = −18 … 18 step 0.5), normalized by its own sum. |
| F7 | `STL_e = Σ/4` | `STL_e = Σ·0.5` (Cam spacing) |

### 11.1 Error budget [DESIGN]
Measured on the full test corpus (§26) for the quantity actually used, the anchor PLE (§12, §14):
- mean `|PLE_fast − PLE_ref| ≤ 0.3 LU`, max ≤ 0.75 LU
- for track-level mean LTL: mean relative error ≤ 3%, max ≤ 8%

If a target is missed, the resolution is increased in this order until it passes: F1 → 2 ms, F5 → 0.25 Cam, F3 → 6 windows. The final configuration and the measured errors are recorded in a "Measured" table appended here, with a version bump.

### 11.2 Measured (2026-10-26, `tools/perceptual/validate_reference.py`)
**Chosen configuration:** F1 = **8 ms** frames (`a_8 = 1 − (1 − a_1)^8`). F3, F5, F6 and F7 are as tabulated. The frame was coarsened from the planned 4 ms after measurement: on the A17 big core it cuts full analysis from 10.8 s to 6.9 s, and the accuracy below stays inside the §11.1 budget.

| Check (12 signals at 32 kHz: tones, noise, AM, dichotic, 3 music excerpts) | Result | Budget |
|---|---|---|
| C++ reference (§10) vs authors' TVL18 MATLAB code in GNU Octave, LTL frame-wise after 100 ms | **max relative error 0.000000** on every signal | ≤ 1% |
| Fast vs reference, song-level mean LTL | worst **2.40%** (100 Hz tone), average 0.81% | ≤ 3% mean / 8% max |
| Fast vs reference, frame-wise LTL | worst **6.4%** (quiet piano), frame-mean ≤ 2.5% | – |
| 1 kHz diotic tone at 40 dB SPL | reference 1.00005 sone; fast 0.99995 | 1 sone by definition |
| PLE read-back of an independent pink tone complex | −29.97 (for −30), −13.92 (for −14) | ≤ 0.05 LU on the table's own noise; ±0.5 otherwise |

A 2.4% loudness error corresponds to ≈ 0.34 LU (loudness doubles per ~10 dB). The anchor-PLE error on full songs is measured on the corpus in the M3 corpus report.

### 11.3 Cost budget
The analysis budget (§22.3) is for the whole of pass 1 + pass 2 + decode. The fast model is expected to use ≤ 50% of it on the A78 cores. Milestone 3 reports measured numbers.

---

## 12. Perceptual calibration and the LUFS-equivalent scale (PLE) [DESIGN]

Loudness in sones depends on the absolute playback level, which the phone cannot know. Sable fixes a nominal listening calibration and maps perceptual results onto the familiar LUFS scale.

### 12.1 Nominal calibration
`D_cal = 89.0 dB SPL` = the RMS sound pressure level of a full-scale sinusoid (peak 1.0), per ear. This is the model's `dBMax` **[TVL18-CODE]** **[TUNE]**.
Rationale: a 1 kHz tone at −14 dBFS peak in both channels reads −14 LUFS, and its RMS SPL is 89 − 14 = 75 dB SPL. So content normalized to the Standard target is modeled as playing at ≈ 75 dB SPL, a typical comfortable level for music on headphones and near-field speakers. Output-specific calibration offsets (§20) shift this per output.

### 12.2 Pink-noise reference curve
`N_pink(x)`: the mean LTL (sones, after the first 2 s) of **diotic** pink noise (identical L/R, −3 dB/octave, band-limited 20 Hz–15 kHz, 30 s, deterministic generator, seed 0x5AB1E) scaled to `x` LUFS, under `D_cal`. Tabulated for `x = −70 … +3` LUFS in 0.5 LU steps, separately for the reference and the fast model. Generated by `tools/gen_pink_table` and committed as data with its generator.
`N_pink` is strictly increasing. The test asserts it.

### 12.3 PLE
For a perceptual loudness value `N` (sones): `PLE(N) = N_pink^{-1}(N) + c_m`, with the **music-centring offset `c_m = +0.65 LU`** **[TUNE]**: the median of `PLE_pink(N_anchor) − L_anchor` over the 52-track M3 corpus. With it, typical music lands where BS.1770 puts it, and the perceptual lens only moves songs that genuinely differ (deep bass, sparse or dense spectra). (MATH-1.7: before centring, using the *mean* LTL, all music read 3.8 LU below pink noise; see §14.7.) Inversion uses monotone piecewise-linear interpolation in `(x, log N)`. Outside the table it is clamped to the end values and flagged.

### 12.4 Prescaled analysis (level dependence)
Because loudness is level-dependent, pass 2 runs on the signal **prescaled** by `s = T_std − L_I` dB (`T_std = −14`, §16). The integrated loudness is ≈ −14 LUFS during perceptual analysis. That places the analysis near the level where Standard mode plays it. Unscaled results: `PLE_unscaled = PLE(N) − s`.
**Refinement:** let `g*` = the **Standard-mode `g_anchor`** (§16.2). It is taken before intimacy, the peak cap, learning and calibration, and is always computed with `T_std`, whatever the current mode. If `|g* − s| > 6 dB`, pass 2 is re-run once with `s' = g*` (background only, never in JIT), and the result replaces the first. One refinement at most; the iteration is not continued.
*Measured (MATH-1.8):* on the 22 corpus tracks where `|g* − s|` was 2.1–4.3 dB, re-running changed `PLE_anchor` by a median 0.021 LU and at most 0.109 LU, which is negligible. The trigger was therefore raised from 2 dB to 6 dB, beyond every corpus case. That removes a second perceptual pass costing ≈ 4 s on the A17.
If `L_I` is absent (silence), pass 2 is skipped entirely and every PLE is absent. `s` is always based on `L_I`, never on `L_anchor`, which can be absent even when `L_I` exists (§16.2).
**Quiet mode** (§16.1) reuses the Standard-mode PLE. Its −5 dB level change is not re-evaluated perceptually (the optional contour, §24, handles spectral balance at low level). This approximation is documented in the Why sheet's technical details.

---

## 13. Structure lens [DESIGN]

### 13.1 Feature frames
- Frame length `T_fr = 0.5 s × max(1, ceil(duration/900 s))`, which caps the number of frames at about 1800. The hop equals `T_fr`, made of `F = T_fr/0.1` consecutive sub-blocks.
- Per frame `r`:
  - `x_L = clamp(−0.691 + 10·log10(Σ_i G_i·Σ_{m∈r} q_{i,m}/(F·H)), −70, 0)` — loudness.
  - `x_o = 10·log10(O_{r,o}/Σ_o O_{r,o})` for the 8 octave bands — spectral shape relative to the frame total. Clamped to ≥ −60.
- Silent frames (§13.4.2 definition) are excluded from the normalization statistics and from σ² (§13.2).
- Normalization: each dimension is z-scored over non-silent frames (population SD; dimensions with SD < 1e-9 become 0). Weights: `w_L = 1`, `w_o = 1/sqrt(8)`, so loudness and spectral shape weigh equally.

### 13.2 Self-similarity matrix
`S(a, b) = exp(−‖x̃_a − x̃_b‖²/(2σ²))`, with `σ² = median` of `‖x̃_a − x̃_b‖²` over all pairs `a < b` of non-silent frames (lower median for even counts). If σ² = 0, then σ² = 1.

### 13.3 Novelty
Foote checkerboard kernel, half-size `K = round(8 s / T_fr)` frames:
`k(a, b) = sgn(a)·sgn(b)·γ(a)·γ(b)` for `a, b ∈ {−K, …, K−1}`, with `sgn(a) = +1` for `a ≥ 0` and −1 otherwise, and `γ(a) = exp(−½·((a + 0.5)/(K/2))²)`.
`ν(r) = Σ_{a,b} k(a, b)·S(r + a, r + b)` with `S = 0` outside the matrix. Then `ν ← max(ν, 0)/max_r ν` (if the max is 0, there are no novelty boundaries).

### 13.4 Boundaries
1. Novelty peaks: `r` with `ν(r) = max ν(r ± 4 frames)`, `ν(r) ≥ max(0.1, mean(ν) + 0.5·sd(ν))` **[TUNE]**. Then greedy selection by height (ties: the earlier frame) with minimum separation `round(8 s/T_fr)` frames. Novelty boundaries strictly inside a fade interval (§13.5) are dropped.
2. Silence edges: every start or end of a run of ≥ 2 s of silent sub-blocks. **Silent sub-block** (the single silence definition used everywhere in §13–§18): sub-block `m` is silent iff the loudness of the 400 ms window of sub-blocks `m−2 … m+1` (centred on the *start* of `m`, half a sub-block early, which is accepted), clipped to the signal and computed like §3.2 over the available sub-blocks — is absent or `< Θ_sil = max(−60, L_I − 35)` LUFS. If `L_I` is absent, `Θ_sil = −60`. §13.1's frame exclusion uses the same test: a frame is silent iff all its sub-blocks are silent.
3. Fade edges (§13.5).
4. Sections = intervals between sorted unique boundaries, plus 0 and the end. Before merging, each section is typed:
   - **silence**: ≥ 90% silent sub-blocks; adjacent silence sections coalesce into one
   - **fade**: equal to a §13.5 interval
   - non-silent: everything else

   *Mean-feature distance* between two sections = the Euclidean distance between their mean normalized feature vectors `x̃` (§13.1), over non-silent frames. **Merging**, repeated until no candidate remains:
   - a candidate is a non-silent, non-fade section shorter than 8 s
   - pick the **shortest** candidate (ties: the earliest)
   - merge it into the adjacent non-fade, non-silent neighbour with the smaller mean-feature distance (ties: the earlier neighbour)
   - if it has no such neighbour, it stays as it is and is no longer a candidate

   Fade intervals are never merged.

### 13.5 Fade detection
On `S_k` (10 Hz):
Fade detection uses a symmetric **1 s loudness series** `F_k`: 10 consecutive sub-blocks `k … k+9`, computed like §3.4 and stamped at the window **centre** `(k + 5)·H/fs`. This makes fade-in and fade-out mirror images. Values exist from 0.5 s after the start to 0.5 s before the end.
- `F(t)` = the value with the nearest stamp.
- `t_first` / `t_last` = the start of the first / end of the last non-silent sub-block (§13.4.2), clamped to the available stamps.
- Absent values are treated as −70 LUFS.

In the fade-out rule below, read `S` as `F`.
- **Fade-out:** a candidate start `t_f` *passes* if, over the `S` values with timestamps in `[t_f, t_last]`:
  - the least-squares slope is ≤ −1.5 LU/s **[TUNE]**
  - **the decline has started at `t_f`**: the slope over `[t_f, t_f + 2 s]` is ≤ −0.75 LU/s **[TUNE]**. Without this, a steep fade could pull a flat stretch of body into the fade (found by the M3 synthetic test, MATH-1.6).
  - the drop `S(t_f) − S(t_last) ≥ 10 LU`
  - `t_last − t_f ≥ 4 s`

  Scan `t_f = t_last − 4.0, t_last − 4.5, t_last − 5.0, …` (backwards in 0.5 s steps, never before the first `S` value). **Stop at the first candidate that fails**, after at least one has passed, and take the **last passing** (earliest) `t_f`. If the very first candidate fails, scanning continues until one passes or the start of the track is reached. If none passes, there is no fade-out.
- **Fade-in:** the mirror image at the start. Scan `t_f = t_first + 4.0, +4.5, …` forwards with the conditions slope ≥ +1.5 LU/s, slope over `[t_f − 2 s, t_f]` ≥ +0.75 LU/s, rise `F(t_f) − F(t_first) ≥ 10 LU` and `t_f − t_first ≥ 4 s`, and the same stop rule.

### 13.6 Section types (assigned in this order)
1. **silence**: ≥ 90% of its sub-blocks are silent (§13.4).
2. **fade-in / fade-out**: the intervals found in §13.5.
3. **intro**: the first non-silent, non-fade section, if its duration ≤ 25% of the non-silent duration and its loudness `L_s ≤ L_I − 3 LU` **[TUNE]**.
4. **outro**: the last non-silent, non-fade section, under the same conditions.
5. **build**: a section ≥ 8 s whose `S` slope is ≥ +0.3 LU/s with a total rise ≥ 4 LU **[TUNE]**, and whose following section is at least as loud as the build's last 3 s.
6. **body**: everything else.
7. **breakdown** (re-labels a body section after §14): an interior body section with `L_s ≤ L_anchor − 6 LU` and duration ≥ 8 s.

Section loudness `L_s` is §4.3 over the section.

---

## 14. Anchor [DESIGN]

The anchor is the sustained part the song is built around. Brief spikes, silence, intros, outros and fades are excluded by construction.

1. Candidates `Q` = **body** sections (§13.6) with duration ≥ 8 s. Silence, fades, intro, outro and builds are excluded.
2. `D_ns` = the non-silent duration. Required anchor duration `D_req = min(D_ns, max(20 s, 0.2·D_ns))`.
3. `L_top = max_{s∈Q} L_s`.
4. For `δ = 3, 4, 5, 6` LU **[TUNE: start 3, step 1, max 6]**: `A = { s ∈ Q : L_s ≥ L_top − δ }`. Stop at the first `δ` where `Σ_{s∈A} dur(s) ≥ D_req`.
5. If no `δ ≤ 6` satisfies this, or `Q = ∅`, or `D_ns < 20 s`: **fallback anchor** = all non-silent, non-fade sections (or the whole track if that is empty), flagged `anchorFallback = true`.
6. **Anchor loudness** `L_anchor` = §4.3 over the anchor intervals (energy-domain, gated within the set).
7. **Perceptual anchor**: `N_anchor` = the **95th percentile** (nearest rank, `idx = floor(0.95·(n−1) + 0.5)` of the ascending values) of `LTL_m` (sones) over sub-blocks `m` such that `m` lies in an anchor interval, **block `m` exists, `m ∈ J_g`** (the anchor's gated block set from step 6), and `LTL_m` is defined. If that set is empty, `N_anchor` is absent. *Why the 95th percentile (MATH-1.7):* sones are compressive, so the mean penalizes fluctuating material (speech, solo piano) relative to steady noise. Moore et al. predict overall loudness from the high values of long-term loudness, not its mean. On the 52-track corpus the mean gave median `PLE − L_anchor` = −3.76 LU (SD 3.72), and the 95th percentile gave −0.65 LU (SD 3.00). Then `PLE_anchor = PLE(N_anchor) − s` (§12.4).
8. **Anchor position** (for display): the anchor intervals plus `t_center` = the duration-weighted center of the anchor.

---

## 15. Character labels [DESIGN]

Descriptive and explainable, with one deterministic rule table. Rules are evaluated in order; the **first match** wins. Only the **quiet and intimate** label affects gain (§16.3). The others explain decisions and drive the UI copy.

| Order | Label | Condition (all must hold) |
|---|---|---|
| 1 | quiet and intimate | `L_anchor ≤ −20 LUFS` and `PLR_a ≥ 12 dB` and (`D_a ≤ 0.6` or `ρ ≤ 1.5 /s`) |
| 2 | build and release | (≥ 2 build sections) or (build sections ≥ 10% of the non-silent duration) |
| 3 | dynamic arc | `LRA ≥ 9 LU` or section contrast `C_s ≥ 9 LU` |
| 4 | sustained and dense | `LRA ≤ 6 LU` and `PLR_a ≤ 11 dB` and `D_a ≥ 0.7` |
| 5 | steady | otherwise |

All thresholds are **[TUNE]**.
- Section contrast `C_s = max_s L_s − min_s L_s` over non-silent, non-fade sections ≥ 8 s. If fewer than 2 such sections exist, `C_s = 0`.
- Rationale for rule 1: the mastering level is an artistic choice. A quiet master that is also uncompressed and sparse is the signature of an intimate recording (solo piano, whispered vocal), and that intent is what we preserve.

Absent inputs use three-valued logic (Kleene): a comparison with an absent value is *unknown*. `unknown AND false = false`, `unknown AND true = unknown`, `unknown OR true = true`, `unknown OR false = unknown`. A rule matches only if its condition is *true*; *unknown* does not match.

---

## 16. Gain decision — track mode [DESIGN]

All gains are in dB, computed in `double`.

### 16.1 Targets
`T = T_std = −14.0` (Standard mode) or `T = T_quiet = −19.0` (Quiet mode; owner decision 2026-10-26, was −18 in the original spec), on the PLE (LUFS-equivalent) scale.

### 16.2 Anchor match
1. `g_anchor = T − PLE_anchor`
2. **Perceptual deviation bound** (protects against model failure on unusual signals): `PLE_anchor` is clamped to `[L_anchor − B_p, L_anchor + B_p]` before step 1, with `B_p = 3.0 LU` **[TUNE]** (was 4.0 before MATH-1.7: "change less"). Rule `PERCEPTUAL_BOUND` fires if the clamp is active.
3. If `PLE_anchor` is absent (the perceptual pass did not complete), `L_anchor` is used. Rule `BS1770_ONLY` fires. If `L_anchor` is also absent while `L_I` exists (e.g. the anchor blocks are all gated out), `L_I` is used and rule `ANCHOR_FALLBACK` fires.

### 16.3 Intimacy allowance
If the label is **quiet and intimate**: `g_intent = g_anchor − a_int`. Otherwise `g_intent = g_anchor`.
`a_int` is a user setting in [0, 2.0] LU, default **1.0** **[DESIGN, documented bound]**. Rule `INTIMACY` fires when `a_int > 0`.

### 16.4 Peak integrity
`g_cap = C_out − TP_eff`, where `C_out` is the output ceiling (§20.1) and `TP_eff = truePeakDbtp + (decodeClipped ? M_clipTP : 0) + H_contour` (`H_contour` §24.4; 0 when the contour is off).
`g_track = min(g_intent, g_cap)`. Rule `PEAK_CAP` fires if `g_cap < g_intent`, and the Why sheet reports how much quieter the song is because of it (`g_intent − g_cap`).
If TP is absent (digital silence), `g_cap = +∞` and the rule never fires.
With the opt-in limiter (§23.5), the cap becomes `g_cap_lim = g_cap + 6.0` (at most 6 dB of limiting **[DESIGN]**), and `g_track = min(g_intent, g_cap_lim)`. Rule `LIMITER` fires only if `g_intent > g_cap`, i.e. only when the limiter actually has work to do. `PEAK_CAP` then also fires only if `g_intent > g_cap_lim`. §17, §18 and §21 use `g_cap_eff` as the effective cap: `g_cap_lim` with the limiter, `g_cap` without.

### 16.5 Formal statement
Track mode solves: `g_track = argmin_g |g − g_intent|  s.t.  g ≤ g_cap`. The solution is `min(g_intent, g_cap)`.

### 16.6 Undefined / short cases
- Integrated loudness absent (silence): `g_track = min(0, g_cap)`. Rule `UNDEFINED_LOUDNESS`.
- `shortFile` (§4.5): anchor = whole file, `L_anchor = PLE_anchor = L_short`. The usual §16.2–16.4 apply. Rule `SHORT_FILE`.

---

## 17. Album mode [DESIGN]

### 17.1 When it applies (setting Auto / Always track / Always album)
- **Always track**: never.
- **Always album**: whenever the track belongs to an album (album id + album artist), even in shuffle.
- **Auto** (default) **[DESIGN, owner decision 2026-10-26]**: the listener's *intent* decides, not queue adjacency. Each queue item records how it was queued:
  - `ALBUM` intent: queued by **Play album** (shuffle off) on an album. Every item of that album placed by that action uses **album gain**. An *album run* = the maximal run of consecutive `ALBUM`-intent items of the same album.
  - `SONG` intent: queued by **tapping a song** (from any screen, including inside an album), by shuffle, from a playlist, or into a mixed queue. The tapped song and every item that follows it are balanced **in their own world**: track mode (§16) plus seam smoothing (§18), even if they are the same album's next tracks in order.
  - Items added later inherit the intent of the action that added them.
- Album gain needs **every track of that album in the library** to be fully analysed. Until then the run uses track mode, and the missing tracks are queued for analysis at high priority. Rule `ALBUM_PENDING`.

### 17.2 Album gain
- Album members `𝒜` = the album's tracks with a defined `L_I` that are **not** `shortFile` (§4.5). Short files and silent tracks in an album run use album gain but do not contribute to it. If `𝒜` is empty, the run uses track mode.
- `L_album_anchor = Integrated(⋃_{t∈𝒜} anchorBlocks_t)`: §4.1 over the pooled anchor blocks.
- `PLE_album = 10·log10( Σ_{t∈𝒜} w_t·10^(P_t/10) / Σ_t w_t )`, with `w_t` = anchor duration of track `t`, and `P_t = PLE_anchor,t` after its §16.2 clamp; `L_anchor,t` if the PLE is absent; `L_I,t` if both are absent. This is energy-domain pooling of LUFS-equivalent values (§0.2).
- Perceptual bound as in §16.2, relative to `L_album_anchor`.
- `g_album_intent = T − PLE_album − (a_int if ≥ 50% of Σ w_t comes from tracks labelled quiet and intimate, else 0)`.
- `g_album = min(g_album_intent, g_album_cap)`, with `g_album_cap = C_out − max_t TP_eff,t`, plus 6.0 dB when the limiter is on (as in §16.4). One gain for the whole album, so the artist's contrast between songs is preserved exactly.
- No seam smoothing inside an album run (§18).

---

## 18. Seam smoothing [DESIGN]

Applies only to adjacent queue items `(prev → cur)` that are **not** in the same album run.

- `L_end(prev)` = the short-term loudness of the last 3 s of `prev`'s non-silent audio. It is `S_k` for the window ending at the last non-silent sub-block, or the energy mean over what exists if the non-silent audio is shorter than 3 s.
- `L_open(cur)` = the short-term loudness of the first 3 s of `cur`'s non-silent audio. Both use the BS.1770 short-term values plus each track's **PLE offset** `(PLE_anchor − L_anchor)` (clamped as in §16.2), so seams are compared on the same perceptual scale as anchors.
- `g_prev` = `prev`'s **`g_seamed`** (§21 step 2): its base/seam gain, *before* the learning offset and the output calibration. Seams are solved in the "engine" domain. The user offsets `o` and `c_out` are added afterwards to both tracks alike: `c_out` is the same for both, and `o` is a deliberate per-track preference the seam must not undo. So they are not counted twice.

Here `g_cap,cur` means the **effective** cap `g_cap_eff` (= `g_cap_lim` when the limiter is on, §16.4). If either term of a track's PLE offset is absent, that offset is 0.

**Problem:** `g_cur = argmin_g |(L_open + g) − (L_end + g_prev)|  s.t.  |g − g_track,cur| ≤ 0.5 dB,  g ≤ g_cap,cur`.
**Solution:** `g_cur = min( clamp(L_end + g_prev − L_open, g_track,cur − 0.5, g_track,cur + 0.5), g_cap,cur )`.

**Skip** (`g_cur = g_track,cur`) if any of these hold:
- `prev` ends in a fade-out or `cur` starts with a fade-in
- `L_end + g_prev < −40` or `L_open + g_track,cur < −40` (both **after** gain, in the engine domain). The transition passes through near-silence, so there is no perceptible jump.
- the user skipped into `cur` manually rather than via a natural transition
- `prev` is absent.

Because every `g_cur` stays within ±0.5 dB of its own `g_track`, errors cannot accumulate along a long queue. Rule `SEAM` fires with the applied delta.

---

## 19. Listener learning [DESIGN]

### 19.1 Events
A volume-key event counts as feedback when **all** of these hold:
- it happens within the first `t_w = 20 s` of a track that started by a natural transition or a user tap
- it is not reversed within 3 s (a press immediately undone cancels)
- the output route did not change in the previous 10 s
- A/B mode is off.

`Δv` = the change of the media stream's output level in dB, from `AudioManager.getStreamVolumeDb()` before and after (negative = turned down). Presses within one 20 s window accumulate into a single event.
**Environment filter:** if the user's last feedback event (on the previous track) had the same sign and similar size (|ratio| ∈ [0.5, 2]), this event is treated as a change of environment and weighted `ω = 0.25`. Otherwise `ω = 1`.

### 19.2 Update (exponential decay + clamp)
State per track `o_t` and per artist `o_a` (dB), each with a timestamp `τ`.
Decay to now: `o ← o·2^(−(now − τ)/H_life)`, `H_life = 30 days`.
On an event: `o_t ← clamp(o_t + η_t·ω·Δv, −2, +2)` and `o_a ← clamp(o_a + η_a·ω·Δv, −2, +2)`, with `η_t = 0.5`, `η_a = 0.25`, then `τ ← now` **[TUNE: η, H_life]**.
Applied offset: `o = clamp(o_t + (n_a ≥ 2 ? o_a : 0), −2, +2)`, where `n_a` = the number of distinct tracks by that artist that have contributed events.
Offsets are visible on the Why sheet and resettable (per track, per artist, all).

---

## 20. Output awareness [DESIGN]

### 20.1 Ceilings `C_out` (dBTP)
| Output | Default ceiling | Why |
|---|---|---|
| Phone speaker | −1.0 | **[R128]** max true peak −1 dBTP; the local DAC path |
| USB-C (digital or adapter) | −1.0 | as above |
| Wired (if ever present) | −1.0 | as above |
| Bluetooth A2DP, codec AAC / aptX / LDAC / LC3 | −1.5 | Lossy re-encoding can create new overs (inter-sample peaks after the codec's band-limiting and quantization). Lower ceilings for lossy chains are common practice; EBU Tech 3344 discusses this, but its exact figures have **not been verified** by us (the text was not consulted). The values here are our conservative choice **[TUNE]**. |
| Bluetooth, SBC or codec unknown | −2.0 | SBC at typical bitpools is the least transparent |

The codec comes from `BluetoothCodecStatus` when the platform exposes it; otherwise "unknown". These ceilings are conservative defaults **[TUNE]**, user-overridable in [−3, −0.5].

### 20.2 Calibration offset
`c_out` ∈ [−3, +3] dB per output route (default 0), set by the user. It represents the perceived level difference between outputs. It shifts the gain and is reported on the Why sheet.

---

## 21. Final gain composition (order of operations) [DESIGN]

For the item about to play:
1. `g_base` = album gain (§17) if in an album run, else `g_track` (§16).
2. If not album: `g_seamed` = §18. Otherwise `g_seamed = g_base`.
3. `g_user = g_seamed + o (§19) + c_out (§20.2)`.
4. `g_final = min(g_user, g_cap_eff)`, where `g_cap_eff` is the album or track cap (§16.4, §17.2), so learning and calibration can never break peak integrity.
5. `gainLin = (float) 10^(g_final/20)`, computed in `double` and then rounded once to `float`.

The Why sheet shows `g_track`, `g_final` and every rule that fired, with its delta in dB.

---

## 22. Just-in-time analysis and provisional gain [DESIGN]

### 22.1 Provisional gain
Never play un-normalized audio at full level. Before any analysis exists, `g_prov = T + 8.0` dB, i.e. assume the track is a loud master at −8 LUFS: Standard −6 dB, Quiet −11 dB **[TUNE]**.

### 22.2 JIT analysis
Started when a never-analysed track is about to play (queue look-ahead: the next item is pre-analysed while the current one plays):
- Excerpts: `[0, 10 s)` and three 15 s windows centred at 30%, 50% and 70% of the duration (clipped and merged). Decoded by seeking.
- Measures (§2–§6) on each excerpt. `L_jit` = the loudest middle-excerpt integrated loudness (§4.1 per excerpt). `TP_jit` = max over excerpts + `M_jitTP = 1.0 dB` (peaks not yet seen) **[TUNE]**.
- `g_jit = min(T − L_jit, C_out − TP_jit)`. No perceptual model, no labels, no intimacy. Rule `JIT`.
- Wait budget: playback waits up to `W_jit = 1.5 s` for JIT. If it isn't ready, playback starts at `g_prov` and moves to `g_jit` when ready (§23.3).
- The full analysis runs in the background. When it finishes, the gain moves to the full decision (§23.3).

### 22.3 Analysis budget
Full analysis of a 4-minute 44.1 kHz stereo FLAC in ≤ 10 s on the A17 (2× A78), measured and reported in Milestone 2 (baseline) and Milestone 3 (with the perceptual model).

---

## 23. Applying gain in playback [DESIGN]

### 23.1 Processor
A custom Media3 `AudioProcessor` multiplies every sample by `gainLin` (float). It does no allocation, takes no locks and makes no blocking calls; it reads the target gain from a lock-free single-producer/single-consumer slot.

### 23.2 Track boundaries
Each media item's first output frame is identified by its presentation timestamp. The new item's gain applies exactly from its first sample: a **step**, not a ramp.
- In album runs, consecutive items have the same gain, so the step is 0.
- Between different songs, a step applied exactly at the first sample of a new item adds a discontinuity of `(g₂ − g₁)·x[0]` only if the next track's first sample is non-zero. Songs almost always begin with near-zero samples, and any step there is masked by the track change itself. This is preferred over ramping into the new song, which would alter its attack.

### 23.3 In-track changes
For gain changes that happen while a track is playing (JIT → full analysis, provisional → JIT, setting changes; learning offsets never change mid-track, §28):
- Rate-limited changes (analysis updates): the gain moves in dB along a raised-cosine curve over `T = max(0.5 s, (π/2)·|Δ| s)`. The curve's peak slope is `(π/2)·|Δ|/T`, which is ≤ 1 dB/s whenever `T ≥ (π/2)|Δ|`.
- User-initiated changes (mode switch, A/B switch): a 20 ms raised-cosine crossfade in linear amplitude: `g(n) = g₀ + (g₁ − g₀)·(1 − cos(π·n/N))/2`, `N = round(0.020·fs)`.

The ramp is per sample, so there is no zipper noise.

### 23.4 Float path and dither
- The output is float (`AudioFormat.ENCODING_PCM_FLOAT`) where the device and Media3 support it.
- If the output must be 16-bit, the processor adds **TPDF dither** right before quantization: `d = (r₁ + r₂ − 1)·LSB`, where `r₁, r₂ ~ U[0, 1)` from a xorshift128+ PRNG and `LSB = 1/32768`. Then `q = round_half_even(clamp((x + d)·32768, −32768, 32767))`. No noise shaping.
- Clamp at quantization is the only clipping anywhere. The peak cap keeps it unreachable except under the opt-in limiter.

### 23.5 Opt-in true-peak limiter
Off by default. When on: 5 ms look-ahead, peak detection on an oversampled sidechain using the **same factor and filter as §6** (L = 8 below 96 kHz). A coarser sidechain could under-read by 0.55 dB and let peaks exceed the ceiling. gain computer `min(1, 10^(C_out/20)/peak)`, attack = instant within the look-ahead (raised-cosine over 5 ms), release time constant 100 ms. Its maximum gain reduction is bounded by §16.4.

---

## 24. Quiet-mode loudness contour (opt-in) [STD + DESIGN]

The only non-flat processing, and only when enabled in Quiet mode.

### 24.1 Equal-loudness contours [ISO226] [PROVISIONAL]
`L_p(f, L_N)` (dB SPL of a pure tone at frequency `f` with loudness level `L_N` phon), using the **ISO 226:2003** formula and tables:
`A_f = 4.47·10⁻³·(10^(0.025·L_N) − 1.15) + (0.4·10^((T_f + L_U)/10 − 9))^α_f`
`L_p = (10/α_f)·log10(A_f) − L_U + 94`
with the tables for `f, α_f, L_U, T_f` at the 29 one-third-octave frequencies 20 Hz … 12.5 kHz (`engine/src/iso226_tables.inc`, values from ISO 226:2003 Table 1).
**[PROVISIONAL]** ISO 226:2023's Annex B tables are not freely available. The revision's authors report that the 2023 contours differ from 2003 by −0.6 … +0.3 dB overall, and by at most ±0.3 dB above 10 phon **[SUZUKI24]**. Sable's use is 65–80 phon, where the difference is ≤ 0.3 dB, below the resolution of this effect. The 2023 formula with `α_r = 0.3` and the 2023 tables will replace this when the standard text is available (version bump).
Sanity test: `L_p(1000, L_N) = L_N ± 0.05` for `L_N` = 20 … 90.

### 24.2 Compensation target
Reference loudness level `L_ref = 75 phon` (the Standard calibration, §12.1). Quiet level `L_q = L_ref + (T_quiet − T_std) = 70 phon`.
`H(f) = [L_p(f, L_q) − L_p(f, L_ref)] − (L_q − L_ref)` (dB). This is the extra level at `f`, relative to 1 kHz, that keeps the spectral balance perceived at `L_ref`. It is positive at low frequencies, and small and mixed at high frequencies.
Output calibration `c_out` shifts both levels equally (`L_ref + c_out`, `L_q + c_out`).

### 24.3 Filter realization
One low-shelf + one high-shelf biquad (RBJ cookbook form, `S = 1`):
- Low-shelf: corner `f_L` from the grid {60, 80, 100, 125, 160, 200, 250} Hz and gain `G_L ∈ [0, +3] dB` in steps of 0.1 dB
- High-shelf: corner `f_H` from {4k, 5k, 6.3k, 8k} Hz and gain `G_H ∈ [−1, +2] dB` in steps of 0.1 dB
- Chosen by exhaustive grid search minimizing `Σ_f (R(f) − H(f))²` over the 1/3-octave frequencies 25 Hz … 12.5 kHz, where `R` is the cascade's magnitude response in dB. Ties go to the smaller |gain|.
Deterministic, with a hard maximum boost of +3 dB, so it is "subtle" by construction.

### 24.4 Headroom
`H_contour = max(0, G_L) + max(0, G_H)` dB is added to `TP_eff` (§16.4) while the contour is on. This bounds the cascade's maximum gain at all frequencies up to Nyquist, which is where the high shelf peaks. It is conservative: the boost is assumed to add to peaks.

---

## 25. Determinism and numeric policy [DESIGN]

Goal: the same PCM produces **bit-identical** analysis results on x86-64 (host) and ARM64 (phone), every run.

### 25.1 Compiler policy
`-ffp-contract=off`, no `-ffast-math`, no `-Ofast`, no `long double`. Explicit `float`/`double`. No FMA, implicit or explicit.

### 25.2 Deterministic math library (`sable::dm`)
`exp, log, log10, pow, sin, cos, tan, sinh/cosh (sech)` are implemented in `engine/src/dmath.cpp` using only IEEE-754 `+ − × ÷` and `sqrt` (all exactly rounded). Each uses argument reduction plus a fixed polynomial. Platform libm is never called in the engine, because libm results differ between bionic and UCRT. Accuracy is tested against `std::` versions: ≤ 2 ulp on 10⁶ random arguments per function (§26).

### 25.3 Denormals
- Filter states are flushed explicitly when `|s| < 1e-30`. This is for speed; it is not what makes results identical.
- **Every analysis thread sets the FP control state explicitly** at the start of every analysis call: FTZ **on** and DAZ **on** (x86 MXCSR bits FTZ/DAZ; ARM64 `FPCR.FZ`, which flushes both inputs and outputs). The previous state is restored on exit, with an RAII guard. Without this, subnormal decoded samples, `y²` underflow, near-zero true-peak taps times small samples, or `dm::exp` underflow could be flushed on one platform and not on the other.
- **No ISA-identity claim for subnormal edge cases.** x86 detects tininess after rounding, while AArch64 FZ flushes before rounding. A result whose exact value lies just below the smallest normal can therefore be kept on x86 and flushed on ARM.
- Sable therefore **flushes explicitly** wherever products can underflow, so both ISAs only ever see values far from that band:
  - float paths flush `|v| < 2^-100` to 0: true-peak products, the perceptual fast model, and decoded samples on entry
  - double paths flush `|v| < 2^-1000`: `y²`, `dm::` results, and filter states (via the 1e-30 rule)
- **Test:** targeted operands whose products land in `[(1−2^-24)·FLT_MIN, FLT_MIN)`, the double analogue, and subnormal-sample signals. Host and device must agree bit for bit.
- The real-time audio thread uses the same setting.

### 25.4 Summation order
All reductions run in a documented fixed order: sequential per channel, then over channels in channel order. Where SIMD is used, the code is written against Sable's own 4-lane abstraction (`sable::f32x4`, `f64x2` pairs). Its scalar fallback **emulates the same 4 lanes and the same final reduction `(l0 + l1) + (l2 + l3)`**, so vectorized and scalar builds compute the same operations in the same order. They are tested for identical output.
- Only non-fusing intrinsics are used: x86 `_mm_mul_ps`/`_mm_add_ps`; ARM `vmulq_f32`/`vaddq_f32`. **Never** `vmlaq`/`vfmaq`/`_mm_fmadd`. `vmlaq_f32` may be lowered to a fused multiply-add on AArch64.
- No third-party DSP or SIMD code in the engine.
- Compilers: clang only, on both targets (llvm-mingw on the host, NDK clang on Android), with `-ffp-contract=off -fno-fast-math`. MSVC is not supported for the engine.

### 25.5 In-tree FFT and resampler
- **FFT:** iterative radix-2 complex FFT, decimation in time, bit-reversed input order. Real FFTs of size N use an N/2 complex FFT plus a split step. Twiddles are computed once by `dm::sin`/`dm::cos` in double and stored as float. The butterfly order is fixed.
- **Resampler** (§10.1 step 1, §11 F3 decimator): polyphase Kaiser-windowed sinc with rational ratio `L/M` reduced by gcd. Taps are designed in double via `dm`, stored as float, and accumulated in the §25.4 lane order. Parameters are given at each use.

### 25.6 Number formatting
Golden files serialize doubles with `std::to_chars` (shortest round-trip representation), not `printf`. Parsing uses `std::from_chars`.

### 25.7 Threading
One track is analysed on one thread. Several tracks may run in parallel; results don't depend on scheduling.

### 25.8 Versioning
The analysis result carries `engineVersion` and `mathVersion`. A MATH version bump invalidates cached analyses.

---

## 26. Verification plan (what proves each section)

| § | Test | Pass criterion |
|---|---|---|
| 2 | Coefficients at 48 kHz vs BS.1770 tables | `|Δ| ≤ 1e-8` |
| 2 | Cascade vs libebur128 4th-order form, white noise, 10 s | integrated `|Δ| ≤ 1e-6 LU` |
| 3–4 | EBU T3341 cases 1, 3–5 (stereo), 6 (5.0 raw multichannel), 7–8 (authentic) | I = −23.0 ± 0.1 LUFS |
| 3–4 | EBU T3341 case 2 | M, S, I = −33.0 ± 0.1 LUFS |
| 3 | T3341 cases 9, 10 (S), 12, 13 (M) | ±0.1 LUFS |
| 3.5 | Fine-grid Mmax/Smax on T3341 cases 10, 13 | each segment −23.0 ± 0.1 LUFS |
| 2.4 | Pink noise at 8 … 384 kHz vs 48 kHz | within the §2.4 table |
| 11.0 | Fast vs reference total intensity, 1 kHz sine and white noise | ≤ 0.1 dB |
| 25.3 | Subnormal-sample signals, FTZ/DAZ guard | bit-identical host vs device |
| 1.2 | 7.1 file with back surrounds; 5.1 downmix TP | weights per Tables 4–5; TP measured on the downmix |
| 5 | EBU T3342 cases 1–6 | ±1 LU |
| 6 | T3341 cases 15–23 at 44.1, 48 and 96 kHz | `+0.2/−0.4 dB` |
| 6 | TP ≥ sample peak for 1000 random signals | always |
| 4.1 | Property: `+x dB` gain (x ∈ {−20, −6.02, +3.7, +12}) applied to float samples, on signals with no block within |x| LU of the −70 LUFS absolute gate (the absolute gate does not move with gain, so blocks crossing it legitimately change the result) | `|ΔL_I − x| ≤ 1e-5 LU` (exact up to float rounding of the scaled samples; ~1e-7 relative) |
| 3–6 | Property: the same signal at 44.1 vs 48 kHz (generated band-limited, or resampled with a −140 dB filter) | `|ΔL_I| ≤ 0.1 LU`, `|ΔLRA| ≤ 0.1 LU` |
| all | Property: swap L/R | bit-identical results |
| 0.3 | Silence; 0, 1, 10, 100, 399, 400, 401 ms files; DC; single impulse; ±1.0 full-scale square | no NaN/∞ in any field; absent where defined absent |
| 1.2 | Mono vs the same file as dual-mono stereo | identical results |
| 4.4 | Album pooling vs concatenated audio | equal to concatenation within 1e-9 LU (blocks straddling a track join excluded on both sides) |
| cross | Corpus vs libebur128 (built from source), FFmpeg `ebur128=peak=true` and pyloudnorm | `|ΔI| ≤ 0.1 LU`. LRA within 0.2 LU of libebur128 (method note §5). TP within 0.3 dB (filter note §6). Every exceedance explained in the report. |
| 10 | Reference vs TVL18-CODE in Octave | §10.2 criteria |
| 11 | Fast vs reference model | §11.1 budget |
| 12 | `N_pink` strictly increasing; PLE(pink at x) = x | `|Δ| ≤ 0.05 LU` |
| 13–15 | Hand-labelled corpus checks + synthetic constructs (e.g. 20 s at −30, 60 s at −14, 20 s fade): sections, anchor and labels as designed | boundaries within ±1.5 s on synthetic constructs (0.5 s fade scan step on a 1 s series) |
| 16–21 | Unit tests for every rule: peak cap active/inactive, intimacy on/off and bounds, perceptual bound, album pooling and cap, album pending, seam clamps (both bounds, cap, every skip condition), learning clamp/decay/environment filter/artist threshold, composition order | exact expected values |
| 22 | JIT vs full gain on the corpus | report the distribution; target 90% within 1.5 dB |
| 23 | Rendered boundary: null test of the processor output vs offline `x·gain` per item | bit-exact sample alignment; ramp shapes exact |
| 23.4 | Dither statistics (mean, TPDF histogram), determinism with a fixed seed | as specified |
| 24 | `L_p(1000, L_N) = L_N`, ISO 226:2003 spot values, filter fit error | tabulated |
| 25 | `sable::dm` vs std | ≤ 2 ulp |
| 25 | Golden files on host = golden files on the A17 (`adb shell` test binary) | bit-identical |

---

## 27. Constants (single table)

| Symbol | Value | Unit | § | Tag |
|---|---|---|---|---|
| K-weight stage 1 f0, G, Q, Vb exponent | 1681.974450955533, 3.999843853973347, 0.7071752369554196, 0.4996667741545416 | Hz, dB, –, – | 2.1 | STD |
| K-weight stage 2 f0, Q | 38.13547087602444, 0.5003270373238773 | Hz, – | 2.2 | STD |
| Loudness offset | −0.691 | LU | 3 | STD |
| Block / hop | 400 / 100 | ms | 3 | STD |
| Short-term window | 3 | s | 3.4 | STD |
| Absolute gate (integrated) | > −70 | LUFS | 4.1 | STD |
| Relative gate (integrated) | > −10 | LU | 4.1 | STD |
| LRA gates | ≥ −70 LUFS, ≥ −20 LU | | 5 | STD |
| LRA percentiles | 10th, 95th; idx = floor((n−1)p + 0.5) | | 5 | STD |
| Surround weights | 1.41 for 60°–120° (incl. 5.1 BACK); 1.0 for back 120°–180° and heights; mask mapping table | | 1.2 | STD |
| Power floor | 1e-20 | | 0.3 | DESIGN |
| TP oversampling | 8 (fs < 96k), 4 (else) | × | 6.1 | DESIGN |
| TP filter Mh, β | 16, 8.0 | input samples per side, – | 6.2 | DESIGN |
| Clipped-decode TP margin | +1.0 | dB | 1.1 | TUNE |
| Spectral density threshold | 30 | dB | 8.4 | TUNE |
| Onset threshold, spacing | 6 dB, 50 ms | | 9 | TUNE |
| TVL constants | C 0.0631; STL aA 0.045, aR 0.033; LTL aA 0.01, aR 0.00133; B 0.08; θ 1.5978 | | 10 | STD |
| D_cal | 89.0 | dB SPL | 12.1 | TUNE |
| Pink seed | 0x5AB1E | | 12.2 | DESIGN |
| PLE refinement trigger | 6.0 | dB | 12.4 | DESIGN (measured, 1.8) |
| Feature frame | 0.5 s × max(1, ceil(D/900 s)) | s | 13.1 | DESIGN |
| Novelty kernel half-size | 8 | s | 13.3 | TUNE |
| Novelty threshold | max(0.1, μ + 0.5σ) | | 13.4 | TUNE |
| Min section | 8 | s | 13.4 | TUNE |
| Silence threshold | max(−60, L_I − 35) | LUFS | 13.4 | TUNE |
| Fade slope / drop / min duration | 1.5 LU/s, 10 LU, 4 s | | 13.5 | TUNE |
| Intro/outro | ≤ 25% duration, ≤ L_I − 3 | | 13.6 | TUNE |
| Build | slope ≥ 0.3 LU/s, rise ≥ 4 LU, ≥ 8 s | | 13.6 | TUNE |
| Breakdown | ≤ L_anchor − 6 LU | | 13.6 | TUNE |
| Anchor duration | ≥ max(20 s, 20%) | | 14 | TUNE |
| Anchor δ sequence | 3, 4, 5, 6 | LU | 14 | TUNE |
| Labels | see §15 table | | 15 | TUNE |
| T_std, T_quiet | −14, −19 | LUFS-eq | 16.1 | DESIGN (owner) |
| Perceptual bound B_p | 3.0 | LU | 16.2 | TUNE |
| Music-centring offset c_m | +0.65 | LU | 12.3 | TUNE |
| Anchor perceptual statistic | 95th percentile of gated anchor LTL | | 14.7 | DESIGN |
| Intimacy a_int | default 1.0, range [0, 2] | LU | 16.3 | DESIGN |
| Limiter max reduction | 6.0 | dB | 16.4 | DESIGN |
| Album intimacy share | 50 | % | 17.2 | DESIGN |
| Seam bound | ±0.5 | dB | 18 | DESIGN (spec) |
| Seam window | 3 | s | 18 | DESIGN |
| Seam silence skip | −40 | LUFS | 18 | TUNE |
| Learning window t_w | 20 | s | 19.1 | TUNE |
| Learning η_t, η_a | 0.5, 0.25 | | 19.2 | TUNE |
| Learning half-life | 30 | days | 19.2 | TUNE |
| Learning clamp | ±2 | dB | 19.2 | DESIGN (spec) |
| Environment weight | 0.25 | | 19.1 | TUNE |
| Ceilings | speaker/USB −1.0; BT −1.5; SBC/unknown −2.0 | dBTP | 20.1 | TUNE |
| Calibration offset range | [−3, +3] | dB | 20.2 | DESIGN |
| Provisional gain | T + 8 | dB | 22.1 | TUNE |
| JIT TP margin, wait | 1.0 dB, 1.5 s | | 22.2 | TUNE |
| Analysis budget | ≤ 10 s per 4 min | | 22.3 | DESIGN (spec) |
| Ramp rate (analysis updates) | 1 | dB/s | 23.3 | DESIGN |
| User crossfade | 20 | ms | 23.3 | DESIGN |
| Dither | TPDF, 1 LSB per uniform | | 23.4 | STD practice |
| Limiter | 5 ms look-ahead, 100 ms release | | 23.5 | DESIGN |
| Contour L_ref | 75 | phon | 24.2 | TUNE |
| Contour max boost | +3 | dB | 24.3 | DESIGN |
| Contour shelf grids | f_L ∈ {60,80,100,125,160,200,250} Hz, G_L ∈ [0,3]; f_H ∈ {4k,5k,6.3k,8k} Hz, G_H ∈ [−1,2]; 0.1 dB steps | | 24.3 | DESIGN |
| Fine max grid | 10 ms hop | | 3.5 | DESIGN |
| Onset flux ε, local-max window | 1e-12, ±2 frames (±20 ms) | | 9 | DESIGN |
| Silence run for a boundary | ≥ 2 | s | 13.4 | TUNE |
| Novelty peak window | ±4 frames | | 13.4 | TUNE |
| Perceptual resampler | half-length 32 samples, β 10, cutoff 0.95·Nyquist | | 10.1 | DESIGN |
| Fast-model intensity factor κ | 1.61087 (+2.07 dB) | | 11.0 | STD-derived |
| Fast-model W table | 1 dB grid, X ∈ [−30, 130] dB | | 11 F5 | DESIGN |
| Learning reversal / route-change windows | 3 s / 10 s | | 19.1 | TUNE |
| Low-rate K-weighting flag | fs < 32 kHz | | 2.4 | DESIGN |

---

## 28. Edge behaviours and known blind spots [DESIGN]

- **Post-processing after Sable** (Samsung Dolby Atmos/Adapt Sound/EQ, Android `LoudnessEnhancer`, Bluetooth absolute volume): all ceilings and loudness figures assume a bit-transparent path after Sable's processor. Sable reports detectable effects (`AudioEffect.queryEffects`, Samsung SoundAlive state where exposed) on the Why sheet. It does not try to compensate.
- **Learning vs the user's own correction:** the volume press itself has already corrected the current track at the system level. Learned offsets are therefore applied **from the next play** of that track or artist, never mid-track. So there is no double correction.
- **Mid-stream format changes** (chained Ogg with a new rate or channel count):
  - At each link boundary the filters are re-initialised for the new format and the sub-block grid restarts. A partial trailing block of the previous link is discarded.
  - All per-time series (block powers, S, F, fine M/S, perceptual LTL, feature frames) are concatenated in time order.
  - Integrated loudness pools all blocks as in §4.4. LRA uses the concatenated S values. TP is the max.
  - Structure runs on the concatenated frames, with a **forced boundary** at each link.
  - The perceptual model restarts per link, with its AGC states reset.
  - Playback reconfigures the processor at the link boundary.
- **Cache identity:** the cache key is a hash of the decoded-audio-relevant payload (§ M4 spec: audio frames only, excluding tags). If the audio changes, the key changes and the track is re-analysed. If only tags change, nothing is re-analysed.
- **Crossfade:** Sable does not crossfade. Transitions are gapless or hard (§23.2). If crossfade is added later, a section here will define the gain trajectory over the overlap.
- **Existing ReplayGain / R128 tags** are read and shown for comparison on the Why sheet. They are never used for Sable's gain and never written.
- **Learning stacking across replays:** if the user leaves the system volume lowered after a feedback press, the next play also applies the learned offset. The partial credit `η_t = 0.5` bounds this: the replay is at most half a step quieter than the user's own correction implied. Repeated presses converge rather than compound, because each new event is measured relative to the level at that time.

## 29. References

- **[BS1770]** Recommendation ITU-R BS.1770-5 (11/2023), *Algorithms to measure audio programme loudness and true-peak audio level*. Annex 1 (eqs. 1–7, Tables 1–3), Annex 2 (true peak). Algorithmically identical to BS.1770-4 for mono/stereo/5.1 channel-based audio.
- **[T3341]** EBU Tech 3341 v4 (11/2023), *Loudness Metering: 'EBU Mode'*. §2.2–2.6, Table 1 (cases 1–23).
- **[T3342]** EBU Tech 3342 v4 (11/2023), *Loudness Range*. §3.1, Table 1, MATLAB reference.
- **[R128]** EBU R 128 (2023), *Loudness normalisation and permitted maximum level of audio signals*.
- **[T3344]** EBU Tech 3344, *Guidelines for distribution and reproduction in accordance with EBU R 128* (listed for completeness; not consulted).
- **[LIBEBUR128]** J. Jiixyj et al., libebur128, commit 67b33ab, `ebur128.c` `ebur128_init_filter`, LRA and true-peak code. MIT licence.
- **[TVL16]** B.C.J. Moore, B.R. Glasberg, A. Varathanathan, J. Schlittenlacher (2016). "A loudness model for time-varying sounds incorporating binaural inhibition." *Trends in Hearing* 20:1–16. doi:10.1177/2331216516682698 (PMC5318944).
- **[TVL18]** B.C.J. Moore, M. Jervis, L. Harries, J. Schlittenlacher (2018). "Testing and refining a loudness model for time-varying sounds incorporating binaural inhibition." *JASA* 143:1504–1513. The basis of ISO 532-3:2023.
- **[TVL18-CODE]** Moore, Glasberg & Schlittenlacher, *Matlab code for calculation of the loudness of time-varying sounds* (TVL 2018), University of Cambridge, `tv2018matlab.zip`. Free for research use; Sable is a personal, non-commercial project (see README, Licensing notes).
- **[MGB97]** B.C.J. Moore, B.R. Glasberg, T. Baer (1997). "A model for the prediction of thresholds, loudness and partial loudness." *JAES* 45:224–240.
- **[GM06]** B.R. Glasberg, B.C.J. Moore (2006). "Prediction of absolute thresholds and equal-loudness contours using a modified loudness model." *JASA* 120:585–588.
- **[ISO532-3]** ISO 532-3:2023, *Acoustics — Methods for calculating loudness — Part 3: Moore-Glasberg-Schlittenlacher method*. Not consulted (paywalled); see §10.
- **[ISO226]** ISO 226:2003 and ISO 226:2023, *Acoustics — Normal equal-loudness-level contours*.
- **[SUZUKI24]** Y. Suzuki, H. Takeshima, K. Kurakata (2024). "Revision of ISO 226 'Normal Equal-Loudness-Level Contours' from 2003 to 2023 edition." *Acoust. Sci. & Tech.* 45(1):1–8. doi:10.1250/ast.e23.66.
- **[FOOTE00]** J. Foote (2000). "Automatic audio segmentation using a measure of audio novelty." *Proc. IEEE ICME*.
- **[BS775]** Recommendation ITU-R BS.775, multichannel stereophonic sound system (downmix coefficients).
- **[AES-TD1004]** AES TD1004.1.15-10, *Recommendation for loudness of audio streaming and network file playback* (PLR).

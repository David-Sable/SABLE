# Independent review of MATH-1.0-draft (2026-10-25)

Reviewer: a fresh subagent that did not write MATH.md. It checked the spec against the BS.1770-5, Tech 3341/3342 and R128 texts, the TVL16 paper, the TVL18 MATLAB code and libebur128, with numeric checks in Python.
Status: **all findings open**. Each will be fixed in MATH-1.1 or answered in writing below it.

## Blockers
1. **§3.3/3.5:** momentary loudness only on the 100 ms grid fails T3341 case 13 (onsets at 20 ms steps; −0.46 dB at a 40 ms onset). Compute Mmax/Smax on a ≤10 ms grid; keep 100 ms for gating and LRA.
2. **§11 F3:** the fast-model bin power is ≈2.07 dB below the reference scaling (TVL18 uses `(2|X|/N)²·10^0.332·2^(w−1)`). Multiply by `2·(3/8)·10^0.332`, or define it as "match the reference intensity for a sinusoid" and test that.
3. **§1.2:** back surrounds (120°–180°) weigh 1.00, not 1.41 (BS.1770-5 Tables 4/5). Use the position-dependent weights.

## Major
4. **§8.1:** the `N_s` formula gives 8192, not 4096, at 44.1/48 kHz. Use `floor`, or give an explicit table.
5. **§2:** K-weighting via the libebur128 bilinear transform deviates at low sample rates (8 kHz +0.21 to +0.42 dB, 11.025 kHz up to +0.27 dB, 22.05 kHz +0.08 dB). Document per-rate tolerances and test them, or normalise stage 2 and fit below 32 kHz.
6. **§10.1.4:** the reference pruning maximum is taken over both ears and all bins, including DC.
7. **§10.1.6:** the `A(f)` interpolation is MATLAB 'cubic' = PCHIP with extrapolation. At 1.75 Cam the input is below the table range, and Octave would return NaN. Specify the extrapolation or clamp, and patch `get_A` in the Octave harness.
8. **§10.1:** time alignment is undefined (FIR trimming `out(1025:end-1024)`, frame n ↔ t = n ms, symmetric `hann`, inclusive band-edge bins).
9. **§16.4:** the limiter cap formula suppresses positive user offsets. Use `g_cap_lim = g_cap + 6`, and fire LIMITER only when it actually limits.
10. **§18/§21:** the seam double-counts `c_out` and the previous track's learning offset. Use the previous track's `g_seamed`, and make the skip test use the same before/after-gain reference on both sides.
11. **§13.5:** the fade search contradicts itself, and the `S_k` timestamp is undefined. Define S_k at its window end, and say "scan backwards, stop at the first failure".
12. **§13.4:** merging is order-dependent and can merge fades away. Merge shortest first, exempt fades, define absent `M_j` as silent, and use a single silence definition.
13. **§1.2/§6.3:** for >2-channel files, measure TP on the downmix (the BS.775 sum can exceed the raw TP by +7.7 dB).
14. **§25.3:** the FTZ-independence claim is false. Set FTZ/DAZ to one known state on every analysis thread on both ISAs, and test it.
15. **§25:** determinism also needs an in-tree FFT with `dm` twiddles, a fixed resampler, no intrinsics that may fuse, `std::to_chars` instead of `%.17g`, and a scalar 4-lane reduction emulation.
16. **§12.4:** the refinement trigger is ambiguous. Use the Standard-mode `g_intent`, and skip when L_I is absent.
17. **§14.7:** define it as "include sub-block m iff block m exists and m ∈ J_g".
18. **§17:** undefined album cases: absent PLE (substitute L_anchor), short files, and disc boundaries in "no gaps".

## Minor
19. **§3.1:** the deviation is 0.045%, not 0.005%. Also, 4H = 4412 at 11.025 kHz is not "nearest sample".
20. **§6.2:** set the phase-0 taps to exactly δ by construction. "Scalloping" should read "DC gain error per phase". (Mh = 16, β = 8 passes cases 15–23 in simulation.)
21. libebur128 does no TP oversampling at ≥192 kHz, uses a histogram only in histogram mode, and its gates are `>=`. Correct the comparison notes.
22. **§23.3:** the raised-cosine ramp must last `max(0.5, (π/2)|Δ|)` s to stay ≤ 1 dB/s.
23. **§24.4:** contour headroom must include the high-shelf gain up to Nyquist: `max(0, G_L) + max(0, G_H)`.
24. Wording and table fixes:
    - §27: Mh counts input samples, not taps; add the missing constants to the table.
    - §13.1: rename `T_f`, which clashes with ISO 226.
    - §26: case 2 expects −33.0, not −23.0.
    - decodeClipped applies only to lossy sources.
    - Define three-valued logic for labels with absent inputs.
    - §16.2: fall back to L_I when L_anchor is absent.
    - §20.1: the Tech 3344 citation is unverified.

## Blind spots raised
- OEM/Android post-processing after the processor.
- Learning stacks on top of the user's own system-volume change.
- Chained Ogg streams that change sample rate or channel count.
- Cache invalidation when a file's audio changes.
- Crossfade transitions.
- Existing ReplayGain/R128 tags.
- The limiter sidechain uses L = 4 while analysis uses L = 8, so it can under-read by up to 0.55 dB.

## Verified correct (coverage)
- K-weighting constants match libebur128; at 48 kHz they match BS.1770 Tables 1/2 to ≤ 9e-16.
- Gating comparisons and energy-domain means are correct, and the J_g non-empty proof holds.
- LRA matches the T3342 MATLAB code exactly.
- libebur128 behaviour notes (dual mono, 49-tap TP filter, 1 s LRA cadence) are correct.
- TP under-read table values and the polyphase indexing are correct.
- Every TVL constant, table and formula matches the TVL18 code.
- The fast-model AGC conversion, kernel renormalisation and Σ·0.5 are correct.
- The ISO 226:2003 formula reproduces L_N at 1 kHz within 0.012 dB, and the SUZUKI24 claims are accurate.
- The §18 closed form matches its constrained problem.
- The §0.3, §4.4, §8.2 and §23.4 arithmetic is correct.

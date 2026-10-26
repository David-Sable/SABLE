# Independent review of MATH-1.1 (2026-10-26)

Reviewer: a second fresh subagent, independent of the author and of the first reviewer. It checked MATH-1.1 against the BS.1770-5, Tech 3341/3342 and TVL18 sources and libebur128, with numeric simulations in Python.
**Outcome:** no blockers. All 1.0-draft blockers are confirmed fixed, including T3341 case 13 on the fine grid, the κ derivation, and cases 15–23 with phase-0 δ. Every finding below is resolved in **MATH-1.2**.

## Part A — 1.0-draft findings
| Status | Findings |
|---|---|
| Resolved | 1, 2, 4, 6, 7, 8, 10, 13, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24 |
| Partially resolved in 1.1, completed in 1.2 | 3 (mask mapping → B1), 5 (table/test → B2, B3), 9 (seam/album cap → B4), 11 (fade-in → B5), 12 (typing order → B9), 14 (FTZ claim → B6), 25 (learning mid-track; stacking) |

## Part B — new findings and their 1.2 resolution
| # | Severity | Finding | Resolution in 1.2 |
|---|---|---|---|
| B1 | Major | Channel-mask → azimuth mapping undefined. 5.1 BACK must be ±110° (1.41), or case 6 fails at −23.40. Downmix TP bound wrong for 7.1. | Mask table added in §1.2. Downmix coefficients defined for every channel. Bound is now `20·log10(1 + 0.7071·n_s)`: 7.7 dB for 5.1, 9.9 dB for 7.1. §27 updated. |
| B2 | Major | Low-rate K-weighting deviation understated: 8 kHz is 0.501 dB, 11.025 kHz is 0.292 dB. | §2.4 table corrected (≤ 0.51 / ≤ 0.30 dB). The Why-sheet note says ±0.5 LU. |
| B3 | Major | The full-band pink-noise rate test fails by construction. | The test now uses noise band-limited to 0.45·min(fs, 48 kHz) at both rates. |
| B4 | Major | The seam and album caps ignored the limiter allowance; the PLE offset was undefined when a term is absent. | `g_cap_eff` is used in §17, §18 and §21. The album cap gets +6 dB with the limiter on. An absent offset counts as 0. PEAK_CAP and LIMITER firing is defined. |
| B5 | Major | The fade-in test was broken by window-end stamps (no S before 3 s). | Fade detection uses a symmetric 1 s series F stamped at its centre. Fade-in and fade-out are now exact mirror images. |
| B6 | Major | The claim that x86 FTZ/DAZ and ARM FZ behave identically is false at the underflow boundary (tininess before vs after rounding). | Claim removed. Explicit flush thresholds (2^-100 float, 2^-1000 double) at every product that can underflow, plus a targeted boundary test. |
| B7 | Minor | κ printed as 1.6106 (exact: 1.61087). The invariant is frequency-dependent. | κ corrected. The invariant is stated as approximate, with frequency-specific tolerances (≤ 0.25 dB at 100 Hz–2.5 kHz, ≤ 0.6 dB at 50 Hz). Fast-model bands are half-open. |
| B8 | Minor | `X` per ERB was transposed relative to `InputLevelPerERB.m`. | Indexing copied from the code: each source spreads with its own p. |
| B9 | Minor | Merging used "non-silent" before it was defined; window off-centre; fades split by novelty; ties undefined. | Sections are typed before merging. The window offset is stated. Novelty boundaries inside fades are dropped. Ties go to the earlier frame. The distance metric is defined. |
| B10 | Minor | Wrong "no frames" sentence in §10.1.11. | Removed. |
| B11 | Minor | §12.4 contradicted §16.2; the intimacy setting moved the refinement trigger. | Trigger uses the Standard-mode `g_anchor`. `s` always uses `L_I`. |
| B12 | Minor | Fine window 40h ≠ 4H when fs is not divisible by 100; "5 ms each edge" wording. | Stated and accepted (< 0.5%). Wording now "≤ 5 ms in total". |
| B13 | Minor | The sub-block timestamp contradicted the general rule. | Marked as an explicit exception. |
| B14 | Minor | Chained-Ogg LRA, structure and perceptual analysis undefined. | Fully defined in §28: concatenated series, forced boundary at each link, perceptual model restarted per link. |
| Leftovers | – | T3344 described as consulted; learning listed as mid-track; stacking across replays. | Fixed in §29, §23.3 and §28. |

Reviewer's simulation scripts (kw.py, fine.py, tp.py, kappa.py) were kept out of the repo. Their results are quoted above.

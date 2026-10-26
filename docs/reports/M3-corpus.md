# Milestone 3: intent layers on real music (2026-10-26)

Data: `M3-corpus.csv`. 52 tracks: one per album from the owner's collection, plus the free-licence recordings
(solo piano, orchestral, chamber, folk, jazz, electronic, ambient, industrial, spoken word). Each track runs the
full two-pass pipeline (`sable_cli --decide`): BS.1770, the perceptual lens at its prescale, the §12.4 refinement,
structure, anchor and character, then Standard-mode gain.

## Calibration study (MATH-1.7)
| Anchor perceptual statistic | median PLE − L_anchor | SD |
|---|---|---|
| mean LTL (MATH ≤ 1.6) | −3.76 LU | 3.72 |
| median | −3.47 | 3.89 |
| 90th percentile | −1.06 | 3.11 |
| **95th percentile (adopted)** | **−0.65** | **3.00** |
| maximum | +0.85 | 2.87 (more outliers) |

Adopted: 95th percentile, music-centring offset c_m = +0.65 LU, perceptual bound B_p = 3 LU.

## Result with MATH-1.7
- PLE − L_anchor: median −0.03 LU, SD 3.00.
- **Sable vs plain −14 LUFS integrated normalization (same peak cap): median −0.10 dB; 32/52 tracks within ±1 dB.**
- Largest moves, all for the reasons the spec targets:
  - *Dynamic arcs played lower* (anchor = the sustained loud part, not an average pulled down by quiet passages): Smetana *Vltava* −4.3 dB; Josh Woodward folk −3.6 / −2.9 dB.
  - *Dense / bright masters played lower* (they feel louder than their LUFS): NIN *Letting You* −2.9, *1,000,000* −2.8, Breaking Benjamin *Dark* −2.3, JPEGMAFIA *TSAR BOMBA* −2.2 dB.
  - *Sparse or softer-feeling masters played slightly higher*: NewJeans +1.7, JPEGMAFIA *1539 N. Calvert* +2.0 dB.
- Characters: steady 18, dynamic arc 16, sustained and dense 13, quiet and intimate 4 (Goldberg Aria, Var. 13, Beethoven Adagio, NIN *999,999*), build and release 1.
- Anchors cover a median of 76% of the non-silent duration; no fallbacks. 22/52 tracks used the one-step prescale refinement (§12.4).

## Open items
- Listening check by the owner: labels and the largest moves above.
- Refinement doubles pass-2 cost on 42% of tracks; to be measured on the A17 (M3 close-out).

## Close-out (2026-10-26)
- **Refinement study (MATH-1.8):** on the 22 tracks that triggered the 2 dB refinement, re-running the perceptual
  pass changed PLE_anchor by a median 0.021 LU and at most 0.109 LU (level steps 2.1-4.3 dB). The trigger was
  raised to 6 dB, so the second pass is now a safety net only.
- **On the Galaxy A17:** test suite 2,571/2,571 assertions pass on ARM64. `--decide` output (sections, anchor,
  character, Standard/Quiet gains, rules) is byte-identical to the host on all 5 golden tracks.
- **Full analysis time** (pass 1 + perceptual pass + understanding + decision; decoded input; Cortex-A78 big core):

  | Track | Duration | Time | Per 4 min |
  |---|---|---|---|
  | NIN *Discipline* | 4:19 | 5.10 s | 4.7 s |
  | NIN *Demon Seed* | 4:59 | 5.70 s | 4.6 s |
  | Death Grips (brickwalled) | 2:52 | 3.51 s | 4.9 s |

  About 5 s of the 10 s budget remains for decoding (two passes), which M4 measures.
- **Hygiene:** host 40/40 test cases (2,577 assertions); ASan + UBSan clean; clang-tidy clean across the engine.

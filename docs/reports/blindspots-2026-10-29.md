# Intent engine blind-spot pass (2026-10-29, PC, MATH-1.33)

David asked for a deep pass on what Sable does not understand about music. No engine change came out of it: every
suspect was either handled already, absent from the library, or not supported by the data. What was checked, how, and
what to watch for next. Scripts: `tools/blindspots/` (outputs in `~/dev/sable-blindspots/`, never committed).

## Material
- **The whole library:** all 418 songs on the phone are on the PC (`~/dev/sable-corpus/drive` + `phone-extra`; the
  only differences were apostrophes in file names), plus the 42 free recordings (classical, ambient, solo piano,
  speech, CC music). 464 files through `sable_cli --decide` (`run_all.py`), no errors.
- **20 constructed cases** from library songs (`gen_cases.py`, `run_cases.py`): hidden track after 4 min of silence,
  live crowd noise, spoken skit before the song, 30 dB crescendo, one full-scale click in a quiet piece, right = −left,
  left only, DC offset 0.3, 90 s ambient intro, telephone band, sub-bass only, 8 kHz mono voice memo, quiet piece with
  a loud ending, two-part suite (second half −14 dB), 0.6 s stutter gaps, white-noise wall, 55 Hz drone, 6 kHz tone,
  −30 dB master, 15 min mix.
- **David's 37 song lessons** (30 JBL, 4 car, 3 speaker), from `--es cmd learned`.

## Findings
| Suspect | Result | Action |
|---|---|---|
| Constructed cases | Read as a listener would expect: anchors on the sustained loud part, intros/outros/fades/silence typed, bounds and caps firing where they should | none |
| **One click holds a song back** | A single 0.5 ms full-scale click capped a quiet piano piece ~15 dB under its intent (PEAK_CAP). In the library, the 4 capped songs have real peaks: Dawn has 10 ms windows within 1 dB of its peak across 66 s, The Funeral across 5 s (`peaks.py`) | Known edge, absent from the library (vinyl rips with clicks would hit it). The opt-in limiter covers it. Any automatic fix would change the audio: ask David first |
| **Mono speakers** (phone speaker, JBL Charge 5 fold L+R) | Fold-down loss relative to a centred song (`mono_fold.py`): median 0.5 dB, 90th percentile 1.1, max 2.2 (Ice Cube ≈ 0.1, Breaking Benjamin / Charli ≈ 1.0). Against the JBL lessons it separates up from down by only 0.2 dB (SD 0.4) | Not worth a rescan; noted |
| **Place tone** (MATH idea 1: a place's lessons following spectral tilt) | JBL: lessons correlate with tilt r = −0.60 (bright down, dark up; also with master loudness and PLE−L, which move together here); car leans the other way. But leave-one-album-out prediction got the direction right 10 of 19 times and cut the mean miss only 1.56 → 1.45 dB; the Injury Reserve album carries the correlation | **Not built.** Re-run `lessons_vs_features.py` and the leave-one-album-out test when the JBL and car have more lessons from more albums |
| Quiet openings | 7 songs open ≥ 6 LU under their anchor but the opening (< 8 s) is merged into the first body section, so it sits inside the anchor interval | No gain effect (the gated energy mean ignores it; the press rule reads the measured loudness, not the section). Cosmetic |
| Anchors, labels, short files | No fallback anchors; no "quiet and intimate" in the library (it fires on string quartets, slow symphony movements, solo piano, NIN "999,999" as designed); smallest anchor shares are real dynamic songs; skits read cleanly; Tinashe's 6 s "You" (+7.7 dB) is the known owner-accepted case | none |
| Perceptual model on sparse / bass-heavy material | PLE − L down to −12 LU on Kevin MacLeod "Bass Vibes", −8 on Ghosts I; the ±3 LU bound holds them. The fast model is validated against the reference within 2.4% on bass tones (MATH §11.2), so this is the model's reading, not drift | none |
| Dynamic material vs the cap | Classical, speech and CC MP3s mostly end PEAK_CAP-bound below target | By design (no limiter by default) |

Not in David's library, still open from earlier: ALAC that the phone's extractor cannot read (stays provisional), 5.1
files (perceptual model uses the front pair, up to ~3 dB too loud), hidden tracks (one gain for the whole file).

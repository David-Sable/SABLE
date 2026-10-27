// Sable Intent Engine, layers 2-3: understanding (structure, anchor, character) and balancing (gain rules).
// Pure, deterministic functions over the analysis results. Each cites its MATH.md section.
#pragma once

#include <optional>
#include <string>
#include <vector>

#include "sable/analyzer.h"
#include "sable/perceptual.h"

namespace sable {

enum class SectionType { Silence, FadeIn, FadeOut, Intro, Outro, Build, Body, Breakdown };
const char* toString(SectionType t);

struct Section {  // MATH §13.4-13.6; times in seconds on the 100 ms sub-block grid
  double startSec = 0.0, endSec = 0.0;
  SectionType type = SectionType::Body;
  std::optional<double> loudnessLufs;  // §4.3 over the section's blocks
  double duration() const { return endSec - startSec; }
};

enum class Character { QuietIntimate, BuildRelease, DynamicArc, SustainedDense, Steady };
const char* toString(Character c);

struct TrackUnderstanding {
  // Character descriptors (MATH §7-§9)
  std::optional<double> tiltDbPerOct, density, densityAnchor, transientsPerSec, plrAnchorDb, sectionContrastLu;
  // Structure (MATH §13)
  std::vector<Section> sections;
  std::optional<std::pair<double, double>> fadeIn, fadeOut;
  double nonSilentSec = 0.0;
  // Anchor (MATH §14)
  std::vector<std::pair<double, double>> anchor;  // intervals (s)
  bool anchorFallback = false;
  double anchorCentreSec = 0.0, anchorSec = 0.0;
  std::optional<double> anchorLufs;   // L_anchor
  std::optional<double> anchorPle;    // PLE_anchor (unscaled, §12.4)
  std::vector<double> anchorLtlSone;  // gated anchor sub-block LTL values (diagnostics / calibration study)
  std::vector<double> anchorBlockPower;  // P_j of the anchor's member blocks (for album pooling, §17.2)
  // Seams (MATH §18): BS.1770 short-term loudness of the first / last 3 s of non-silent audio
  std::optional<double> openLufs, endLufs;
  Character character = Character::Steady;
};

// Layer 2. `perceptual` may be null (BS.1770-only). prescaleDb is the s used for the perceptual pass (§12.4).
TrackUnderstanding understand(const LoudnessResult& r, const PerceptualSeries* perceptual, double prescaleDb);

// ---- Layer 3: balancing (MATH §16-§21) ----
struct GainContext {
  double targetLufs = -14.0;          // §16.1: -14 Standard, -19 Quiet
  double intimacyLu = 1.0;            // §16.3, [0, 2]
  double ceilingDbtp = -1.0;          // §20.1
  bool decodeClipped = false;         // §1.1
  double contourHeadroomDb = 0.0;     // §24.4
  bool limiter = false;               // §16.4 / §23.5
};

struct Rule {
  std::string id;
  double deltaDb = 0.0;
};

struct GainDecision {
  double intentDb = 0.0;   // g_intent
  double capDb = 0.0;      // effective cap (g_cap or g_cap_lim)
  double gainDb = 0.0;     // g_track (or g_album)
  std::vector<Rule> rules;
};

// §16: track mode. Needs the loudness result (for L_I, TP, shortFile) and the understanding.
// MATH §16.2.3: the anchor's PLE, or nothing when it is absent or the anchor holds less than A_ple = 3 s of audio.
std::optional<double> usablePle(const TrackUnderstanding& u);

GainDecision decideTrackGain(const LoudnessResult& r, const TrackUnderstanding& u, const GainContext& ctx);

// §17.2: album mode over the album's tracks.
GainDecision decideAlbumGain(const std::vector<const LoudnessResult*>& tracks,
                             const std::vector<const TrackUnderstanding*>& und, const GainContext& ctx);

// §18: seam smoothing. Returns the seamed gain for `cur` given the previous item's seamed gain.
struct SeamInput {
  std::optional<double> prevEndLufs, prevPleOffset, curOpenLufs, curPleOffset;
  double prevSeamedDb = 0.0, curTrackDb = 0.0, curCapDb = 0.0;
  bool prevFadeOut = false, curFadeIn = false, manualSkip = false, havePrev = true;
};
double seamGain(const SeamInput& in, std::vector<Rule>* rules = nullptr);

// §19: listener learning.
struct LearnedOffset {
  double valueDb = 0.0;
  double updatedDays = 0.0;  // timestamp in days
};
double decayedOffset(const LearnedOffset& o, double nowDays);                 // §19.2 decay
void learnEvent(LearnedOffset& o, double eta, double weightedDeltaDb, double nowDays);  // §19.2 update + clamp
double sectionAdjustedDelta(double deltaDb, double sectionDevDb);                        // §19.1 (MATH-1.27) press minus what the section explains
// §19.2 combination: a song's own lesson first; the artist's only for songs not taught yet, from >= 2 songs that agree.
double appliedOffset(double trackDb, double artistDb, int artistTracks, bool trackTaught = false, bool artistAgrees = true);

// §21: final composition.
double finalGain(double seamedDb, double learnedDb, double outputCalDb, double capEffDb);

}  // namespace sable

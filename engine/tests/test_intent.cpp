// Intent layers: structure/anchor on synthetic songs, and exact unit tests for every gain rule. MATH §13-§21, §26.
#include <cmath>
#include <string>

#include "doctest.h"
#include "sable/intent.h"
#include "signals.h"

namespace {

// A "song" made of noise-like segments: (seconds, rms dBFS, fade?) with distinct spectra per segment.
sigs::Audio song(std::initializer_list<std::tuple<double, double, int>> segs, int fs = 44100) {
  sigs::Audio a{fs, 2, {}};
  std::uint64_t seed = 7;
  for (const auto& [sec, db, fade] : segs) {
    auto seg = sigs::multiSine(fs, 2, sec, 60.0, 12000.0, 40, db, seed++, fade == 2 ? -1.0 : 0.0);
    const std::size_t n = seg.frames();
    for (std::size_t i = 0; i < n; ++i) {
      double g = 1.0;
      if (fade == 1) g = std::pow(10.0, (-40.0 * static_cast<double>(i) / static_cast<double>(n)) / 20.0);  // 40 dB fade-out
      seg.x[2 * i] = static_cast<float>(seg.x[2 * i] * g);
      seg.x[2 * i + 1] = static_cast<float>(seg.x[2 * i + 1] * g);
    }
    a.x.insert(a.x.end(), seg.x.begin(), seg.x.end());
  }
  return a;
}

sable::LoudnessResult result(double li, double tp, double lra = 5.0) {
  sable::LoudnessResult r;
  r.integratedLufs = li;
  r.truePeakDbtp = tp;
  r.lraLu = lra;
  return r;
}

double ruleDelta(const sable::GainDecision& d, const char* id) {
  for (const auto& r : d.rules) if (r.id == id) return r.deltaDb;
  return 999.0;
}
bool hasRule(const sable::GainDecision& d, const char* id) { return ruleDelta(d, id) != 999.0; }

}  // namespace

TEST_CASE("Structure: quiet intro, body, fade-out -> sections and anchor on the body (MATH 13-14)") {
  const auto a = song({{16.0, -40.0, 0}, {60.0, -16.0, 0}, {14.0, -16.0, 1}});
  const auto r = sigs::analyze(a);
  const auto u = sable::understand(r, nullptr, 0.0);
  for (const auto& s : u.sections) MESSAGE(std::string(sable::toString(s.type)) << " " << s.startSec << "-" << s.endSec << " L=" << s.loudnessLufs.value_or(-99));
  REQUIRE(u.fadeOut.has_value());
  MESSAGE("fade-out " << u.fadeOut->first << "-" << u.fadeOut->second << ", anchor " << u.anchorSec << " s, L_anchor " << *u.anchorLufs);
  CHECK(std::fabs(u.fadeOut->first - 76.0) <= 1.5);  // MATH 26: 0.5 s scan step + 1 s smoothing
  bool introOk = false;
  for (const auto& s : u.sections) if (s.type == sable::SectionType::Intro && std::fabs(s.endSec - 16.0) <= 1.0) introOk = true;
  CHECK(introOk);
  REQUIRE(u.anchorLufs);
  // Anchor = the 60 s body only: excludes the quiet intro and the fade, so it is louder than L_I.
  CHECK(u.anchorSec == doctest::Approx(60.0).epsilon(0.05));
  CHECK(*u.anchorLufs > *r.integratedLufs);
  REQUIRE(u.openLufs);
  REQUIRE(u.endLufs);
}

TEST_CASE("Edge silences are recorded separately; tilt over time has one value per 0.5 s (MATH 17.3, 8.5)") {
  const auto a = song({{3.0, -90.0, 0}, {20.0, -14.0, 0}, {2.0, -90.0, 0}});
  const auto r = sigs::analyze(a);
  const auto u = sable::understand(r, nullptr, 0.0);
  CHECK(u.leadSilenceSec == doctest::Approx(3.0).epsilon(0.05));
  CHECK(u.trailSilenceSec > 1.5);
  CHECK(u.trailSilenceSec <= 2.0 + 1e-9);  // never longer than the real silence: the 0.4 s smoothing only shortens edges
  CHECK(u.shapeTilt.size() == (r.bandEnergy.size() / sable::LoudnessResult::kBands + 4) / 5);
  // A pause in the middle is not an edge silence.
  const auto m = sable::understand(sigs::analyze(song({{10.0, -14.0, 0}, {4.0, -90.0, 0}, {10.0, -14.0, 0}})), nullptr, 0.0);
  CHECK(m.leadSilenceSec < 0.2);
  CHECK(m.trailSilenceSec < 0.2);
}

TEST_CASE("Structure: a steady song -> one body anchor, not fallback; deterministic") {
  const auto a = song({{45.0, -14.0, 0}});
  const auto r = sigs::analyze(a);
  const auto u1 = sable::understand(r, nullptr, 0.0), u2 = sable::understand(r, nullptr, 0.0);
  CHECK(!u1.anchorFallback);
  CHECK(std::fabs(*u1.anchorLufs - *r.integratedLufs) < 0.3);
  CHECK((u1.anchor == u2.anchor));
  CHECK((u1.character == u2.character));
}

TEST_CASE("Short and silent tracks do not crash and fall back sanely") {
  for (double sec : {0.0, 0.3, 5.0, 19.0}) {
    sigs::Audio a{44100, 2, {}};
    double ph = 0;
    sigs::appendSine(a, 440.0, -20.0, sec, ph);
    const auto r = sigs::analyze(a);
    const auto u = sable::understand(r, nullptr, 0.0);
    const auto d = sable::decideTrackGain(r, u, {});
    CAPTURE(sec);
    CHECK(std::isfinite(d.gainDb));
  }
}

// ---------------- gain rules (exact values) ----------------

TEST_CASE("Track gain: anchor match, perceptual bound, BS1770-only, peak cap (MATH 16)") {
  sable::TrackUnderstanding u;
  u.anchorLufs = -10.0;
  u.anchorPle = -9.0;
  u.anchorSec = 60.0;    // a real anchor: its PLE is trusted (§16.2.3)
  sable::GainContext c;  // T = -14, ceiling -1
  auto r = result(-11.0, -3.0);
  auto d = sable::decideTrackGain(r, u, c);
  CHECK(d.intentDb == doctest::Approx(-5.0));   // -14 - (-9)
  CHECK(d.gainDb == doctest::Approx(-5.0));
  CHECK(!hasRule(d, "PEAK_CAP"));
  u.anchorPle = -2.0;  // 8 LU above L_anchor: clamped to -10 + 3 = -7
  d = sable::decideTrackGain(r, u, c);
  CHECK(d.intentDb == doctest::Approx(-7.0));
  CHECK(ruleDelta(d, "PERCEPTUAL_BOUND") == doctest::Approx(-5.0));
  u.anchorPle.reset();
  d = sable::decideTrackGain(r, u, c);
  CHECK(d.intentDb == doctest::Approx(-4.0));
  CHECK(hasRule(d, "BS1770_ONLY"));
  // Quiet master that would need +10 dB but peaks at -4 dBTP: cap = -1 - (-4) = +3
  u.anchorLufs = -24.0;
  u.anchorPle = -24.0;
  r = result(-26.0, -4.0);
  d = sable::decideTrackGain(r, u, c);
  CHECK(d.intentDb == doctest::Approx(10.0));
  CHECK(d.gainDb == doctest::Approx(3.0));
  CHECK(ruleDelta(d, "PEAK_CAP") == doctest::Approx(-7.0));
  // Quiet mode target -19
  c.targetLufs = -19.0;
  d = sable::decideTrackGain(r, u, c);
  CHECK(d.intentDb == doctest::Approx(5.0));
  CHECK(d.gainDb == doctest::Approx(3.0));
}

TEST_CASE("Intimacy allowance: on, off, and clamped to [0, 2] (MATH 16.3)") {
  sable::TrackUnderstanding u;
  u.anchorLufs = -24.0;
  u.anchorPle = -24.0;
  u.character = sable::Character::QuietIntimate;
  auto r = result(-25.0, -12.0);
  sable::GainContext c;
  CHECK(sable::decideTrackGain(r, u, c).gainDb == doctest::Approx(9.0));  // 10 - 1
  c.intimacyLu = 0.0;
  auto d = sable::decideTrackGain(r, u, c);
  CHECK(d.gainDb == doctest::Approx(10.0));
  CHECK(!hasRule(d, "INTIMACY"));
  c.intimacyLu = 5.0;  // clamped to 2
  CHECK(sable::decideTrackGain(r, u, c).gainDb == doctest::Approx(8.0));
  u.character = sable::Character::Steady;
  CHECK(sable::decideTrackGain(r, u, c).gainDb == doctest::Approx(10.0));
}

TEST_CASE("Peak cap inputs: clipped decode margin, contour headroom, limiter allowance (MATH 16.4)") {
  sable::TrackUnderstanding u;
  u.anchorLufs = -20.0;
  u.anchorPle = -20.0;
  auto r = result(-21.0, -2.0);
  sable::GainContext c;  // intent +6, cap -1 - (-2) = +1
  CHECK(sable::decideTrackGain(r, u, c).gainDb == doctest::Approx(1.0));
  c.decodeClipped = true;  // TP_eff = -1 -> cap 0
  CHECK(sable::decideTrackGain(r, u, c).gainDb == doctest::Approx(0.0));
  c.decodeClipped = false;
  c.contourHeadroomDb = 1.5;  // TP_eff = -0.5 -> cap -0.5
  CHECK(sable::decideTrackGain(r, u, c).gainDb == doctest::Approx(-0.5));
  c.contourHeadroomDb = 0.0;
  c.limiter = true;  // cap + 6 = 7 > intent 6 -> full intent, limiter fires, peak cap does not
  auto d = sable::decideTrackGain(r, u, c);
  CHECK(d.gainDb == doctest::Approx(6.0));
  CHECK(ruleDelta(d, "LIMITER") == doctest::Approx(5.0));
  CHECK(!hasRule(d, "PEAK_CAP"));
  u.anchorPle = -30.0;  // intent +16 beyond cap_lim 7
  u.anchorLufs = -30.0;
  d = sable::decideTrackGain(r, u, c);
  CHECK(d.gainDb == doctest::Approx(7.0));
  CHECK(hasRule(d, "PEAK_CAP"));
  // limiter on but nothing to limit: no LIMITER rule
  u.anchorLufs = u.anchorPle = -12.0;
  d = sable::decideTrackGain(r, u, c);
  CHECK(!hasRule(d, "LIMITER"));
}

TEST_CASE("Undefined loudness (silence) -> 0 dB, never boosted (MATH 16.6)") {
  sable::LoudnessResult r;  // no loudness, no TP
  const auto d = sable::decideTrackGain(r, sable::TrackUnderstanding{}, {});
  CHECK(d.gainDb == 0.0);
  CHECK(hasRule(d, "UNDEFINED_LOUDNESS"));
}

TEST_CASE("Album gain: energy pooling of anchors, intimacy share, max TP cap (MATH 17.2)") {
  sable::LoudnessResult r1 = result(-10.0, -1.5), r2 = result(-20.0, -6.0);
  sable::TrackUnderstanding u1, u2;
  u1.anchorLufs = u1.anchorPle = -9.0;
  u2.anchorLufs = u2.anchorPle = -19.0;
  u1.anchorSec = u2.anchorSec = 60.0;
  u1.anchorBlockPower.assign(10, std::pow(10.0, (-9.0 + 0.691) / 10.0));
  u2.anchorBlockPower.assign(10, std::pow(10.0, (-19.0 + 0.691) / 10.0));
  sable::GainContext c;
  const auto d = sable::decideAlbumGain({&r1, &r2}, {&u1, &u2}, c);
  const double ple = 10.0 * std::log10(0.5 * (std::pow(10.0, -0.9) + std::pow(10.0, -1.9)));  // energy mean
  CHECK(d.intentDb == doctest::Approx(-14.0 - ple));
  CHECK(d.capDb == doctest::Approx(-1.0 - (-1.5)));  // loudest TP in the album
  CHECK(d.gainDb == doctest::Approx(std::min(-14.0 - ple, 0.5)));
  // Not the mean of the track values (MATH 4.4 / 17.2):
  CHECK(std::fabs(ple - (-14.0)) > 0.5);
  u1.character = u2.character = sable::Character::QuietIntimate;  // 100% intimate share
  CHECK(sable::decideAlbumGain({&r1, &r2}, {&u1, &u2}, c).intentDb == doctest::Approx(-14.0 - ple - 1.0));
}

TEST_CASE("Seam smoothing: both clamps, cap, skips (MATH 18)") {
  sable::SeamInput s;
  s.prevEndLufs = -10.0;
  s.curOpenLufs = -14.0;
  s.prevSeamedDb = -4.0;  // prev plays its end at -14
  s.curTrackDb = 0.0;     // cur opens at -14 -> ideal 0
  s.curCapDb = 5.0;
  CHECK(sable::seamGain(s) == doctest::Approx(0.0));
  s.curOpenLufs = -15.0;  // ideal +1 -> clamped +0.5
  CHECK(sable::seamGain(s) == doctest::Approx(0.5));
  s.curOpenLufs = -12.0;  // ideal -2 -> clamped -0.5
  CHECK(sable::seamGain(s) == doctest::Approx(-0.5));
  s.curOpenLufs = -14.3;  // ideal +0.3 within bounds
  CHECK(sable::seamGain(s) == doctest::Approx(0.3));
  s.curCapDb = 0.1;  // capped
  CHECK(sable::seamGain(s) == doctest::Approx(0.1));
  s.curCapDb = 5.0;
  for (int k = 0; k < 4; ++k) {
    sable::SeamInput t = s;
    if (k == 0) t.prevFadeOut = true;
    if (k == 1) t.curFadeIn = true;
    if (k == 2) t.manualSkip = true;
    if (k == 3) t.havePrev = false;
    CHECK(sable::seamGain(t) == doctest::Approx(0.0));
  }
  s.prevEndLufs = -45.0;  // transition through near-silence
  s.prevSeamedDb = 0.0;
  CHECK(sable::seamGain(s) == doctest::Approx(0.0));
}

TEST_CASE("Listener learning: clamp, decay, artist threshold, composition cap (MATH 19, 21)") {
  sable::LearnedOffset o;
  sable::learnEvent(o, 0.5, -3.0, 0.0);
  CHECK(o.valueDb == doctest::Approx(-1.5));
  sable::learnEvent(o, 0.5, -3.0, 0.0);
  CHECK(o.valueDb == doctest::Approx(-2.0));  // clamped at -2
  CHECK(sable::decayedOffset(o, 30.0) == doctest::Approx(-1.0));   // half-life 30 days
  CHECK(sable::decayedOffset(o, 60.0) == doctest::Approx(-0.5));
  CHECK(sable::appliedOffset(-1.5, -1.0, 1) == doctest::Approx(-1.5));  // artist needs >= 2 tracks
  CHECK(sable::appliedOffset(-1.5, -1.0, 2) == doctest::Approx(-2.0));  // clamped sum
  CHECK(sable::appliedOffset(1.0, 0.5, 3) == doctest::Approx(1.5));
  CHECK(sable::appliedOffset(-0.5, 1.7, 3, true) == doctest::Approx(-0.5));  // a taught song keeps its own lesson
  CHECK(sable::appliedOffset(0.0, 1.7, 3, false, false) == 0.0);           // the artist's songs disagree
  CHECK(sable::finalGain(2.0, 1.5, 0.5, 3.0) == doctest::Approx(3.0));   // cap wins over learning + calibration
  CHECK(sable::finalGain(-2.0, -1.0, 0.0, 3.0) == doctest::Approx(-3.0));
}

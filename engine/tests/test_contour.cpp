// Quiet-mode contour (MATH §24): ISO 226 sanity, target shape, shelf responses, deterministic grid fit.
#include <cmath>
#include <cstdio>

#include "doctest.h"
#include "sable/contour.h"

TEST_CASE("contour: ISO 226 reproduces the 1 kHz definition (MATH §24.1 sanity test)") {
  for (double ln = 20; ln <= 90; ln += 10) CHECK(std::fabs(sable::iso226Spl(17, ln) - ln) <= 0.05);
  CHECK(sable::iso226Freq(17) == 1000.0);
  // Known shape: at 40 phon a 100 Hz tone needs ~22 dB more than at 1 kHz (ISO 226:2003 figure).
  CHECK(sable::iso226Spl(7, 40) - 40.0 == doctest::Approx(22.0).epsilon(0.1));
}

TEST_CASE("contour: target lifts the bass at the quiet level, ~0 at 1 kHz (MATH §24.2)") {
  CHECK(std::fabs(sable::contourTarget(17, 0.0)) < 0.1);
  CHECK(sable::contourTarget(1, 0.0) > 1.0);   // 25 Hz
  CHECK(sable::contourTarget(7, 0.0) > 0.3);   // 100 Hz
  CHECK(sable::contourTarget(1, 0.0) > sable::contourTarget(7, 0.0));
}

TEST_CASE("contour: RBJ shelves reach their gain at the band edges") {
  const auto lo = sable::lowShelf(48000, 100, 2.5), hi = sable::highShelf(48000, 6300, 1.5);
  CHECK(sable::biquadResponseDb(lo, 48000, 2.0) == doctest::Approx(2.5).epsilon(1e-3));
  CHECK(sable::biquadResponseDb(lo, 48000, 10000.0) == doctest::Approx(0.0).epsilon(1e-2));
  CHECK(sable::biquadResponseDb(hi, 48000, 23999.0) == doctest::Approx(1.5).epsilon(1e-3));
  CHECK(sable::biquadResponseDb(sable::lowShelf(48000, 100, 0.0), 48000, 50.0) == doctest::Approx(0.0));
}

TEST_CASE("contour: grid fit is bounded, subtle and deterministic (MATH §24.3-24.4)") {
  const auto d = sable::designQuietContour(0.0);
  std::printf("contour fit: low %g Hz %+g dB, high %g Hz %+g dB, headroom %g dB, rms error %.3f dB\n", d.lowHz, d.lowGainDb, d.highHz, d.highGainDb,
              d.headroomDb, d.rmsErrorDb);
  CHECK(d.lowGainDb >= 0.0);
  CHECK(d.lowGainDb <= 3.0);
  CHECK(d.highGainDb >= -1.0);
  CHECK(d.highGainDb <= 2.0);
  CHECK(d.lowGainDb > 0.0);  // the quiet level needs some bass
  CHECK(d.headroomDb == doctest::Approx(std::fmax(0.0, d.lowGainDb) + std::fmax(0.0, d.highGainDb)));
  CHECK(d.rmsErrorDb < 1.0);
  CHECK(sable::contourJson(0.0, 44100) == sable::contourJson(0.0, 44100));
  // Calibration shifts both levels: a louder output (+3) needs no more bass than a quieter one (-3).
  CHECK(sable::designQuietContour(3.0).lowGainDb <= sable::designQuietContour(-3.0).lowGainDb);
}

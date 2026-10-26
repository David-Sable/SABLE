// Quiet-mode loudness contour (MATH §24): ISO 226 equal-loudness compensation as one low shelf + one high shelf.
#pragma once

#include <array>
#include <string>

namespace sable {

// §24.1: SPL (dB) of a pure tone at table frequency index i (0..28, 20 Hz .. 12.5 kHz) with loudness level LN phon.
double iso226Spl(int i, double phon);
double iso226Freq(int i);

// §24.2: the target compensation H(f) (dB, relative to 1 kHz) at table index i, for output calibration calDb.
double contourTarget(int i, double calDb);

// RBJ cookbook shelving biquads, S = 1, normalised so a0 = 1: {b0, b1, b2, a1, a2}.
std::array<double, 5> lowShelf(double fs, double f0, double gainDb);
std::array<double, 5> highShelf(double fs, double f0, double gainDb);
double biquadResponseDb(const std::array<double, 5>& c, double fs, double f);

struct ContourDesign {
  double lowHz = 0.0, lowGainDb = 0.0, highHz = 0.0, highGainDb = 0.0;
  double headroomDb = 0.0;  // §24.4 H_contour
  double rmsErrorDb = 0.0;  // fit residual over the 1/3-octave grid
};

// §24.3: exhaustive grid search, responses evaluated at the reference rate 48 kHz (so the design is rate-independent).
ContourDesign designQuietContour(double calDb);

// JSON for the app: the design plus both biquads at the playback rate fs.
std::string contourJson(double calDb, double fs);

}  // namespace sable

// Deterministic math for the Sable engine. MATH §25.2.
// Built only from IEEE-754 + - * / and sqrt (exactly rounded), so results are
// bit-identical on x86-64 and ARM64. Platform libm is never used in the engine.
#pragma once

#include <cstdint>

namespace sable::dm {

double exp(double x);
double log(double x);    // natural log; x > 0
double log10(double x);  // x > 0
double pow(double x, double y);  // x > 0
double sin(double x);
double cos(double x);
double tan(double x);
double sqrt(double x);   // IEEE sqrt (exactly rounded)
double fabs(double x);

// 10^(db/20) and 10^(db/10), MATH §0.1.
inline double dbToAmplitude(double gainDb) { return pow(10.0, gainDb / 20.0); }
inline double dbToPower(double levelDb) { return pow(10.0, levelDb / 10.0); }

// Exact scaling by 2^k via the exponent field (no libm).
double scalbn(double x, int k);

inline constexpr double kPi = 3.14159265358979323846;

}  // namespace sable::dm

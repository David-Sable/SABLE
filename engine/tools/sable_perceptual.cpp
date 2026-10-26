// Host-only validation tool: run the perceptual reference (and the fast model) on a raw float32 interleaved
// stereo file and write long-term loudness per native frame. Usage:
//   sable_perceptual <in.f32> <sampleRate> <firPath> <out_ref.txt> <out_fast.txt> [dcalDb]
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "sable/perceptual.h"

int main(int argc, char** argv) {
  if (argc < 6) {
    std::fprintf(stderr, "usage: sable_perceptual <in.f32> <fs> <fir> <out_ref.txt> <out_fast.txt> [dcal]\n");
    return 2;
  }
  std::FILE* f = std::fopen(argv[1], "rb");
  if (!f) return 2;
  std::vector<float> x;
  float buf[4096];
  std::size_t n;
  while ((n = std::fread(buf, sizeof(float), 4096, f)) > 0) x.insert(x.end(), buf, buf + n);
  std::fclose(f);
  const int fs = std::atoi(argv[2]);
  const double dcal = argc > 6 ? std::atof(argv[6]) : 89.0;
  const auto ref = sable::referenceLoudness(x, fs, 2, 0.0, argv[3], dcal);
  sable::FastLoudness fast(fs, 2, 0.0, dcal);
  fast.push(x.data(), x.size() / 2);
  const auto fr = fast.finish();
  for (int k = 0; k < 2; ++k) {
    std::FILE* o = std::fopen(argv[4 + k], "w");
    if (!o) return 2;
    for (double v : (k == 0 ? ref.ltlPerFrame : fr.ltlPerFrame)) std::fprintf(o, "%.10g\n", v);
    std::fclose(o);
  }
  return 0;
}

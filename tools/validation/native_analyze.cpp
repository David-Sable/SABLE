#include <chrono>
#include <iostream>
#include "sable/audio_source.h"
int main(int argc, char** argv) {
  auto t0 = std::chrono::steady_clock::now();
  auto s = sable::openFlacPath(argv[1]);
  if (!s) { std::cout << "{\"open\":\"FAILED\"}\n"; return 0; }
  std::string j = sable::analyzeSource(*s);
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
  std::cout << ms << "\t" << s->totalFrames() << "\t" << j << "\n";
}

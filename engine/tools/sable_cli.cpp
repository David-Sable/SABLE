// sable_cli: analyze a WAV file (or WAV on stdin, e.g. from `ffmpeg -i x.flac -f wav -c:a pcm_f32le -`).
// Streams in chunks; never loads the whole file.
// Usage: sable_cli [--series] [--mask HEX] [--perceptual [--prescale DB]] <file.wav | ->
//        sable_cli --decide <file.wav>     full two-pass pipeline (MATH §12.4) + understanding + gain decision
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

#include "sable/analyzer.h"
#include "sable/intent.h"
#include "sable/perceptual.h"

namespace {

using u8 = unsigned char;

bool readExact(std::FILE* f, void* dst, std::size_t n) { return std::fread(dst, 1, n, f) == n; }
bool skipBytes(std::FILE* f, std::uint32_t n) {
  u8 tmp[4096];
  while (n > 0) {
    const std::size_t k = n < sizeof tmp ? n : sizeof tmp;
    if (!readExact(f, tmp, k)) return false;
    n -= static_cast<std::uint32_t>(k);
  }
  return true;
}
std::uint32_t le32(const u8* p) {
  return static_cast<std::uint32_t>(p[0]) | (static_cast<std::uint32_t>(p[1]) << 8U) |
         (static_cast<std::uint32_t>(p[2]) << 16U) | (static_cast<std::uint32_t>(p[3]) << 24U);
}
std::uint16_t le16(const u8* p) {
  return static_cast<std::uint16_t>(static_cast<unsigned>(p[0]) | (static_cast<unsigned>(p[1]) << 8U));
}
float pcm24(const u8* p) {
  const std::uint32_t u = (static_cast<std::uint32_t>(p[0]) << 8U) | (static_cast<std::uint32_t>(p[1]) << 16U) |
                          (static_cast<std::uint32_t>(p[2]) << 24U);
  return static_cast<float>(static_cast<double>(static_cast<std::int32_t>(u)) / 2147483648.0);
}

struct FileCloser {
  void operator()(std::FILE* f) const {
    if (f && f != stdin) std::fclose(f);
  }
};

struct WavInfo {
  int fs = 0, channels = 0;
  std::uint32_t mask = 0;
};

// Stream a WAV file: calls `begin` once with the format, then `chunk` for each block of float frames.
const char* readWav(const char* path, const std::function<void(const WavInfo&)>& begin,
                    const std::function<void(const float*, std::size_t)>& chunk) {
  std::unique_ptr<std::FILE, FileCloser> file;
  if (std::strcmp(path, "-") == 0) {
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
#endif
    file.reset(stdin);
  } else {
    file.reset(std::fopen(path, "rb"));
  }
  std::FILE* f = file.get();
  if (!f) return "cannot open input";
  u8 hdr[12];
  if (!readExact(f, hdr, 12) || std::memcmp(hdr, "RIFF", 4) != 0 || std::memcmp(hdr + 8, "WAVE", 4) != 0) return "not a RIFF/WAVE stream";
  unsigned fmtTag = 0, channels = 0, bits = 0;
  std::uint32_t fs = 0, mask = 0;
  for (;;) {
    u8 ck[8];
    if (!readExact(f, ck, 8)) return "no data chunk";
    const std::uint32_t size = le32(ck + 4);
    if (std::memcmp(ck, "fmt ", 4) == 0) {
      if (size < 16 || size > 4096) return "bad fmt chunk";
      std::vector<u8> b(size);
      if (!readExact(f, b.data(), size)) return "short fmt chunk";
      fmtTag = le16(&b[0]);
      channels = le16(&b[2]);
      fs = le32(&b[4]);
      bits = le16(&b[14]);
      if (fmtTag == 0xFFFEU && size >= 40) {
        mask = le32(&b[20]);
        fmtTag = le16(&b[24]);
      }
      if ((size & 1U) != 0U && !skipBytes(f, 1)) return "truncated";
    } else if (std::memcmp(ck, "data", 4) == 0) {
      break;
    } else if (!skipBytes(f, size + (size & 1U))) {
      return "truncated chunk";
    }
  }
  if (channels == 0 || fs == 0) return "bad fmt";
  if (!((fmtTag == 1 && (bits == 16 || bits == 24 || bits == 32)) || (fmtTag == 3 && bits == 32))) return "unsupported sample format";
  begin(WavInfo{static_cast<int>(fs), static_cast<int>(channels), mask});
  const std::size_t bytes = bits / 8, frameBytes = static_cast<std::size_t>(channels) * bytes, chunkFrames = 8192;
  std::vector<u8> raw(chunkFrames * frameBytes);
  std::vector<float> buf(chunkFrames * static_cast<std::size_t>(channels));
  std::size_t carry = 0;
  for (;;) {
    const std::size_t got = std::fread(raw.data() + carry, 1, raw.size() - carry, f) + carry;
    const std::size_t frames = got / frameBytes;
    if (frames == 0) break;
    const std::size_t n = frames * static_cast<std::size_t>(channels);
    for (std::size_t i = 0; i < n; ++i) {
      const u8* p = raw.data() + i * bytes;
      float v = 0.f;
      if (fmtTag == 3) std::memcpy(&v, p, 4);
      else if (bits == 16) v = static_cast<float>(static_cast<std::int16_t>(le16(p))) / 32768.0f;
      else if (bits == 24) v = pcm24(p);
      else v = static_cast<float>(static_cast<double>(static_cast<std::int32_t>(le32(p))) / 2147483648.0);
      buf[i] = v;
    }
    chunk(buf.data(), frames);
    carry = got - frames * frameBytes;
    if (carry != 0) std::memmove(raw.data(), raw.data() + frames * frameBytes, carry);
    if (got < raw.size() && (std::feof(f) != 0 || std::ferror(f) != 0)) break;
  }
  return nullptr;
}

int fail(const char* msg) {
  std::fprintf(stderr, "sable_cli: %s\n", msg);
  return 2;
}

std::string num(const std::optional<double>& v) {
  if (!v) return "null";
  char b[48];
  std::snprintf(b, sizeof b, "%.4f", *v);
  return b;
}

sable::PerceptualSeries perceptualPass(const char* path, double prescale) {
  std::unique_ptr<sable::FastLoudness> m;
  readWav(path, [&](const WavInfo& w) { m = std::make_unique<sable::FastLoudness>(w.fs, w.channels, prescale); },
          [&](const float* x, std::size_t n) { m->push(x, n); });
  return m->finish();
}

std::string decisionJson(const char* mode, const sable::GainDecision& d) {
  std::string o = std::string("\"") + mode + "\":{\"gainDb\":" + num(d.gainDb) + ",\"intentDb\":" + num(d.intentDb) +
                  ",\"capDb\":" + (d.capDb > 1e300 ? std::string("null") : num(d.capDb)) + ",\"rules\":[";
  for (std::size_t i = 0; i < d.rules.size(); ++i) {
    if (i) o += ',';
    o += "{\"id\":\"" + d.rules[i].id + "\",\"deltaDb\":" + num(d.rules[i].deltaDb) + "}";
  }
  return o + "]}";
}

int decide(const char* path) {  // MATH §12.4 two-pass pipeline, §13-§16
  std::unique_ptr<sable::Analyzer> an;
  const char* err = readWav(path, [&](const WavInfo& w) { an = std::make_unique<sable::Analyzer>(w.fs, w.channels, sable::channelWeightsFor(w.channels, w.mask)); },
                            [&](const float* x, std::size_t n) { an->push(x, n); });
  if (err) return fail(err);
  const auto r = an->finish();
  sable::GainContext std;
  std.targetLufs = -14.0;
  sable::TrackUnderstanding u;
  double s = 0.0;
  bool refined = false;
  if (r.integratedLufs) {
    s = -14.0 - *r.integratedLufs;
    auto ps = perceptualPass(path, s);
    u = sable::understand(r, &ps, s);
    const auto first = sable::decideTrackGain(r, u, std);
    double gAnchor = first.intentDb;
    for (const auto& rule : first.rules) if (rule.id == "ANCHOR_MATCH") gAnchor = rule.deltaDb;
    if (std::fabs(gAnchor - s) > 2.0) {  // one refinement (MATH §12.4)
      s = gAnchor;
      ps = perceptualPass(path, s);
      u = sable::understand(r, &ps, s);
      refined = true;
    }
  } else {
    u = sable::understand(r, nullptr, 0.0);
  }
  sable::GainContext quiet = std;
  quiet.targetLufs = -19.0;
  const auto dStd = sable::decideTrackGain(r, u, std), dQuiet = sable::decideTrackGain(r, u, quiet);
  std::string o = "{\"integratedLufs\":" + num(r.integratedLufs) + ",\"lraLu\":" + num(r.lraLu) + ",\"truePeakDbtp\":" + num(r.truePeakDbtp) +
                  ",\"anchorLufs\":" + num(u.anchorLufs) + ",\"anchorPle\":" + num(u.anchorPle) + ",\"anchorSec\":" + num(u.anchorSec) +
                  ",\"nonSilentSec\":" + num(u.nonSilentSec) + ",\"anchorFallback\":" + (u.anchorFallback ? "true" : "false") +
                  ",\"character\":\"" + sable::toString(u.character) + "\",\"tilt\":" + num(u.tiltDbPerOct) + ",\"density\":" + num(u.density) +
                  ",\"densityAnchor\":" + num(u.densityAnchor) + ",\"transientsPerSec\":" + num(u.transientsPerSec) +
                  ",\"plrAnchor\":" + num(u.plrAnchorDb) + ",\"sectionContrast\":" + num(u.sectionContrastLu) + ",\"prescaleDb\":" + num(s) +
                  ",\"refined\":" + (refined ? "true" : "false") + ",\"sections\":[";
  for (std::size_t i = 0; i < u.sections.size(); ++i) {
    const auto& sec = u.sections[i];
    if (i) o += ',';
    o += "{\"type\":\"" + std::string(sable::toString(sec.type)) + "\",\"start\":" + num(sec.startSec) + ",\"end\":" + num(sec.endSec) +
         ",\"lufs\":" + num(sec.loudnessLufs) + "}";
  }
  o += "]," + decisionJson("standard", dStd) + "," + decisionJson("quiet", dQuiet) + "}";
  std::puts(o.c_str());
  return 0;
}

int run(int argc, char** argv) {
  bool series = false, perceptual = false, decideMode = false;
  double prescaleDb = 0.0;
  std::uint32_t maskOverride = 0;
  const char* path = nullptr;
  for (int i = 1; i < argc; ++i) {
    if (std::strcmp(argv[i], "--series") == 0) series = true;
    else if (std::strcmp(argv[i], "--perceptual") == 0) perceptual = true;
    else if (std::strcmp(argv[i], "--decide") == 0) decideMode = true;
    else if (std::strcmp(argv[i], "--prescale") == 0 && i + 1 < argc) prescaleDb = std::strtod(argv[++i], nullptr);
    else if (std::strcmp(argv[i], "--mask") == 0 && i + 1 < argc) maskOverride = static_cast<std::uint32_t>(std::strtoul(argv[++i], nullptr, 16));
    else path = argv[i];
  }
  if (!path) return fail("usage: sable_cli [--series] [--mask HEX] [--perceptual [--prescale DB]] | --decide <file.wav | ->");
  if (decideMode) {
    if (std::strcmp(path, "-") == 0) return fail("--decide needs a file (two passes)");
    return decide(path);
  }
  std::unique_ptr<sable::Analyzer> an;
  std::unique_ptr<sable::FastLoudness> fast;
  const char* err = readWav(path,
      [&](const WavInfo& w) {
        an = std::make_unique<sable::Analyzer>(w.fs, w.channels, sable::channelWeightsFor(w.channels, maskOverride ? maskOverride : w.mask));
        if (perceptual) fast = std::make_unique<sable::FastLoudness>(w.fs, w.channels, prescaleDb);
      },
      [&](const float* x, std::size_t n) {
        an->push(x, n);
        if (fast) fast->push(x, n);
      });
  if (err) return fail(err);
  std::string json = sable::toJson(an->finish(), series);
  if (fast) {  // perceptual summary: mean and max long-term loudness (sones)
    const auto ps = fast->finish();
    double sum = 0.0, mx = 0.0;
    std::size_t n = 0;
    for (const auto& v : ps.ltlSone) if (v) { sum += *v; mx = std::max(mx, *v); ++n; }
    char tail[160];
    std::snprintf(tail, sizeof tail, ",\"ltlMeanSone\":%.9g,\"ltlMaxSone\":%.9g}", n ? sum / static_cast<double>(n) : 0.0, mx);
    json.pop_back();
    json += tail;
  }
  std::puts(json.c_str());
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  try {
    return run(argc, argv);
  } catch (...) {
    return fail("unexpected error");
  }
}

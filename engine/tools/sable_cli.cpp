// sable_cli: analyze a WAV file (or WAV on stdin, e.g. from `ffmpeg -i x.flac -f wav -c:a pcm_f32le -`)
// and print the engine's JSON result. Streams in chunks; never loads the whole file.
// Usage: sable_cli [--series] [--mask HEX] <file.wav | ->
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

#include "sable/analyzer.h"

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

int fail(const char* msg) {
  std::fprintf(stderr, "sable_cli: %s\n", msg);
  return 2;
}

struct FileCloser {
  void operator()(std::FILE* f) const {
    if (f && f != stdin) std::fclose(f);
  }
};

int run(int argc, char** argv) {
  bool series = false;
  std::uint32_t maskOverride = 0;
  const char* path = nullptr;
  for (int i = 1; i < argc; ++i) {
    if (std::strcmp(argv[i], "--series") == 0) series = true;
    else if (std::strcmp(argv[i], "--mask") == 0 && i + 1 < argc) maskOverride = static_cast<std::uint32_t>(std::strtoul(argv[++i], nullptr, 16));
    else path = argv[i];
  }
  if (!path) return fail("usage: sable_cli [--series] [--mask HEX] <file.wav | ->");

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
  if (!f) return fail("cannot open input");

  u8 hdr[12];
  if (!readExact(f, hdr, 12) || std::memcmp(hdr, "RIFF", 4) != 0 || std::memcmp(hdr + 8, "WAVE", 4) != 0) return fail("not a RIFF/WAVE stream");

  unsigned fmtTag = 0, channels = 0, bits = 0;
  std::uint32_t fs = 0, mask = 0;
  for (;;) {
    u8 ck[8];
    if (!readExact(f, ck, 8)) return fail("no data chunk");
    const std::uint32_t size = le32(ck + 4);
    if (std::memcmp(ck, "fmt ", 4) == 0) {
      if (size < 16 || size > 4096) return fail("bad fmt chunk");
      std::vector<u8> b(size);
      if (!readExact(f, b.data(), size)) return fail("short fmt chunk");
      fmtTag = le16(&b[0]);
      channels = le16(&b[2]);
      fs = le32(&b[4]);
      bits = le16(&b[14]);
      if (fmtTag == 0xFFFEU && size >= 40) {
        mask = le32(&b[20]);
        fmtTag = le16(&b[24]);  // SubFormat GUID's first two bytes
      }
      if ((size & 1U) != 0U && !skipBytes(f, 1)) return fail("truncated");
    } else if (std::memcmp(ck, "data", 4) == 0) {
      break;  // stream the data; size may be 0xFFFFFFFF when piped
    } else if (!skipBytes(f, size + (size & 1U))) {
      return fail("truncated chunk");
    }
  }
  if (channels == 0 || fs == 0) return fail("bad fmt");
  const bool ok = (fmtTag == 1 && (bits == 16 || bits == 24 || bits == 32)) || (fmtTag == 3 && bits == 32);
  if (!ok) return fail("unsupported sample format");
  if (maskOverride != 0) mask = maskOverride;

  const int ch = static_cast<int>(channels);
  sable::Analyzer an(static_cast<int>(fs), ch, sable::channelWeightsFor(ch, mask));
  const std::size_t bytes = bits / 8;
  const std::size_t frameBytes = static_cast<std::size_t>(channels) * bytes;
  const std::size_t chunkFrames = 8192;
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
    an.push(buf.data(), frames);
    carry = got - frames * frameBytes;
    if (carry != 0) std::memmove(raw.data(), raw.data() + frames * frameBytes, carry);
    if (got < raw.size() && (std::feof(f) != 0 || std::ferror(f) != 0)) break;
  }
  std::puts(sable::toJson(an.finish(), series).c_str());
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

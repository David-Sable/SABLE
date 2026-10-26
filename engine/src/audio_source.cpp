// In-engine FLAC / MP3 sources and the native analysis drivers. See audio_source.h.
#include "sable/audio_source.h"

#include <algorithm>
#include <cstdio>
#include <vector>

#if defined(_WIN32)
#include <io.h>
#define SABLE_DUP _dup
#define SABLE_FDOPEN _fdopen
#define SABLE_FSEEK _fseeki64
#define SABLE_FTELL _ftelli64
#else
#include <unistd.h>
#define SABLE_DUP dup
#define SABLE_FDOPEN fdopen
#define SABLE_FSEEK fseeko
#define SABLE_FTELL ftello
#endif

#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Weverything"
#endif
#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#define DR_FLAC_NO_OGG
#include "../third_party/dr_libs/dr_flac.h"
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_NO_SIMD  // scalar float code: identical results on x86-64 and ARM64 (MATH §25)
#define MINIMP3_NO_STDIO
#include "../third_party/minimp3/minimp3_ex.h"
#if defined(__clang__)
#pragma clang diagnostic pop
#endif

#include "sable/pipeline.h"

namespace sable {
namespace {

constexpr std::size_t kChunk = 4096;

std::FILE* dupFile(int fd) {
  const int d = SABLE_DUP(fd);
  return d < 0 ? nullptr : SABLE_FDOPEN(d, "rb");
}

// ---------------- FLAC ----------------
size_t flacRead(void* user, void* out, size_t bytes) { return std::fread(out, 1, bytes, static_cast<std::FILE*>(user)); }
drflac_bool32 flacSeek(void* user, int offset, drflac_seek_origin origin) {
  const int whence = origin == DRFLAC_SEEK_SET ? SEEK_SET : (origin == DRFLAC_SEEK_CUR ? SEEK_CUR : SEEK_END);
  return SABLE_FSEEK(static_cast<std::FILE*>(user), offset, whence) == 0 ? DRFLAC_TRUE : DRFLAC_FALSE;
}
drflac_bool32 flacTell(void* user, drflac_int64* cursor) {
  const long long p = SABLE_FTELL(static_cast<std::FILE*>(user));
  if (p < 0) return DRFLAC_FALSE;
  *cursor = p;
  return DRFLAC_TRUE;
}

class FlacSource final : public AudioSource {
 public:
  static std::unique_ptr<AudioSource> open(std::FILE* f) {
    if (!f) return nullptr;
    std::unique_ptr<FlacSource> s(new FlacSource());
    s->file_ = f;
    s->flac_ = drflac_open(flacRead, flacSeek, flacTell, f, nullptr);
    if (!s->flac_) return nullptr;
    return s;
  }
  ~FlacSource() override {
    if (flac_) drflac_close(flac_);
    if (file_) std::fclose(file_);
  }
  int sampleRate() const override { return static_cast<int>(flac_->sampleRate); }
  int channels() const override { return flac_->channels; }
  std::uint64_t totalFrames() const override { return flac_->totalPCMFrameCount; }
  std::size_t read(float* out, std::size_t frames) override {
    const std::size_t ch = static_cast<std::size_t>(channels());
    s32_.resize(frames * ch);
    const auto got = static_cast<std::size_t>(drflac_read_pcm_frames_s32(flac_, frames, s32_.data()));
    // dr_flac left-justifies samples in int32: scaling by 2^-31 is exact for <= 24-bit sources.
    for (std::size_t i = 0; i < got * ch; ++i) out[i] = static_cast<float>(s32_[i]) * (1.0f / 2147483648.0f);
    return got;
  }
  bool seek(std::uint64_t frame) override { return drflac_seek_to_pcm_frame(flac_, frame) == DRFLAC_TRUE; }

 private:
  FlacSource() = default;
  std::FILE* file_ = nullptr;
  drflac* flac_ = nullptr;
  std::vector<drflac_int32> s32_;
};

// ---------------- MP3 ----------------
size_t mp3Read(void* buf, size_t size, void* user) { return std::fread(buf, 1, size, static_cast<std::FILE*>(user)); }
int mp3Seek(uint64_t pos, void* user) { return SABLE_FSEEK(static_cast<std::FILE*>(user), static_cast<long long>(pos), SEEK_SET); }

class Mp3Source final : public AudioSource {
 public:
  static std::unique_ptr<AudioSource> open(std::FILE* f) {
    if (!f) return nullptr;
    std::unique_ptr<Mp3Source> s(new Mp3Source());
    s->file_ = f;
    s->io_.read = mp3Read;
    s->io_.read_data = f;
    s->io_.seek = mp3Seek;
    s->io_.seek_data = f;
    if (mp3dec_ex_open_cb(&s->dec_, &s->io_, MP3D_SEEK_TO_SAMPLE) != 0 || s->dec_.info.channels <= 0) return nullptr;
    s->open_ = true;
    return s;
  }
  ~Mp3Source() override {
    if (open_) mp3dec_ex_close(&dec_);
    if (file_) std::fclose(file_);
  }
  int sampleRate() const override { return dec_.info.hz; }
  int channels() const override { return dec_.info.channels; }
  std::uint64_t totalFrames() const override { return dec_.samples / static_cast<std::uint64_t>(dec_.info.channels); }
  std::size_t read(float* out, std::size_t frames) override {
    const std::size_t ch = static_cast<std::size_t>(channels());
    return mp3dec_ex_read(&dec_, out, frames * ch) / ch;  // float output; values beyond +-1 are kept
  }
  bool seek(std::uint64_t frame) override { return mp3dec_ex_seek(&dec_, frame * static_cast<std::uint64_t>(channels())) == 0; }

 private:
  Mp3Source() = default;
  std::FILE* file_ = nullptr;
  mp3dec_io_t io_{};
  mp3dec_ex_t dec_{};
  bool open_ = false;
};

}  // namespace

std::unique_ptr<AudioSource> openFlacPath(const char* path) { return FlacSource::open(std::fopen(path, "rb")); }
std::unique_ptr<AudioSource> openFlacFd(int fd) { return FlacSource::open(dupFile(fd)); }
std::unique_ptr<AudioSource> openMp3Path(const char* path) { return Mp3Source::open(std::fopen(path, "rb")); }
std::unique_ptr<AudioSource> openMp3Fd(int fd) { return Mp3Source::open(dupFile(fd)); }

std::string analyzeSource(AudioSource& src) {
  const int ch = src.channels();
  TrackPipeline p(src.sampleRate(), ch, 0, false);  // in-engine float decodes are never clipped (MATH §1.1)
  std::vector<float> buf(kChunk * static_cast<std::size_t>(ch));
  src.seek(0);
  for (std::size_t n; (n = src.read(buf.data(), kChunk)) > 0;) p.pushPass1(buf.data(), n);
  if (p.endPass1()) {
    do {
      p.beginPass2();
      src.seek(0);
      for (std::size_t n; (n = src.read(buf.data(), kChunk)) > 0;) p.pushPass2(buf.data(), n);
    } while (p.endPass2());
  }
  return p.resultJson();
}

std::string jitSource(AudioSource& src, double ceilingDbtp) {  // MATH §22.2 excerpts
  const int ch = src.channels();
  const std::uint64_t fs = static_cast<std::uint64_t>(src.sampleRate()), total = src.totalFrames();
  JitPipeline j(src.sampleRate(), ch, 0);
  std::vector<float> buf(kChunk * static_cast<std::size_t>(ch));
  auto excerpt = [&](std::uint64_t start, std::uint64_t len, bool middle) {
    if (len == 0 || (total > 0 && start >= total)) return;
    if (total > 0 && start + len > total) len = total - start;
    src.seek(start);
    j.beginExcerpt(middle);
    for (std::uint64_t done = 0; done < len;) {
      const std::size_t want = static_cast<std::size_t>(std::min<std::uint64_t>(kChunk, len - done));
      const std::size_t n = src.read(buf.data(), want);
      if (n == 0) break;
      j.push(buf.data(), n);
      done += n;
    }
    j.endExcerpt();
  };
  excerpt(0, 10 * fs, false);
  for (double p : {0.3, 0.5, 0.7}) {
    const auto c = static_cast<std::uint64_t>(static_cast<double>(total) * p);
    const std::uint64_t half = 15 * fs / 2;
    excerpt(c > half ? c - half : 0, 15 * fs, true);
  }
  return j.resultJson(ceilingDbtp);
}

}  // namespace sable

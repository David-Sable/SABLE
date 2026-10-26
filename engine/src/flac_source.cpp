// dr_flac-backed FLAC source and native FLAC drivers for the track / JIT pipelines.
#include "sable/flac_source.h"

#include <algorithm>
#include <cstdio>
#include <vector>

#if defined(_WIN32)
#include <io.h>
#define SABLE_DUP _dup
#define SABLE_FDOPEN _fdopen
#define SABLE_FSEEK _fseeki64
#else
#include <unistd.h>
#define SABLE_DUP dup
#define SABLE_FDOPEN fdopen
#define SABLE_FSEEK fseeko
#endif

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#define DR_FLAC_NO_OGG
#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Weverything"
#endif
#include "../third_party/dr_libs/dr_flac.h"
#if defined(__clang__)
#pragma clang diagnostic pop
#endif

#include "sable/pipeline.h"

namespace sable {
namespace {

size_t onRead(void* user, void* out, size_t bytes) { return std::fread(out, 1, bytes, static_cast<std::FILE*>(user)); }
drflac_bool32 onSeek(void* user, int offset, drflac_seek_origin origin) {
  const int whence = origin == DRFLAC_SEEK_SET ? SEEK_SET : (origin == DRFLAC_SEEK_CUR ? SEEK_CUR : SEEK_END);
  return SABLE_FSEEK(static_cast<std::FILE*>(user), offset, whence) == 0 ? DRFLAC_TRUE : DRFLAC_FALSE;
}
drflac_bool32 onTell(void* user, drflac_int64* cursor) {
#if defined(_WIN32)
  const long long p = _ftelli64(static_cast<std::FILE*>(user));
#else
  const long long p = ftello(static_cast<std::FILE*>(user));
#endif
  if (p < 0) return DRFLAC_FALSE;
  *cursor = p;
  return DRFLAC_TRUE;
}

constexpr std::size_t kChunk = 4096;

}  // namespace

struct FlacSource::Impl {
  std::FILE* file = nullptr;
  drflac* flac = nullptr;
  std::vector<drflac_int32> s32;
  ~Impl() {
    if (flac) drflac_close(flac);
    if (file) std::fclose(file);
  }
};

static std::unique_ptr<FlacSource::Impl> openFile(std::FILE* f) {
  if (!f) return nullptr;
  auto impl = std::make_unique<FlacSource::Impl>();
  impl->file = f;
  impl->flac = drflac_open(onRead, onSeek, onTell, f, nullptr);
  if (!impl->flac) return nullptr;
  return impl;
}

std::unique_ptr<FlacSource> FlacSource::openPath(const char* path) {
  auto impl = openFile(std::fopen(path, "rb"));
  if (!impl) return nullptr;
  std::unique_ptr<FlacSource> s(new FlacSource());
  s->impl_ = std::move(impl);
  return s;
}

std::unique_ptr<FlacSource> FlacSource::openFd(int fd) {
  const int d = SABLE_DUP(fd);
  if (d < 0) return nullptr;
  auto impl = openFile(SABLE_FDOPEN(d, "rb"));
  if (!impl) return nullptr;
  std::unique_ptr<FlacSource> s(new FlacSource());
  s->impl_ = std::move(impl);
  return s;
}

FlacSource::~FlacSource() = default;
int FlacSource::sampleRate() const { return static_cast<int>(impl_->flac->sampleRate); }
int FlacSource::channels() const { return impl_->flac->channels; }
std::uint64_t FlacSource::totalFrames() const { return impl_->flac->totalPCMFrameCount; }

std::size_t FlacSource::read(float* out, std::size_t frames) {
  const std::size_t ch = static_cast<std::size_t>(channels());
  impl_->s32.resize(frames * ch);
  const auto got = static_cast<std::size_t>(drflac_read_pcm_frames_s32(impl_->flac, frames, impl_->s32.data()));
  // dr_flac left-justifies samples in int32; dividing by 2^31 is exact for <= 24-bit sources.
  for (std::size_t i = 0; i < got * ch; ++i) out[i] = static_cast<float>(impl_->s32[i]) * (1.0f / 2147483648.0f);
  return got;
}

bool FlacSource::seek(std::uint64_t frame) { return drflac_seek_to_pcm_frame(impl_->flac, frame) == DRFLAC_TRUE; }

std::string analyzeFlac(FlacSource& src) {
  const int ch = src.channels();
  TrackPipeline p(src.sampleRate(), ch, 0, false);  // lossless: never decode-clipped (MATH §1.1)
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

std::string jitFlac(FlacSource& src, double ceilingDbtp) {  // MATH §22.2 excerpts
  const int ch = src.channels();
  const std::uint64_t fs = static_cast<std::uint64_t>(src.sampleRate()), total = src.totalFrames();
  JitPipeline j(src.sampleRate(), ch, 0);
  std::vector<float> buf(kChunk * static_cast<std::size_t>(ch));
  auto excerpt = [&](std::uint64_t start, std::uint64_t len, bool middle) {
    if (len == 0 || start >= total) return;
    if (start + len > total) len = total - start;
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
    const std::uint64_t s = c > half ? c - half : 0;
    excerpt(s, 15 * fs, true);
  }
  return j.resultJson(ceilingDbtp);
}

}  // namespace sable

// JNI bridge to the Sable engine. Thin by design: all analysis logic lives in the portable engine (MATH ยง25).
// Audio is passed as direct float ByteBuffers (no copies).
#include <jni.h>

#include <string>

#include "sable/audio_source.h"
#include "sable/pipeline.h"
#include "sable/contour.h"
#include "sable/plan.h"

namespace {

const float* floats(JNIEnv* env, jobject buffer) { return static_cast<const float*>(env->GetDirectBufferAddress(buffer)); }
jstring str(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }
sable::TrackPipeline* track(jlong h) { return reinterpret_cast<sable::TrackPipeline*>(h); }
sable::JitPipeline* jit(jlong h) { return reinterpret_cast<sable::JitPipeline*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_sable_engine_SableEngine_trackCreate(JNIEnv*, jclass, jint fs, jint ch, jint mask, jboolean clipped) {
  return reinterpret_cast<jlong>(new sable::TrackPipeline(fs, ch, static_cast<std::uint32_t>(mask), clipped == JNI_TRUE));
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_trackPushPass1(JNIEnv* env, jclass, jlong h, jobject buf, jint frames) {
  if (h == 0) return;
  track(h)->pushPass1(floats(env, buf), static_cast<std::size_t>(frames));
}
JNIEXPORT jboolean JNICALL Java_app_sable_engine_SableEngine_trackEndPass1(JNIEnv*, jclass, jlong h) {
  if (h == 0) return JNI_FALSE;
  return track(h)->endPass1() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_trackBeginPass2(JNIEnv*, jclass, jlong h) { if (h == 0) return; track(h)->beginPass2(); }
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_trackPushPass2(JNIEnv* env, jclass, jlong h, jobject buf, jint frames) {
  if (h == 0) return;
  track(h)->pushPass2(floats(env, buf), static_cast<std::size_t>(frames));
}
JNIEXPORT jboolean JNICALL Java_app_sable_engine_SableEngine_trackEndPass2(JNIEnv*, jclass, jlong h) {
  if (h == 0) return JNI_FALSE;
  return track(h)->endPass2() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_trackResult(JNIEnv* env, jclass, jlong h) { if (h == 0) return str(env, ""); return str(env, track(h)->resultJson()); }
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_trackDestroy(JNIEnv*, jclass, jlong h) { if (h == 0) return; delete track(h); }

JNIEXPORT jlong JNICALL Java_app_sable_engine_SableEngine_jitCreate(JNIEnv*, jclass, jint fs, jint ch, jint mask) {
  return reinterpret_cast<jlong>(new sable::JitPipeline(fs, ch, static_cast<std::uint32_t>(mask)));
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitBegin(JNIEnv*, jclass, jlong h, jboolean middle) { if (h == 0) return; jit(h)->beginExcerpt(middle == JNI_TRUE); }
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitPush(JNIEnv* env, jclass, jlong h, jobject buf, jint frames) {
  if (h == 0) return;
  jit(h)->push(floats(env, buf), static_cast<std::size_t>(frames));
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitEnd(JNIEnv*, jclass, jlong h) { if (h == 0) return; jit(h)->endExcerpt(); }
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_jitResult(JNIEnv* env, jclass, jlong h, jdouble ceiling) {
  if (h == 0) return str(env, "");
  return str(env, jit(h)->resultJson(ceiling));
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitDestroy(JNIEnv*, jclass, jlong h) { if (h == 0) return; delete jit(h); }

// FLAC (dr_flac) and MP3 (minimp3) are decoded inside the engine from a file descriptor.
// kind: 0 = FLAC, 1 = MP3. Returns "" if the file cannot be opened.
static std::unique_ptr<sable::AudioSource> openKind(int fd, int kind) { return kind == 1 ? sable::openMp3Fd(fd) : sable::openFlacFd(fd); }
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_analyzeNativeFd(JNIEnv* env, jclass, jint fd, jint kind) {
  auto src = openKind(fd, kind);
  return str(env, src ? sable::analyzeSource(*src) : std::string());
}
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_jitNativeFd(JNIEnv* env, jclass, jint fd, jint kind, jdouble ceiling) {
  auto src = openKind(fd, kind);
  return str(env, src ? sable::jitSource(*src, ceiling) : std::string());
}

// Queue planner (MATH ง17.1, ง18, ง21, ง22). JSON in/out: docs/plan-json.md.
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_planQueue(JNIEnv* env, jclass, jstring input) {
  const char* c = env->GetStringUTFChars(input, nullptr);
  std::string in(c ? c : "");
  if (c) env->ReleaseStringUTFChars(input, c);
  return str(env, sable::planQueueJson(in));
}

JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_learnUpdate(JNIEnv* env, jclass, jstring input) {
  const char* c = env->GetStringUTFChars(input, nullptr);
  std::string in(c ? c : "");
  if (c) env->ReleaseStringUTFChars(input, c);
  return str(env, sable::learnUpdateJson(in));
}

// Quiet-mode contour (MATH ง24): design for a calibration offset, with biquads at the playback rate.
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_contourDesign(JNIEnv* env, jclass, jdouble calDb, jdouble fs) {
  return str(env, sable::contourJson(calDb, fs));
}

}  // extern "C"

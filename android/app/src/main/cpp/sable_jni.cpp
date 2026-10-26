// JNI bridge to the Sable engine. Thin by design: all analysis logic lives in the portable engine (MATH §25).
// Audio is passed as direct float ByteBuffers (no copies).
#include <jni.h>

#include <string>

#include "sable/pipeline.h"

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
  track(h)->pushPass1(floats(env, buf), static_cast<std::size_t>(frames));
}
JNIEXPORT jboolean JNICALL Java_app_sable_engine_SableEngine_trackEndPass1(JNIEnv*, jclass, jlong h) {
  return track(h)->endPass1() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_trackBeginPass2(JNIEnv*, jclass, jlong h) { track(h)->beginPass2(); }
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_trackPushPass2(JNIEnv* env, jclass, jlong h, jobject buf, jint frames) {
  track(h)->pushPass2(floats(env, buf), static_cast<std::size_t>(frames));
}
JNIEXPORT jboolean JNICALL Java_app_sable_engine_SableEngine_trackEndPass2(JNIEnv*, jclass, jlong h) {
  return track(h)->endPass2() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_trackResult(JNIEnv* env, jclass, jlong h) { return str(env, track(h)->resultJson()); }
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_trackDestroy(JNIEnv*, jclass, jlong h) { delete track(h); }

JNIEXPORT jlong JNICALL Java_app_sable_engine_SableEngine_jitCreate(JNIEnv*, jclass, jint fs, jint ch, jint mask) {
  return reinterpret_cast<jlong>(new sable::JitPipeline(fs, ch, static_cast<std::uint32_t>(mask)));
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitBegin(JNIEnv*, jclass, jlong h, jboolean middle) { jit(h)->beginExcerpt(middle == JNI_TRUE); }
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitPush(JNIEnv* env, jclass, jlong h, jobject buf, jint frames) {
  jit(h)->push(floats(env, buf), static_cast<std::size_t>(frames));
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitEnd(JNIEnv*, jclass, jlong h) { jit(h)->endExcerpt(); }
JNIEXPORT jstring JNICALL Java_app_sable_engine_SableEngine_jitResult(JNIEnv* env, jclass, jlong h, jdouble ceiling) {
  return str(env, jit(h)->resultJson(ceiling));
}
JNIEXPORT void JNICALL Java_app_sable_engine_SableEngine_jitDestroy(JNIEnv*, jclass, jlong h) { delete jit(h); }

}  // extern "C"

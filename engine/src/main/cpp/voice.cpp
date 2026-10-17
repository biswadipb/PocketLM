// Piko's own voice: text to speech by a model on the phone. The work is in voice_core.h; this file is the bridge to Kotlin.

#include <jni.h>
#include <algorithm>
#include <string>
#include <unistd.h>
#include <vector>

#include "voice_core.h"

namespace {

pocketlm::VoiceSession g_voice;

std::string to_std(JNIEnv *env, jstring text) {
    if (!text) { return {}; }
    const auto *chars = env->GetStringUTFChars(text, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(text, chars);
    return out;
}

}  // namespace

/** Loads the voice model and its audio add-on. Returns the sample rate of its speech, or a negative code (see VoiceSession::load). */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_VoiceEngineImpl_nativeLoad(
        JNIEnv *env, jobject /*unused*/, jstring jmodel, jstring jprojector, jint n_threads) {
    const int cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int threads = n_threads > 0 ? std::min((int) n_threads, cores) : std::max(2, std::min(4, cores - 2));
    return g_voice.load(to_std(env, jmodel), to_std(env, jprojector), threads, /* cpu_only */ true);
}

/** Speaks the text; returns mono samples, or null (see nativeLastError). */
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_arm_aichat_internal_VoiceEngineImpl_nativeSpeak(
        JNIEnv *env, jobject /*unused*/, jstring jtext, jstring jlanguage, jstring jspeaker, jint max_frames,
        jint top_k, jfloat top_p, jint seed) {
    std::vector<float> pcm;
    const uint32_t seed_value = seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed;
    if (!g_voice.speak(to_std(env, jtext), to_std(env, jlanguage), to_std(env, jspeaker), max_frames, top_k, top_p, seed_value, pcm)) {
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray((jsize) pcm.size());
    if (!out) { return nullptr; }
    env->SetFloatArrayRegion(out, 0, (jsize) pcm.size(), pcm.data());
    return out;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_VoiceEngineImpl_nativeLastError(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(g_voice.error().c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_VoiceEngineImpl_nativeCancel(JNIEnv * /*env*/, jobject /*unused*/) {
    g_voice.cancel();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_VoiceEngineImpl_nativeUnload(JNIEnv * /*env*/, jobject /*unused*/) {
    g_voice.release();
}

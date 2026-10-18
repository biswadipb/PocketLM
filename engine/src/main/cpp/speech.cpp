// Speech to text, on the phone. An audio-capable model (Qwen3-ASR) listens through llama.cpp's multimodal library and
// writes down what it hears. This is a second, separate model with its own context: it does not touch the chat model.

#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "logging.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"

namespace {

constexpr int SPEECH_CONTEXT = 4096;
constexpr int SPEECH_BATCH   = 512;

llama_model               * g_sp_model = nullptr;
llama_context             * g_sp_ctx   = nullptr;
mtmd_context              * g_sp_mtmd  = nullptr;
common_chat_templates_ptr   g_sp_templates;
ggml_backend_dev_t          g_sp_no_devices[1] = {nullptr};

void free_speech() {
    if (g_sp_mtmd)  { mtmd_free(g_sp_mtmd);          g_sp_mtmd = nullptr; }
    g_sp_templates.reset();
    if (g_sp_ctx)   { llama_free(g_sp_ctx);          g_sp_ctx = nullptr; }
    if (g_sp_model) { llama_model_free(g_sp_model);  g_sp_model = nullptr; }
}

bool valid_utf8(const std::string &text) {
    const auto *bytes = reinterpret_cast<const unsigned char *>(text.c_str());
    while (*bytes) {
        int extra;
        if ((*bytes & 0x80) == 0x00) extra = 0;
        else if ((*bytes & 0xE0) == 0xC0) extra = 1;
        else if ((*bytes & 0xF0) == 0xE0) extra = 2;
        else if ((*bytes & 0xF8) == 0xF0) extra = 3;
        else return false;
        bytes++;
        for (int i = 0; i < extra; i++, bytes++) {
            if ((*bytes & 0xC0) != 0x80) return false;
        }
    }
    return true;
}

}  // namespace

/**
 * Loads the speech model [jmodel] and its audio add-on [jprojector]. Returns the sample rate the model listens at
 * (16000), or -1 if the model could not be loaded, -2 if the add-on could not be, -3 if the pair cannot take audio.
 * It always runs on the CPU, so the chat model keeps the GPU to itself.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_SpeechEngineImpl_nativeLoad(
        JNIEnv *env, jobject /*unused*/, jstring jmodel, jstring jprojector, jint n_threads) {
    free_speech();

    const auto *model_chars = env->GetStringUTFChars(jmodel, nullptr);
    const std::string model_path(model_chars);
    env->ReleaseStringUTFChars(jmodel, model_chars);
    const auto *proj_chars = env->GetStringUTFChars(jprojector, nullptr);
    const std::string projector_path(proj_chars);
    env->ReleaseStringUTFChars(jprojector, proj_chars);

    llama_model_params model_params = llama_model_default_params();
    model_params.devices = g_sp_no_devices;
    model_params.n_gpu_layers = 0;
    g_sp_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!g_sp_model) { return -1; }

    const int cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int threads = n_threads > 0 ? std::min((int) n_threads, cores) : std::max(2, std::min(4, cores - 2));

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = SPEECH_CONTEXT;
    ctx_params.n_batch = SPEECH_BATCH;
    ctx_params.n_ubatch = SPEECH_BATCH;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    g_sp_ctx = llama_init_from_model(g_sp_model, ctx_params);
    if (!g_sp_ctx) {
        free_speech();
        return -1;
    }

    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.print_timings = false;
    params.warmup = false;
    params.n_threads = threads;
    g_sp_mtmd = mtmd_init_from_file(projector_path.c_str(), g_sp_model, params);
    if (!g_sp_mtmd) {
        free_speech();
        return -2;
    }
    if (!mtmd_support_audio(g_sp_mtmd)) {
        free_speech();
        return -3;
    }
    g_sp_templates = common_chat_templates_init(g_sp_model, "");
    const int rate = mtmd_get_audio_sample_rate(g_sp_mtmd);
    LOGi("speech: loaded, listens at %d Hz", rate);
    return rate > 0 ? rate : 16000;
}

/**
 * Writes down the speech in [jsamples] (mono, floats from -1 to 1, at the rate the model listens at). Returns the raw
 * text the model produced, or null if it could not be run.
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_SpeechEngineImpl_nativeTranscribe(
        JNIEnv *env, jobject /*unused*/, jfloatArray jsamples, jint max_tokens) {
    if (!g_sp_mtmd || !g_sp_ctx) { return nullptr; }

    const jsize n_samples = env->GetArrayLength(jsamples);
    if (n_samples < 1600) { return env->NewStringUTF(""); }  // less than a tenth of a second
    std::vector<float> samples((size_t) n_samples);
    env->GetFloatArrayRegion(jsamples, 0, n_samples, samples.data());

    llama_memory_clear(llama_get_memory(g_sp_ctx), true);

    // The question the model is asked is the one llama.cpp uses for its speech models.
    const common_chat_prompt_preset asr = common_chat_get_asr_prompt(g_sp_templates.get());
    std::vector<common_chat_msg> history;
    std::string formatted;
    const auto add = [&](const std::string &role, const std::string &content, bool add_assistant) {
        common_chat_msg message;
        message.role = role;
        message.content = content;
        formatted += common_chat_format_single(g_sp_templates.get(), history, message, add_assistant, /* use_jinja */ false);
        history.push_back(message);
    };
    if (!asr.system.empty()) { add("system", asr.system, false); }
    add("user", std::string(mtmd_default_marker()) + asr.user, true);

    mtmd_bitmap *bitmap = mtmd_bitmap_init_from_audio(samples.size(), samples.data());
    if (!bitmap) { return nullptr; }
    mtmd_input_text text{formatted.c_str(), formatted.size(), /* add_special */ true, /* parse_special */ true};
    const mtmd_bitmap *bitmaps[1] = {bitmap};
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();

    std::string result;
    bool ok = false;
    if (mtmd_tokenize(g_sp_mtmd, chunks, &text, bitmaps, 1) != 0) {
        LOGe("speech: could not prepare the audio");
    } else if ((int) mtmd_helper_get_n_tokens(chunks) + max_tokens >= SPEECH_CONTEXT) {
        LOGe("speech: the recording is too long for the model's memory");
    } else {
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(g_sp_mtmd, g_sp_ctx, chunks, 0, 0, SPEECH_BATCH, true, &n_past) != 0) {
            LOGe("speech: could not read the audio");
        } else {
            common_params_sampling sparams;
            sparams.temp = 0.0f;  // the most likely words, not creative ones
            common_sampler *sampler = common_sampler_init(g_sp_model, sparams);
            const llama_vocab *vocab = llama_model_get_vocab(g_sp_model);
            common_batch batch(g_sp_ctx);
            ok = true;
            for (int i = 0; i < max_tokens; i++) {
                const llama_token id = common_sampler_sample(sampler, g_sp_ctx, -1);
                common_sampler_accept(sampler, id, true);
                if (llama_vocab_is_eog(vocab, id)) { break; }
                result += common_token_to_piece(g_sp_ctx, id);
                batch.clear();
                batch.add(id, n_past++, 0, true);
                if (llama_process(g_sp_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                    LOGe("speech: decoding failed");
                    ok = !result.empty();
                    break;
                }
            }
            common_sampler_free(sampler);
        }
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    if (!ok) { return nullptr; }
    if (!valid_utf8(result)) { result.clear(); }
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_SpeechEngineImpl_nativeUnload(JNIEnv * /*env*/, jobject /*unused*/) {
    free_speech();
}

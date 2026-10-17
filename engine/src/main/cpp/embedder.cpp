// Sentence embeddings for document search. This is a second, separate model: it has its own context and does
// not touch the chat model, so a chat can stay loaded while documents are indexed.

#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>
#include <unistd.h>

#include "common.h"
#include "llama.h"
#include "logging.h"

namespace {

llama_model   * g_emb_model = nullptr;
llama_context * g_emb_ctx   = nullptr;
common_batch    g_emb_batch;
int             g_emb_dims  = 0;
int             g_emb_n_ctx = 0;

void free_embedder() {
    g_emb_batch = common_batch();
    if (g_emb_ctx)   { llama_free(g_emb_ctx);          g_emb_ctx = nullptr; }
    if (g_emb_model) { llama_model_free(g_emb_model);  g_emb_model = nullptr; }
    g_emb_dims = 0;
    g_emb_n_ctx = 0;
}

}  // namespace

/**
 * Loads [jpath] as the embedding model. Returns the size of the vectors it makes (a positive number), or
 * -1 if the file could not be loaded, -2 if the model does not make sentence embeddings (an ordinary chat model, or a
 * re-ranker), -3 if it is an encoder-decoder model, which is not supported.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_EmbeddingEngineImpl_nativeLoad(JNIEnv *env, jobject /*unused*/, jstring jpath, jint n_threads, jint n_ctx_wanted) {
    free_embedder();

    const auto *path = env->GetStringUTFChars(jpath, 0);
    llama_model_params model_params = llama_model_default_params();
    // Always on the CPU: with GPU libraries around, llama.cpp would otherwise move the model to a GPU on its own.
    static ggml_backend_dev_t no_devices[1] = {nullptr};
    model_params.devices = no_devices;
    model_params.n_gpu_layers = 0;
    g_emb_model = llama_model_load_from_file(path, model_params);
    LOGi("%s: %s %s", __func__, path, g_emb_model ? "loaded" : "FAILED");
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_emb_model) { return -1; }

    if (llama_model_has_encoder(g_emb_model) && llama_model_has_decoder(g_emb_model)) {
        free_embedder();
        return -3;
    }

    const int n_cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int threads = n_threads > 0 ? std::min((int) n_threads, n_cores) : std::max(2, std::min(4, n_cores - 2));
    const int n_train = llama_model_n_ctx_train(g_emb_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(32, std::min((int) n_ctx_wanted, n_train > 0 ? n_train : (int) n_ctx_wanted));
    // A model that reads the whole text at once has to take it in one piece.
    cp.n_batch = cp.n_ctx;
    cp.n_ubatch = cp.n_ctx;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.embeddings = true;
    g_emb_ctx = llama_init_from_model(g_emb_model, cp);
    if (!g_emb_ctx) {
        free_embedder();
        return -1;
    }

    const enum llama_pooling_type pooling = llama_pooling_type(g_emb_ctx);
    if (pooling == LLAMA_POOLING_TYPE_NONE || pooling == LLAMA_POOLING_TYPE_RANK) {
        free_embedder();
        return -2;
    }

    g_emb_batch = common_batch(g_emb_ctx);
    g_emb_dims = llama_model_n_embd_out(g_emb_model);
    g_emb_n_ctx = (int) llama_n_ctx(g_emb_ctx);
    LOGi("%s: %d dimensions, %d tokens, %d threads", __func__, g_emb_dims, g_emb_n_ctx, threads);
    return g_emb_dims;
}

/** The meaning of [jtext] as a unit-length vector, or null if it could not be worked out. Longer text is cut to fit. */
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_arm_aichat_internal_EmbeddingEngineImpl_nativeEmbed(JNIEnv *env, jobject /*unused*/, jstring jtext) {
    if (!g_emb_ctx || g_emb_dims <= 0) { return nullptr; }

    const auto *chars = env->GetStringUTFChars(jtext, 0);
    const std::string text(chars);
    env->ReleaseStringUTFChars(jtext, chars);

    const llama_vocab *vocab = llama_model_get_vocab(g_emb_model);
    std::vector<llama_token> tokens = common_tokenize(vocab, text, /*add_special*/ true, /*parse_special*/ false);
    if (tokens.empty()) { return nullptr; }
    if ((int) tokens.size() > g_emb_n_ctx) {
        // Keep the closing separator the model expects after the last word.
        const llama_token last = tokens.back();
        tokens.resize(g_emb_n_ctx);
        if (last == llama_vocab_sep(vocab) || last == llama_vocab_eos(vocab)) { tokens.back() = last; }
    }

    g_emb_batch.clear();
    for (size_t i = 0; i < tokens.size(); i++) {
        g_emb_batch.add(tokens[i], (llama_pos) i, 0, true);
    }
    // Nothing from an earlier text may carry over.
    llama_memory_clear(llama_get_memory(g_emb_ctx), true);
    if (llama_process(g_emb_ctx, LLAMA_PROCESS_TYPE_DECODE, g_emb_batch.get()) < 0) {
        LOGe("%s: processing failed", __func__);
        return nullptr;
    }

    const float *embd = llama_get_embeddings_seq(g_emb_ctx, 0);
    if (!embd) { return nullptr; }

    std::vector<float> out(g_emb_dims);
    common_embd_normalize(embd, out.data(), g_emb_dims, 2);   // 2: scale to unit length
    jfloatArray result = env->NewFloatArray(g_emb_dims);
    if (!result) { return nullptr; }
    env->SetFloatArrayRegion(result, 0, g_emb_dims, out.data());
    return result;
}

/** How many tokens [jtext] is, for deciding whether it will be cut. -1 if no embedding model is loaded. */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_EmbeddingEngineImpl_nativeCountTokens(JNIEnv *env, jobject /*unused*/, jstring jtext) {
    if (!g_emb_model) { return -1; }
    const auto *chars = env->GetStringUTFChars(jtext, 0);
    const std::string text(chars);
    env->ReleaseStringUTFChars(jtext, chars);
    return (jint) common_tokenize(llama_model_get_vocab(g_emb_model), text, true, false).size();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_EmbeddingEngineImpl_nativeUnload(JNIEnv * /*env*/, jobject /*unused*/) {
    free_embedder();
}

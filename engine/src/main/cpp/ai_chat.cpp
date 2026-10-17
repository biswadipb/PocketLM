#include <android/log.h>
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "speculative.h"
#include "json-schema-to-grammar.h"
#include <deque>
#include <algorithm>

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static int                                g_n_ctx = DEFAULT_CONTEXT_SIZE;
static llama_model                      * g_model;
static llama_context                    * g_context;
static common_batch                       g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static mtmd_context                     * g_mtmd = nullptr;
static int                                g_n_threads = 4;
static std::vector<llama_adapter_lora *>  g_lora;       // LoRA adapters attached to the loaded model
static std::vector<float>                 g_lora_scale;
static int                                g_base_threads = 4; // what was chosen when the context was made; scaled down when the phone runs hot

// ---- GPUs -------------------------------------------------------------------------------------
//
// The GPU backends (Vulkan, OpenCL) are separate libraries that are NOT loaded when the app starts: just loading
// one makes the phone's graphics driver run, and a faulty driver would then take the app down on every start.
// They are loaded only when the user asks for a GPU. llama.cpp uses every GPU it knows about unless told
// otherwise, so every model is loaded with an explicit list of devices: the chosen GPU, or none at all.

static ggml_backend_dev_t g_no_devices[1] = {nullptr};  // an empty, NULL-ended list: keep this model on the CPU
static std::string        g_backend_dir;                // where the backend libraries are
static bool               g_gpu_libs_loaded = false;
static std::string        g_model_path;
static std::string        g_gpu_active;                 // e.g. "Vulkan: Mali-G615" while the loaded model runs on a GPU
static std::string        g_gpu_note;                   // why a GPU was asked for but is not used

static std::string lower_case(std::string text) {
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return (char) std::tolower(c); });
    return text;
}

static bool is_gpu(ggml_backend_dev_t dev) {
    const auto type = ggml_backend_dev_type(dev);
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

static std::string backend_name_of(ggml_backend_dev_t dev) {
    return ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev));
}

/** The GPU for [want] ("auto", "vulkan" or "opencl"), or null. Auto prefers OpenCL on Adreno, and Vulkan elsewhere. */
static ggml_backend_dev_t pick_gpu(const std::string &want) {
    ggml_backend_dev_t vulkan = nullptr, opencl = nullptr, adreno = nullptr;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto *dev = ggml_backend_dev_get(i);
        if (!is_gpu(dev)) { continue; }
        const std::string backend = lower_case(backend_name_of(dev));
        const std::string description = lower_case(ggml_backend_dev_description(dev));
        if (backend.find("vulkan") != std::string::npos && !vulkan) { vulkan = dev; }
        if (backend.find("opencl") != std::string::npos) {
            if (!opencl) { opencl = dev; }
            if (!adreno && description.find("adreno") != std::string::npos) { adreno = dev; }
        }
    }
    if (want == "vulkan") { return vulkan; }
    if (want == "opencl") { return opencl; }
    if (adreno) { return adreno; }
    return vulkan ? vulkan : opencl;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    g_backend_dir = path_to_backend;
    ggml_backend_load_all_from_path(path_to_backend);  // finds the CPU libraries; the GPU ones have other names
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path, jstring jgpu) {
    llama_model_params model_params = llama_model_default_params();
    g_gpu_active.clear();
    g_gpu_note.clear();

    // "" keeps the model on the CPU; "auto", "vulkan" or "opencl" ask for a GPU (its library must be loaded already).
    const auto *gpu_chars = env->GetStringUTFChars(jgpu, nullptr);
    const std::string want = lower_case(gpu_chars);
    env->ReleaseStringUTFChars(jgpu, gpu_chars);
    ggml_backend_dev_t gpu[2] = {nullptr, nullptr};
    if (!want.empty()) {
        gpu[0] = pick_gpu(want);
        if (!gpu[0]) { g_gpu_note = "No usable GPU was found, so the CPU is used."; }
    }
    model_params.devices = gpu[0] ? gpu : g_no_devices;
    model_params.n_gpu_layers = gpu[0] ? -1 : 0;

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    g_model_path = model_path;
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    if (!model && gpu[0]) {
        LOGw("%s: loading on the GPU failed; trying the CPU", __func__);
        g_gpu_note = "The GPU could not load this model, so the CPU is used.";
        model_params.devices = g_no_devices;
        model_params.n_gpu_layers = 0;
        gpu[0] = nullptr;
        model = llama_model_load_from_file(model_path, model_params);
    }
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    if (gpu[0]) { g_gpu_active = backend_name_of(gpu[0]) + ": " + ggml_backend_dev_description(gpu[0]); }
    return 0;
}

static bool g_cache_fell_back = false;   // a compressed memory cache was asked for but could not be used

static ggml_type cache_type_for_bits(int bits) {
    switch (bits) {
        case 8:  return GGML_TYPE_Q8_0;
        case 4:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE, const int requested_threads = 0,
                                   const int cache_bits = 16) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int n_threads = requested_threads > 0
            ? std::min(requested_threads, n_cores)
            : std::max(N_THREADS_MIN, std::min(N_THREADS_MAX, n_cores - N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);
    g_n_threads = n_threads;
    g_base_threads = n_threads;

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Clamping %d down to it.",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = std::min(n_ctx, trained_context_size);
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    g_cache_fell_back = false;
    if (cache_bits == 8 || cache_bits == 4) {
        // A compressed value cache needs flash attention.
        ctx_params.type_k = cache_type_for_bits(cache_bits);
        ctx_params.type_v = cache_type_for_bits(cache_bits);
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr && (cache_bits == 8 || cache_bits == 4)) {
        LOGw("%s: a %d-bit memory cache did not work here, using the normal one", __func__, cache_bits);
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        g_cache_fell_back = true;
        context = llama_init_from_model(g_model, ctx_params);
    }
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

struct SamplerConfig {
    float    temp           = 0.7f;
    float    top_p          = 0.95f;
    int      top_k          = 40;
    float    min_p          = 0.05f;
    float    repeat_penalty = 1.0f;
    uint32_t seed           = LLAMA_DEFAULT_SEED;
};

static SamplerConfig g_sampler_config;
static std::string   g_grammar; // GBNF constraining the output; empty means unconstrained

static common_sampler *new_sampler() {
    common_params_sampling sparams;
    sparams.temp           = g_sampler_config.temp;
    sparams.top_p          = g_sampler_config.top_p;
    sparams.top_k          = g_sampler_config.top_k;
    sparams.min_p          = g_sampler_config.min_p;
    sparams.penalty_repeat = g_sampler_config.repeat_penalty;
    sparams.seed           = g_sampler_config.seed;
    if (!g_grammar.empty()) {
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, g_grammar);
    }
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(
        JNIEnv * /*env*/, jobject /*unused*/, jint n_ctx, jint n_threads, jfloat temp, jfloat top_p,
        jint top_k, jfloat min_p, jfloat repeat_penalty, jint seed, jint cache_bits) {
    // On a GPU, run one token through the model before trusting it: the GPU compiles its programs on first use, so a
    // problem shows up here, while loading, instead of in the middle of a reply.
    const auto make_context = [&]() -> llama_context * {
        llama_context *made = init_context(g_model, n_ctx, n_threads, cache_bits);
        if (made && !g_gpu_active.empty()) {
            llama_token first = llama_vocab_bos(llama_model_get_vocab(g_model));
            if (first == LLAMA_TOKEN_NULL) { first = 0; }
            common_batch probe(made);
            probe.add(first, 0, 0, true);
            const bool worked = llama_process(made, LLAMA_PROCESS_TYPE_DECODE, probe.get()) == 0;
            llama_memory_clear(llama_get_memory(made), true);
            if (!worked) {
                llama_free(made);
                return nullptr;
            }
        }
        return made;
    };
    auto *context = make_context();
    if (!context && !g_gpu_active.empty()) {
        // The GPU could hold the model but not run it: start over on the CPU.
        LOGw("%s: the GPU did not work; reloading the model on the CPU", __func__);
        llama_model_free(g_model);
        llama_model_params cpu_only = llama_model_default_params();
        cpu_only.devices = g_no_devices;
        cpu_only.n_gpu_layers = 0;
        g_model = llama_model_load_from_file(g_model_path.c_str(), cpu_only);
        g_gpu_active.clear();
        g_gpu_note = "The GPU ran out of memory or failed, so the CPU is used.";
        if (!g_model) { return 1; }
        context = make_context();
    }
    if (!context) { return 1; }
    g_context = context;
    g_n_ctx = (int) llama_n_ctx(context);
    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler_config = {temp, top_p, top_k, min_p, repeat_penalty,
                        seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed};
    g_grammar.clear();
    g_sampler = new_sampler();
    return 0;
}

/** True if a compressed memory cache was asked for at the last load but the normal one is used instead. */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_cacheFellBack(JNIEnv * /*env*/, jobject /*unused*/) {
    return g_cache_fell_back;
}

/**
 * Changes the temperature and top-p used from now on, keeping the other sampler settings.
 * Safe between messages; the sampler is rebuilt, and the old one is kept if that fails.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setSampling(JNIEnv * /*env*/, jobject /*unused*/, jfloat temp, jfloat top_p) {
    if (!g_model || !g_sampler) { return 1; }
    const SamplerConfig previous = g_sampler_config;
    g_sampler_config.temp = temp;
    g_sampler_config.top_p = top_p;

    common_sampler *fresh = nullptr;
    try {
        fresh = new_sampler();
    } catch (...) {
        fresh = nullptr;
    }
    if (!fresh) {
        g_sampler_config = previous;
        return 2;
    }
    common_sampler_free(g_sampler);
    g_sampler = fresh;
    return 0;
}

/**
 * Constrains all further output to [jgrammar] (GBNF), or lifts the constraint when it is empty.
 * Safe between messages; the sampler is rebuilt.
 */
static int swap_in_grammar(const std::string &grammar) {
    const std::string previous = g_grammar;
    g_grammar = grammar;

    // Build the replacement first: if the grammar is rejected, keep the working sampler as it was.
    common_sampler *fresh = nullptr;
    try {
        fresh = new_sampler();
    } catch (...) {
        fresh = nullptr;
    }
    if (!fresh) {
        g_grammar = previous;
        return 2;
    }
    common_sampler_free(g_sampler);
    g_sampler = fresh;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setGrammar(JNIEnv *env, jobject /*unused*/, jstring jgrammar) {
    if (!g_model || !g_sampler) { return 1; }
    const auto *grammar = env->GetStringUTFChars(jgrammar, nullptr);
    const std::string text(grammar);
    env->ReleaseStringUTFChars(jgrammar, grammar);
    return swap_in_grammar(text);
}

/**
 * Constrains all further output to JSON that follows [jschema] (a JSON Schema), by turning the schema into a
 * grammar. Returns 3 if the schema cannot be read or converted.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setSchemaGrammar(JNIEnv *env, jobject /*unused*/, jstring jschema) {
    if (!g_model || !g_sampler) { return 1; }
    const auto *schema_chars = env->GetStringUTFChars(jschema, nullptr);
    const std::string schema_text(schema_chars);
    env->ReleaseStringUTFChars(jschema, schema_chars);

    std::string grammar;
    try {
        grammar = json_schema_to_grammar(common_json::parse(schema_text), /* force_gbnf */ true);
    } catch (...) {
        return 3;
    }
    if (grammar.empty()) { return 3; }
    return swap_in_grammar(grammar);
}

/**
 * Chooses how messages are wrapped for the model: a built-in template by name ("chatml", "llama3", ...), or
 * the model's own when [jname] is empty. Call right after the model loaded, before the system prompt.
 * Returns 2 if the name is not a built-in template.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_applyChatTemplate(JNIEnv *env, jobject /*unused*/, jstring jname) {
    if (!g_model) { return 1; }
    const auto *name_chars = env->GetStringUTFChars(jname, nullptr);
    const std::string name(name_chars);
    env->ReleaseStringUTFChars(jname, name_chars);

    if (!name.empty()) {
        const char *builtin[256];
        const int count = llama_chat_builtin_templates(builtin, 256);
        bool known = false;
        for (int i = 0; i < count && i < 256; i++) {
            if (name == builtin[i]) { known = true; break; }
        }
        if (!known) { return 2; }
    }
    try {
        g_chat_templates = common_chat_templates_init(g_model, name);
    } catch (...) {
        g_chat_templates = common_chat_templates_init(g_model, "");
        return 3;
    }
    return 0;
}

static std::string get_backend() {
    // What the loaded model runs on, not every backend that happens to be registered.
    return g_gpu_active.empty() ? "CPU" : g_gpu_active;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch batch(context);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            batch.add(0, i, 0, false);
        }

        batch.set_output(batch.size() - 1, true);
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            batch.clear();
            for (j = 0; j < pl; j++) {
                batch.add(0, i, j, true);
            }

            if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

// Every token in the model's memory, in order. Kept so a draft model can be brought level with it.
// Invalid once something that is not a plain token (an image) has been added.
static std::vector<llama_token> g_history;
static bool                     g_history_valid = true;

// A place to come back to, so the app can ask the model something in the middle of a conversation (for example for a
// title) and then forget the question and the answer without reading the whole conversation again.
struct Checkpoint {
    bool      valid         = false;
    unsigned  epoch         = 0;     // which conversation it belongs to
    int       shifts        = 0;     // how many times the context had been shifted
    size_t    chat_count    = 0;
    llama_pos position      = 0;
    size_t    history_size  = 0;
    bool      history_valid = true;
};
static Checkpoint g_checkpoint;
static unsigned   g_epoch  = 0;   // changes whenever a conversation is started over, so an old checkpoint is never used
static int        g_shifts = 0;   // counts context shifts, which move every token and so end a checkpoint

static void reset_long_term_states(const bool clear_kv_cache = true) {
    g_epoch++;
    g_checkpoint.valid = false;
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;
    g_history.clear();
    g_history_valid = true;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    g_shifts++;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    if (g_history_valid && g_history.size() >= (size_t) (system_prompt_position + n_discard)) {
        g_history.erase(g_history.begin() + system_prompt_position, g_history.begin() + system_prompt_position + n_discard);
    } else {
        g_history_valid = false;
    }
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

// Reasoning models write a <think> section first. When the user switches that off, the answer is started for the
// model with an empty one, which is what such models' own templates do for "no thinking".
static bool g_skip_thinking = false;

static bool ends_with(const std::string &text, const char *suffix) {
    const size_t n = strlen(suffix);
    return text.size() >= n && text.compare(text.size() - n, n, suffix) == 0;
}

static void skip_thinking_if_asked(std::string &formatted_prompt) {
    if (!g_skip_thinking) { return; }
    if (ends_with(formatted_prompt, "<think>\n")) {
        formatted_prompt += "\n</think>\n\n";   // the template already opened the section
    } else if (ends_with(formatted_prompt, "<think>")) {
        formatted_prompt += "\n\n</think>\n\n";
    } else {
        formatted_prompt += "<think>\n\n</think>\n\n";
    }
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added a %s message (%d characters)", __func__, role.c_str(), (int) formatted.size());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static bool g_cut_by_length = false; // the last reply ended because it reached its length limit
static std::string g_last_prompt;    // the last text given to the model, after the chat template was applied
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

// Speculative decoding: tokens already in the model's memory that have not been handed out yet, and a token
// that was chosen (and accepted by the sampler) but has not been put into the model's memory yet.
static std::deque<llama_token> g_emit_queue;
static llama_token             g_pending_token = LLAMA_TOKEN_NULL;

static void reset_short_term_states() {
    stop_generation_position = 0;
    g_cut_by_length = false;
    g_emit_queue.clear();
    g_pending_token = LLAMA_TOKEN_NULL;
    cached_token_chars.clear();
    assistant_ss.str("");
}

/** How many tokens of the conversation are in the model's memory, how many fit, and whether the last reply was cut. */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_contextUsed(JNIEnv * /*env*/, jobject /*unused*/) {
    return (jint) current_position;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_contextTotal(JNIEnv * /*env*/, jobject /*unused*/) {
    return (jint) g_n_ctx;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_lastReplyCut(JNIEnv * /*env*/, jobject /*unused*/) {
    return g_cut_by_length ? JNI_TRUE : JNI_FALSE;
}

static int decode_tokens_in_batches(
        llama_context *context,
        common_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        if (start_pos + i + cur_batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            batch.add(token_id, position, 0, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
        g_history.insert(g_history.end(), tokens.begin() + i, tokens.begin() + i + cur_batch_size);
        if (g_history.size() != (size_t) (start_pos + i + cur_batch_size)) { g_history_valid = false; }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received (%d characters)", __func__, (int) strlen(system_prompt));
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
    g_last_prompt = formatted_system_prompt;

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received (%d characters)", __func__, (int) strlen(user_prompt));
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
        skip_thinking_if_asked(formatted_user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);
    g_last_prompt = formatted_user_prompt;

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    current_position += user_prompt_size;
    stop_generation_position = current_position + n_predict;
    return 0;
}

/**
 * Vision: a multimodal projector (mmproj GGUF) lets the loaded model read images.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadProjector(JNIEnv *env, jobject /*unused*/, jstring jpath) {
    if (!g_model) { return 1; }
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }

    const auto *path = env->GetStringUTFChars(jpath, nullptr);
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.print_timings = false;
    params.warmup = false;
    params.n_threads = g_n_threads;
    g_mtmd = mtmd_init_from_file(path, g_model, params);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_mtmd) { return 2; }
    if (!mtmd_support_vision(g_mtmd)) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
        return 3;
    }
    return 0;
}

constexpr jsize MAX_IMAGES_PER_PROMPT = 8;

/** Sends a message together with one or more images. Returns 0 on success, 1 without a vision add-on, 2 if an image cannot be read. */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPromptWithImages(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jobjectArray jimage_paths,
        jint n_predict
) {
    reset_short_term_states();
    if (!g_mtmd) { return 1; }
    const jsize n_images = env->GetArrayLength(jimage_paths);
    if (n_images < 1 || n_images > MAX_IMAGES_PER_PROMPT) { return 2; }

    // One marker per image, in order, before the words.
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string content;
    for (jsize i = 0; i < n_images; i++) {
        content += mtmd_get_marker(g_mtmd);
        content += "\n";
    }
    content += user_prompt;
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    std::string formatted = has_chat_template ? chat_add_and_format(ROLE_USER, content) : content;
    if (has_chat_template) { skip_thinking_if_asked(formatted); }
    g_last_prompt = formatted;

    std::vector<mtmd_helper_bitmap_wrapper> wrappers;
    const auto free_images = [&wrappers]() {
        for (auto &w : wrappers) {
            if (w.bitmap) { mtmd_bitmap_free(w.bitmap); }
            if (w.video_ctx) { mtmd_helper_video_free(w.video_ctx); }
        }
    };
    for (jsize i = 0; i < n_images; i++) {
        auto *const jpath = (jstring) env->GetObjectArrayElement(jimage_paths, i);
        const auto *const image_path = env->GetStringUTFChars(jpath, nullptr);
        mtmd_helper_bitmap_wrapper wrapper =
                mtmd_helper_bitmap_init_from_file(g_mtmd, image_path, false, mtmd_helper_init_opt_default());
        env->ReleaseStringUTFChars(jpath, image_path);
        env->DeleteLocalRef(jpath);
        if (!wrapper.bitmap) {
            LOGe("%s: could not decode image %d", __func__, (int) i + 1);
            free_images();
            return 2;
        }
        wrappers.push_back(wrapper);
    }

    mtmd_input_text text{formatted.c_str(), formatted.size(), current_position == 0, true};
    std::vector<const mtmd_bitmap *> bitmaps;
    for (const auto &w : wrappers) { bitmaps.push_back(w.bitmap); }
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();

    int result = 0;
    if (mtmd_tokenize(g_mtmd, chunks, &text, bitmaps.data(), bitmaps.size()) != 0) {
        LOGe("%s: mtmd_tokenize failed", __func__);
        result = 3;
    } else if (current_position + (llama_pos) mtmd_helper_get_n_tokens(chunks) >= g_n_ctx - OVERFLOW_HEADROOM) {
        LOGe("%s: the images do not fit in the remaining context", __func__);
        result = 4;
    } else {
        llama_pos new_position = current_position;
        if (mtmd_helper_eval_chunks(g_mtmd, g_context, chunks, current_position, 0, BATCH_SIZE,
                                    true, &new_position) != 0) {
            LOGe("%s: mtmd_helper_eval_chunks failed", __func__);
            result = 5;
        } else {
            current_position = new_position;
            stop_generation_position = current_position + n_predict;
            g_history_valid = false; // an image is not a plain token
        }
    }

    mtmd_input_chunks_free(chunks);
    free_images();
    return result;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

/**
 * Speculative decoding with a small "draft" model. The draft model guesses the next few tokens cheaply, the
 * main model checks every guess in a single pass, and each correct guess saves a whole step of the main model.
 * The words that come out are exactly the ones the main model would have chosen on its own.
 */
static llama_model             * g_draft_model = nullptr;
static llama_context           * g_draft_ctx   = nullptr;
static common_batch              g_draft_batch;
static std::vector<llama_token>  g_draft_hist;      // the tokens in the draft model's memory
static int                       g_draft_n        = 4;
static long                      g_draft_proposed = 0;
static long                      g_draft_accepted = 0;
constexpr int DRAFT_N_MIN = 1;
constexpr int DRAFT_N_MAX = 8;

static void free_draft() {
    g_draft_batch = common_batch();
    if (g_draft_ctx) { llama_free(g_draft_ctx); g_draft_ctx = nullptr; }
    if (g_draft_model) { llama_model_free(g_draft_model); g_draft_model = nullptr; }
    g_draft_hist.clear();
    g_draft_n = 4;
    g_draft_proposed = 0;
    g_draft_accepted = 0;
}

/** True when the draft model can be used for the next step. */
static bool draft_ready() {
    return g_draft_ctx && g_history_valid && g_grammar.empty() && g_history.size() == (size_t) current_position;
}

/** Adds [count] tokens after the ones the draft model already holds; only the last one gets logits. */
static bool draft_decode(const llama_token *tokens, int count) {
    for (int i = 0; i < count; i += BATCH_SIZE) {
        const int n = std::min(count - i, BATCH_SIZE);
        g_draft_batch.clear();
        for (int j = 0; j < n; j++) {
            g_draft_batch.add(tokens[i + j], (llama_pos) (g_draft_hist.size() + j), 0, i + j == count - 1);
        }
        if (llama_process(g_draft_ctx, LLAMA_PROCESS_TYPE_DECODE, g_draft_batch.get()) != 0) { return false; }
        g_draft_hist.insert(g_draft_hist.end(), tokens + i, tokens + i + n);
    }
    return true;
}

static llama_token draft_best_guess() {
    const int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(g_draft_model));
    const float *logits = llama_get_logits_ith(g_draft_ctx, -1);
    if (!logits) { return LLAMA_TOKEN_NULL; }
    return (llama_token) (std::max_element(logits, logits + n_vocab) - logits);
}

static void draft_forget_everything() {
    llama_memory_clear(llama_get_memory(g_draft_ctx), false);
    g_draft_hist.clear();
}

/** Up to [n_max] guesses for what follows [first] (the token that is about to be added to the conversation). */
static std::vector<llama_token> draft_tokens(llama_token first, int n_max) {
    std::vector<llama_token> guesses;

    // Bring the draft model's memory level with the main model's: keep what matches, redo the rest.
    size_t common = 0;
    while (common < g_draft_hist.size() && common < g_history.size() && g_draft_hist[common] == g_history[common]) {
        common++;
    }
    if (common < g_draft_hist.size()) {
        if (!llama_memory_seq_rm(llama_get_memory(g_draft_ctx), 0, (llama_pos) common, -1)) {
            draft_forget_everything();
            common = 0;
        } else {
            g_draft_hist.resize(common);
        }
    }
    std::vector<llama_token> feed(g_history.begin() + common, g_history.end());
    feed.push_back(first);
    if ((int) (g_draft_hist.size() + feed.size()) + n_max + 1 >= (int) llama_n_ctx(g_draft_ctx)) { return guesses; }
    if (!draft_decode(feed.data(), (int) feed.size())) {
        draft_forget_everything();
        return guesses;
    }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    for (int i = 0; i < n_max; i++) {
        const llama_token guess = draft_best_guess();
        if (guess == LLAMA_TOKEN_NULL || llama_vocab_is_eog(vocab, guess)) { break; }
        guesses.push_back(guess);
        if (i + 1 < n_max && !draft_decode(&guesses.back(), 1)) { break; }
    }
    return guesses;
}

/** Remembers where the conversation is now. Returns false if there is no conversation to remember. */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeMarkCheckpoint(JNIEnv * /*env*/, jobject /*unused*/) {
    if (!g_model || !g_context) { return JNI_FALSE; }
    g_checkpoint = {true, g_epoch, g_shifts, chat_msgs.size(), current_position, g_history.size(), g_history_valid};
    return JNI_TRUE;
}

/**
 * Forgets everything said since [markCheckpoint]. Returns 0 when the conversation is back as it was, 1 when there is
 * nothing to go back to (the conversation was started over or shifted meanwhile), 2 when this model's memory cannot
 * forget the end of a conversation (the app then rebuilds it).
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeReturnToCheckpoint(JNIEnv * /*env*/, jobject /*unused*/) {
    const Checkpoint cp = g_checkpoint;
    g_checkpoint.valid = false;
    if (!cp.valid || !g_model || !g_context || cp.epoch != g_epoch || cp.shifts != g_shifts) { return 1; }
    if (current_position < cp.position || chat_msgs.size() < cp.chat_count) { return 1; }

    reset_short_term_states();
    if (current_position > cp.position &&
        !llama_memory_seq_rm(llama_get_memory(g_context), 0, cp.position, -1)) {
        return 2;
    }
    current_position = cp.position;
    chat_msgs.erase(chat_msgs.begin() + (long) cp.chat_count, chat_msgs.end());
    if (cp.history_valid && g_history_valid && g_history.size() >= cp.history_size) {
        g_history.resize(cp.history_size);
    } else {
        g_history_valid = false;
    }
    if (g_draft_ctx) { draft_forget_everything(); }
    return 0;
}

/** Turns a token that is now part of the conversation into the text for the app; null when the reply is over. */
static jstring emit_token(JNIEnv *env, llama_token token_id) {
    // Stop if the token ends the reply
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", token_id);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", token_id);
        result = env->NewStringUTF("");
    }
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Tokens that a speculative step already added to the model's memory are handed out first.
    if (!g_emit_queue.empty()) {
        const llama_token queued = g_emit_queue.front();
        g_emit_queue.pop_front();
        return emit_token(env, queued);
    }

    // Infinite text generation via context shifting
    if (current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        g_cut_by_length = true;
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // The next token is the one a speculative step left over, or a fresh sample
    llama_token token_id;
    if (g_pending_token != LLAMA_TOKEN_NULL) {
        token_id = g_pending_token;
        g_pending_token = LLAMA_TOKEN_NULL;
    } else {
        token_id = common_sampler_sample(g_sampler, g_context, -1);
        common_sampler_accept(g_sampler, token_id, true);
    }

    // Ask the draft model for a few guesses at what comes after it
    std::vector<llama_token> guesses;
    if (draft_ready() && !llama_vocab_is_eog(llama_model_get_vocab(g_model), token_id)) {
        int room = std::min(g_draft_n, (int) (stop_generation_position - current_position) - 1);
        room = std::min(room, g_n_ctx - OVERFLOW_HEADROOM - (int) current_position - 2);
        if (room > 0) { guesses = draft_tokens(token_id, room); }
    }

    // Add the token, and the guesses after it, to the model's memory in one pass
    g_batch.clear();
    g_batch.add(token_id, current_position, 0, true);
    for (size_t i = 0; i < guesses.size(); i++) {
        g_batch.add(guesses[i], current_position + 1 + (llama_pos) i, 0, true);
    }
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }
    g_history.push_back(token_id);

    if (guesses.empty()) {
        current_position++;
        return emit_token(env, token_id);
    }

    // Keep the guesses the main model agrees with, plus the one token it chose itself after them
    const std::vector<llama_token> chosen = common_sampler_sample_and_accept_n(g_sampler, g_context, guesses);
    const size_t right = chosen.empty() ? 0 : chosen.size() - 1;
    g_draft_proposed += (long) guesses.size();
    g_draft_accepted += (long) right;

    if (right < guesses.size()) {
        const bool removed = llama_memory_seq_rm(
                llama_get_memory(g_context), 0, current_position + 1 + (llama_pos) right, -1);
        if (!removed) {
            LOGe("%s: could not forget the wrong guesses; turning the draft model off", __func__);
            free_draft();
        }
    }
    current_position += 1 + (llama_pos) right;
    for (size_t i = 0; i < right; i++) {
        g_history.push_back(guesses[i]);
        g_emit_queue.push_back(guesses[i]);
    }
    if (!chosen.empty()) { g_pending_token = chosen.back(); }

    if (right == guesses.size() && g_draft_n < DRAFT_N_MAX) {
        g_draft_n++;
    } else if (right * 2 < guesses.size() && g_draft_n > DRAFT_N_MIN) {
        g_draft_n--;
    }
    return emit_token(env, token_id);
}

/**
 * Loads a small model to draft for the main one. Returns 0 on success, 1 if no model is loaded, 2 if the file
 * cannot be loaded, 3 if its vocabulary does not match, 4 if this kind of model cannot take back wrong guesses,
 * 5 if its memory could not be created.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_attachDraft(JNIEnv *env, jobject /*unused*/, jstring jpath) {
    free_draft();
    if (!g_model || !g_context) { return 1; }
    if (llama_model_is_recurrent(g_model) || llama_model_is_hybrid(g_model)) { return 4; }

    const auto *path_chars = env->GetStringUTFChars(jpath, nullptr);
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(jpath, path_chars);

    llama_model_params draft_params = llama_model_default_params();
    draft_params.devices = g_no_devices;  // the draft model stays on the CPU
    draft_params.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), draft_params);
    if (!model) { return 2; }
    if (!common_speculative_are_compatible(g_model, model)) {
        llama_model_free(model);
        return 3;
    }
    if (llama_model_is_recurrent(model) || llama_model_is_hybrid(model)) {
        llama_model_free(model);
        return 4;
    }

    llama_context_params params = llama_context_default_params();
    params.n_ctx = std::min(g_n_ctx, llama_model_n_ctx_train(model));
    params.n_batch = BATCH_SIZE;
    params.n_ubatch = BATCH_SIZE;
    params.n_threads = g_n_threads;
    params.n_threads_batch = g_n_threads;
    llama_context *context = llama_init_from_model(model, params);
    if (!context) {
        llama_model_free(model);
        return 5;
    }
    g_draft_model = model;
    g_draft_ctx = context;
    g_draft_batch = common_batch(context);
    return 0;
}

/** True if the loaded model's own chat template is one for a model that thinks before it answers. */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeSupportsThinking(JNIEnv * /*env*/, jobject /*unused*/) {
    if (!g_model) { return false; }
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    return tmpl && (strstr(tmpl, "enable_thinking") != nullptr || strstr(tmpl, "<think>") != nullptr);
}

/** Switches the model's thinking section on or off for further messages; ignored for models that do not think. */
extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeSetThinking(JNIEnv * /*env*/, jobject /*unused*/, jboolean enabled) {
    bool thinks = false;
    if (g_model) {
        const char *tmpl = llama_model_chat_template(g_model, nullptr);
        thinks = tmpl && (strstr(tmpl, "enable_thinking") != nullptr || strstr(tmpl, "<think>") != nullptr);
    }
    g_skip_thinking = thinks && !enabled;
}

/**
 * Reads a LoRA adapter file and keeps it ready for applyLoras(). Call after prepare(). The adapter has to be made
 * for the loaded model. Returns 0, 1 if no model is loaded, or 2 if the adapter could not be used.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_attachLora(JNIEnv *env, jobject /*unused*/, jstring jpath, jfloat scale) {
    if (!g_model || !g_context) { return 1; }
    const auto *path = env->GetStringUTFChars(jpath, 0);
    llama_adapter_lora *adapter = nullptr;
    try {
        adapter = llama_adapter_lora_init(g_model, path);
    } catch (...) {
        adapter = nullptr;
    }
    LOGi("%s: %s %s", __func__, path, adapter ? "attached" : "FAILED");
    env->ReleaseStringUTFChars(jpath, path);
    if (!adapter) { return 2; }
    g_lora.push_back(adapter);
    g_lora_scale.push_back(scale);
    return 0;
}

/** Switches on all the adapters read with attachLora(). Returns 0, or 3 if they could not be applied. */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_applyLoras(JNIEnv * /*env*/, jobject /*unused*/) {
    if (!g_context) { return 1; }
    if (g_lora.empty()) { return 0; }
    const int32_t result = llama_set_adapters_lora(g_context, g_lora.data(), g_lora.size(), g_lora_scale.data());
    return result == 0 ? 0 : 3;
}

/**
 * Runs with [percent] of the threads chosen at load (at least one), for the main model and the draft model.
 * Takes effect with the next decode. 100 puts the original count back.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_scaleThreads(JNIEnv * /*env*/, jobject /*unused*/, jint percent) {
    if (!g_context) { return 1; }
    const int share = std::max(10, std::min(100, (int) percent));
    const int threads = std::max(1, (g_base_threads * share + 50) / 100);
    g_n_threads = threads;
    llama_set_n_threads(g_context, threads, threads);
    if (g_draft_ctx) { llama_set_n_threads(g_draft_ctx, threads, threads); }
    LOGi("%s: Using %d of %d threads", __func__, threads, g_base_threads);
    return 0;
}

/** How many of the draft model's guesses were right, for the prompt inspector. */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_draftSummary(JNIEnv *env, jobject /*unused*/) {
    if (!g_draft_ctx) { return env->NewStringUTF(""); }
    char text[160];
    snprintf(text, sizeof(text), "%ld of %ld guessed tokens were right", g_draft_accepted, g_draft_proposed);
    return env->NewStringUTF(text);
}

/**
 * Loads the GPU libraries (once) and lists the GPUs they found, one per line as "backend<TAB>name<TAB>description<TAB>bytes".
 * This is the first time the phone's graphics driver is touched, so the caller guards it against a crash.
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_findGpus(JNIEnv *env, jobject /*unused*/) {
    if (!g_gpu_libs_loaded) {
        g_gpu_libs_loaded = true;
        for (const char *name : {"gpu-vulkan", "gpu-opencl"}) {
            const std::string path = g_backend_dir + "/lib" + name + ".so";
            if (access(path.c_str(), R_OK) != 0) { continue; }
            if (!ggml_backend_load(path.c_str())) { LOGw("findGpus: could not load %s", path.c_str()); }
        }
    }
    std::string text;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto *dev = ggml_backend_dev_get(i);
        if (!is_gpu(dev)) { continue; }
        size_t free_bytes = 0, total_bytes = 0;
        ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);
        text += backend_name_of(dev) + "\t" + ggml_backend_dev_name(dev) + "\t" + ggml_backend_dev_description(dev) +
                "\t" + std::to_string(total_bytes) + "\n";
    }
    if (!is_valid_utf8(text.c_str())) { text.clear(); }
    return env->NewStringUTF(text.c_str());
}

/** "Vulkan: Mali-G615" while the loaded model runs on a GPU; otherwise why it does not (or empty). */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_gpuInUse(JNIEnv *env, jobject /*unused*/) {
    const std::string text = g_gpu_active.empty() ? g_gpu_note : g_gpu_active;
    return env->NewStringUTF(is_valid_utf8(text.c_str()) ? text.c_str() : "");
}

/** True if the loaded model runs on a GPU (so [gpuInUse] is the device, not a reason). */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_onGpu(JNIEnv * /*env*/, jobject /*unused*/) {
    return g_gpu_active.empty() ? JNI_FALSE : JNI_TRUE;
}

/**
 * The text most recently handed to the model (the system prompt or a message, with the chat template
 * applied), for the prompt inspector. Cut at a character boundary if it is very long.
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_lastPrompt(JNIEnv *env, jobject /*unused*/) {
    constexpr size_t MAX_BYTES = 200 * 1024;
    std::string text = g_last_prompt;
    if (text.size() > MAX_BYTES) {
        size_t cut = MAX_BYTES;
        while (cut > 0 && (((unsigned char) text[cut]) & 0xC0) == 0x80) { cut--; }
        text.resize(cut);
    }
    if (!is_valid_utf8(text.c_str())) { text = "(this prompt could not be shown)"; }
    return env->NewStringUTF(text.c_str());
}

/**
 * Saved chat state: the model's memory of the conversation (its KV cache) plus the few values the engine
 * keeps beside it, so a chat can be picked up again without reading it all through a second time.
 *
 * Two files are written next to each other: "<prefix>.kv" (llama.cpp's sequence state) and "<prefix>.meta"
 * (a label, the positions and the chat so far). Each is written under a temporary name and then renamed;
 * the meta file records the size of the kv file, so a pair that does not belong together is rejected.
 */
constexpr uint32_t STATE_META_MAGIC   = 0x31534C50; // "PLS1"
constexpr uint32_t STATE_META_VERSION = 1;
constexpr size_t   STATE_META_MAX     = 64 * 1024 * 1024;

static void meta_put_u32(std::string &out, uint32_t value) {
    out.append(reinterpret_cast<const char *>(&value), sizeof(value));
}

static void meta_put_str(std::string &out, const std::string &text) {
    meta_put_u32(out, (uint32_t) text.size());
    out.append(text);
}

struct MetaReader {
    const std::string &data;
    size_t pos = 0;
    bool ok = true;

    uint32_t u32() {
        if (!ok || pos + sizeof(uint32_t) > data.size()) { ok = false; return 0; }
        uint32_t value;
        memcpy(&value, data.data() + pos, sizeof(value));
        pos += sizeof(value);
        return value;
    }

    std::string str() {
        const uint32_t len = u32();
        if (!ok || len > data.size() - pos) { ok = false; return {}; }
        std::string text = data.substr(pos, len);
        pos += len;
        return text;
    }
};

static bool read_whole_file(const std::string &path, std::string &out, size_t max_bytes) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) { return false; }
    const std::streamoff size = in.tellg();
    if (size < 0 || (size_t) size > max_bytes) { return false; }
    out.resize((size_t) size);
    in.seekg(0);
    in.read(out.data(), size);
    return (bool) in;
}

static long long file_size_of(const std::string &path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    return in ? (long long) in.tellg() : -1;
}

/** Returns the number of bytes written, -1 if the state is bigger than [max_bytes], or -2 if saving failed
 *  (or the conversation no longer has [expected_tokens] tokens). */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_saveChatState(
        JNIEnv *env, jobject /*unused*/, jstring jprefix, jstring jtag, jlong max_bytes, jint expected_tokens) {
    if (!g_context || current_position <= 0) { return -2; }
    // The caller labelled the state it saw; if the conversation moved on since, saving would mislabel it.
    if (expected_tokens >= 0 && current_position != expected_tokens) { return -2; }

    const size_t needed = llama_state_seq_get_size(g_context, 0);
    if (max_bytes > 0 && needed > (size_t) max_bytes) { return -1; }

    const auto *prefix_chars = env->GetStringUTFChars(jprefix, nullptr);
    const auto *tag_chars = env->GetStringUTFChars(jtag, nullptr);
    const std::string prefix(prefix_chars);
    const std::string tag(tag_chars);
    env->ReleaseStringUTFChars(jprefix, prefix_chars);
    env->ReleaseStringUTFChars(jtag, tag_chars);

    const std::string kv_path = prefix + ".kv";
    const std::string meta_path = prefix + ".meta";
    const std::string kv_tmp = kv_path + ".tmp";
    const std::string meta_tmp = meta_path + ".tmp";

    // The tokens go into the file too, so a draft model can be brought level with the conversation later.
    llama_token no_tokens[1] = {0};
    const bool history_saved = g_history_valid && g_history.size() == (size_t) current_position;
    const size_t written = llama_state_seq_save_file(
            g_context, kv_tmp.c_str(), 0,
            history_saved ? g_history.data() : no_tokens, history_saved ? g_history.size() : 0);
    if (written == 0) {
        std::remove(kv_tmp.c_str());
        return -2;
    }

    std::string meta;
    meta_put_u32(meta, STATE_META_MAGIC);
    meta_put_u32(meta, STATE_META_VERSION);
    meta_put_str(meta, tag);
    meta_put_u32(meta, (uint32_t) current_position);
    meta_put_u32(meta, (uint32_t) system_prompt_position);
    meta_put_u32(meta, (uint32_t) written);
    meta_put_u32(meta, (uint32_t) (written >> 32));
    meta_put_u32(meta, history_saved ? 1u : 0u);
    meta_put_u32(meta, (uint32_t) chat_msgs.size());
    for (const auto &msg : chat_msgs) {
        meta_put_str(meta, msg.role);
        meta_put_str(meta, msg.content);
    }

    {
        std::ofstream out(meta_tmp, std::ios::binary | std::ios::trunc);
        out.write(meta.data(), (std::streamsize) meta.size());
        out.flush();
        if (!out) {
            std::remove(kv_tmp.c_str());
            std::remove(meta_tmp.c_str());
            return -2;
        }
    }

    if (std::rename(kv_tmp.c_str(), kv_path.c_str()) != 0 ||
        std::rename(meta_tmp.c_str(), meta_path.c_str()) != 0) {
        std::remove(kv_tmp.c_str());
        std::remove(meta_tmp.c_str());
        std::remove(kv_path.c_str());
        std::remove(meta_path.c_str());
        return -2;
    }
    LOGi("%s: saved %zu bytes at position %d", __func__, written, (int) current_position);
    return (jlong) written;
}

/**
 * Brings back a state saved with the same [tag]. Returns 0 on success, 1 if there is nothing usable
 * (nothing was changed), or 2 if loading failed part way (the memory was cleared; set a system prompt again).
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_restoreChatState(
        JNIEnv *env, jobject /*unused*/, jstring jprefix, jstring jtag) {
    if (!g_context) { return 1; }

    const auto *prefix_chars = env->GetStringUTFChars(jprefix, nullptr);
    const auto *tag_chars = env->GetStringUTFChars(jtag, nullptr);
    const std::string prefix(prefix_chars);
    const std::string tag(tag_chars);
    env->ReleaseStringUTFChars(jprefix, prefix_chars);
    env->ReleaseStringUTFChars(jtag, tag_chars);

    const std::string kv_path = prefix + ".kv";
    std::string meta;
    if (!read_whole_file(prefix + ".meta", meta, STATE_META_MAX)) { return 1; }

    MetaReader reader{meta};
    if (reader.u32() != STATE_META_MAGIC || reader.u32() != STATE_META_VERSION) { return 1; }
    if (reader.str() != tag) { return 1; }
    const uint32_t position = reader.u32();
    const uint32_t system_position = reader.u32();
    const uint64_t kv_low = reader.u32();
    const uint64_t kv_high = reader.u32();
    const uint32_t history_flag = reader.u32();
    const uint32_t msg_count = reader.u32();
    if (!reader.ok || system_position > position || (int) position >= g_n_ctx) { return 1; }

    std::vector<common_chat_msg> messages;
    for (uint32_t i = 0; i < msg_count && reader.ok; i++) {
        common_chat_msg msg;
        msg.role = reader.str();
        msg.content = reader.str();
        messages.push_back(std::move(msg));
    }
    if (!reader.ok) { return 1; }
    if (file_size_of(kv_path) != (long long) (kv_low | (kv_high << 32))) { return 1; }

    // From here on the model's memory is replaced.
    reset_long_term_states();
    reset_short_term_states();

    std::vector<llama_token> tokens((size_t) g_n_ctx + 8);
    size_t token_count = 0;
    const size_t read = llama_state_seq_load_file(
            g_context, kv_path.c_str(), 0, tokens.data(), tokens.size(), &token_count);
    if (read == 0 || llama_memory_seq_pos_max(llama_get_memory(g_context), 0) < 0) {
        LOGe("%s: could not load the saved state", __func__);
        reset_long_term_states();
        return 2;
    }
    if (history_flag == 1 && token_count == (size_t) position) {
        g_history.assign(tokens.begin(), tokens.begin() + token_count);
        g_history_valid = true;
    } else {
        g_history.clear();
        g_history_valid = false;
    }

    chat_msgs = std::move(messages);
    system_prompt_position = (llama_pos) system_position;
    current_position = (llama_pos) position;
    LOGi("%s: restored position %d", __func__, (int) current_position);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    g_lora.clear();          // the adapters themselves are freed along with the model
    g_lora_scale.clear();
    free_draft();
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    g_batch = common_batch();
    llama_free(g_context);
    llama_model_free(g_model);
    g_gpu_active.clear();
    g_gpu_note.clear();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}

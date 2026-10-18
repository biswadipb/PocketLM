// Text to speech with llama.cpp's audio generation (libmtmd): a backbone model plus an audio add-on that turns the
// model's hidden states into sound. Following tools/tts/tts.cpp. Kept free of JNI so it can be tried on a computer.

#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"

#ifdef __ANDROID__
#include "logging.h"
#else
#define LOGi(...) do { std::fprintf(stderr, "[voice] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGe(...) LOGi(__VA_ARGS__)
#define LOGw(...) LOGi(__VA_ARGS__)
#endif

namespace pocketlm {

class VoiceSession {
public:
    ~VoiceSession() { release(); }

    /**
     * Loads the backbone model and the audio add-on. Returns the sample rate of the sound it makes (for example 24000),
     * or -1 if the model could not be loaded, -2 if the add-on could not be, -3 if the pair cannot make speech.
     * [cpu_only] keeps everything off the phone's GPU, so the chat model keeps it to itself.
     */
    int load(const std::string &model_path, const std::string &projector_path, int threads, bool cpu_only) {
        release();
        llama_model_params model_params = llama_model_default_params();
        if (cpu_only) {
            model_params.devices = no_devices_;
            model_params.n_gpu_layers = 0;
        }
        model_ = llama_model_load_from_file(model_path.c_str(), model_params);
        if (!model_) { return -1; }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = CONTEXT;
        ctx_params.n_batch = BATCH;
        ctx_params.n_ubatch = BATCH;
        if (threads > 0) {
            ctx_params.n_threads = threads;
            ctx_params.n_threads_batch = threads;
        }
        // The hidden state of each token is what the audio add-on turns into sound.
        ctx_params.embeddings = true;
        ctx_ = llama_init_from_model(model_, ctx_params);
        if (!ctx_) {
            release();
            return -1;
        }

        mtmd_context_params params = mtmd_context_params_default();
        params.use_gpu = !cpu_only;
        params.print_timings = false;
        params.warmup = false;
        if (threads > 0) { params.n_threads = threads; }
        mtmd_ = mtmd_init_from_file(projector_path.c_str(), model_, params);
        if (!mtmd_) {
            release();
            return -2;
        }
        const mtmd_gen_audio_info info = mtmd_gen_audio_get_info(mtmd_);
        if (info.type == MTMD_GEN_AUDIO_TYPE_NONE || info.sample_rate <= 0) {
            release();
            return -3;
        }
        sample_rate_ = info.sample_rate;
        LOGi("voice: loaded, %d Hz", sample_rate_);
        return sample_rate_;
    }

    bool loaded() const { return mtmd_ != nullptr; }

    int sample_rate() const { return sample_rate_; }

    /** Asks a running [speak] to stop at the next frame. Safe to call from another thread. */
    void cancel() { cancel_.store(true); }

    const std::string &error() const { return error_; }

    /**
     * Speaks [text]: fills [pcm] with mono samples between -1 and 1 at [sample_rate]. [language] and [speaker_file] (a
     * short recording of the voice to imitate) are optional and not used by every model. Returns false, with [error]
     * saying why, if it failed or was cancelled.
     */
    bool speak(const std::string &text, const std::string &language, const std::string &speaker_file, int max_frames,
               int top_k, float top_p, uint32_t seed, std::vector<float> &pcm) {
        error_.clear();
        pcm.clear();
        if (!loaded()) { return fail("No voice model is loaded."); }
        cancel_.store(false);
        llama_memory_clear(llama_get_memory(ctx_), true);

        mtmd::bitmap_ptr speaker;
        if (!speaker_file.empty()) {
            auto wrapper = mtmd_helper_bitmap_init_from_file(mtmd_, speaker_file.c_str(), false, mtmd_helper_init_opt_default());
            if (!wrapper.bitmap) { return fail("The voice sample could not be read. Use a WAV or MP3 file."); }
            speaker.reset(wrapper.bitmap);
        }

        common_params_sampling sparams;
        sparams.top_k = top_k;
        sparams.top_p = top_p;
        sparams.seed = seed;
        common_sampler *sampler = common_sampler_init(model_, sparams);
        if (!sampler) { return fail("The voice model could not be prepared."); }
        struct Free { common_sampler *s; ~Free() { common_sampler_free(s); } } free_sampler{sampler};

        const auto t_start = std::chrono::steady_clock::now();
        const auto since = [&]() { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t_start).count(); };
        mtmd_helper::gen_audio gen(ctx_, mtmd_);
        mtmd_helper_gen_audio_inp inp{};
        inp.seq_id = 0;
        inp.prompt = text.c_str();
        inp.prompt_len = text.size();
        inp.speaker_ref = speaker.get();
        inp.lang = language.empty() ? nullptr : language.c_str();
        inp.top_k = top_k;
        inp.top_p = top_p;
        inp.seed = seed;
        inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
        if (gen.set_input(&inp) != 0) { return fail("The voice model could not take that text."); }

        // Stage 1: the model reads the text.
        for (;;) {
            const int32_t remaining = gen.step_prompt(BATCH);
            if (remaining < 0) { return fail("The voice model could not read that text."); }
            if (remaining == 0) { break; }
            if (cancel_.load()) { return fail("Stopped."); }
        }

        const double t_prompt = since();

        // Stage 2 and 3: one frame at a time, the model's hidden state becomes sound.
        auto sample = [&]() -> llama_token {
            const llama_token t = common_sampler_sample(sampler, ctx_, -1);
            common_sampler_accept(sampler, t, true);
            return t;
        };
        llama_token sampled = sample();
        const float *state = llama_get_embeddings_ith(ctx_, -1);
        bool stop = false;
        int frames = 0;
        while (!stop && frames < max_frames) {
            if (cancel_.load()) { return fail("Stopped."); }
            const float *next = nullptr;
            if (gen.step_gen(sampled, state, &next, &stop) != 0) { return fail("The voice model failed while speaking."); }
            if (!next) { break; }
            frames++;
            state = next;
            sampled = sample();
        }

        const double t_frames = since();
        int32_t rate = 0;
        const char *data = nullptr;
        size_t length = 0;
        int64_t samples = 0;
        if (gen.get_output(&rate, &data, &length, &samples) != 0 || !data || samples <= 0) {
            return fail("The voice model made no sound.");
        }
        const float *floats = reinterpret_cast<const float *>(data);
        pcm.assign(floats, floats + samples);
        LOGi("voice: %d frames; text %.1fs, frames %.1fs, sound %.1fs; %.2fs of speech", frames, t_prompt, t_frames - t_prompt, since() - t_frames,
             (double) samples / (rate > 0 ? rate : 1));
        return true;
    }

    void release() {
        if (mtmd_) { mtmd_free(mtmd_); mtmd_ = nullptr; }
        if (ctx_) { llama_free(ctx_); ctx_ = nullptr; }
        if (model_) { llama_model_free(model_); model_ = nullptr; }
        sample_rate_ = 0;
    }

private:
    static constexpr int CONTEXT = 4096;
    static constexpr int BATCH = 512;

    bool fail(const char *message) {
        error_ = message;
        return false;
    }

    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    mtmd_context *mtmd_ = nullptr;
    int sample_rate_ = 0;
    std::atomic<bool> cancel_{false};
    std::string error_;
    ggml_backend_dev_t no_devices_[1] = {nullptr};
};

}  // namespace pocketlm

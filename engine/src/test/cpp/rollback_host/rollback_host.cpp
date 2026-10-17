// Checks, with a real model on a computer, the idea behind naming a chat "in place": a question is put to the model in the
// middle of a conversation, answered, and then forgotten by removing its tokens from the model's memory
// (llama_memory_seq_rm from the checkpoint to the end). If that works, the conversation continues exactly as if the
// question had never been asked. Mirrors nativeMarkCheckpoint / nativeReturnToCheckpoint in ai_chat.cpp.
//
//   rollback_host MODEL.gguf

#include <cmath>
#include <cstdio>
#include <vector>

#include "common.h"
#include "llama.h"

static std::vector<float> run(llama_context *ctx, const llama_vocab *vocab, bool side_question, bool forget) {
    llama_memory_t memory = llama_get_memory(ctx);
    llama_memory_clear(memory, true);
    common_batch batch(ctx);
    const int n_vocab = llama_vocab_n_tokens(vocab);

    auto decode = [&](const std::vector<llama_token> &tokens, llama_pos start) {
        for (size_t i = 0; i < tokens.size(); i++) {
            batch.clear();
            batch.add(tokens[i], start + (llama_pos) i, 0, true);
            if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { std::fprintf(stderr, "decode failed\n"); std::exit(1); }
        }
    };
    auto best = [&]() {
        const float *logits = llama_get_logits_ith(ctx, -1);
        int arg = 0;
        for (int i = 1; i < n_vocab; i++) if (logits[i] > logits[arg]) arg = i;
        return (llama_token) arg;
    };

    const auto conversation = common_tokenize(ctx, "The quick brown fox jumps over the lazy dog. Hello, my name is", true, true);
    decode(conversation, 0);
    const llama_pos checkpoint = (llama_pos) conversation.size();

    llama_pos end = checkpoint;  // where the conversation continues
    if (side_question) {
        auto question = common_tokenize(ctx, "\nGive this conversation a short title:", false, true);
        decode(question, checkpoint);
        llama_pos at = checkpoint + (llama_pos) question.size();
        for (int i = 0; i < 8; i++) {          // the model "answers" with a few tokens, as when a title is written
            const llama_token t = best();
            decode({t}, at++);
        }
        end = at;
        if (forget) {
            if (!llama_memory_seq_rm(memory, 0, checkpoint, -1)) { std::fprintf(stderr, "seq_rm refused\n"); std::exit(1); }
            end = checkpoint;
        }
    }

    // The conversation goes on: the next token is decoded where it would have been.
    const auto next = common_tokenize(ctx, " and", false, true);
    decode({next[0]}, end);
    const float *logits = llama_get_logits_ith(ctx, -1);
    return std::vector<float>(logits, logits + n_vocab);
}

int main(int argc, char **argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: %s MODEL.gguf\n", argv[0]); return 2; }
    llama_backend_init();
    llama_model *model = llama_model_load_from_file(argv[1], llama_model_default_params());
    if (!model) { std::fprintf(stderr, "could not load the model\n"); return 1; }
    llama_context_params params = llama_context_default_params();
    params.n_ctx = 2048;
    params.n_batch = 512;
    llama_context *ctx = llama_init_from_model(model, params);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const auto plain = run(ctx, vocab, false, false);
    const auto forgotten = run(ctx, vocab, true, true);
    const auto remembered = run(ctx, vocab, true, false);

    auto diff = [](const std::vector<float> &a, const std::vector<float> &b) {
        double m = 0;
        for (size_t i = 0; i < a.size(); i++) m = std::fmax(m, std::fabs(a[i] - b[i]));
        return m;
    };
    const double after_forgetting = diff(plain, forgotten);
    const double without_forgetting = diff(plain, remembered);
    std::printf("largest difference in the next token's scores:\n  question asked then forgotten: %g\n  question asked and kept:      %g\n",
                after_forgetting, without_forgetting);
    const bool ok = after_forgetting == 0.0 && without_forgetting > 1e-3;
    std::printf("%s\n", ok ? "PASS: forgetting the question restores the conversation exactly" : "FAIL");
    llama_free(ctx);
    llama_model_free(model);
    return ok ? 0 : 1;
}

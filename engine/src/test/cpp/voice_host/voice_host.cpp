// Tries engine/src/main/cpp/voice_core.h on a computer, with a real voice model, and writes what it made to a WAV file.
// Not part of the app. Build and run with run.sh in this folder.
//
//   voice_host MODEL.gguf MMPROJ.gguf "text to speak" OUT.wav [SPEAKER.wav] [LANGUAGE]

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "voice_core.h"

static void write_wav(const char *path, const std::vector<float> &pcm, int rate) {
    std::vector<int16_t> s(pcm.size());
    for (size_t i = 0; i < pcm.size(); i++) {
        const float v = std::fmax(-1.f, std::fmin(1.f, pcm[i]));
        s[i] = (int16_t) std::lrintf(v * 32767.f);
    }
    FILE *f = std::fopen(path, "wb");
    const uint32_t data = (uint32_t) (s.size() * 2), riff = 36 + data, fmt = 16, byte_rate = rate * 2;
    const uint16_t pcm_tag = 1, mono = 1, align = 2, bits = 16;
    std::fwrite("RIFF", 1, 4, f); std::fwrite(&riff, 4, 1, f); std::fwrite("WAVEfmt ", 1, 8, f); std::fwrite(&fmt, 4, 1, f);
    std::fwrite(&pcm_tag, 2, 1, f); std::fwrite(&mono, 2, 1, f); std::fwrite(&rate, 4, 1, f); std::fwrite(&byte_rate, 4, 1, f);
    std::fwrite(&align, 2, 1, f); std::fwrite(&bits, 2, 1, f); std::fwrite("data", 1, 4, f); std::fwrite(&data, 4, 1, f);
    std::fwrite(s.data(), 2, s.size(), f);
    std::fclose(f);
}

int main(int argc, char **argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s MODEL.gguf MMPROJ.gguf \"text\" OUT.wav [SPEAKER.wav] [LANGUAGE]\n", argv[0]);
        return 2;
    }
    llama_backend_init();
    pocketlm::VoiceSession session;
    const auto t0 = std::chrono::steady_clock::now();
    const bool cpu_only = std::getenv("VOICE_ALL_DEVICES") == nullptr;  // by default as on the phone: the CPU only
    const int threads = std::getenv("VOICE_THREADS") ? std::atoi(std::getenv("VOICE_THREADS")) : 8;
    const int rate = session.load(argv[1], argv[2], threads, cpu_only);
    const double load_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    if (rate <= 0) {
        std::fprintf(stderr, "load failed: %d\n", rate);
        return 1;
    }
    std::printf("loaded in %.1fs, %d Hz\n", load_s, rate);

    std::vector<float> pcm;
    const auto t1 = std::chrono::steady_clock::now();
    const bool ok = session.speak(argv[3], argc > 6 ? argv[6] : "en", argc > 5 ? argv[5] : "", 400, 40, 0.95f, 1234, pcm);
    const double speak_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t1).count();
    if (!ok) {
        std::fprintf(stderr, "speak failed: %s\n", session.error().c_str());
        return 1;
    }
    double sum = 0; float peak = 0;
    for (float v : pcm) { sum += v * v; peak = std::fmax(peak, std::fabs(v)); }
    const double seconds = (double) pcm.size() / rate;
    std::printf("%zu samples = %.2fs of audio in %.1fs (%.2fx real time), rms %.4f, peak %.3f\n",
                pcm.size(), seconds, speak_s, seconds / speak_s, std::sqrt(sum / pcm.size()), peak);
    write_wav(argv[4], pcm, rate);
    return 0;
}

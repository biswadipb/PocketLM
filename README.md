# PocketLM

Run AI language models locally on your Android phone, like LM Studio does on the desktop. Nothing leaves your device: models are downloaded once and inference happens on-device with [llama.cpp](https://github.com/ggml-org/llama.cpp).

## Features

- Chat with streaming responses
- Model manager: download from a built-in catalog, paste a Hugging Face `.gguf` link, or import a file from storage
- Resumable downloads, load/unload/delete models
- Any GGUF model supported by llama.cpp

## Project layout

| Path | What it is |
|---|---|
| `app/` | Jetpack Compose UI, ViewModel, model storage and downloads |
| `engine/` | JNI bridge and Kotlin API around llama.cpp (adapted from llama.cpp's `examples/llama.android`) |
| `third_party/llama.cpp` | llama.cpp as a git submodule |

## Building

Requirements: Android Studio (or JDK 17+), Android SDK 36, NDK 27, CMake 3.22.1.

```bash
git clone --recurse-submodules https://github.com/biswadipb/PocketLM.git
cd PocketLM
./gradlew :app:assembleDebug
```

If you already cloned without submodules: `git submodule update --init --depth 1`.

The first build compiles llama.cpp for `arm64-v8a` and `x86_64`, so it takes a few minutes.

## Choosing a model

Phones have limited RAM. As a rule of thumb, Q4-quantised models of 0.5B-1.5B parameters run well on most devices, and 3B wants 6 GB+ of RAM.

## Roadmap

- Adjustable context length, temperature and thread count
- Saved conversations
- GPU / NPU acceleration
- Hugging Face model search

## License

Apache-2.0. See [LICENSE](LICENSE). The `engine/` module is adapted from llama.cpp (MIT); see `third_party/llama.cpp/LICENSE`.

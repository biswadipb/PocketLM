# PocketLM

Run AI language models locally on your Android phone, like LM Studio does on the desktop. Nothing leaves your device: models are downloaded once and inference happens on-device with [llama.cpp](https://github.com/ggml-org/llama.cpp).

> **Status: early.** The app compiles and the retrieval logic has unit tests, but it has not yet been run on a device or emulator. Expect rough edges, especially in vision.

## Features

**Chat**
- Streaming replies with a stop button
- Markdown rendering: headings, lists, bold, italic, inline code, and fenced code blocks with a copy button
- Copy any reply, and regenerate the latest one
- Speed under each reply: tokens per second, token count, and time to first token

**Models**
- Built-in catalog of small models, resumable downloads, load / unload / delete
- Search Hugging Face for any GGUF model and download a specific quantisation
- Paste a `.gguf` link or import a file from storage
- Model details read from the GGUF header: parameters, architecture, quantisation, trained context length, layers, license

**Conversations**
- Chats are saved automatically; reopen or delete them from History
- Reopening a chat reloads its model and replays the recent conversation with your next message

**Documents**
- Attach text files (txt, md, csv, json, code) to a chat and ask questions about them
- Relevant excerpts are found with BM25 keyword search, so no embedding model is needed
- Attachments are saved with the chat

**Vision**
- Attach a photo to a message when a vision model is loaded
- Pair a vision add-on (`mmproj` file) with each model; the catalog includes SmolVLM 500M, which comes with its add-on
- Hugging Face search lists vision add-ons next to models

**Settings**
- Light, dark, or follow-system theme
- Generation: temperature, top-p, context length, CPU threads (applied when a model loads or a new chat starts)
- System prompt presets: four built in, plus your own

## Known limits

- PDFs are not supported as attachments, only plain text files
- One image per message, shrunk to 1024 px on the long side; earlier images are not re-shown to the model after a chat is reopened or a reply is regenerated
- Document search matches keywords, not meaning, so a question worded differently from the document may miss
- Long document chats fill the context faster; raise the context length in Settings if needed
- CPU only; no GPU or NPU acceleration yet

## Project layout

| Path | What it is |
|---|---|
| `app/` | Jetpack Compose UI, ViewModel, model storage, chat history, document retrieval |
| `engine/` | JNI bridge and Kotlin API around llama.cpp, including the multimodal (vision) library; adapted from llama.cpp's `examples/llama.android` |
| `third_party/llama.cpp` | llama.cpp as a git submodule |

## Building

Requirements: Android Studio (or JDK 17+), Android SDK 36, NDK 27, CMake 3.22.1. The app needs Android 11 (API 30) or newer.

```bash
git clone --recurse-submodules https://github.com/biswadipb/PocketLM.git
cd PocketLM
./gradlew :app:assembleDebug
```

If you already cloned without submodules: `git submodule update --init --depth 1`.

The first build compiles llama.cpp for `arm64-v8a` and `x86_64`, so it takes a few minutes. The debug APK is large (hundreds of MB) because the native libraries are not stripped.

Run the unit tests with `./gradlew :app:testDebugUnitTest`.

## Choosing a model

Phones have limited RAM. As a rule of thumb, Q4-quantised models of 0.5B-1.5B parameters run well on most devices, and 3B wants 6 GB+ of RAM.

## Roadmap

- Run on a device and fix what breaks
- Export and share chats, rename chats
- Local OpenAI-compatible API server
- Semantic document search with an embedding model, and PDF support
- GPU / NPU acceleration
- Smaller release builds

## License

Apache-2.0. See [LICENSE](LICENSE). The `engine/` module is adapted from llama.cpp (MIT); see `third_party/llama.cpp/LICENSE`.

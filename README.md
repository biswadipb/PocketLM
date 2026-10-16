# PocketLM

Run AI language models locally on your Android phone, like LM Studio does on the desktop. Nothing leaves your device: models are downloaded once and inference happens on-device with [llama.cpp](https://github.com/ggml-org/llama.cpp).

> **Status: early.** The app compiles and the retrieval logic has unit tests, but it has not yet been run on a device or emulator. Expect rough edges, especially in vision.

## Features

**Chat**
- Streaming replies with a stop button
- Markdown rendering: headings, lists, bold, italic, inline code, and fenced code blocks with a copy button
- Copy any reply, regenerate the latest one, or edit one of your messages and re-run from there
- Saved prompts: reusable messages you can insert from the message box
- Dictate a message with your phone's speech recogniser, and have replies read aloud (per reply, or automatically)
- Share text into PocketLM from any app, or send selected text through the text selection menu
- Open straight to the chat from a Quick Settings tile, a home-screen widget, or the app-icon shortcut
- Per-chat options: pick the model and system prompt for just that chat, or restrict replies to valid JSON
- Speed under each reply: tokens per second, token count, and time to first token
- Reasoning models: the `<think>` section a model writes first is folded into a "Thought process" you can open; copying, reading aloud and notifications use the answer only
- A memory meter shows how full the conversation is, with a warning as it fills, and a Continue button appears when a reply stops at its length limit
- Summarise and continue: once a chat is filling up, the model condenses it into a short summary and carries on from that, instead of silently dropping the oldest messages. Your messages stay on screen
- Regenerating keeps the earlier replies: page between them with `‹ 2/3 ›` under the latest reply, and your next message continues from the version shown
- Prompt inspector: see exactly what the model received for the latest reply (system prompt, document excerpts, tool results, and the text after the chat template), to work out why it answered as it did
- Replies can be restricted to valid JSON, or to JSON that follows a schema you type

**Models**
- Built-in catalog of small models with a fit check for this phone and a "Recommended" pick; load / unload / delete
- Resumable downloads that keep going in the background, with a progress notification
- Search for GGUF models and download a specific quantisation from Hugging Face, ModelScope, Kaggle, or any Hugging Face compatible server you type in (a regional mirror, or one on your own network)
- Paste a `.gguf` link or import a file from storage
- Model details read from the GGUF header: parameters, architecture, quantisation, trained context length, layers, license
- Fit warnings: an estimate of the memory each model needs, with a confirmation before loading one that probably will not fit
- Settings for each model: its own temperature, context length and so on, its own system prompt, a chat template to use when the model's own is missing or wrong (ChatML, Llama 3, Gemma and others), and an optional smaller draft model that speeds it up (experimental)
- Benchmark the loaded model (prompt and reply speed) and compare results across models
- Compare two models side by side on the same question

**Conversations**
- Chats are saved automatically; reopen, rename, pin, search and delete them from History
- Share a chat as text, or save it as a Markdown file
- Long replies keep going in the background (even with the screen off), with a Stop button in a small notification, and you get a notification when one is ready
- Reopening a chat reloads its model and picks up where you left off: the model's memory of each chat is saved after every reply, so there is nothing to read through again. If that saved state is missing (cleared, or the model or instructions changed), the recent conversation is replayed with your next message instead

**Documents**
- Attach text files (txt, md, csv, json, code) or PDFs to a chat and ask questions about them
- Attach a web page by its link: PocketLM downloads the page on the phone, keeps its text with the chat, and searches it like any other document
- PDFs are read as text; scanned PDFs (pictures of pages) are read with on-device text recognition, up to 40 pages
- A photo attached while the loaded model can't see images is turned into text the same way and attached as a document
- Relevant excerpts are found with BM25 keyword search, so no embedding model is needed
- Attachments are saved with the chat

**Vision**
- Attach a photo to a message when a vision model is loaded
- Pair a vision add-on (`mmproj` file) with each model; the catalog includes SmolVLM 500M, which comes with its add-on
- Hugging Face search lists vision add-ons next to models

**Local API server**
- Turn on an OpenAI-compatible server (`/v1/models`, `/v1/chat/completions` and `/v1/completions`, with streaming; per-request `temperature`, `top_p`, `max_tokens` and `stop` are honoured) so other apps on your Wi-Fi can use the loaded model
- Protected by an API key (sent as `Authorization: Bearer <key>`); the port is configurable
- `response_format` is honoured: `json_object` for any valid JSON, or `json_schema` for replies that follow a schema
- Serves one request at a time and restores your chat afterwards. Earlier turns in a request are replayed inside the prompt rather than through the model's chat template

```bash
curl http://PHONE_IP:8080/v1/chat/completions \
  -H "Authorization: Bearer pk-your-key" -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Say hello"}]}'
```

**Settings**
- Light, dark, or follow-system theme
- Backup and restore: one zip with your chats (with photos and attached files), saved prompts, presets and settings; the API key, app lock and downloaded models are left out
- Sync between devices (Settings, Sync): keeps your chats, with their photos and attached files, the same on all your devices. Pick where they go:
  - a folder you choose with Android's folder picker, which works with Google Drive, Dropbox, OneDrive, Nextcloud, Syncthing and any other app that offers its files there
  - a WebDAV folder (Nextcloud, ownCloud, a NAS), with a user name and app password
  - S3-compatible storage (Amazon S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO) with an endpoint, bucket and access keys
  
  Everything is encrypted on the phone before it is uploaded (AES-256-GCM, key from your passphrase with PBKDF2-HMAC-SHA256, 600,000 rounds), so the service only sees scrambled files and file sizes. The first device chooses the passphrase, the others enter the same one. The passphrase is never stored or uploaded; the derived key and any passwords are kept only wrapped by the Android Keystore. Each chat is compared on its own and the more recently changed copy wins; a deleted chat leaves a small note so the deletion reaches your other devices too. Syncing runs when you tap Sync now, shortly after a chat is saved or changed, and when the app opens (automatic syncs can be limited to Wi-Fi). Optionally, generation settings, system prompts, saved prompts and memory notes sync too; the API key, app lock, model and hub settings, downloaded models and per-phone choices (theme, threads, context size) never do
- Free memory when idle: unload the model after 5 to 60 minutes of inactivity; it loads again when you send a message (off by default)
- Memory notes: facts about you ("I'm vegetarian", "I write Kotlin") that are added to every chat's system prompt while switched on; they are never sent to apps that use the local API server
- Save each chat's state (on by default): faster reopening and waking, at the cost of cache storage (up to about 0.75 GB in all, which Android or "Clear cache" can take back)
- Default assistant: pick PocketLM in your phone's assistant setting and the phone's assistant gesture opens PocketLM ready to type
- Diagnostics: a report to copy into a bug report, with your device, why the app last closed (for example Android ending it because memory ran low), any crash, and recent warnings and errors. Your messages are not written to the log
- Model tools: let the model use a calculator, the date and time, a Wikipedia lookup, or a web search (through a SearXNG server you choose) while it answers (off by default; the model asks with a short tag, the app runs the tool and hands back the result)
- Generation: temperature, top-p, top-k, min-p, repeat penalty, seed, context length, CPU threads (applied when a model loads or a new chat starts), and max reply length (applies to the next reply)
- Stop words: end a reply as soon as one appears (applies immediately)
- System prompt presets: four built in, plus your own
- App lock: require fingerprint, face or screen lock; also hides the app in recent apps and blocks screenshots
- Storage: see what PocketLM uses, clean up unused files, or delete all chats
- Battery and heat warnings appear in the chat when the phone is warm or low on power

## Known limits

- Sync is written and unit tested (encryption round trips, Signature V4 against Amazon's published examples, merging of conflicting chat sets, a fake WebDAV and S3 server, a real local HTTP server for GET/PUT/DELETE) but has never run on a device or against a real Drive, Dropbox, Nextcloud or S3 account. The folder picker backend (including how different storage apps handle file names and overwrites), WebDAV and S3 are all untested against real services
- Sync conflicts are settled per chat by the time it was last changed on each phone, so a wrong phone clock can make an older edit win. If two phones change the same chat before either syncs, the later edit replaces the earlier one entirely; the chats are not merged message by message. A chat deleted on one phone and edited later on another comes back
- Phones only learn about changes when they sync (see above); nothing polls in the background. An open chat is not replaced under you: it updates after you leave it
- There is no lock on the sync location, so two phones syncing at the same moment can briefly write the index over each other. Each phone writes anything missing again on its next sync, so it settles by itself, but a file-sync app such as Dropbox may make "conflicted copy" files that PocketLM ignores
- Changing the passphrase is not supported: turn sync off, then set it up again at a new, empty location with a new passphrase. A forgotten passphrase cannot be recovered
- Deletions are remembered for 180 days; a phone that was off for longer may bring a deleted chat back. Restoring an old backup of a chat that was deleted elsewhere will have the deletion applied again at the next sync
- WebDAV and S3 addresses must be https (Android blocks plain http), and a server with a self-signed certificate will not work. WebDAV sync only needs GET, PUT and DELETE, but creating a missing folder (MKCOL) and the folder listing (PROPFIND) are not standard on Android's HTTP client and are tried on a best-effort basis, so create the WebDAV folder yourself first. S3 requests are path-style; the access key needs permission to list, read, write and delete in the bucket
- Sync does not carry the saved chat states (the model's memory of each chat); a synced chat is read in again the first time it is opened on another phone

- Saved chat states, summaries, reply versions, the draft model and the assistant service are all written but untested on a device; speculative decoding in particular is new native code, so turn it off for a model that misbehaves
- A draft model must share the main model's vocabulary (usually a smaller model of the same family), keeps a second model in memory, and is switched off while replies are restricted to JSON
- Reply versions can be paged on the latest reply only; once you send another message, the version you were on becomes part of the chat
- Which gesture opens the assistant depends on the phone maker; some phones use the power button, some the home button or a corner swipe

- PDF text extraction and text recognition (OCR) are untested on a device; OCR reads printed Latin-alphabet text only
- Tool use depends on the model following a text format, which small models do inconsistently
- Web search needs a SearXNG server with JSON results turned on (most public ones turn it off), and was written against its documented format rather than a live server
- One image per message, shrunk to 1024 px on the long side; earlier images are not re-shown to the model after a chat is reopened or a reply is regenerated
- Document search matches keywords, not meaning, so a question worded differently from the document may miss
- Long document chats fill the context faster; raise the context length in Settings if needed
- The API server is plain HTTP on your local network, so only enable it on a network you trust; it stops if Android closes the app, and the phone may sleep and drop requests
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

The first build compiles llama.cpp, so it takes a few minutes. Debug builds include `arm64-v8a` and `x86_64` (for the emulator).

For a release build, which is for real phones (`arm64-v8a` only, stripped, minified, about 33 MB):

```bash
./gradlew :app:assembleRelease
```

The result is unsigned; sign it with your own keystore before installing it or publishing it. The native libraries are built for 16 KB memory pages, which newer Android versions require.

Run the unit tests with `./gradlew :app:testDebugUnitTest`.

## Choosing a model

Phones have limited RAM. As a rule of thumb, Q4-quantised models of 0.5B-1.5B parameters run well on most devices, and 3B wants 6 GB+ of RAM.

## Roadmap

- Run on a device and fix what breaks
- More tools
- Semantic document search with an embedding model, and more OCR languages
- GPU / NPU acceleration

## License

Apache-2.0. See [LICENSE](LICENSE). The `engine/` module is adapted from llama.cpp (MIT); see `third_party/llama.cpp/LICENSE`.

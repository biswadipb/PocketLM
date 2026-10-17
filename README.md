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
- Incognito chats: the eye-off button starts a chat that is never saved, synced or listed in History. Its attached files and photos are erased when you leave it (and on the next start if the app was killed), its notification does not show the reply, and the screen is hidden from recent apps and screenshots while it is open
- Thinking switch: for models that think before answering (Qwen3, DeepSeek R1 and similar), a per-chat switch in Chat options starts the answer straight away, which is much faster on a phone
- Search this chat for a word, and star messages to keep them: History, star icon, lists every starred message and opens its chat at that message
- Share a PDF, photo or text file from another app into PocketLM and it opens a fresh chat with the file attached and a question ready; a shared link is read as a web page
- Suggested memory notes: Chat options, Suggest memory notes. The model reads what you said and offers facts worth remembering; you tick the ones to keep
- Hands-free conversation: tap the voice-chat button and talk. PocketLM listens, sends what you said, reads the reply aloud and listens again, until you say "stop", tap End, or leave the screen. It uses the phone's speech recogniser (on the phone itself where it has one) and needs the microphone permission
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
- Search for GGUF models and download a specific quantisation from Hugging Face, ModelScope, Kaggle (with a Load more button for further pages of results), or any Hugging Face compatible server you type in (a regional mirror, or one on your own network)
- Paste a `.gguf` link or import a file from storage
- Model details read from the GGUF header: parameters, architecture, quantisation, trained context length, layers, license
- Fit warnings: an estimate of the memory each model needs, with a confirmation before loading one that probably will not fit
- LoRA adapters: attach small fine-tune files to a model, each with a strength, in that model's settings. They are applied when the model loads, and a saved chat state is never reused with different adapters
- Settings for each model: its own temperature, context length and so on, its own system prompt, a chat template to use when the model's own is missing or wrong (ChatML, Llama 3, Gemma and others), and an optional smaller draft model that speeds it up (experimental)
- Benchmark the loaded model (prompt and reply speed) and compare results across models
- Compare two models side by side on the same question

**Conversations**
- Chats are saved automatically; reopen, rename, pin, search and delete them from History
- Tags: label chats (up to 8 each) and filter History by a tag. Tags travel with the chat when syncing
- Import from ChatGPT or Claude: Settings, Backup. Choose the .zip you were sent (or its conversations.json); the text of every conversation becomes a chat, tagged by where it came from. The branch of a ChatGPT conversation that was last shown is kept. Importing the same file again does not duplicate chats
- Share a chat as text, or save it as a Markdown file
- Long replies keep going in the background (even with the screen off), with a Stop button in a small notification, and you get a notification when one is ready
- Reopening a chat reloads its model and picks up where you left off: the model's memory of each chat is saved after every reply, so there is nothing to read through again. If that saved state is missing (cleared, or the model or instructions changed), the recent conversation is replayed with your next message instead

**Documents**
- Attach text files (txt, md, csv, json, code) or PDFs to a chat and ask questions about them
- Attach a web page by its link: PocketLM downloads the page on the phone, keeps its text with the chat, and searches it like any other document
- PDFs are read as text; scanned PDFs (pictures of pages) are read with on-device text recognition, up to 40 pages. Choose the writing system in Settings, Text recognition: Latin, Chinese, Japanese, Korean or Devanagari. Each one's language data is downloaded once through Google Play services the first time it is used (it is not packed into the app) and works offline after that
- A photo attached while the loaded model can't see images is turned into text the same way and attached as a document
- Relevant excerpts are found with BM25 keyword search, which needs no extra model
- My notes (Settings): choose a folder of text, Markdown, HTML or PDF files (an Obsidian vault, a Drive or Nextcloud folder) and chats look for answers in it too, with the passages shown to the model and labelled by file. It is read-only; only new or changed files are read again, at the start of the app and on request; what is learned stays on the phone and is not synced or backed up. It can be switched off per chat in Chat options. Folders over about 3,000 files or 12 million characters are cut short
- Optional search by meaning: choose a small embedding model in Settings, Document search (BGE Small, Nomic Embed or BGE M3 can be downloaded there). Each document is read once by that model in the background, and the results are merged with the keyword ranking, so a question worded differently from the document still finds the right passage. Until a document has been read, keyword search answers
- Attachments are saved with the chat

**Vision**
- Attach up to 4 photos to a message when a vision model is loaded; the model sees them in the order you added them
- Pair a vision add-on (`mmproj` file) with each model; the catalog includes SmolVLM 500M, which comes with its add-on
- Hugging Face search lists vision add-ons next to models

**Local API server**
- Turn on an OpenAI-compatible server (`/v1/models`, `/v1/chat/completions`, `/v1/completions`, `/v1/embeddings` with an embedding model chosen (float or base64), and tool calls (`tools`, `tool_choice`, `tool_calls`, and `tool` messages) on chat completions, with streaming; per-request `temperature`, `top_p`, `max_tokens` and `stop` are honoured) so other apps on your Wi-Fi can use the loaded model
- Protected by an API key (sent as `Authorization: Bearer <key>`); the port is configurable
- `response_format` is honoured: `json_object` for any valid JSON, or `json_schema` for replies that follow a schema
- Serves one request at a time and restores your chat afterwards. Earlier turns in a request are replayed inside the prompt rather than through the model's chat template

```bash
curl http://PHONE_IP:8080/v1/chat/completions \
  -H "Authorization: Bearer pk-your-key" -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Say hello"}]}'
```

**Settings**
- Light, dark, or follow-system theme, and a text size setting (85% to 150%). On a tablet, foldable or phone on its side, History stays beside the chat
- Backup and restore: one zip with your chats (with photos and attached files), saved prompts, presets and settings; the API key, app lock and downloaded models are left out
- Sync between devices (Settings, Sync): keeps your chats, with their photos and attached files, the same on all your devices. Pick where they go:
  - a folder you choose with Android's folder picker, which works with Google Drive, Dropbox, OneDrive, Nextcloud, Syncthing and any other app that offers its files there
  - a WebDAV folder (Nextcloud, ownCloud, a NAS), with a user name and app password
  - S3-compatible storage (Amazon S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO) with an endpoint, bucket and access keys
  
  Everything is encrypted on the phone before it is uploaded (AES-256-GCM, key from your passphrase with PBKDF2-HMAC-SHA256, 600,000 rounds), so the service only sees scrambled files and file sizes. The first device chooses the passphrase, the others enter the same one. The passphrase is never stored or uploaded; the derived key and any passwords are kept only wrapped by the Android Keystore. Each chat is compared on its own and the more recently changed copy wins; a deleted chat leaves a small note so the deletion reaches your other devices too. Syncing runs when you tap Sync now, shortly after a chat is saved or changed, when the app opens, and in the background every 15 minutes, hour, 6 hours or day while the app is closed (Android chooses the exact time; automatic syncs can be limited to Wi-Fi). The passphrase can be changed: everything at the location is locked again with the new one, and other devices are asked for it the next time they sync. Optionally, generation settings, system prompts, saved prompts and memory notes sync too; the API key, app lock, model and hub settings, downloaded models and per-phone choices (theme, threads, context size) never do
- Free memory when idle: unload the model after 5 to 60 minutes of inactivity; it loads again when you send a message (off by default)
- Memory notes: facts about you ("I'm vegetarian", "I write Kotlin") that are added to every chat's system prompt while switched on; they are never sent to apps that use the local API server
- Save each chat's state (on by default): faster reopening and waking, at the cost of cache storage (up to about 0.75 GB in all, which Android or "Clear cache" can take back)
- Default assistant: pick PocketLM in your phone's assistant setting and the phone's assistant gesture opens PocketLM ready to type
- Diagnostics: a report to copy into a bug report, with your device, why the app last closed (for example Android ending it because memory ran low), any crash, and recent warnings and errors. Your messages are not written to the log
- Model tools: let the model use a calculator, the date and time, a Wikipedia lookup, or a web search while it answers. Search with DuckDuckGo (nothing to set up), Brave Search (with your own free API key) or a SearXNG server you choose (off by default; the model asks with a short tag, the app runs the tool and hands back the result)
- Compact memory cache: store the model's memory of the chat in 8 or 4 bits instead of 16 (about half or a quarter of the RAM), for a longer context or a bigger model. If the engine cannot do it, the normal cache is used and you are told
- Generation: temperature, top-p, top-k, min-p, repeat penalty, seed, context length, CPU threads (applied when a model loads or a new chat starts), and max reply length (applies to the next reply)
- Stop words: end a reply as soon as one appears (applies immediately)
- System prompt presets: four built in, plus your own
- App lock: require fingerprint, face or screen lock; also hides the app in recent apps and blocks screenshots
- Storage: see what PocketLM uses, clean up unused files, or delete all chats
- Battery and heat warnings appear in the chat when the phone is warm or low on power
- Slow down when hot or low on battery (on by default): while the phone is warm or the battery is low and not charging, the model uses fewer threads and replies are capped at 768 tokens (384 when very hot or the battery is nearly flat), then everything goes back to normal. The context length is not changed, since that needs the model to load again. API requests keep the length they asked for

## Known limits

- Also new and untested on a device: the compact memory cache, the thinking switch (it starts the answer with an empty thinking section, for models whose chat template mentions thinking), incognito chats, sharing files and links in, search in a chat and starred messages, text size and the two-pane layout, suggested notes, and API tool calls
- API tool calls are done by describing the functions to the model in its instructions in the `<tool_call>` form that Qwen, Hermes and Llama 3.1 models know, so they only work with models that follow it. A request with tools is answered once the model has finished, even when streaming is asked for
- Incognito keeps the chat out of every file PocketLM writes, but the phone's speech recogniser (if you use voice) and the keyboard are outside its control
- My notes was built and unit tested (the folder rules, the saved copy, merging excerpts) but its reading of a folder through Android's folder picker has never run, and a big folder will take a while the first time. New and untested on a device: the hands-free conversation (speech recognition, text-to-speech and the loop between them), LoRA adapters, search by meaning and `/v1/embeddings` (the native embedding code builds, but no embedding model has been loaded), slowing down when hot, and the background sync job. The ChatGPT and Claude importers were written from the formats those services use and tested on made-up files that follow them, not on a real export, so a format change or an unusual conversation may be skipped (the import counts them)
- A LoRA adapter has to be made for the exact model it is used with. PocketLM checks the architecture and then lets the engine decide; one that does not fit is skipped with a message. Adapters apply to the main model, not to a draft model
- Search by meaning uses the first 512 tokens of each passage, only helps once a document has been read by the embedding model (large documents take a while), and does not cover pictures. Its vectors are kept on the phone only and are not synced or backed up
- Imports bring in text only, not images, files or the tools a conversation used, and conversations over about 2 million characters are skipped
- Sync is written and unit tested (encryption round trips, Signature V4 against Amazon's published examples, merging of conflicting chat sets, a fake WebDAV and S3 server, a real local HTTP server for GET/PUT/DELETE) but has never run on a device or against a real Drive, Dropbox, Nextcloud or S3 account. The folder picker backend (including how different storage apps handle file names and overwrites), WebDAV and S3 are all untested against real services
- Sync conflicts are settled per chat by the time it was last changed on each phone, so a wrong phone clock can make an older edit win. If two phones change the same chat before either syncs, the later edit replaces the earlier one entirely; the chats are not merged message by message. A chat deleted on one phone and edited later on another comes back
- Phones learn about changes when they sync: when you tap Sync now, after a change, when the app opens, and in the background at the interval you chose, which Android may delay a lot when the phone is idle or the battery is low. An open chat is not replaced under you: it updates after you leave it
- There is no lock on the sync location, so two phones syncing at the same moment can briefly write the index over each other. Each phone writes anything missing again on its next sync, so it settles by itself, but a file-sync app such as Dropbox may make "conflicted copy" files that PocketLM ignores
- Changing the passphrase makes this phone delete and send everything again, so it needs a connection and takes a while for a large history; if it is interrupted, change it again. Other devices keep their chats and ask for the new passphrase. A forgotten passphrase cannot be recovered
- Deletions are remembered for 180 days; a phone that was off for longer may bring a deleted chat back. Restoring an old backup of a chat that was deleted elsewhere will have the deletion applied again at the next sync
- WebDAV and S3 addresses must be https (Android blocks plain http), and a server with a self-signed certificate will not work. WebDAV sync only needs GET, PUT and DELETE, but creating a missing folder (MKCOL) and the folder listing (PROPFIND) are not standard on Android's HTTP client and are tried on a best-effort basis, so create the WebDAV folder yourself first. S3 requests are path-style; the access key needs permission to list, read, write and delete in the bucket
- Sync does not carry the saved chat states (the model's memory of each chat); a synced chat is read in again the first time it is opened on another phone

- Saved chat states, summaries, reply versions, the draft model and the assistant service are all written but untested on a device; speculative decoding in particular is new native code, so turn it off for a model that misbehaves
- A draft model must share the main model's vocabulary (usually a smaller model of the same family), keeps a second model in memory, and is switched off while replies are restricted to JSON
- Reply versions can be paged on the latest reply only; once you send another message, the version you were on becomes part of the chat
- Which gesture opens the assistant depends on the phone maker; some phones use the power button, some the home button or a corner swipe

- PDF text extraction and text recognition (OCR) are untested on a device. OCR reads printed text only, needs Google Play services to download its language data once, and does not cover Cyrillic, Arabic, Greek, Hebrew or Thai
- Tool use depends on the model following a text format, which small models do inconsistently
- Web search with DuckDuckGo reads its plain results page, which DuckDuckGo can change or block (it was checked against a real page, but not inside the app); Brave Search and SearXNG were written against their documented formats rather than live servers, and a SearXNG server needs JSON results turned on (most public ones turn it off)
- Up to 4 images per message, each shrunk to 1024 px on the long side; earlier images are not re-shown to the model after a reply is regenerated or when a saved chat state is not used
- Document search matches keywords, not meaning, so a question worded differently from the document may miss
- Long document chats fill the context faster; raise the context length in Settings if needed
- The API server is plain HTTP unless you turn on HTTPS in Settings. With HTTPS the phone makes its own certificate, which apps that call it must be told to trust (copy it from Settings); it is renewed when the phone joins a new network. The server stops if Android closes the app, and the phone may sleep and drop requests
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
- GPU / NPU acceleration

## License

Apache-2.0. See [LICENSE](LICENSE). The `engine/` module is adapted from llama.cpp (MIT); see `third_party/llama.cpp/LICENSE`.

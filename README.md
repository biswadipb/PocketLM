# PocketLM

Run AI language models locally on your Android phone, like LM Studio does on the desktop. Nothing leaves your device: models are downloaded once and inference happens on-device with [llama.cpp](https://github.com/ggml-org/llama.cpp).

The assistant you talk to in PocketLM is called **Piko**: it is the name in the default system prompt, on the "Ask Piko" tile, widget and text-selection entries, and in the phone's default-assistant list. The app itself stays PocketLM.

> **Status: early.** The app compiles and the retrieval logic has unit tests, but it has not yet been run on a device or emulator. Expect rough edges, especially in vision.

## Features

**Chat**
- Streaming replies with a stop button
- Markdown rendering: headings, lists, bold, italic, inline code, and fenced code blocks with a copy button
- Copy any reply, regenerate the latest one, or edit one of your messages and re-run from there
- Saved prompts: reusable messages you can insert from the message box. A prompt can have fields that are filled in when you insert it: `{{date}}`, `{{time}}`, `{{weekday}}`, `{{clipboard}}` (read only when the prompt asks for it), and `{{ask: tone}}` or `{{ask: tone = friendly}}`, which opens a small form asking for each answer. Anything else in double braces is left alone
- Branch from here: the ⋮ menu on a message copies the chat up to that point (with its photos and attached files, which are copied so deleting one chat never affects the other) into a new chat and opens it. On one of your own messages it keeps what came before and puts that message back in the message box to reword
- Automatic chat names: after the first reply, the model names the chat (a short title) and, if you already use tags, files it under up to two that clearly fit; it never invents new tags. It asks inside the conversation it already remembers and then forgets the question, so the next message needs no replay. Switch it off in Settings; a chat you renamed is never touched
- Sources under a reply: when documents or your notes folder supplied passages, "Looked at" chips under the reply name the files; tapping one shows the passage (and copies it). They record what the model was shown, not what it used. They are saved with the chat, and appear in exports
- Dictate a message with your phone's speech recogniser, or, in Settings, with a speech model that runs on the phone (Qwen3-ASR, about 1 GB, many languages) so no audio is sent anywhere; replies can be read aloud (per reply, or automatically)
- Share text into PocketLM from any app, or select text and choose Explain, Translate, Summarise or Improve with PocketLM in the text selection menu
- Characters: presets with a greeting and an emoji. A new chat offers "Start a chat as…" with a storyteller, interview coach, debate partner and pirate built in, and you can make your own in Settings, System prompt
- Reply from the notification: when a reply is ready while the app is closed, type your next message into the notification and the model answers without opening the app (if the app is locked or closed it opens with your text ready instead)
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
- Replies can be restricted to valid JSON, to JSON that follows a schema you type, or to a grammar you write (Chat options, Custom grammar; llama.cpp's GBNF format, with five examples to start from, such as "yes or no" or a bullet list). The app checks the grammar before using it and says which line is wrong. A grammar replaces JSON mode while it is on, switches the draft model off, and is best used with thinking switched off

**Health and fitness**
- A hub for fitness, the gym, food and diet, opened from the "Health and fitness" chip on an empty chat or from Settings, Tools. A row of cards starts each thing: build a workout plan, plan meals for a week, log a meal, log a workout, quiz yourself, flashcards, and get coached step by step
- "About you": age, height, weight, activity, goal, how you eat, allergies, where you train, experience and anything else. Plans, meal plans and the coach all read it, so they fit you. It is saved in one small file on the phone and is not synced, backed up, or sent anywhere; "Delete my health data" erases it with the log and flashcard progress
- Daily numbers are worked out in code, not guessed by the model: calories from the Mifflin-St Jeor equation and your activity, a gentle goal (at most a fifth or 500 kcal under what you use, or 10% / 350 kcal over for building muscle), protein by body weight, fat, carbohydrate and water. They are labelled as estimates. Calories are never set below 1,200 (female), 1,350 (other) or 1,500 (male), never above what you use when losing, and a BMI under 18.5 never gets a weight-loss target
- Safety: no calorie or weight targets for anyone under 18, anyone who says they are pregnant or breastfeeding, or anyone with a history of disordered eating; a medical condition or injury adds a reminder to check with a professional. Every health chat carries rules that forbid crash diets, extreme fasting, laxatives, steroids and risky supplements, and tell the model to point you to a doctor. Piko says it is not a doctor or a dietitian, and the screen repeats it
- Workout and meal plans are written as a normal chat (character "Piko coach", tagged "health" and named for the plan), so you can ask for changes and find them again in History or on the hub
- Today: calories, protein and water against your targets, a week of bars, a day streak, a 250 ml water button with undo, and weigh-ins. Meals come from a built-in table of about 65 common foods (including Indian staples) for instant, exact-per-100 g values, from the model's estimate of a description you typed (always shown for you to correct before it is saved), or typed by hand
- Quiz yourself: 32 built-in questions on nutrition, training and recovery with explanations (works with no model), or ask Piko for five more on any topic; flashcards (29 built in, and Piko can write more) are shown again after 1, 3, 7 and 14 days when you remember them and start over when you do not

**Models**
- Built-in catalog of small models with a fit check for this phone and a "Recommended" pick; load / unload / delete
- Resumable downloads that keep going in the background, with a progress notification
- Downloads from Hugging Face are checked against the SHA-256 it publishes when they finish (a file that does not match is deleted), and can be limited to Wi-Fi so they never use mobile data. A model list in PocketLM's repository (`catalog/models.json`) can add models to the catalog without a new app version; it is read when you open Models, can be switched off, and only Hugging Face and ModelScope links in it are accepted
- Download a model by its Ollama name (Models, From the Ollama library): type `llama3.2:1b` and PocketLM reads the model's manifest from registry.ollama.ai, shows the size, and downloads the model file (and a vision add-on, if it has one), checking it against the SHA-256 in the manifest. Only the model file is taken, not Ollama's template or settings, and PocketLM runs it with llama.cpp, so a model made in Ollama's own newer format may not load
- Search for GGUF models and download a specific quantisation from Hugging Face, ModelScope, Kaggle (with a Load more button for further pages of results), or any Hugging Face compatible server you type in (a regional mirror, or one on your own network)
- Paste a `.gguf` link or import a file from storage
- Model details read from the GGUF header: parameters, architecture, quantisation, trained context length, layers, license
- Fit warnings: an estimate of the memory each model needs, with a confirmation before loading one that probably will not fit
- LoRA adapters: attach small fine-tune files to a model, each with a strength, in that model's settings. They are applied when the model loads, and a saved chat state is never reused with different adapters
- Settings for each model: its own temperature, context length and so on, its own system prompt, a chat template to use when the model's own is missing or wrong (ChatML, Llama 3, Gemma and others), and an optional smaller draft model that speeds it up (experimental)
- GPU acceleration (experimental, in each model's settings): run a model on the phone's GPU through Vulkan (any vendor) or OpenCL (Qualcomm Adreno), or let PocketLM choose. It is off by default, loads the GPU libraries only when you ask (so a faulty graphics driver cannot stop the app starting), checks the GPU with a test pass while loading, falls back to the CPU if anything fails, and switches itself off if the app closes while trying it. Mali phones (such as the Moto Edge 50 Neo) can only use Vulkan; OpenCL needs a supported Adreno (Snapdragon 8 Gen 3 or newer, per llama.cpp). Expect mostly faster reading of long prompts; writing replies is often no faster. The draft model, document search and image reading stay on the CPU
- Benchmark the loaded model (prompt and reply speed) and compare results across models
- Compare two models side by side on the same question

**Conversations**
- Chats are saved automatically; reopen, rename, pin, search and delete them from History
- Tags: label chats (up to 8 each) and filter History by a tag. Tags travel with the chat when syncing
- Projects (History, folder icon): a group of chats that share instructions and documents. Every chat in a project follows its instructions and can answer from its documents without attaching them again. Projects stay on the phone that made them
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
- My notes (Settings): choose a folder of text, Markdown, HTML or PDF files (an Obsidian vault, a Drive or Nextcloud folder) and chats look for answers in it too, with the passages shown to the model and labelled by file. PocketLM reads the folder and adds to it only when you ask: "Save to my notes" (the ⋮ menu on a reply, or Chat options for a whole chat) writes a Markdown file with a small header (source, date, tags) into a "PocketLM" folder inside it, named after the day and subject, never replacing or changing anything. That needs the folder to have been chosen with permission to edit (Settings says so, with an "Allow saving" button for folders chosen earlier). Only new or changed files are read again, at the start of the app and on request; what is learned stays on the phone and is not synced or backed up. It can be switched off per chat in Chat options. Folders over about 3,000 files or 12 million characters are cut short
- Optional search by meaning: choose a small embedding model in Settings, Document search (BGE Small, Nomic Embed or BGE M3 can be downloaded there). Each document is read once by that model in the background, and the results are merged with the keyword ranking, so a question worded differently from the document still finds the right passage. Until a document has been read, keyword search answers
- Attachments are saved with the chat

**Vision**
- Attach up to 4 photos to a message when a vision model is loaded; the model sees them in the order you added them
- Pair a vision add-on (`mmproj` file) with each model; the catalog includes SmolVLM 500M, which comes with its add-on
- Hugging Face search lists vision add-ons next to models

**Local API server**
- Turn on an OpenAI-compatible server (`/v1/models`, `/v1/chat/completions`, `/v1/completions`, `/v1/embeddings` with an embedding model chosen (float or base64), `/v1/audio/transcriptions` with the speech model used for dictation (upload a recording as `file`; WAV is read directly, and MP3, M4A, AAC, OGG, FLAC and the like through the phone's own decoders, up to 5 minutes and 25 MB; the answer is `json`, `text` or `verbose_json`), and tool calls (`tools`, `tool_choice`, `tool_calls`, and `tool` messages) on chat completions, with streaming; per-request `temperature`, `top_p`, `max_tokens` and `stop` are honoured) so other apps on your Wi-Fi can use the loaded model
- A request's `model` can load another downloaded model if you switch on "Let requests choose the model" (off by default): the model is loaded first (after a memory check), stays loaded afterwards, and `GET /v1/models` then lists every downloaded chat model. A name that matches nothing is ignored, as many clients send names such as "gpt-4"
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
- Automation: let Tasker, MacroDroid, Automate or a similar app send PocketLM a prompt and get the answer back, even when PocketLM is closed (Settings, Automation; off by default). The app sends a broadcast to the receiver `com.pocketlm.app/.AutomationReceiver` with the action `com.pocketlm.app.action.ASK` and the extras `token` (the secret shown in Settings) and `prompt`; optional extras are `system`, `model`, `max_tokens`, `reply_package`, `reply_action`, `request_id`, `notify` and `save_chat`. Requests without the secret, or while it is switched off, are ignored silently. The answer is sent as a broadcast only to the package named in `reply_package`, with the extras `reply`, `error`, `model` and `request_id`. Your memory notes are never included, at most 8 requests wait at a time, and prompts are limited to 20,000 characters. A background job loads the model, answers and unloads it again (if PocketLM is open with a model loaded, that model answers)
- Scheduled prompts: have PocketLM run a prompt by itself at a time you choose, on the days you choose, such as a morning summary of the notes that changed (Settings, Scheduled prompts). The answer comes as a notification and as a chat in History tagged "scheduled". A prompt can include nothing else, the notes that changed since it last ran (the run is skipped, without using the model, if none did), or the passages of your notes that match it. Android picks the exact moment, usually within about 20 minutes after the time, and only when the battery is not low; a phone that was off catches up once. "Run now" tries a schedule
- Scheduled page (History, clock icon): the same tasks as a page like Gemini's "Scheduled". Describe a task in one sentence and Piko works out the time, the days and the prompt ("every weekday at 7:30, summarise the tech news", "on Mondays and Thursdays at 6pm, write me a study quiz", "weekly on Sunday at 10am plan my meals"); the form opens with what it understood, so you check it before it is saved. Nine suggested tasks (news digest, morning briefing with the weather, study quiz, market trends, meal plan, industry digest, something new from Wikipedia, notes recap, journal prompt) start the form filled in, and say which tools they need. Each task shows when it last ran, how it went, when it runs next, and opens the chat its last run saved
- Scheduled tasks can use tools: with "Let it use my tools" on, a run that nobody is watching can search the web, read a page, check the weather, look things up on Wikipedia and use any skill, with the same rules as in a chat (it may only open addresses from the prompt or from its own search results). The tools that need you to answer (reminders, suggested notes) are never offered to it. Each run asks the model up to three times for a tool, then answers
- Customize (History, sliders icon): three tabs like Gemini's. Discover has 16 ready-made skills in four groups (everyday tools, writing, study, work) that can be added with one tap or opened, changed and then added; Connectors has the model tools as switches and shortcuts to the notes folder, Wikipedia file and sync; Skills lists yours with a switch, Use, Edit and Delete, and Create writes a new one. A skill is a name, a one-line description and instructions (up to 4,000 characters). Piko is shown only the names and descriptions; when a request fits it loads the instructions with a skill tool, so skills use almost no memory until needed. They are kept on the phone and included in backups, not in chat sync
- Wikipedia without a connection (Settings): give the Wikipedia tool a Wikipedia file from Kiwix (a ZIM file) and it reads articles on the phone instead of asking wikipedia.org. Download "English Wikipedia, top articles" (about 285 MB, the start of each article; the newest file is looked up on download.kiwix.org and its checksum is checked) or choose any .zim file, up to many gigabytes, since only the part needed is read. Articles are found by title, including redirects and questions like "who was Ada Lovelace" (names inside a question are tried), and the start of the article is returned without the infobox. It is written in plain Kotlin, including a Zstandard decoder (no native library), and also reads xz-compressed files
- Natural voice (Settings, Voice; experimental and slow, see Known limits): read replies aloud with a speech model on the phone instead of the phone's voice. It uses llama.cpp's audio generation; the download is the official Qwen3-TTS 1.7B (about 1.5 GB), which speaks ten languages and can imitate a short recording of a voice you choose (use your own voice, or one you have permission to use). A reply is spoken a sentence at a time while the next is made, and the model is let go a few minutes after it last spoke
- Free memory when idle: unload the model after 5 to 60 minutes of inactivity; it loads again when you send a message (off by default)
- Memory notes: facts about you ("I'm vegetarian", "I write Kotlin") that are added to every chat's system prompt while switched on; they are never sent to apps that use the local API server
- Save each chat's state (on by default): faster reopening and waking, at the cost of cache storage (up to about 0.75 GB in all, which Android or "Clear cache" can take back)
- Default assistant: pick Piko in your phone's assistant setting and the phone's assistant gesture opens PocketLM ready to type. If you allow it to use text and a screenshot from the screen in the phone's assistant settings, the gesture opens a fresh chat with what was on your screen attached, and the question "What is on my screen?" ready (password fields are left out)
- Diagnostics: a report to copy into a bug report, with your device, why the app last closed (for example Android ending it because memory ran low), any crash, and recent warnings and errors. Your messages are not written to the log
- Faster new chats: when a system prompt is long (instructions, memory notes, a project's, a character's), what the model remembers after reading it is saved once and loaded the next time the same prompt is used with the same model and settings, instead of being read again. Short prompts are not saved, and the saved states live in the cache and can be cleared
- Accessibility: switches and their explanations are one control each; message bubbles say who wrote them and offer copy, read aloud, star, edit, regenerate, branch and save in the screen reader's actions menu; headings are marked; rows that open something say so; touch targets are at least 48 dp; a polite announcement tells a screen-reader user when a reply starts and when it is ready; text and control colours are checked against WCAG 2 contrast ratios (4.5 for text, 3 for outlines) by a unit test, in both themes
- Model tools: let the model use a calculator, the date and time, a Wikipedia lookup (online, or from a Wikipedia file on the phone), or a web search while it answers. Search with DuckDuckGo (nothing to set up), Brave Search (with your own free API key) or a SearXNG server you choose (off by default; the model asks with a short tag, the app runs the tool and hands back the result)
- Six more tools, each off until you switch it on in Settings:
  - Read a web page: opens a link you wrote in the chat, or one a web search returned, and reads its text (the first 3,000 characters). It will not open an address the model made up, and never opens `localhost`, `.local` names or private and link-local network addresses, including when a site redirects to one or its name resolves to one
  - Search my chats: finds messages in your saved chats by keywords and gives the model the best few, without sending anything out. It is not used in incognito chats, and once it has returned results the model cannot use the web, Wikipedia, weather or currency tools for the rest of that reply
  - Unit and currency conversion: length, weight, volume, temperature, speed, area, data size and time, worked out exactly on the phone; money uses European Central Bank rates from frankfurter.dev (only the currency codes are sent; rates are kept for 12 hours)
  - Weather: the current weather and a three day forecast from open-meteo.com (only the place name is sent; no key)
  - Timers and reminders: "in 10 minutes", "at 18:30", "tomorrow at 9:00", a weekday or a date, then what to be reminded of. They are notifications set with Android alarms, kept on the phone (not backed up or synced), survive a restart, and are listed in Settings where each can be deleted. At most 20 wait at once, up to a year ahead. Exact timing needs the "alarms and reminders" permission, which Settings links to; without it a reminder can arrive a few minutes late
  - Remember things for me: the model can suggest a note about you ("Prefers metric units"), which appears above the message box with Save and No thanks. Nothing is saved until you tap Save, so text from a web page cannot add to what the model remembers about you
- Compact memory cache: store the model's memory of the chat in 8 or 4 bits instead of 16 (about half or a quarter of the RAM), for a longer context or a bigger model. If the engine cannot do it, the normal cache is used and you are told
- Generation: temperature, top-p, top-k, min-p, repeat penalty, seed, context length, CPU threads (applied when a model loads or a new chat starts), and max reply length (applies to the next reply)
- Performance presets: Battery saver, Balanced, Fast and Long chats set threads, context length, memory cache size and reply length together; changing any of them by hand shows "Custom"
- Stop words: end a reply as soon as one appears (applies immediately)
- System prompt presets: four built in, plus your own
- App lock: require fingerprint, face or screen lock; also hides the app in recent apps and blocks screenshots
- Storage: see what PocketLM uses, clean up unused files, or delete all chats
- Battery and heat warnings appear in the chat when the phone is warm or low on power
- Slow down when hot or low on battery (on by default): while the phone is warm or the battery is low and not charging, the model uses fewer threads and replies are capped at 768 tokens (384 when very hot or the battery is nearly flat), then everything goes back to normal. The context length is not changed, since that needs the model to load again. API requests keep the length they asked for

## Known limits

- New and untested on a device: dictation with a speech model (it uses llama.cpp's support for Qwen3-ASR, so how long it takes after you stop speaking on a mid-range phone is unmeasured), asking about the screen (the phone only shares the screen if you allow it in its assistant settings, and some apps block it), replying from a notification, the extra text-selection entries, projects, characters, performance presets, checksum checks, Wi-Fi-only downloads and the online model list
- Projects, characters you make yourself and the performance presets stay on the phone; a chat that belongs to a project shows no project on a phone that does not have it. Checksums can only be checked for Hugging Face downloads

- Also new and untested on a device: the compact memory cache, the thinking switch (it starts the answer with an empty thinking section, for models whose chat template mentions thinking), incognito chats, sharing files and links in, search in a chat and starred messages, text size and the two-pane layout, suggested notes, and API tool calls
- Health and fitness is built and unit tested (the calorie and safety rules, the log, the food table, the quiz and flashcard logic) but has not been used on a device. The numbers are textbook estimates for adults and the model's meal estimates and plans can be wrong; none of it is medical advice. The food table covers about 65 foods, so many dishes need the model's estimate or your own numbers
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
- Skills and the scheduled page are covered by unit tests (the sentence reader, the suggested tasks, the tool loop, the skill rules and the skill tool), but the screens and the scheduled runs have not been run on a phone. A small model may not choose the right skill, or may not ask for a tool in the exact format, so a scheduled run that needs the web can come back without it; "Run now" shows what a task does. The sentence reader is rule-based, not a model: it knows times such as "at 7:30", "at 6pm", "every morning", days, weekdays, weekends and "weekly", and shows its reading for you to correct
- The six newer tools (page reading, chat search, conversion, weather, reminders, suggested notes) are covered by unit tests with the network and the phone replaced by stand-ins, and the weather and currency services were checked with `curl` against the real servers, but none of it has run inside the app on a phone. Reminders need notifications allowed (the app asks once) and, for exact timing, the alarms permission; the model is told when either is missing. Page reading depends on the page being plain HTML: a page built by scripts, behind a login, or a PDF gives little or nothing. Currency rates cover about 30 currencies (those the European Central Bank publishes). History search matches words, not meaning
- Web search with DuckDuckGo reads its plain results page, which DuckDuckGo can change or block (it was checked against a real page, but not inside the app); Brave Search and SearXNG were written against their documented formats rather than live servers, and a SearXNG server needs JSON results turned on (most public ones turn it off)
- Up to 4 images per message, each shrunk to 1024 px on the long side; earlier images are not re-shown to the model after a reply is regenerated or when a saved chat state is not used
- Document search matches keywords, not meaning, so a question worded differently from the document may miss
- Long document chats fill the context faster; raise the context length in Settings if needed
- The API server is plain HTTP unless you turn on HTTPS in Settings. With HTTPS the phone makes its own certificate, which apps that call it must be told to trust (copy it from Settings); it is renewed when the phone joins a new network. The server stops if Android closes the app, and the phone may sleep and drop requests
- GPU acceleration is new, off by default and has never run on a real GPU (see "GPU acceleration" above). There is no NPU support

## Project layout

| Path | What it is |
|---|---|
| `app/` | Jetpack Compose UI, ViewModel, model storage, chat history, document retrieval |
| `engine/` | JNI bridge and Kotlin API around llama.cpp, including the multimodal (vision) library; adapted from llama.cpp's `examples/llama.android` |
| `third_party/llama.cpp` | llama.cpp as a git submodule |
| `third_party/OpenCL-Headers`, `OpenCL-ICD-Loader`, `Vulkan-Headers`, `SPIRV-Headers` | Small Khronos header repositories (submodules) that the Android NDK lacks, needed to build the GPU backends |

## Building

Requirements: Android Studio (or JDK 17+), Android SDK 36, NDK 27, CMake 3.22.1, Python 3 (it embeds the OpenCL kernels) and the computer's own C/C++ compiler (it builds the Vulkan shader tool). The app needs Android 11 (API 30) or newer.

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

- New in the batch that added branching, notes saving, chat names, prompt fields, custom grammar, sources, automation, schedules, Wikipedia files, Ollama downloads, API transcription and the natural voice: all of it is written and unit tested, none of it has run on a device. In particular: Branch from here, Save to my notes (writing through Android's folder picker, and how storage apps name a new file), chat names (the in-place question and rollback is native code that compiles and is covered by the conversation rules, but has not run against a real model; a model that answers badly just leaves the title taken from the first message), the broadcast receiver and its answer (checked only by unit tests of the rules), background jobs (Android may delay, skip or shorten them; "expedited" jobs have a daily allowance), saving and loading the model's memory of a system prompt, and screen-reader behaviour (no TalkBack run)
- Automation answers are sent as a broadcast, which other apps can only receive if they ask for it; Tasker does. A sender who knows the secret can have PocketLM run prompts and spend battery, so keep it private and make a new one if it leaks. A background run uses the app's CPU settings but not a model's GPU, draft model or adapters, and a thinking model answers without thinking
- Custom grammar is checked for the mistakes people make (a missing `root`, a rule used but never written, unclosed quotes, brackets and repeat counts, unknown escapes); a grammar can still be refused by the engine for something subtler, such as a rule that calls itself before reading anything, and then replies are left unrestricted with a message. A grammar makes replies well-formed, not correct
- Wikipedia from a file finds articles by title only. The ZIM files carry a full-text index for Kiwix's own readers (Xapian) that PocketLM cannot read, so a question that does not contain the article's name may find nothing or the wrong article; the "top" file holds the most read articles, not all of them. The reader and its Zstandard decoder were tested against files made by the reference compressor and against the real `wikipedia_en_top_mini` file (about 700,000 entries, answers in 5 to 45 ms on a computer), but not on a phone. Opening a file from some cloud storage may fail because it cannot be read in pieces; copy it to the phone
- Ollama downloads were checked against the real registry (manifest, redirect to storage, resumable ranges, the GGUF header of the file) but not downloaded in full or loaded; a vision add-on from Ollama may be in a format llama.cpp does not read, and then images stay off with a message
- The natural voice is the least certain part. llama.cpp's own text-to-speech tool no longer supports OuteTTS; it now supports Qwen3-TTS (which has official GGUF files) and Pocket TTS (which has to be converted by hand and is not offered for download). The voice code follows llama.cpp's own tool and compiles for the phone; on an idle 10-core Mac (CPU only, 8 threads) it made 4.0 s of speech in 12.9 s, about 0.31 times real time, the same as llama.cpp's own tool, so a phone, which is several times slower, would take a minute or more per sentence. Whether a phone even has the memory for a 1.7 billion parameter speech model is not known. The phone's own voice stays the default and is used whenever the natural voice is off, missing or fails
- The speech-to-text of `/v1/audio/transcriptions` shares the dictation model, so both are unavailable while the other is working, and a recording that is not WAV depends on the phone having a decoder for it

## Roadmap

- Run on a device and fix what breaks (this is now the biggest item: very little of the app has been run on a phone)
- Tools that act on the phone itself (calendar, contacts, files)
- Check GPU acceleration on real phones, and offload only some layers
- NPU acceleration

## License

Apache-2.0. See [LICENSE](LICENSE). The `engine/` module is adapted from llama.cpp (MIT); see `third_party/llama.cpp/LICENSE`.

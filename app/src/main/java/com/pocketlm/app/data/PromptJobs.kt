package com.pocketlm.app.data

import com.arm.aichat.GenerationParams
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** How much of the notes folder a prompt that runs by itself may read. */
enum class NotesUse(val key: String) {
    /** Only the prompt. */
    None("none"),

    /** The notes that changed since the prompt last ran. */
    Recent("recent"),

    /** The passages that best match the prompt. */
    Search("search");

    companion object {
        fun of(key: String?): NotesUse = entries.firstOrNull { it.key == key } ?: None
    }
}

/** Where an app that asked a question wants the answer sent: an explicit broadcast to that app only. */
data class ReplyTarget(val packageName: String, val action: String, val requestId: String?)

/**
 * A prompt to run without anyone looking at the chat screen: one that another app sent (Tasker and the like), or one the
 * person scheduled. It waits in a [PromptQueue] until the app's background job gets to it.
 */
data class PromptJob(
    val id: String,
    val kind: Kind,
    val prompt: String,
    val system: String? = null,
    /** The model to use (file name, with or without .gguf), or null for the one used last. */
    val model: String? = null,
    val maxTokens: Int = Automation.DEFAULT_TOKENS,
    /** Names the notification and the saved chat. */
    val title: String = "",
    val target: ReplyTarget? = null,
    /** Show the answer in a notification. */
    val notify: Boolean = false,
    /** Keep the answer as a chat in History. */
    val saveChat: Boolean = false,
    val notes: NotesUse = NotesUse.None,
    /** The schedule this run belongs to, for scheduled prompts. */
    val scheduleId: String? = null,
    val createdAt: Long = 0,
    /** How many times a run was started, so a prompt that keeps getting interrupted is not retried for ever. */
    val attempts: Int = 0,
) {
    enum class Kind { Automation, Scheduled }
}

/** Saving a [PromptJob] as text, so it survives the app being closed before the background job gets to it. */
object PromptJobs {
    fun toJson(job: PromptJob): String = JSONObject()
        .put("id", job.id)
        .put("kind", job.kind.name)
        .put("prompt", job.prompt)
        .put("system", job.system ?: JSONObject.NULL)
        .put("model", job.model ?: JSONObject.NULL)
        .put("maxTokens", job.maxTokens)
        .put("title", job.title)
        .put("notify", job.notify)
        .put("saveChat", job.saveChat)
        .put("notes", job.notes.key)
        .put("scheduleId", job.scheduleId ?: JSONObject.NULL)
        .put("createdAt", job.createdAt)
        .put("attempts", job.attempts)
        .apply {
            job.target?.let {
                put("replyPackage", it.packageName)
                put("replyAction", it.action)
                put("requestId", it.requestId ?: JSONObject.NULL)
            }
        }
        .toString()

    fun fromJson(text: String): PromptJob? = runCatching {
        val o = JSONObject(text)
        PromptJob(
            id = o.getString("id"),
            kind = PromptJob.Kind.entries.first { it.name == o.getString("kind") },
            prompt = o.getString("prompt"),
            system = o.optString("system", "").ifEmpty { null },
            model = o.optString("model", "").ifEmpty { null },
            maxTokens = o.optInt("maxTokens", Automation.DEFAULT_TOKENS).coerceIn(1, Automation.MAX_TOKENS),
            title = o.optString("title", ""),
            target = o.optString("replyPackage", "").takeIf { it.isNotEmpty() }?.let {
                ReplyTarget(it, o.optString("replyAction", Automation.DEFAULT_REPLY_ACTION), o.optString("requestId", "").ifEmpty { null })
            },
            notify = o.optBoolean("notify", false),
            saveChat = o.optBoolean("saveChat", false),
            notes = NotesUse.of(o.optString("notes", "none")),
            scheduleId = o.optString("scheduleId", "").ifEmpty { null },
            createdAt = o.optLong("createdAt", 0),
            attempts = o.optInt("attempts", 0),
        )
    }.getOrNull()
}

/** The prompts waiting for the background job, one small file each, oldest first. */
class PromptQueue(private val dir: File) {

    @Synchronized
    fun add(job: PromptJob): Boolean {
        dir.mkdirs()
        if (files().size >= MAX_PENDING) return false
        val target = File(dir, name(job))
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(PromptJobs.toJson(job))
        return tmp.renameTo(target)
    }

    /** The oldest waiting prompt, or null. Files that cannot be read are removed. */
    @Synchronized
    fun peek(): PromptJob? {
        for (file in files()) {
            PromptJobs.fromJson(runCatching { file.readText() }.getOrDefault(""))?.let { return it }
            file.delete()
        }
        return null
    }

    /** Counts one more start for the prompt [id] and returns the new count (0 if it is no longer waiting). */
    @Synchronized
    fun countAttempt(id: String): Int {
        val file = files().firstOrNull { it.name.endsWith("-$id.json") } ?: return 0
        val job = PromptJobs.fromJson(runCatching { file.readText() }.getOrDefault("")) ?: return 0
        val updated = job.copy(attempts = job.attempts + 1)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(PromptJobs.toJson(updated))
        tmp.renameTo(file)
        return updated.attempts
    }

    @Synchronized
    fun remove(id: String) {
        files().filter { it.name.endsWith("-$id.json") }.forEach { it.delete() }
    }

    @Synchronized
    fun size(): Int = files().size

    /** True if a prompt of this schedule is already waiting, so a slow phone does not pile up copies. */
    @Synchronized
    fun hasSchedule(scheduleId: String): Boolean =
        files().any { f -> PromptJobs.fromJson(runCatching { f.readText() }.getOrDefault(""))?.scheduleId == scheduleId }

    private fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    private fun name(job: PromptJob) = "%015d-%s.json".format(job.createdAt, job.id)

    companion object {
        /** More than this and new prompts are refused, so a misbehaving app cannot fill the phone's storage or battery. */
        const val MAX_PENDING = 8

        /** A prompt interrupted this many times is given up on. */
        const val MAX_ATTEMPTS = 3
    }
}

/** What other apps send, and how it is checked. */
object Automation {
    const val ACTION_ASK = "com.pocketlm.app.action.ASK"
    const val DEFAULT_REPLY_ACTION = "com.pocketlm.app.action.REPLY"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_PROMPT = "prompt"
    const val EXTRA_SYSTEM = "system"
    const val EXTRA_MODEL = "model"
    const val EXTRA_MAX_TOKENS = "max_tokens"
    const val EXTRA_REPLY_PACKAGE = "reply_package"
    const val EXTRA_REPLY_ACTION = "reply_action"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_NOTIFY = "notify"
    const val EXTRA_SAVE_CHAT = "save_chat"

    /** Extras of the broadcast that carries the answer back. */
    const val EXTRA_REPLY = "reply"
    const val EXTRA_ERROR = "error"

    const val MAX_PROMPT_CHARS = 20_000
    const val MAX_SYSTEM_CHARS = 4_000
    const val DEFAULT_TOKENS = 512
    const val MAX_TOKENS = 4_096

    private val packageName = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    /** A new secret for other apps to present, such as `pk-` followed by 32 hexadecimal digits. */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return "pa-" + bytes.joinToString("") { "%02x".format(it) }
    }

    /** True if [given] is the secret. The comparison takes the same time however many characters match. */
    fun tokenMatches(expected: String?, given: String?): Boolean {
        if (expected.isNullOrEmpty() || given == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }

    /** Reads the extras of an intent; kept apart from Android's Bundle so the rules can be tested. */
    interface Extras {
        fun string(name: String): String?
        fun int(name: String): Int?
        fun bool(name: String): Boolean?
    }

    sealed interface Parsed {
        /** Run this. */
        data class Accepted(val job: PromptJob) : Parsed

        /** Not run, and nobody is told (automation is off, or the secret is wrong). */
        data class Ignored(val why: String) : Parsed

        /** Not run, and the sender is told why through [target], when it asked for an answer. */
        data class Refused(val why: String, val target: ReplyTarget?) : Parsed
    }

    fun parse(extras: Extras, enabled: Boolean, token: String?, now: Long, newId: () -> String): Parsed {
        if (!enabled) return Parsed.Ignored("Automation is switched off in PocketLM's settings.")
        if (!tokenMatches(token, extras.string(EXTRA_TOKEN))) return Parsed.Ignored("The token did not match.")

        val replyPackage = extras.string(EXTRA_REPLY_PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }
        val replyAction = extras.string(EXTRA_REPLY_ACTION)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REPLY_ACTION
        val requestId = extras.string(EXTRA_REQUEST_ID)?.take(200)
        val target = if (replyPackage != null && packageName.matches(replyPackage) && replyAction.length <= 200 && replyAction.none { it.isWhitespace() }) {
            ReplyTarget(replyPackage, replyAction, requestId)
        } else {
            null
        }

        val prompt = extras.string(EXTRA_PROMPT)?.trim().orEmpty()
        if (prompt.isEmpty()) return Parsed.Refused("There is no prompt.", target)
        if (prompt.length > MAX_PROMPT_CHARS) return Parsed.Refused("The prompt is longer than $MAX_PROMPT_CHARS characters.", target)
        val system = extras.string(EXTRA_SYSTEM)?.trim()?.takeIf { it.isNotEmpty() }
        if (system != null && system.length > MAX_SYSTEM_CHARS) {
            return Parsed.Refused("The system prompt is longer than $MAX_SYSTEM_CHARS characters.", target)
        }
        return Parsed.Accepted(
            PromptJob(
                id = newId(),
                kind = PromptJob.Kind.Automation,
                prompt = prompt,
                system = system,
                model = extras.string(EXTRA_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
                maxTokens = (extras.int(EXTRA_MAX_TOKENS) ?: DEFAULT_TOKENS).coerceIn(1, MAX_TOKENS),
                title = prompt.lineSequence().first().take(48),
                target = target,
                notify = extras.bool(EXTRA_NOTIFY) == true,
                saveChat = extras.bool(EXTRA_SAVE_CHAT) == true,
                createdAt = now,
            ),
        )
    }
}

/** Which downloaded model a prompt without a screen should use. */
object HeadlessModels {
    class Choice(val fileName: String, val sizeBytes: Long)

    sealed interface Pick {
        data class Found(val fileName: String) : Pick

        /** A model was asked for by name and is not downloaded. */
        data class Missing(val requested: String) : Pick

        data object NoModels : Pick
    }

    private fun bare(name: String) = name.removeSuffix(".gguf").lowercase()

    /**
     * The model asked for if there is one; otherwise [preferred], then the one used last ([last]), then the smallest chat
     * model. Models for dictation and for searching are never chosen, since they cannot chat.
     */
    fun pick(requested: String?, preferred: String?, last: String?, models: List<Choice>): Pick {
        val chat = models.filterNot { SpeechModels.isSpeechFile(it.fileName) || EmbeddingModels.isEmbedding(null, it.fileName) }
        if (!requested.isNullOrBlank()) {
            val match = chat.firstOrNull { bare(it.fileName) == bare(requested) }
            return if (match != null) Pick.Found(match.fileName) else Pick.Missing(requested)
        }
        for (name in listOf(preferred, last)) {
            if (name.isNullOrBlank()) continue
            chat.firstOrNull { it.fileName == name }?.let { return Pick.Found(it.fileName) }
        }
        return chat.minByOrNull { it.sizeBytes }?.let { Pick.Found(it.fileName) } ?: Pick.NoModels
    }
}

/** Reading the generation settings the way the chat screen does, for a prompt that runs without it. */
object HeadlessParams {
    /** Just enough of the app's saved settings to read. */
    interface Source {
        fun int(key: String, default: Int): Int
        fun float(key: String, default: Float): Float
    }

    private const val CONTEXT = "gen_context_size"
    private const val THREADS = "gen_threads"
    private const val CACHE_BITS = "cache_bits"

    /** A prompt that nobody is waiting for does not need a long memory; this keeps it quick and light. */
    const val MAX_CONTEXT = 4_096

    fun read(source: Source, own: ModelSettings?): GenerationParams {
        val defaults = GenerationParams()
        val app = GenerationParams(
            contextSize = source.int(CONTEXT, defaults.contextSize),
            threads = source.int(THREADS, defaults.threads),
            temperature = source.float(SettingKeys.TEMP, defaults.temperature),
            topP = source.float(SettingKeys.TOP_P, defaults.topP),
            topK = source.int(SettingKeys.TOP_K, defaults.topK),
            minP = source.float(SettingKeys.MIN_P, defaults.minP),
            repeatPenalty = source.float(SettingKeys.REPEAT, defaults.repeatPenalty),
            seed = source.int(SettingKeys.SEED, defaults.seed),
            cacheBits = source.int(CACHE_BITS, defaults.cacheBits),
        )
        // The model's own settings win, as in the chat. Its GPU, adapters and draft model are left out: CPU only, nothing extra.
        val chosen = own?.params ?: app
        return chosen.copy(contextSize = chosen.contextSize.coerceAtMost(MAX_CONTEXT), chatTemplate = own?.chatTemplate.orEmpty())
    }
}

package com.pocketlm.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PromptJobsTest {

    private class MapExtras(private val values: Map<String, Any?>) : Automation.Extras {
        override fun string(name: String) = values[name] as? String
        override fun int(name: String) = (values[name] as? Int) ?: (values[name] as? String)?.toIntOrNull()
        override fun bool(name: String) = values[name] as? Boolean
    }

    private fun parse(vararg pairs: Pair<String, Any?>, enabled: Boolean = true, token: String? = "pa-secret") =
        Automation.parse(MapExtras(mapOf(*pairs)), enabled, token, now = 1_000, newId = { "job1" })

    private val good = arrayOf<Pair<String, Any?>>("token" to "pa-secret", "prompt" to "What is 2+2?")

    @Test
    fun onlyTheRightSecretIsAccepted() {
        assertTrue(Automation.tokenMatches("pa-secret", "pa-secret"))
        assertFalse(Automation.tokenMatches("pa-secret", "pa-secreT"))
        assertFalse(Automation.tokenMatches("pa-secret", "pa-secret "))
        assertFalse(Automation.tokenMatches("pa-secret", null))
        assertFalse(Automation.tokenMatches(null, null))
        assertFalse(Automation.tokenMatches("", ""))
    }

    @Test
    fun aNewSecretLooksRightAndIsDifferentEachTime() {
        val a = Automation.newToken()
        val b = Automation.newToken()
        assertTrue(a, Regex("pa-[0-9a-f]{32}").matches(a))
        assertNotEquals(a, b)
    }

    @Test
    fun aRequestIsIgnoredWhenAutomationIsOffOrTheSecretIsWrong() {
        assertTrue(parse(*good, enabled = false) is Automation.Parsed.Ignored)
        assertTrue(parse("token" to "nope", "prompt" to "hi") is Automation.Parsed.Ignored)
        assertTrue(parse("prompt" to "hi") is Automation.Parsed.Ignored)
        assertTrue(parse(*good, token = null) is Automation.Parsed.Ignored)
    }

    @Test
    fun aGoodRequestBecomesAJobWithSensibleDefaults() {
        val job = (parse(*good) as Automation.Parsed.Accepted).job
        assertEquals("job1", job.id)
        assertEquals(PromptJob.Kind.Automation, job.kind)
        assertEquals("What is 2+2?", job.prompt)
        assertEquals(Automation.DEFAULT_TOKENS, job.maxTokens)
        assertNull(job.target)
        assertNull(job.model)
        assertFalse(job.notify)
        assertFalse(job.saveChat)
        assertEquals(1_000, job.createdAt)
        assertEquals("What is 2+2?", job.title)
    }

    @Test
    fun optionsAreReadIncludingNumbersSentAsText() {
        val job = (parse(
            *good, "system" to " Be brief. ", "model" to "qwen", "max_tokens" to "99999", "notify" to true, "save_chat" to true,
        ) as Automation.Parsed.Accepted).job
        assertEquals("Be brief.", job.system)
        assertEquals("qwen", job.model)
        assertEquals(Automation.MAX_TOKENS, job.maxTokens)
        assertTrue(job.notify)
        assertTrue(job.saveChat)
        assertEquals(1, (parse(*good, "max_tokens" to 0) as Automation.Parsed.Accepted).job.maxTokens)
    }

    @Test
    fun theAnswerGoesOnlyToAnAppThatNamedItselfProperly() {
        val job = (parse(*good, "reply_package" to "net.dinglisch.android.taskerm", "request_id" to "r7") as Automation.Parsed.Accepted).job
        assertEquals(ReplyTarget("net.dinglisch.android.taskerm", Automation.DEFAULT_REPLY_ACTION, "r7"), job.target)
        val custom = (parse(*good, "reply_package" to "com.example.app", "reply_action" to "com.example.GOT_IT") as Automation.Parsed.Accepted).job
        assertEquals("com.example.GOT_IT", custom.target?.action)
        assertNull((parse(*good, "reply_package" to "not a package") as Automation.Parsed.Accepted).job.target)
        assertNull((parse(*good, "reply_package" to "justone") as Automation.Parsed.Accepted).job.target)
        assertNull((parse(*good, "reply_package" to "com.example.app", "reply_action" to "has space") as Automation.Parsed.Accepted).job.target)
    }

    @Test
    fun badRequestsAreRefusedAndTheSenderIsToldWhenItAskedForAnAnswer() {
        val empty = parse("token" to "pa-secret", "prompt" to "  ", "reply_package" to "com.example.app") as Automation.Parsed.Refused
        assertEquals("com.example.app", empty.target?.packageName)
        val long = parse("token" to "pa-secret", "prompt" to "x".repeat(Automation.MAX_PROMPT_CHARS + 1)) as Automation.Parsed.Refused
        assertNull(long.target)
        val system = parse(*good, "system" to "x".repeat(Automation.MAX_SYSTEM_CHARS + 1)) as Automation.Parsed.Refused
        assertTrue(system.why.contains("system"))
    }

    @Test
    fun aJobSurvivesBeingSavedAsText() {
        val job = PromptJob(
            "id1", PromptJob.Kind.Scheduled, "Summarise\nmy notes", system = "Be brief", model = "qwen.gguf", maxTokens = 700,
            title = "Morning", target = ReplyTarget("com.example.app", "A", "r1"), notify = true, saveChat = true,
            notes = NotesUse.Recent, scheduleId = "s1", createdAt = 55, attempts = 2,
        )
        assertEquals(job, PromptJobs.fromJson(PromptJobs.toJson(job)))
        val plain = PromptJob("id2", PromptJob.Kind.Automation, "hi", createdAt = 1)
        assertEquals(plain, PromptJobs.fromJson(PromptJobs.toJson(plain)))
        assertNull(PromptJobs.fromJson("not json"))
        assertNull(PromptJobs.fromJson("""{"id":"x"}"""))
    }

    // ---- the queue ---------------------------------------------------------------------

    private val dirs = ArrayList<File>()

    private fun queue(): Pair<PromptQueue, File> {
        val dir = Files.createTempDirectory("queue").toFile().also(dirs::add)
        return PromptQueue(dir) to dir
    }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun job(id: String, at: Long, schedule: String? = null) =
        PromptJob(id, PromptJob.Kind.Automation, "p$id", createdAt = at, scheduleId = schedule)

    @Test
    fun theOldestPromptComesFirst() {
        val (q, _) = queue()
        assertNull(q.peek())
        assertTrue(q.add(job("b", 20)))
        assertTrue(q.add(job("a", 10)))
        assertEquals("a", q.peek()?.id)
        q.remove("a")
        assertEquals("b", q.peek()?.id)
        q.remove("b")
        assertNull(q.peek())
        assertEquals(0, q.size())
    }

    @Test
    fun theQueueRefusesToGrowWithoutLimit() {
        val (q, _) = queue()
        repeat(PromptQueue.MAX_PENDING) { assertTrue(q.add(job("j$it", it.toLong()))) }
        assertFalse(q.add(job("extra", 99)))
        assertEquals(PromptQueue.MAX_PENDING, q.size())
    }

    @Test
    fun waitingPromptsAreRememberedByAnotherQueueObject() {
        val (q, dir) = queue()
        q.add(job("a", 1, schedule = "s1"))
        val again = PromptQueue(dir)
        assertEquals("a", again.peek()?.id)
        assertTrue(again.hasSchedule("s1"))
        assertFalse(again.hasSchedule("s2"))
    }

    @Test
    fun anUnreadableFileIsDroppedInsteadOfBlockingTheQueue() {
        val (q, dir) = queue()
        File(dir, "000000000000001-broken.json").writeText("{{{")
        q.add(job("good", 2))
        assertEquals("good", q.peek()?.id)
        assertEquals(1, q.size())
    }

    @Test
    fun startsAreCounted() {
        val (q, dir) = queue()
        q.add(job("a", 1))
        assertEquals(1, q.countAttempt("a"))
        assertEquals(2, q.countAttempt("a"))
        assertEquals(2, PromptQueue(dir).peek()?.attempts)
        assertEquals(0, q.countAttempt("missing"))
    }

    // ---- choosing a model ---------------------------------------------------------------

    private fun models(vararg pairs: Pair<String, Long>) = pairs.map { HeadlessModels.Choice(it.first, it.second) }

    private val library = models(
        "Qwen2.5-3B-Instruct-Q4_K_M.gguf" to 2_000L,
        "SmolLM2-360M-Instruct-Q8_0.gguf" to 400L,
        "Qwen3-ASR-0.6B-Q8_0.gguf" to 100L,
        "bge-small-en-v1.5-q8_0.gguf" to 50L,
    )

    @Test
    fun aModelAskedForByNameIsFoundWhateverTheCapitalsOrSuffix() {
        assertEquals(HeadlessModels.Pick.Found("Qwen2.5-3B-Instruct-Q4_K_M.gguf"), HeadlessModels.pick("qwen2.5-3b-instruct-q4_k_m", null, null, library))
        assertEquals(HeadlessModels.Pick.Found("SmolLM2-360M-Instruct-Q8_0.gguf"), HeadlessModels.pick("SmolLM2-360M-Instruct-Q8_0.gguf", null, "Qwen2.5-3B-Instruct-Q4_K_M.gguf", library))
    }

    @Test
    fun aModelAskedForButNotDownloadedIsReportedNotReplaced() {
        assertEquals(HeadlessModels.Pick.Missing("llama"), HeadlessModels.pick("llama", null, "SmolLM2-360M-Instruct-Q8_0.gguf", library))
        assertEquals(HeadlessModels.Pick.Missing("bge-small-en-v1.5-q8_0"), HeadlessModels.pick("bge-small-en-v1.5-q8_0", null, null, library))
    }

    @Test
    fun withoutARequestTheModelUsedLastIsPreferredThenTheSmallestChatModel() {
        assertEquals(HeadlessModels.Pick.Found("Qwen2.5-3B-Instruct-Q4_K_M.gguf"), HeadlessModels.pick(null, null, "Qwen2.5-3B-Instruct-Q4_K_M.gguf", library))
        assertEquals(HeadlessModels.Pick.Found("SmolLM2-360M-Instruct-Q8_0.gguf"), HeadlessModels.pick(null, null, "deleted.gguf", library))
        assertEquals(HeadlessModels.Pick.Found("SmolLM2-360M-Instruct-Q8_0.gguf"), HeadlessModels.pick(" ", null, null, library))
        assertEquals(HeadlessModels.Pick.NoModels, HeadlessModels.pick(null, null, null, models("Qwen3-ASR-0.6B-Q8_0.gguf" to 1L)))
        assertEquals(HeadlessModels.Pick.NoModels, HeadlessModels.pick(null, null, null, emptyList()))
    }

    // ---- settings -----------------------------------------------------------------------

    private class MapSource(private val ints: Map<String, Int> = emptyMap(), private val floats: Map<String, Float> = emptyMap()) : HeadlessParams.Source {
        override fun int(key: String, default: Int) = ints[key] ?: default
        override fun float(key: String, default: Float) = floats[key] ?: default
    }

    @Test
    fun theAppsSettingsAreReadWithAShorterMemory() {
        val defaults = com.arm.aichat.GenerationParams()
        val none = HeadlessParams.read(MapSource(), null)
        assertEquals(minOf(defaults.contextSize, HeadlessParams.MAX_CONTEXT), none.contextSize)
        assertEquals(defaults.temperature, none.temperature, 0f)

        val set = HeadlessParams.read(
            MapSource(ints = mapOf("gen_context_size" to 16_384, "gen_threads" to 3, "gen_top_k" to 10), floats = mapOf("gen_temperature" to 0.1f)),
            null,
        )
        assertEquals(HeadlessParams.MAX_CONTEXT, set.contextSize)
        assertEquals(3, set.threads)
        assertEquals(10, set.topK)
        assertEquals(0.1f, set.temperature, 0f)
        assertEquals(1_024, HeadlessParams.read(MapSource(ints = mapOf("gen_context_size" to 1_024)), null).contextSize)
    }

    @Test
    fun aModelsOwnSettingsWinButItsGpuAndExtrasAreLeftOut() {
        val own = ModelSettings(
            params = com.arm.aichat.GenerationParams(contextSize = 2_048, temperature = 0.9f),
            chatTemplate = "chatml", draftFile = "draft.gguf", gpu = "vulkan",
            adapters = listOf(AdapterSetting("a.gguf", 1f)),
        )
        val read = HeadlessParams.read(MapSource(floats = mapOf("gen_temperature" to 0.1f)), own)
        assertEquals(2_048, read.contextSize)
        assertEquals(0.9f, read.temperature, 0f)
        assertEquals("chatml", read.chatTemplate)
        assertEquals("", read.gpuBackend)
        assertEquals("", read.draftModelPath)
        assertTrue(read.adapters.isEmpty())
    }
}

package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHubTest {

    @Test
    fun huggingFaceSearchReadsIdsAndCounts() {
        val json = """[{"id":"Qwen/Qwen2.5-0.5B-Instruct-GGUF","downloads":279396,"likes":152},{"modelId":"a/b","downloads":1}]"""
        val models = HfHub.parseSearch(json)
        assertEquals(listOf("Qwen/Qwen2.5-0.5B-Instruct-GGUF", "a/b"), models.map { it.id })
        assertEquals(152L, models[0].likes)
    }

    @Test
    fun huggingFaceFilesKeepOnlyUsableGgufSmallestFirstWithAddOnsLast() {
        val json = """[
            {"type":"file","path":".gitattributes","size":10},
            {"type":"directory","path":"sub"},
            {"type":"file","path":"m-q8_0.gguf","size":800,"lfs":{"size":800}},
            {"type":"file","path":"m-q4_k_m.gguf","size":400,"lfs":{"size":400}},
            {"type":"file","path":"mmproj-m.gguf","size":50,"lfs":{"size":50}},
            {"type":"file","path":"big-00001-of-00003.gguf","size":9000}
        ]"""
        val files = HfHub.parseFiles(json)
        assertEquals(listOf("m-q4_k_m.gguf", "m-q8_0.gguf", "mmproj-m.gguf"), files.map { it.path })
        assertTrue(files.last().isProjector)
        assertEquals("Q4_K_M", files.first().quant)
    }

    @Test
    fun modelScopeSearchBuildsOwnerSlashName() {
        val json = """{"Code":200,"Data":{"Model":{"Models":[
            {"Path":"unsloth","Name":"Llama-GGUF","Downloads":30875,"Stars":27},
            {"Path":"","Name":"broken","Downloads":1,"Stars":0}]}}}"""
        val models = ModelScopeHub.parseSearch(json)
        assertEquals(listOf("unsloth/Llama-GGUF"), models.map { it.id })
        assertEquals(30875L, models[0].downloads)
        assertEquals(27L, models[0].likes)
    }

    @Test
    fun modelScopeSearchWithoutResultsIsEmpty() {
        assertTrue(ModelScopeHub.parseSearch("""{"Code":200,"Data":{}}""").isEmpty())
    }

    @Test
    fun modelScopeFilesUseBlobEntries() {
        val json = """{"Code":200,"Data":{"Files":[
            {"Type":"tree","Path":"docs"},
            {"Type":"blob","Path":"x-q4_0.gguf","Size":428730208},
            {"Type":"blob","Path":"README.md","Size":10}]}}"""
        assertEquals(listOf("x-q4_0.gguf" to 428730208L), ModelScopeHub.parseFiles(json).map { it.path to it.sizeBytes })
    }

    @Test
    fun downloadUrlsFollowEachHubsLayout() {
        assertEquals("https://huggingface.co/a/b/resolve/main/f.gguf", HfHub("https://huggingface.co").downloadUrl("a/b", "f.gguf"))
        assertEquals("https://www.modelscope.cn/models/a/b/resolve/master/f.gguf", ModelScopeHub().downloadUrl("a/b", "f.gguf"))
        assertEquals("https://hf-mirror.com/a/b/resolve/main/f.gguf", HfHub("https://hf-mirror.com").downloadUrl("a/b", "f.gguf"))
    }

    @Test
    fun mirrorAddressesAreTidied() {
        assertEquals("https://hf-mirror.com", ModelHub.normaliseAddress(" hf-mirror.com/ "))
        assertEquals("http://192.168.1.5:8000", ModelHub.normaliseAddress("http://192.168.1.5:8000/"))
        assertNull(ModelHub.normaliseAddress("   "))
        assertNull(ModelHub.normaliseAddress("https://"))
    }

    // ---- Kaggle ----

    private val kaggleSearch = """{"models":[
        {"ref":"mistral-ai/magistral-small-2506-gguf","title":"Magistral Small 2506 gguf","voteCountNullable":3,
         "instances":[{"slug":"magistral-small-2506_gguf","framework":"gguf","versionNumber":1}]},
        {"ref":"dangkhoa2016/qwen3-vl-4b","title":"Qwen3-VL-4B","voteCountNullable":0,
         "instances":[{"slug":"q4-k-m","framework":"gguf","versionNumber":2},
                      {"slug":"f16","framework":"gguf","versionNumber":1},
                      {"slug":"hf","framework":"transformers","versionNumber":1}]},
        {"ref":"someone/not-gguf","title":"Other","voteCountNullable":9,
         "instances":[{"slug":"default","framework":"pyTorch","versionNumber":1}]}
    ],"nextPageToken":"x"}"""

    @Test
    fun kaggleSearchListsOneResultPerGgufVariation() {
        val results = KaggleHub.parseSearch(kaggleSearch)
        assertEquals(
            listOf(
                "mistral-ai/magistral-small-2506-gguf/gguf/magistral-small-2506_gguf/1",
                "dangkhoa2016/qwen3-vl-4b/gguf/q4-k-m/2",
                "dangkhoa2016/qwen3-vl-4b/gguf/f16/1",
            ),
            results.map { it.id },
        )
        assertEquals("Qwen3-VL-4B · q4-k-m", results[1].title)
        assertEquals(3L, results[0].likes)
        assertEquals(-1L, results[0].downloads)
    }

    @Test
    fun kaggleFilesKeepOnlyGgufAndDropDocs() {
        val json = """{"files":[
            {"name":"Magistral-Small-2506.gguf","size":47153519680},
            {"name":"Magistral-Small-2506_Q8_0.gguf","size":25054780480},
            {"name":"README.md","size":5816},{"name":"SYSTEM_PROMPT.txt","size":819}],"nextPageToken":""}"""
        assertEquals(
            listOf("Magistral-Small-2506_Q8_0.gguf", "Magistral-Small-2506.gguf"),
            KaggleHub.parseFiles(json).map { it.path },
        )
    }

    @Test
    fun kaggleDownloadUrlsPointAtOneFileAndEscapeNames() {
        val id = "owner/model/gguf/q4-k-m/2"
        assertEquals(
            "https://www.kaggle.com/api/v1/models/owner/model/gguf/q4-k-m/2/download/my%20model.gguf",
            KaggleHub().downloadUrl(id, "my model.gguf"),
        )
    }

    @Test
    fun kaggleIdsMustHaveFiveParts() {
        assertEquals("q4-k-m", KaggleHub.parseId("owner/model/gguf/q4-k-m/2").variation)
        try {
            KaggleHub.parseId("owner/model")
            org.junit.Assert.fail("expected an error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Unexpected Kaggle model id"))
        }
    }

    @Test
    fun kaggleOffersANextPageUntilTheLastOne() {
        assertEquals("abc123", KaggleHub.parseNextToken("""{"models":[],"nextPageToken":"abc123","totalResults":58}"""))
        assertNull(KaggleHub.parseNextToken("""{"models":[],"nextPageToken":"","totalResults":3}"""))
        assertNull(KaggleHub.parseNextToken("""{"models":[]}"""))
        assertNull(KaggleHub.parseNextToken("not json"))
    }

    @Test
    fun hubsWithoutPagesReturnEverythingAsOnePage() = kotlinx.coroutines.runBlocking {
        val hub = object : ModelHub {
            override suspend fun search(query: String) = listOf(HfModel("a/b", 1, 2, "a/b"))
            override suspend fun files(repoId: String) = emptyList<HfFile>()
            override fun downloadUrl(repoId: String, path: String) = ""
        }
        val page = hub.searchPage("anything", cursor = "ignored")
        assertEquals(1, page.results.size)
        assertNull(page.next)
    }
}

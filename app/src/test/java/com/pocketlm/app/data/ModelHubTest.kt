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
}

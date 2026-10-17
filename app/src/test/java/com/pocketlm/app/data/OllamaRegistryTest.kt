package com.pocketlm.app.data

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

class OllamaRegistryTest {

    // The manifest the registry really sent for smollm2:135m, trimmed of the "from" paths.
    private val real = """
        {"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json",
         "config":{"mediaType":"application/vnd.docker.container.image.v1+json","digest":"sha256:b0f58c4c1a3ca56f34a7673b353d7d09b773a1abce00451a58d5ebd2541331cf","size":561},
         "layers":[
          {"mediaType":"application/vnd.ollama.image.model","digest":"sha256:f535f83ec568d040f88ddc04a199fa6da90923bbb41d4dcaed02caa924d6ef57","size":270885952},
          {"mediaType":"application/vnd.ollama.image.system","digest":"sha256:fbacade46b4da804e0398c339c64b944d4b954452adf77cf050b49420116129e","size":68},
          {"mediaType":"application/vnd.ollama.image.template","digest":"sha256:d502d55c1d609104ae6127aee92eb940e51e15c56dfb26dbd067e2771ee746f1","size":675},
          {"mediaType":"application/vnd.ollama.image.license","digest":"sha256:58d1e17ffe5109a7ae296caafcadfdbe6a7d176f0bc4ab01e12a689b0499d8bd","size":11357},
          {"mediaType":"application/vnd.ollama.image.params","digest":"sha256:f02dd72bb2423204352eabc5637b44d79d17f109fdb510a7c51455892aa2d216","size":59}]}
    """.trimIndent()

    private val modelDigest = "sha256:f535f83ec568d040f88ddc04a199fa6da90923bbb41d4dcaed02caa924d6ef57"
    private val projectorDigest = "sha256:" + "ab".repeat(32)

    @Test
    fun namesAreReadInTheFormsPeopleWriteThem() {
        assertEquals(OllamaRegistry.Ref("library", "llama3.2", "1b"), OllamaRegistry.parse("llama3.2:1b"))
        assertEquals(OllamaRegistry.Ref("library", "llama3.2", "latest"), OllamaRegistry.parse("  Llama3.2 "))
        assertEquals(OllamaRegistry.Ref("library", "llama3.2", "1b"), OllamaRegistry.parse("library/llama3.2:1b"))
        assertEquals(OllamaRegistry.Ref("someone", "my-model", "Q4_K_M"), OllamaRegistry.parse("someone/my-model:Q4_K_M"))
        assertEquals(OllamaRegistry.Ref("library", "qwen2.5", "3b"), OllamaRegistry.parse("ollama run qwen2.5:3b"))
        assertEquals(OllamaRegistry.Ref("library", "gemma3", "4b"), OllamaRegistry.parse("https://ollama.com/library/gemma3:4b"))
        assertEquals(OllamaRegistry.Ref("library", "x", "y"), OllamaRegistry.parse("registry.ollama.ai/library/x:y"))
    }

    @Test
    fun thingsThatAreNotNamesAreRefused() {
        for (bad in listOf("", "   ", "a b", "a/b/c", "../etc/passwd", "name:", ":tag", "na me:1b", "-bad", "x:y z", "a".repeat(80), "x:" + "t".repeat(80), "has\nnewline", "UPPER/../x")) {
            assertNull("'$bad'", OllamaRegistry.parse(bad))
        }
    }

    @Test
    fun addressesAndFileNamesAreBuiltFromTheName() {
        val ref = OllamaRegistry.Ref("library", "llama3.2", "1b")
        val layer = OllamaRegistry.Layer(modelDigest, 10)
        assertEquals("https://registry.ollama.ai/v2/library/llama3.2/manifests/1b", OllamaRegistry.manifestUrl(ref))
        assertEquals("https://registry.ollama.ai/v2/library/llama3.2/blobs/$modelDigest", OllamaRegistry.blobUrl(ref, layer))
        assertEquals("llama3.2:1b", ref.display)
        assertEquals("someone/m:t", OllamaRegistry.Ref("someone", "m", "t").display)
        assertEquals("llama3.2-1b.gguf", OllamaRegistry.fileName(ref))
        assertEquals("someone-m-t.gguf", OllamaRegistry.fileName(OllamaRegistry.Ref("someone", "m", "t")))
        assertEquals("mmproj-llama3.2-1b.gguf", OllamaRegistry.projectorFileName("llama3.2-1b.gguf"))
        assertEquals(modelDigest.removePrefix("sha256:"), OllamaRegistry.Layer(modelDigest, 1).sha256)
    }

    @Test
    fun aBlobLinkNamesTheChecksumItMustHave() {
        val ref = OllamaRegistry.Ref("library", "x", "y")
        assertEquals(modelDigest.removePrefix("sha256:"), OllamaRegistry.expectedSha256(OllamaRegistry.blobUrl(ref, OllamaRegistry.Layer(modelDigest, 1))))
        assertEquals(modelDigest.removePrefix("sha256:"), Verification.expectedFor(OllamaRegistry.blobUrl(ref, OllamaRegistry.Layer(modelDigest, 1))))
        assertNull(OllamaRegistry.expectedSha256("https://huggingface.co/a/b/resolve/main/m.gguf"))
        assertNull(OllamaRegistry.expectedSha256("https://evil.example/v2/library/x/blobs/$modelDigest"))
        assertNull(OllamaRegistry.expectedSha256("https://registry.ollama.ai/v2/library/x/blobs/sha256:short"))
    }

    @Test
    fun theRealManifestGivesTheModelFileOnly() {
        val manifest = OllamaRegistry.parseManifest(real)
        assertEquals(OllamaRegistry.Layer(modelDigest, 270_885_952), manifest.model)
        assertNull(manifest.projector)
    }

    @Test
    fun aVisionAddOnIsFoundToo() {
        val withProjector = real.replace(
            "\"layers\":[",
            "\"layers\":[{\"mediaType\":\"application/vnd.ollama.image.projector\",\"digest\":\"$projectorDigest\",\"size\":600000000},",
        )
        val manifest = OllamaRegistry.parseManifest(withProjector)
        assertEquals(OllamaRegistry.Layer(projectorDigest, 600_000_000), manifest.projector)
        assertEquals(270_885_952L, manifest.model.size)
    }

    private fun refused(json: String): String = try {
        OllamaRegistry.parseManifest(json)
        fail("expected a refusal")
        ""
    } catch (e: OllamaException) {
        e.message.orEmpty()
    }

    @Test
    fun manifestsThatCannotBeTrustedAreRefused() {
        assertTrue(refused("not json").contains("could not be read"))
        assertTrue(refused("{}").contains("no files"))
        assertTrue(refused("""{"layers":[{"mediaType":"application/vnd.ollama.image.template","digest":"$modelDigest","size":5}]}""").contains("no model file"))
        assertTrue(refused("""{"layers":[{"mediaType":"application/vnd.ollama.image.model","digest":"sha256:abc","size":5}]}""").contains("cannot be trusted"))
        assertTrue(refused("""{"layers":[{"mediaType":"application/vnd.ollama.image.model","digest":"$modelDigest","size":0}]}""").contains("cannot be trusted"))
        assertTrue(refused("""{"layers":[{"mediaType":"application/vnd.ollama.image.model","digest":"${modelDigest.uppercase()}","size":5}]}""").contains("cannot be trusted"))
        assertTrue(refused("x".repeat(300_000)).contains("large"))
    }

    // ---- over the network -----------------------------------------------------------------

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun serve(status: Int, body: String, seen: MutableList<Pair<String, String?>> = ArrayList()): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            seen += exchange.requestURI.path to exchange.requestHeaders.getFirst("Accept")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun theManifestIsFetchedFromTheRightPlaceAsDockerJson() {
        val seen = ArrayList<Pair<String, String?>>()
        val base = serve(200, real, seen)
        val manifest = OllamaRegistry.fetchManifest(OllamaRegistry.Ref("library", "smollm2", "135m"), base)
        assertEquals(270_885_952L, manifest.model.size)
        assertEquals("/v2/library/smollm2/manifests/135m", seen.single().first)
        assertEquals("application/vnd.docker.distribution.manifest.v2+json", seen.single().second)
    }

    @Test
    fun aMissingModelIsExplained() {
        val base = serve(404, "{}")
        try {
            OllamaRegistry.fetchManifest(OllamaRegistry.Ref("library", "nope", "1b"), base)
            fail("expected an error")
        } catch (e: OllamaException) {
            assertTrue(e.message, e.message!!.contains("nope:1b is not in the Ollama library"))
        }
    }

    @Test
    fun aRegistryErrorAndAnUnreachableRegistryAreExplained() {
        val base = serve(500, "oops")
        try {
            OllamaRegistry.fetchManifest(OllamaRegistry.Ref("library", "x", "y"), base)
            fail("expected an error")
        } catch (e: OllamaException) {
            assertTrue(e.message, e.message!!.contains("500"))
        }
        server?.stop(0)
        try {
            OllamaRegistry.fetchManifest(OllamaRegistry.Ref("library", "x", "y"), base)
            fail("expected an error")
        } catch (e: OllamaException) {
            assertTrue(e.message, e.message!!.contains("Could not reach"))
        }
    }
}

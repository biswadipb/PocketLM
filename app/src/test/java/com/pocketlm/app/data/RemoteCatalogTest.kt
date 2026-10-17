package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteCatalogTest {

    private fun entry(
        name: String = "Test model",
        file: String = "test-q4.gguf",
        url: String = "https://huggingface.co/owner/repo/resolve/main/test-q4.gguf",
        size: Int = 500,
        extra: String = "",
    ) = """{"name":"$name","description":"d","fileName":"$file","url":"$url","sizeMb":$size$extra}"""

    private fun catalog(vararg entries: String, version: Int = 1) = """{"version":$version,"models":[${entries.joinToString(",")}],"embeddings":[]}"""

    @Test
    fun goodEntriesAreRead() {
        val parsed = RemoteCatalog.parse(catalog(entry(), entry(name = "Other", file = "o.gguf", url = "https://huggingface.co/a/b/resolve/main/o.gguf")))!!
        assertEquals(listOf("Test model", "Other"), parsed.models.map { it.name })
        assertEquals(0, parsed.skipped)
        assertEquals(500, parsed.models[0].sizeMb)
    }

    @Test
    fun downloadLinksMustBeHttpsToATrustedSite() {
        assertTrue(RemoteCatalog.allowedUrl("https://huggingface.co/o/r/resolve/main/m.gguf"))
        assertTrue(RemoteCatalog.allowedUrl("https://www.modelscope.cn/models/o/r/resolve/master/m.gguf"))
        assertFalse(RemoteCatalog.allowedUrl("http://huggingface.co/o/r/resolve/main/m.gguf"))
        assertFalse(RemoteCatalog.allowedUrl("https://evil.example/o/r/resolve/main/m.gguf"))
        assertFalse(RemoteCatalog.allowedUrl("https://huggingface.co.evil.example/o/r/resolve/main/m.gguf"))
        assertFalse(RemoteCatalog.allowedUrl("https://user:pw@huggingface.co/o/r/resolve/main/m.gguf"))
        assertFalse(RemoteCatalog.allowedUrl("https://huggingface.co:8443/o/r/resolve/main/m.gguf"))
        assertFalse(RemoteCatalog.allowedUrl("https://huggingface.co/o/r/blob/main/m.gguf"))
        assertFalse(RemoteCatalog.allowedUrl("https://huggingface.co/o/r/resolve/main/script.sh"))
        assertFalse(RemoteCatalog.allowedUrl("not a url"))
    }

    @Test
    fun badEntriesAreSkippedAndTheRestKept() {
        val parsed = RemoteCatalog.parse(
            catalog(
                entry(),
                entry(file = "../escape.gguf"),
                entry(file = "a/b.gguf"),
                entry(url = "https://evil.example/x/resolve/main/test-q4.gguf"),
                entry(size = 0),
                entry(name = ""),
                entry(extra = ",\"projectorFileName\":\"p.gguf\""),
            ),
        )!!
        assertEquals(1, parsed.models.size)
        assertEquals(6, parsed.skipped)
    }

    @Test
    fun aVisionAddOnNeedsAGoodNameAndLink() {
        val ok = RemoteCatalog.parse(catalog(entry(extra = ",\"projectorFileName\":\"mmproj-x.gguf\",\"projectorUrl\":\"https://huggingface.co/a/b/resolve/main/mmproj-x.gguf\"")))!!
        assertEquals("mmproj-x.gguf", ok.models.single().projectorFileName)
        val bad = RemoteCatalog.parse(catalog(entry(extra = ",\"projectorFileName\":\"mmproj-x.gguf\",\"projectorUrl\":\"https://evil.example/mmproj-x.gguf\"")))!!
        assertTrue(bad.models.isEmpty())
    }

    @Test
    fun somethingThatIsNotACatalogIsRefused() {
        assertNull(RemoteCatalog.parse("not json"))
        assertNull(RemoteCatalog.parse("""{"models":[]}"""))
        assertNull(RemoteCatalog.parse(catalog(entry(), version = 2)))
        assertNotNull(RemoteCatalog.parse(catalog()))
    }

    @Test
    fun anAbsurdNumberOfEntriesIsCapped() {
        val many = (1..200).map { entry(name = "M$it", file = "m$it.gguf", url = "https://huggingface.co/a/b/resolve/main/m$it.gguf") }
        assertEquals(60, RemoteCatalog.parse(catalog(*many.toTypedArray()))!!.models.size)
    }

    @Test
    fun mergingKeepsTheBuiltInOnesFirstAndAddsNewOnes() {
        val builtIn = listOf(CatalogModel("A", "", "a.gguf", "u", 1), CatalogModel("B", "", "b.gguf", "u", 2))
        val extra = listOf(CatalogModel("B2", "newer", "b.gguf", "u2", 3), CatalogModel("C", "", "c.gguf", "u", 4), CatalogModel("C again", "", "c.gguf", "u", 4))
        val merged = RemoteCatalog.merge(builtIn, extra)
        assertEquals(listOf("A", "B2", "C"), merged.map { it.name })
    }

    @Test
    fun theCatalogFileInTheRepositoryIsAcceptedInFull() {
        val file = listOf("catalog/models.json", "../catalog/models.json", "../../catalog/models.json").map(::File).first { it.exists() }
        val parsed = RemoteCatalog.parse(file.readText())!!
        assertTrue(parsed.models.isNotEmpty())
        assertEquals("every entry in the repository's catalog must pass the checks", 0, parsed.skipped)
        val names = parsed.models.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
    }
}

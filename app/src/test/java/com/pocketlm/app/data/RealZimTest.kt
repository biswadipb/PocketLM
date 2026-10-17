package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Checks the reader against a real Kiwix file. It runs only when the environment variable POCKETLM_TEST_ZIM names one
 * (for example wikipedia_en_top_mini_2026-09.zim from download.kiwix.org) and is skipped otherwise.
 */
class RealZimTest {

    private fun withWiki(check: (ZimFile, OfflineWikipedia) -> Unit) {
        val path = System.getenv("POCKETLM_TEST_ZIM")
        assumeTrue("POCKETLM_TEST_ZIM is not set", path != null && File(path).isFile)
        RandomAccessFile(path!!, "r").use { file ->
            val zim = ZimFile.open(ChannelZimSource(file.channel))
            check(zim, OfflineWikipedia(zim))
        }
    }

    @Test
    fun articlesAreFoundByTitleRedirectAndQuestion() = withWiki { zim, wiki ->
        assertTrue(zim.hasTitleIndex)
        for ((query, title) in listOf(
            "Ada Lovelace" to "Ada Lovelace", "ada lovelace" to "Ada Lovelace", "who was Ada Lovelace?" to "Ada Lovelace",
            "Albert Einstein" to "Albert Einstein", "capital of France" to "France", "photosynthesis" to "Photosynthesis", "Tokyo" to "Tokyo",
        )) {
            val article = wiki.lookup(query)
            assertEquals(query, title, article?.title)
            assertTrue(query, article!!.text.length > 200)
        }
        assertNull(wiki.lookup("zzzzqqqq"))
    }

    @Test
    fun theLeadParagraphComesFirstNotTheInfobox() = withWiki { _, wiki ->
        val einstein = wiki.lookup("Albert Einstein")!!.text
        assertTrue(einstein, einstein.startsWith("Albert Einstein (14 March 1879"))
        val tokyo = wiki.lookup("Tokyo")!!.text
        assertTrue(tokyo, tokyo.startsWith("Tokyo, officially the Tokyo Metropolis"))
    }

    @Test
    fun prefixSearchAndRawContentWork() = withWiki { zim, _ ->
        assertEquals(listOf("Ada Lovelace", "Ada Lovelace Byron"), zim.searchTitles("Ada L", 10).map { it.title })
        val entry = zim.resolve(zim.find('C', "Ada_Lovelace")!!)
        assertNotNull(entry)
        assertTrue(String(zim.content(entry!!)).contains("<title>Ada Lovelace</title>"))
    }
}

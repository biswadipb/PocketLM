package com.pocketlm.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KiwixTest {

    private val page = """
        <a href="wikipedia_en_all_mini_2026-06.zim">wikipedia_en_all_mini_2026-06.zim</a> 2026-06-18 13:38 12G
        <a href="wikipedia_en_top_mini_2026-06.zim">wikipedia_en_top_mini_2026-06.zim</a> 2026-06-16 09:18 316M
        <a href="wikipedia_en_top_mini_2026-09.zim">wikipedia_en_top_mini_2026-09.zim</a> 2026-09-18 10:43 283M
        <a href="wikipedia_en_top_mini_2025-12.zim">wikipedia_en_top_mini_2025-12.zim</a> 2025-12-16 09:18 300M
        <a href="wikipedia_en_top_nopic_2026-09.zim">wikipedia_en_top_nopic_2026-09.zim</a> 2026-09-18 10:49 2.6G
        <a href="wikipedia_en_top_mini_2026-09.zim.torrent">x</a>
    """.trimIndent()

    @Test
    fun theNewestFileOfTheKindIsChosenByItsDate() {
        assertEquals("wikipedia_en_top_mini_2026-09.zim", Kiwix.latest(page))
        assertEquals("wikipedia_en_all_mini_2026-06.zim", Kiwix.latest(page, "wikipedia_en_all_mini_"))
        assertEquals("wikipedia_en_top_nopic_2026-09.zim", Kiwix.latest(page, "wikipedia_en_top_nopic_"))
    }

    @Test
    fun aKindThatIsNotListedGivesNothing() {
        assertNull(Kiwix.latest(page, "wikipedia_fr_top_mini_"))
        assertNull(Kiwix.latest("nothing here"))
        assertNull(Kiwix.latest(""))
    }

    @Test
    fun aChecksumFileIsReadFromItsFirstWord() {
        val hash = "9e75b5f03b4ff61864772d811bd9715c9cdc27a429229abb779aaa3cc3aa8e05"
        assertEquals(hash, Kiwix.parseChecksum("$hash  wikipedia_en_top_mini_2026-09.zim\n"))
        assertEquals(hash, Kiwix.parseChecksum("  ${hash.uppercase()}\tfile"))
        assertNull(Kiwix.parseChecksum("short  file"))
        assertNull(Kiwix.parseChecksum("<html>404</html>"))
        assertNull(Kiwix.parseChecksum(""))
    }

    @Test
    fun onlyKiwixFilesAreLookedUpForAChecksum() {
        assertNull(Kiwix.expectedSha256("https://example.com/file.zim"))
        assertNull(Kiwix.expectedSha256("https://download.kiwix.org/zim/wikipedia/readme.txt"))
        assertTrue(Kiwix.FALLBACK_FILE.startsWith(Kiwix.TOP_PREFIX))
    }

    @Test
    fun theWikipediaToolReadsAFileOnThePhoneInsteadOfTheWeb() = runBlocking {
        var asked: String? = null
        val outcome = Tools.run(
            ToolCall("wikipedia", "Ada Lovelace"), setOf(ToolId.Wikipedia),
            offlineWiki = { q -> asked = q; OfflineWikipedia.Article("Ada Lovelace", "An English mathematician.") },
        )
        assertEquals("Ada Lovelace", asked)
        assertEquals("Wikipedia says: Ada Lovelace: An English mathematician.", outcome.modelText)
        val none = Tools.run(ToolCall("wikipedia", "Zzzz"), setOf(ToolId.Wikipedia), offlineWiki = { null })
        assertTrue(none.modelText, none.modelText.contains("no article matching 'Zzzz'"))
        assertTrue(none.modelText, none.modelText.contains("on this phone"))
    }
}

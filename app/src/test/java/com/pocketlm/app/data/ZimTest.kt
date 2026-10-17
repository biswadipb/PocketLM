package com.pocketlm.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZimTest {

    private val ada = """
        <html><head><title>Ada Lovelace</title><style>.x{}</style></head><body>
        <table class="infobox"><tr><td>Born</td><td>10 December 1815</td></tr><tr><td><table><tr><td>nested</td></tr></table></td></tr></table>
        <figure><img src="a.png"><figcaption>Portrait of Ada</figcaption></figure>
        <p class="mw-empty-elt"></p>
        <p>Augusta Ada King, Countess of Lovelace was an English mathematician and writer<sup>[1]</sup> chiefly known for her work on Charles Babbage's analytical engine.[2]</p>
        <p>She was the first to recognise that the machine had applications beyond pure calculation, and published the first algorithm intended to be carried out by such a machine.</p>
        <p>Short.</p>
        </body></html>
    """.trimIndent()

    private val turing = "<html><body><p>Alan Mathison Turing was an English mathematician, computer scientist, logician, cryptanalyst and philosopher.</p></body></html>"

    private fun zim(builder: ZimBuilder = sample()) = ZimFile.open(BytesZimSource(builder.build()))

    private fun sample(major: Int = 6, minor: Int = 3, oldTitleList: Boolean = false, packing: ZimBuilder.Packing = ZimBuilder.Packing.ZstdRaw) =
        ZimBuilder(major, minor, oldTitleList)
            .page("Ada_Lovelace", "Ada Lovelace", ada, packing)
            .page("Alan_Turing", "Alan Turing", turing, packing)
            .page("Ada_(programming_language)", "Ada (programming language)", "<p>Ada is a structured, statically typed programming language designed for large systems.</p>", packing)
            .page("Café", "Café", "<p>A café is a type of restaurant which serves coffee, tea and light meals to its guests.</p>", packing)
            .redirect("Ada", "", "Ada_Lovelace")
            .redirect("Ada_lovelace", "Ada lovelace", "Ada_Lovelace")
            .redirect("Loop_a", "Loop a", "Loop_b")
            .redirect("Loop_b", "Loop b", "Loop_a")

    @Test
    fun theHeaderAndEntriesAreRead() {
        val z = zim()
        assertEquals(9, z.entryCount) // eight items and the title listing
        assertEquals('C', z.contentNamespace)
        assertTrue(z.hasTitleIndex)
    }

    @Test
    fun anEntryIsFoundByItsAddressAndMissingOnesAreNot() {
        val z = zim()
        assertEquals("Ada Lovelace", z.find('C', "Ada_Lovelace")?.title)
        assertEquals("Café", z.find('C', "Café")?.title)
        assertNull(z.find('C', "Ada_Lovelac"))
        assertNull(z.find('C', "Zebra"))
        assertNull(z.find('A', "Ada_Lovelace"))
        assertNotNull(z.find('X', "listing/titleOrdered/v1"))
    }

    @Test
    fun everyEntryIsFoundByItsOwnAddress() {
        val z = zim()
        for (i in 0 until z.entryCount) {
            val e = z.entry(i)
            assertEquals(i, z.find(e.namespace, e.url)?.index)
        }
    }

    @Test
    fun redirectsAreFollowedAndLoopsGiveUp() {
        val z = zim()
        val redirect = z.find('C', "Ada")!!
        assertTrue(redirect.isRedirect)
        assertEquals("Ada_Lovelace", z.resolve(redirect)?.url)
        assertNull(z.resolve(z.find('C', "Loop_a")!!))
        assertEquals("Alan_Turing", z.resolve(z.find('C', "Alan_Turing")!!)?.url)
    }

    @Test
    fun contentComesBackIntactFromEveryKindOfCluster() {
        for (packing in ZimBuilder.Packing.values()) {
            val z = zim(sample(packing = packing))
            val entry = z.find('C', "Alan_Turing")!!
            assertEquals("text/html", entry.mimeType)
            assertEquals(packing.name, turing, String(z.content(entry)))
            assertEquals(packing.name, ada, String(z.content(z.find('C', "Ada_Lovelace")!!)))
        }
    }

    @Test
    fun partOfAPageCanBeReadWithoutTheRest() {
        val z = zim(sample(packing = ZimBuilder.Packing.None))
        val blob = z.blob(z.find('C', "Alan_Turing")!!)
        assertEquals(turing.length, blob.length)
        assertArrayEquals(turing.substring(6, 16).toByteArray(), blob.read(6, 10))
        assertEquals(turing.length - 5, blob.read(5, 1000).size)
    }

    @Test
    fun titlesAreSearchedByPrefixInTitleOrder() {
        val z = zim()
        assertEquals(listOf("Ada", "Ada (programming language)", "Ada Lovelace", "Ada lovelace"), z.searchTitles("Ada").map { it.title })
        assertEquals(listOf("Ada Lovelace"), z.searchTitles("Ada L").map { it.title })
        assertEquals(listOf("Ada lovelace"), z.searchTitles("Ada l").map { it.title })
        assertEquals(listOf("Alan Turing"), z.searchTitles("Al").map { it.title })
        assertTrue(z.searchTitles("Zzz").isEmpty())
        assertEquals(1, z.searchTitles("Ada", 1).size)
    }

    @Test
    fun anOlderFileWithItsTitleListInTheHeaderWorksToo() {
        val z = zim(sample(major = 5, minor = 0, oldTitleList = true))
        assertEquals('A', z.contentNamespace)
        assertEquals("Ada Lovelace", z.find('A', "Ada_Lovelace")?.title)
        assertEquals(listOf("Ada Lovelace"), z.searchTitles("Ada L").map { it.title })
        assertTrue(z.hasTitleIndex)
    }

    @Test
    fun filesThatAreNotZimOrAreDamagedAreRefused() {
        val good = sample().build()
        fun refused(bytes: ByteArray, use: (ZimFile) -> Unit = {}) = try {
            use(ZimFile.open(BytesZimSource(bytes)))
            fail("expected a refusal")
        } catch (e: ZimException) {
            assertTrue(e.message, !e.message.isNullOrBlank())
        }
        refused(ByteArray(10))
        refused("This is not a ZIM file at all, it is just some text that is long enough to have a header.....".toByteArray())
        refused(good.copyOf(good.size).also { it[4] = 9 }) // a version that does not exist
        // Cut in the middle: opening may work, but reading what is missing must fail cleanly.
        refused(good.copyOf(good.size / 2)) { it.content(it.find('C', "Alan_Turing")!!) }
    }

    // ---- looking things up -------------------------------------------------------------------

    @Test
    fun anArticleIsFoundByTitleInAnyCapitalisationOrQuestion() {
        val wiki = OfflineWikipedia(zim())
        for (q in listOf("Ada Lovelace", "ada lovelace", "ADA LOVELACE", "who was Ada Lovelace?", "Tell me about Ada Lovelace", "  \"Ada Lovelace\"  ")) {
            assertEquals(q, "Ada Lovelace", wiki.lookup(q)?.title)
        }
        assertEquals("Alan Turing", wiki.lookup("alan turing")?.title)
        assertEquals("Café", wiki.lookup("café")?.title ?: wiki.lookup("Café")?.title)
    }

    @Test
    fun aRedirectLeadsToItsArticle() {
        assertEquals("Ada Lovelace", OfflineWikipedia(zim()).lookup("Ada")?.title)
    }

    @Test
    fun aTitleThatOnlyBeginsWithTheWordsIsFound() {
        // "Ada programming" is not a title, but the keyword route finds Ada; a prefix of a longer title is found directly.
        assertEquals("Ada (programming language)", OfflineWikipedia(zim()).lookup("Ada (programming")?.title)
    }

    @Test
    fun aQuestionWithoutATitleFallsBackToItsNames() {
        assertEquals("Alan Turing", OfflineWikipedia(zim()).lookup("what did Alan Turing do for computing")?.title)
        assertNull(OfflineWikipedia(zim()).lookup("zzzz qqqq"))
        assertNull(OfflineWikipedia(zim()).lookup("   "))
    }

    @Test
    fun theTextIsTheLeadWithoutTheInfoboxPictureOrReferenceNumbers() {
        val text = OfflineWikipedia(zim()).lookup("Ada Lovelace")!!.text
        assertTrue(text, text.startsWith("Augusta Ada King, Countess of Lovelace was an English mathematician and writer chiefly known"))
        assertTrue(text, text.contains("She was the first to recognise"))
        assertFalse(text, text.contains("Born") || text.contains("nested") || text.contains("Portrait") || text.contains("[1]") || text.contains("[2]"))
        assertFalse(text, text.contains("Short."))
    }

    @Test
    fun theTextIsCutToTheLengthAsked() {
        val text = OfflineWikipedia(zim()).lookup("Ada Lovelace", maxChars = 120)!!.text
        assertTrue(text.length <= 121)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun thingsThatAreNotPagesAreNotReturned() {
        val z = zim(ZimBuilder().other("style.css", "text/css", "body{}".toByteArray()).page("Real", "Real", "<p>${"Some real content, long enough to count as a paragraph of text. ".repeat(2)}</p>"))
        assertNull(OfflineWikipedia(z).lookup("style.css"))
        assertEquals("Real", OfflineWikipedia(z).lookup("real")?.title)
    }

    // ---- turning a question into titles --------------------------------------------------------

    @Test
    fun questionsAreStrippedToTheirSubject() {
        assertEquals("Ada Lovelace", WikiQuery.clean("Who was Ada Lovelace?"))
        assertEquals("Eiffel Tower", WikiQuery.clean("what is the Eiffel Tower"))
        assertEquals("Photosynthesis", WikiQuery.clean("  \"Photosynthesis\".  "))
        assertEquals("capital of France", WikiQuery.clean("capital of France"))
        assertEquals("who", WikiQuery.clean("who"))
    }

    @Test
    fun titlesToTryCoverCapitalisationWithoutRepeats() {
        assertEquals(listOf("ada lovelace", "Ada lovelace", "Ada Lovelace"), WikiQuery.titles("ada lovelace"))
        assertEquals(listOf("Ada Lovelace", "Ada lovelace"), WikiQuery.titles("Ada Lovelace"))
        assertTrue(WikiQuery.titles("").isEmpty())
    }

    @Test
    fun keywordsPutNamesFirstThenLongerWords() {
        assertEquals(listOf("France", "Capital"), WikiQuery.keywords("capital of France").take(2))
        assertEquals("Alan Turing", WikiQuery.keywords("what did Alan Turing do for computing").first())
        assertEquals(listOf("Bank of England"), WikiQuery.phrases("when was the Bank of England founded"))
        assertTrue(WikiQuery.keywords("of the and").isEmpty())
    }
}

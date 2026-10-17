package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTitleTest {

    @Test
    fun theRequestOnlyMentionsTagsWhenThereAreSome() {
        val plain = AutoTitle.request(emptyList())
        assertTrue(plain.contains("Title:"))
        assertFalse(plain.contains("Tags"))
        val tagged = AutoTitle.request(listOf("work", "recipes"))
        assertTrue(tagged.contains("work, recipes"))
        assertTrue(tagged.contains("Tags:"))
    }

    @Test
    fun aTitleAndTagsAreRead() {
        val named = AutoTitle.parse("Title: Sourdough starter tips\nTags: cooking, baking", listOf("Cooking", "work"))
        assertEquals("Sourdough starter tips", named.title)
        assertEquals(listOf("Cooking"), named.tags)
    }

    @Test
    fun aBareLineIsTakenAsTheTitle() {
        assertEquals("Sourdough starter tips", AutoTitle.parse("\"Sourdough starter tips.\"", emptyList()).title)
        assertEquals("Trip to Lisbon", AutoTitle.parse("**Trip to Lisbon**\nSome more text", emptyList()).title)
    }

    @Test
    fun theThinkingSectionIsIgnored() {
        val named = AutoTitle.parse("<think>The user asked about...</think>\n\nTitle: Fixing a leaky tap", emptyList())
        assertEquals("Fixing a leaky tap", named.title)
    }

    @Test
    fun anEmptyOrTagsOnlyReplyHasNoTitle() {
        assertNull(AutoTitle.parse("", emptyList()).title)
        assertNull(AutoTitle.parse("   \n\n", emptyList()).title)
        assertNull(AutoTitle.parse("Tags: work", listOf("work")).title)
        assertEquals(listOf("work"), AutoTitle.parse("Tags: work", listOf("work")).tags)
    }

    @Test
    fun answersThatAreNotTitlesAreRefused() {
        assertNull(AutoTitle.tidy("Sorry, I cannot help with that"))
        assertNull(AutoTitle.tidy("Sure! Here is a title for you"))
        assertNull(AutoTitle.tidy("Here's what I think the chat is about"))
        assertNull(AutoTitle.tidy("As an AI I do not have titles"))
        assertNull(AutoTitle.tidy("ok"))
        assertNull(AutoTitle.tidy("one two three four five six seven eight nine ten eleven"))
    }

    @Test
    fun aWordThatOnlyStartsLikeARefusalIsFine() {
        assertEquals("Surely a good plan", AutoTitle.tidy("Surely a good plan"))
        assertEquals("Certainty in physics", AutoTitle.tidy("Certainty in physics"))
    }

    @Test
    fun quotesMarkdownAndClosingPunctuationAreStripped() {
        assertEquals("Tax questions", AutoTitle.tidy("“Tax questions”"))
        assertEquals("Tax questions", AutoTitle.tidy("# Tax questions:"))
        assertEquals("Tax questions", AutoTitle.tidy("`Tax   questions`!"))
        assertEquals("Why is the sky blue?", AutoTitle.tidy("Why is the sky blue?"))
    }

    @Test
    fun aLongTitleIsCutAtAWordBoundary() {
        val title = AutoTitle.tidy("Planning a long weekend trip to the mountains with friends")!!
        assertTrue(title.length <= AutoTitle.MAX_CHARS)
        assertEquals("Planning a long weekend trip to the mountains", title)
    }

    @Test
    fun onlyTagsThePersonAlreadyUsesAreAccepted() {
        val known = listOf("Work", "recipes", "travel")
        assertEquals(listOf("Work", "travel"), AutoTitle.parse("Title: A\nTags: work, #travel, invented", known).tags)
        assertEquals(emptyList<String>(), AutoTitle.parse("Title: A title\nTags: none", known).tags)
        assertEquals(2, AutoTitle.parse("Title: A title\nTags: Work, recipes, travel", known).tags.size)
        assertEquals(listOf("Work"), AutoTitle.parse("Title: A title\nTags: work, WORK", known).tags)
    }

    @Test
    fun aChatIsNamedOnceAfterItsFirstReply() {
        assertTrue(AutoTitle.due(customTitle = false, exchanges = 1, alreadyTried = false, enabled = true, incognito = false))
        assertFalse(AutoTitle.due(customTitle = true, exchanges = 1, alreadyTried = false, enabled = true, incognito = false))
        assertFalse(AutoTitle.due(customTitle = false, exchanges = 2, alreadyTried = false, enabled = true, incognito = false))
        assertFalse(AutoTitle.due(customTitle = false, exchanges = 1, alreadyTried = true, enabled = true, incognito = false))
        assertFalse(AutoTitle.due(customTitle = false, exchanges = 1, alreadyTried = false, enabled = false, incognito = false))
        assertFalse(AutoTitle.due(customTitle = false, exchanges = 1, alreadyTried = false, enabled = true, incognito = true))
    }
}

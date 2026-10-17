package com.pocketlm.app.data

import com.pocketlm.app.data.ShareIntake.Kind
import com.pocketlm.app.data.ShareIntake.TextPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntakeTest {

    private fun plan(text: String) = ShareIntake.classifyText(text)

    @Test
    fun aBareLinkIsAPageToRead() {
        assertEquals(TextPlan.Page("https://example.com/article?id=5"), plan("https://example.com/article?id=5"))
        assertEquals(TextPlan.Page("http://example.com/a"), plan("  http://example.com/a \n"))
    }

    @Test
    fun aLinkWithItsTitleBesideItIsStillAPage() {
        assertEquals(TextPlan.Page("https://news.example.org/story/1"), plan("A very good story\nhttps://news.example.org/story/1"))
        assertEquals(TextPlan.Page("https://news.example.org/story/1"), plan("https://news.example.org/story/1 via Example News"))
    }

    @Test
    fun punctuationRightAfterALinkIsNotPartOfIt() {
        assertEquals(TextPlan.Page("https://example.com/x"), plan("https://example.com/x."))
        assertEquals(TextPlan.Page("https://example.com/x"), plan("(https://example.com/x)"))
    }

    @Test
    fun aMessageThatMerelyMentionsALinkStaysAMessage() {
        val long = "Can you tell me whether the claims on this page are accurate? I read it yesterday and some of the numbers " +
            "looked odd to me, especially in the second half. https://example.com/claims"
        assertEquals(TextPlan.Message(long), plan(long))
    }

    @Test
    fun severalLinksOrNoLinkAreText() {
        assertEquals(TextPlan.Message("https://a.example/1 https://b.example/2"), plan("https://a.example/1 https://b.example/2"))
        assertEquals(TextPlan.Message("just some words"), plan("  just some words  "))
        assertEquals(TextPlan.Message("ftp://example.com/file"), plan("ftp://example.com/file"))
    }

    @Test
    fun filesGetAFitQuestion() {
        assertEquals(Kind.Pdf, ShareIntake.kindOf("application/pdf"))
        assertEquals(Kind.Image, ShareIntake.kindOf("image/jpeg"))
        assertEquals(Kind.Document, ShareIntake.kindOf("text/markdown"))
        assertEquals(Kind.Document, ShareIntake.kindOf(null))
        assertEquals("Summarise this page.", ShareIntake.suggestedQuestion(Kind.Page))
        assertEquals("Describe this image.", ShareIntake.suggestedQuestion(Kind.Image))
        assertEquals("Summarise this document.", ShareIntake.suggestedQuestion(Kind.Pdf))
    }
}

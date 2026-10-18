package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextTest {
    @Test
    fun pdfFilesAreRecognisedByTheirFirstBytes() {
        assertTrue(PdfText.looksLikePdf("%PDF-1.7".toByteArray()))
        assertFalse(PdfText.looksLikePdf("Hello".toByteArray()))
        assertFalse(PdfText.looksLikePdf(byteArrayOf(0x25, 0x50)))
    }

    @Test
    fun tidyJoinsHyphenatedWordsAndCollapsesBlankLines() {
        val raw = "The computa-\ntion of values\r\n\r\n\r\n\r\nends here.   \nNext"
        assertEquals("The computation of values\n\nends here.\nNext", PdfText.tidy(raw))
    }

    @Test
    fun aRealHyphenBetweenWordsOnOneLineIsKept() {
        assertEquals("a well-known fact", PdfText.tidy("a well-known fact"))
    }
}

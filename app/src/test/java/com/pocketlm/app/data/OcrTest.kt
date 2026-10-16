package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTest {
    @Test
    fun pagesAreLabelledAndBlankPagesSkipped() {
        assertEquals("[Page 1]\nFirst\n\n[Page 3]\nThird", Ocr.combinePages(listOf("First", "  \n", "Third  ")))
        assertEquals("", Ocr.combinePages(listOf("", " ")))
    }

    @Test
    fun aLetterPageIsDrawnAtAReadableWidth() {
        // 612 x 792 points (US letter)
        val (w, h) = Ocr.renderSize(612, 792)
        assertEquals(1600, w)
        assertEquals(2070, h)
    }

    @Test
    fun aVeryTallPageIsLimitedByHeight() {
        val (w, h) = Ocr.renderSize(200, 4000)
        assertEquals(3200, h)
        assertTrue(w < 1600)
    }

    @Test
    fun nonsensePageSizesStillGiveASafeBitmapSize() {
        assertEquals(1600 to 1600, Ocr.renderSize(0, 0))
        assertTrue(Ocr.renderSize(1, 100000).first >= 1)
    }
}

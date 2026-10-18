package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageTest {

    @Test
    fun readsTheTitleAndDropsScriptsAndStyles() {
        val page = HtmlText.extract(
            "<html><head><title>My &amp; Page</title><script>var secret = 1;</script></head>" +
                "<body><h1>Hello</h1><p>First paragraph.</p><p>Second &lt;one&gt;.</p><style>p { color: red }</style></body></html>",
        )
        assertEquals("My & Page", page.title)
        assertTrue("Hello" in page.text)
        assertTrue("First paragraph." in page.text)
        assertTrue("Second <one>." in page.text)
        assertFalse("secret" in page.text)
        assertFalse("color" in page.text)
    }

    @Test
    fun paragraphsStayOnSeparateLinesAndListsGetBullets() {
        val text = HtmlText.extract("<body><p>One</p><p>Two</p><ul><li>Apple</li><li>Pear</li></ul></body>").text
        assertEquals("One\nTwo\n\n- Apple\n- Pear", text)
    }

    @Test
    fun navigationAndFootersAreLeftOut() {
        val text = HtmlText.extract("<body><nav>MENU LINKS</nav><p>The real content</p><footer>COPYRIGHT</footer></body>").text
        assertEquals("The real content", text)
    }

    @Test
    fun theMainPartOfALongPageIsPreferred() {
        val body = "Interesting words. ".repeat(40)
        val text = HtmlText.extract("<body><div>SIDEBAR NOISE</div><main><p>$body</p></main><div>MORE NOISE</div></body>").text
        assertTrue(text.startsWith("Interesting words."))
        assertFalse("NOISE" in text)
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("AB & ©", HtmlText.decode("&#65;&#x42; &amp; &copy;"))
        assertEquals("&unknown;", HtmlText.decode("&unknown;"))
    }

    @Test
    fun addressesAreTidiedOrRefused() {
        assertEquals("https://example.com/page", WebPage.normalise("example.com/page"))
        assertEquals("http://192.168.1.5:8080/x", WebPage.normalise("  http://192.168.1.5:8080/x "))
        assertEquals("https://localhost", WebPage.normalise("localhost"))
        assertNull(WebPage.normalise(""))
        assertNull(WebPage.normalise("not a url"))
        assertNull(WebPage.normalise("ftp://example.com"))
        assertNull(WebPage.normalise("https://nodot"))
        assertNull(WebPage.normalise("javascript:alert(1)"))
    }
}

package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class PageAccessTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text) // a literal: no lookup happens
    private val publicSite: (String) -> List<InetAddress> = { listOf(ip("93.184.216.34")) }

    private fun refusal(input: String, seen: String, resolve: (String) -> List<InetAddress> = publicSite): String = try {
        PageAccess.check(input, seen, resolve)
        fail("expected a refusal for $input")
        ""
    } catch (e: IllegalArgumentException) {
        e.message!!
    }

    @Test
    fun differentWaysOfWritingTheSameAddressMatch() {
        assertEquals(PageAccess.key("https://www.Example.com/a/?x=1#top"), PageAccess.key("http://example.com/a/?x=1"))
        assertEquals("example.com", PageAccess.key("https://example.com/"))
        assertEquals("example.com/page", PageAccess.key("example.com/page,"))
    }

    @Test
    fun onlyAddressesInTheConversationAreMentioned() {
        val seen = "Please read https://example.com/page, thanks. Also see docs.example.org/guide and (https://a.example.net/x)."
        assertTrue(PageAccess.mentioned("https://example.com/page", seen))
        assertTrue(PageAccess.mentioned("https://docs.example.org/guide", seen))
        assertTrue(PageAccess.mentioned("https://a.example.net/x", seen))
        assertFalse(PageAccess.mentioned("https://example.com/other", seen))
        assertFalse(PageAccess.mentioned("https://evil.example/?data=secret", seen))
        assertFalse(PageAccess.mentioned("https://example.com/page", ""))
    }

    @Test
    fun anAddressTheModelMadeUpIsRefused() {
        val message = refusal("https://evil.example/?q=my+password", "Summarise https://news.example.com/story")
        assertTrue(message.contains("paste the link"))
    }

    @Test
    fun anAddressTheUserWroteIsAccepted() {
        assertEquals("https://example.com/a", PageAccess.check("example.com/a", "can you open example.com/a for me", publicSite))
    }

    @Test
    fun namesThatPointAtThePhoneOrTheLocalNetworkAreRefused() {
        assertNull(PageAccess.blockedName("example.com"))
        assertTrue(PageAccess.blockedName("localhost") != null)
        assertTrue(PageAccess.blockedName("printer.local") != null)
        assertTrue(PageAccess.blockedName("nas.home.arpa") != null)
        assertTrue(PageAccess.blockedName("router") != null)
        assertTrue(refusal("http://localhost/admin", "http://localhost/admin").contains("private network"))
        assertTrue(refusal("http://nas.local/files", "see nas.local/files").contains("private network"))
    }

    @Test
    fun privateAddressesAreBlocked() {
        for (bad in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.5", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1")) {
            assertTrue("$bad should be blocked", PageAccess.blockedAddress(ip(bad)))
        }
        for (good in listOf("8.8.8.8", "93.184.216.34", "1.1.1.1", "2606:4700:4700::1111")) {
            assertFalse("$good should be allowed", PageAccess.blockedAddress(ip(good)))
        }
    }

    @Test
    fun aSiteThatSecretlyResolvesToAPrivateAddressIsRefused() {
        val message = refusal("https://sneaky.example/x", "https://sneaky.example/x") { listOf(ip("192.168.1.1")) }
        assertTrue(message.contains("private network"))
        assertTrue(refusal("https://mixed.example/x", "https://mixed.example/x") { listOf(ip("93.184.216.34"), ip("10.0.0.1")) }.contains("private network"))
    }

    @Test
    fun aSiteThatCannotBeFoundSaysSo() {
        val message = refusal("https://nowhere.example/x", "https://nowhere.example/x") { throw java.net.UnknownHostException("no") }
        assertTrue(message.contains("could not be found"))
    }

    @Test
    fun somethingThatIsNotAnAddressIsRefused() {
        assertTrue(refusal("not a link", "not a link").contains("not a web address"))
        assertTrue(refusal("ftp://example.com/x", "ftp://example.com/x").contains("not a web address"))
    }
}

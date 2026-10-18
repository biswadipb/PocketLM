package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProvidersTest {

    // Shaped like the results page DuckDuckGo returns, trimmed down.
    private val page = """
        <div class="result results_links results_links_deep web-result ">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://kotlinlang.org/docs/coroutines-overview.html">Coroutines | Kotlin Documentation</a>
          </h2>
          <a class="result__snippet" href="https://kotlinlang.org/docs/coroutines-overview.html"><b>Coroutines</b> in <b>Kotlin</b> are built on suspending functions &amp; more.</a>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Fkotlin%2Fcoroutines&amp;rut=abc123">Kotlin coroutines on Android</a>
          </h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Use coroutines for async work.</a>
        </div>
        <div class="result result--ad">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=shop.example&amp;u3=abc">Buy things</a>
          </h2>
          <a class="result__snippet" href="https://duckduckgo.com/y.js?x">An advert.</a>
        </div>
        <div class="result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://example.org/no-snippet">A result without a snippet</a>
          </h2>
        </div>
    """.trimIndent()

    @Test
    fun duckDuckGoResultsAreReadWithTitlesAddressesAndSnippets() {
        val hits = WebSearch.parseDuckDuckGo(page)
        assertEquals(3, hits.size)
        assertEquals("Coroutines | Kotlin Documentation", hits[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", hits[0].url)
        assertEquals("Coroutines in Kotlin are built on suspending functions & more.", hits[0].snippet)
    }

    @Test
    fun wrappedLinksGiveTheRealAddress() {
        val hits = WebSearch.parseDuckDuckGo(page)
        assertEquals("https://developer.android.com/kotlin/coroutines", hits[1].url)
        assertEquals("Use coroutines for async work.", hits[1].snippet)
    }

    @Test
    fun advertsAreLeftOutAndAMissingSnippetDoesNotBorrowTheNextOne() {
        val hits = WebSearch.parseDuckDuckGo(page)
        assertTrue(hits.none { it.title == "Buy things" })
        assertEquals("A result without a snippet", hits[2].title)
        assertEquals("", hits[2].snippet)
    }

    @Test
    fun addressesAreCleaned() {
        assertEquals("https://a.example/x?y=1", WebSearch.realAddress("//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.example%2Fx%3Fy%3D1&rut=z"))
        assertEquals("https://a.example/", WebSearch.realAddress("https://a.example/"))
        assertNull(WebSearch.realAddress("https://duckduckgo.com/y.js?ad=1"))
        assertNull(WebSearch.realAddress("javascript:alert(1)"))
    }

    @Test
    fun aPageWithoutResultsGivesNothing() {
        assertTrue(WebSearch.parseDuckDuckGo("<html><body>No results.</body></html>").isEmpty())
        assertNull(WebSearch.format(emptyList()))
    }

    @Test
    fun braveResultsAreReadAndMarkupIsStripped() {
        val json = """{"web":{"results":[
            {"title":"Kotlin","url":"https://kotlinlang.org","description":"A <strong>modern</strong> language &amp; more"},
            {"title":"","url":"https://x"},
            {"title":"Second","url":"https://y.example","description":"Another"}]}}"""
        val hits = WebSearch.parseBrave(json)
        assertEquals(2, hits.size)
        assertEquals("A modern language & more", hits[0].snippet)
        assertEquals("https://y.example", hits[1].url)
        assertTrue(WebSearch.parseBrave("""{"query":{}}""").isEmpty())
    }

    @Test
    fun theModelGetsANumberedListOfFourAtMost() {
        val hits = (1..9).map { SearchHit("T$it", "https://x/$it", "s$it") }
        val text = WebSearch.format(hits)!!
        assertEquals(4, text.lines().size)
        assertTrue(text.startsWith("1. T1 (https://x/1): s1"))
    }

    @Test
    fun theChoiceFollowsWhatWasSavedBeforeThereWasAChoice() {
        assertEquals(SearchProvider.Brave, SearchProvider.initial("brave", ""))
        assertEquals(SearchProvider.SearXNG, SearchProvider.initial(null, "search.example.org"))
        assertEquals(SearchProvider.DuckDuckGo, SearchProvider.initial(null, ""))
        assertEquals(SearchProvider.DuckDuckGo, SearchProvider.initial("nonsense", ""))
    }

    @Test
    fun eachProviderSaysWhatItStillNeeds() {
        assertNull(SearchConfig(SearchProvider.DuckDuckGo).problem())
        assertNotNull(SearchConfig(SearchProvider.Brave, braveKey = " ").problem())
        assertNull(SearchConfig(SearchProvider.Brave, braveKey = "key").problem())
        assertNotNull(SearchConfig(SearchProvider.SearXNG, searxServer = "").problem())
        assertNull(SearchConfig(SearchProvider.SearXNG, searxServer = "search.example.org").problem())
    }
}

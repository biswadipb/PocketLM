package com.pocketlm.app.data

import java.net.InetAddress
import java.net.URI

/**
 * Rules for the "read a web page" tool. A model that has read an untrusted page can be talked into doing odd things, so it
 * may only open an address that already appears in the conversation (something the person typed, or a result a tool
 * returned this turn), and never one that points at the phone itself or at the local network.
 */
object PageAccess {

    /** Anything that looks like a web address, with or without "https://". */
    private val addressLike = Regex(
        "(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(?::\\d{1,5})?(?:[/?#][^\\s<>\"'`)\\]]*)?",
        RegexOption.IGNORE_CASE,
    )

    private val blockedSuffixes = listOf(".local", ".localhost", ".internal", ".localdomain", ".lan", ".home", ".home.arpa", ".corp", ".intranet")

    /** One form for comparing addresses: no scheme, no "www.", no fragment, no closing slash, lower case. */
    fun key(address: String): String {
        var s = address.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        s = s.substringBefore('#')
        s = s.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
        val slash = s.indexOfFirst { it == '/' || it == '?' }
        val host = (if (slash < 0) s else s.substring(0, slash)).lowercase().removePrefix("www.")
        val tail = if (slash < 0) "" else s.substring(slash)
        return (host + tail).trimEnd('/')
    }

    /** Whether [address] is written somewhere in [seen]. */
    fun mentioned(address: String, seen: String): Boolean {
        val wanted = key(address)
        if (wanted.isEmpty()) return false
        return addressLike.findAll(seen).any { key(it.value) == wanted }
    }

    /** Why a host name may not be opened, or null. Only looks at the name; see [blockedAddress] for what it resolves to. */
    fun blockedName(host: String): String? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return "That link has no site name."
        if (h == "localhost" || blockedSuffixes.any { h.endsWith(it) }) return "That link points at this phone or a private network."
        if (!h.contains('.') && !h.contains(':')) return "That link has no real site name."
        return null
    }

    /** True for addresses that belong to this phone or a private network. */
    fun blockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val b = address.address
        if (b.size == 4) {
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            if (first == 0) return true
            if (first == 100 && second in 64..127) return true // carrier-grade NAT
        }
        if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true // fc00::/7
        return false
    }

    /**
     * The clean address to open for [input], or an [IllegalArgumentException] whose message says, in words for the model,
     * why not. [resolve] turns a site name into addresses and can be replaced in tests.
     */
    fun check(
        input: String,
        seen: String,
        resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ): String {
        val address = WebPage.normalise(input) ?: throw IllegalArgumentException("That is not a web address.")
        URI(address).host?.let { name -> blockedName(name)?.let { throw IllegalArgumentException(it) } }
        if (!mentioned(address, seen)) {
            throw IllegalArgumentException(
                "I can only open a link that you wrote or that a search returned. Ask the user to paste the link they want opened.",
            )
        }
        checkHost(address, resolve)
        return address
    }

    /** Throws unless the site in [address] is on the public internet. Also used for every redirect. */
    fun checkHost(address: String, resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() }) {
        val host = try {
            URI(address).host
        } catch (e: Exception) {
            null
        } ?: throw IllegalArgumentException("That is not a web address.")
        blockedName(host)?.let { throw IllegalArgumentException(it) }
        val resolved = try {
            resolve(host.removePrefix("[").removeSuffix("]"))
        } catch (e: Exception) {
            throw IllegalArgumentException("The site $host could not be found.")
        }
        if (resolved.isEmpty() || resolved.any(::blockedAddress)) {
            throw IllegalArgumentException("That link points at this phone or a private network.")
        }
    }
}

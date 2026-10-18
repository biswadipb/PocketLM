package com.pocketlm.app.data

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

data class RemoteFile(val name: String, val size: Long)

/**
 * Where synced files live. It only knows how to list, read, write and delete opaque files by name; what is in
 * them is encrypted before it gets here. Calls block, so run them off the main thread.
 */
interface SyncBackend {
    /** A short description for messages, such as "WebDAV (cloud.example.com)". */
    val label: String

    fun list(): List<RemoteFile>

    /** The file's bytes, or null if there is no such file. */
    fun get(name: String): ByteArray?

    fun put(name: String, data: ByteArray)

    /** Removes a file. Not an error if it is already gone. */
    fun delete(name: String)
}

/** Writes, reads back and removes a small file, which shows that the location really can be used for syncing. */
fun SyncBackend.checkAccess() {
    val name = "pocketlm-probe.tmp"
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    put(name, bytes)
    val back = get(name) ?: throw SyncException("$label accepted a file but would not return it.")
    delete(name)
    if (!back.contentEquals(bytes)) throw SyncException("$label returned different data than was saved.")
}

internal object Xml {
    /** Parses [bytes]. Documents that declare a DOCTYPE or entities are refused, so they cannot pull in other files. */
    fun parse(bytes: ByteArray, namespaceAware: Boolean): Element {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).lowercase()
        if ("<!doctype" in head || "<!entity" in head) throw SyncException("The server sent an answer that cannot be read safely.")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = namespaceAware }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        } catch (e: Exception) {
            throw SyncException("The server's answer could not be read.", e)
        }
    }

    fun children(parent: Element, localName: String): List<Element> =
        (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && (it.localName ?: it.nodeName.substringAfter(':')) == localName }
            .map { it as Element }

    fun text(parent: Element, localName: String): String? = children(parent, localName).firstOrNull()?.textContent?.trim()

    fun descendants(parent: Element, localName: String): List<Element> {
        val all = parent.getElementsByTagName("*")
        return (0 until all.length).map { all.item(it) as Element }.filter { (it.localName ?: it.nodeName.substringAfter(':')) == localName }
    }
}

/**
 * A WebDAV folder, such as one on Nextcloud, ownCloud or a NAS. [folderUrl] is the address of an existing folder.
 * Sync uses GET, PUT and DELETE; PROPFIND (for [list]) and MKCOL are best effort, see [UrlConnectionHttp].
 */
class WebDavBackend(
    folderUrl: String,
    private val username: String,
    private val password: String,
    private val http: SyncHttp = UrlConnectionHttp(),
) : SyncBackend {

    private val base = if (folderUrl.endsWith("/")) folderUrl else "$folderUrl/"

    override val label: String get() = "WebDAV (${runCatching { URI(base).host }.getOrNull() ?: base})"

    private val auth: Map<String, String>
        get() = mapOf(
            "Authorization" to "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8)),
        )

    private fun url(name: String): String {
        require(SyncNames.valid(name)) { "Unexpected file name" }
        return base + SigV4.encode(name, true)
    }

    override fun list(): List<RemoteFile> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
        val r = http.request("PROPFIND", base, auth + mapOf("Depth" to "1", "Content-Type" to "application/xml"), body.toByteArray())
        if (r.code != 207) throw failure(r, "list the folder")
        return parseListing(r.body)
    }

    override fun get(name: String): ByteArray? {
        val r = http.request("GET", url(name), auth)
        return when {
            r.code == 404 -> null
            r.ok -> r.body
            else -> throw failure(r, "read $name")
        }
    }

    override fun put(name: String, data: ByteArray) {
        var r = http.request("PUT", url(name), auth + mapOf("Content-Type" to "application/octet-stream"), data)
        if (r.code == 409 || r.code == 404) {
            // The folder may not exist yet. Creating it needs MKCOL, which not every device can send.
            val made = runCatching { http.request("MKCOL", base, auth) }.getOrNull()
            if (made != null && (made.ok || made.code == 405)) {
                r = http.request("PUT", url(name), auth + mapOf("Content-Type" to "application/octet-stream"), data)
            } else {
                throw SyncException("The folder does not exist on the server. Create it there first, then try again.")
            }
        }
        if (!r.ok) throw failure(r, "save $name")
    }

    override fun delete(name: String) {
        val r = http.request("DELETE", url(name), auth)
        if (!r.ok && r.code != 404) throw failure(r, "remove $name")
    }

    private fun failure(r: HttpResponse, doing: String): SyncException = when (r.code) {
        401, 403 -> SyncException("The server refused the user name or password (HTTP ${r.code}).")
        404 -> SyncException("The folder address was not found on the server.")
        405, 501 -> SyncException("The server does not allow this (HTTP ${r.code}) when trying to $doing.")
        507 -> SyncException("The server is out of space.")
        else -> SyncException("The server could not $doing (HTTP ${r.code}).")
    }

    companion object {
        /** Reads a PROPFIND answer: the files directly inside the folder, not folders. */
        fun parseListing(xml: ByteArray): List<RemoteFile> {
            val root = Xml.parse(xml, namespaceAware = true)
            val files = ArrayList<RemoteFile>()
            for (response in Xml.descendants(root, "response")) {
                val href = Xml.text(response, "href") ?: continue
                val isFolder = Xml.descendants(response, "collection").isNotEmpty()
                if (isFolder) continue
                val path = runCatching { URI(href).rawPath }.getOrNull() ?: href
                // Cut at the last real slash first; an encoded slash inside the name must not pass for a folder.
                val name = runCatching {
                    URLDecoder.decode(path.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                }.getOrNull() ?: continue
                if (!SyncNames.valid(name)) continue
                val size = Xml.descendants(response, "getcontentlength").firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: 0L
                files += RemoteFile(name, size)
            }
            return files
        }
    }
}

/**
 * S3 or an S3-compatible store (MinIO, Cloudflare R2, Backblaze B2, Wasabi, Garage). Requests are addressed
 * path-style (`endpoint/bucket/key`), which all of them accept, and signed with [SigV4].
 */
class S3Backend(
    endpoint: String,
    private val region: String,
    private val bucket: String,
    prefix: String,
    private val accessKey: String,
    private val secretKey: String,
    private val http: SyncHttp = UrlConnectionHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncBackend {

    private val base: URI = URI(endpoint.trim().trimEnd('/'))
    private val basePath = base.path.orEmpty().trimEnd('/')
    private val keyPrefix = prefix.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

    override val label: String get() = "S3 ($bucket at ${base.host})"

    private val hostHeader: String
        get() {
            val defaultPort = if (base.scheme == "https") 443 else 80
            return if (base.port == -1 || base.port == defaultPort) base.host else "${base.host}:${base.port}"
        }

    private fun call(method: String, key: String?, query: List<Pair<String, String>> = emptyList(), body: ByteArray? = null): HttpResponse {
        val path = if (key == null) "$basePath/$bucket" else "$basePath/$bucket/$keyPrefix$key"
        val payloadHash = if (body == null) SigV4.EMPTY_HASH else SigV4.sha256Hex(body)
        val signed = SigV4.sign(accessKey, secretKey, region, method, hostHeader, path, query, emptyMap(), payloadHash, clock())
        val queryText = if (query.isEmpty()) "" else "?" + query.joinToString("&") { "${SigV4.encode(it.first, true)}=${SigV4.encode(it.second, true)}" }
        val url = "${base.scheme}://$hostHeader${SigV4.encodePath(path)}$queryText"
        return http.request(method, url, signed.headers(), body)
    }

    override fun list(): List<RemoteFile> {
        val files = ArrayList<RemoteFile>()
        var token: String? = null
        do {
            val query = buildList {
                add("list-type" to "2")
                if (keyPrefix.isNotEmpty()) add("prefix" to keyPrefix)
                token?.let { add("continuation-token" to it) }
            }
            val r = call("GET", null, query)
            if (!r.ok) throw failure(r, "list the bucket")
            val page = parseListing(r.body, keyPrefix)
            files += page.files
            token = page.next
        } while (token != null)
        return files
    }

    override fun get(name: String): ByteArray? {
        require(SyncNames.valid(name)) { "Unexpected file name" }
        val r = call("GET", name)
        return when {
            r.ok -> r.body
            r.code == 404 && errorCode(r) != "NoSuchBucket" -> null
            else -> throw failure(r, "read $name")
        }
    }

    override fun put(name: String, data: ByteArray) {
        require(SyncNames.valid(name)) { "Unexpected file name" }
        val r = call("PUT", name, body = data)
        if (!r.ok) throw failure(r, "save $name")
    }

    override fun delete(name: String) {
        require(SyncNames.valid(name)) { "Unexpected file name" }
        val r = call("DELETE", name)
        if (!r.ok && r.code != 404) throw failure(r, "remove $name")
    }

    private fun errorCode(r: HttpResponse): String? = runCatching {
        Xml.text(Xml.parse(r.body, namespaceAware = false), "Code")
    }.getOrNull()

    private fun failure(r: HttpResponse, doing: String): SyncException {
        val detail = runCatching {
            val root = Xml.parse(r.body, namespaceAware = false)
            listOfNotNull(Xml.text(root, "Code"), Xml.text(root, "Message")).joinToString(": ")
        }.getOrNull().orEmpty()
        val why = when (r.code) {
            401, 403 -> "The storage refused the access key, or it is not allowed to use this bucket"
            404 -> "The bucket was not found"
            301, 307 -> "The storage wants a different region or address"
            else -> "The storage could not $doing"
        }
        return SyncException("$why (HTTP ${r.code}${if (detail.isNotEmpty()) ", $detail" else ""}).")
    }

    class Page(val files: List<RemoteFile>, val next: String?)

    companion object {
        /** Reads a ListObjectsV2 answer; names have [prefix] removed and anything in a sub-folder is ignored. */
        fun parseListing(xml: ByteArray, prefix: String): Page {
            val root = Xml.parse(xml, namespaceAware = false)
            val files = ArrayList<RemoteFile>()
            for (item in Xml.children(root, "Contents")) {
                val key = Xml.text(item, "Key") ?: continue
                if (!key.startsWith(prefix)) continue
                val name = key.removePrefix(prefix)
                if (!SyncNames.valid(name)) continue
                files += RemoteFile(name, Xml.text(item, "Size")?.toLongOrNull() ?: 0L)
            }
            val truncated = Xml.text(root, "IsTruncated") == "true"
            return Page(files, if (truncated) Xml.text(root, "NextContinuationToken") else null)
        }
    }
}

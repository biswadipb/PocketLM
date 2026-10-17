package com.pocketlm.app.data

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Makes small ZIM files for tests, following https://wiki.openzim.org/wiki/ZIM_file_format. */
class ZimBuilder(private val major: Int = 6, private val minor: Int = 3, private val oldTitleList: Boolean = false) {

    enum class Packing { None, Xz, ZstdRaw }

    private sealed class Item(val namespace: Char, val url: String, val title: String)
    private class Page(ns: Char, url: String, title: String, val data: ByteArray, val mime: String, val packing: Packing) : Item(ns, url, title)
    private class Redirect(ns: Char, url: String, title: String, val target: String) : Item(ns, url, title)

    private val items = ArrayList<Item>()

    fun page(url: String, title: String, html: String, packing: Packing = Packing.ZstdRaw, namespace: Char = if (major >= 6 && minor >= 1) 'C' else 'A') = apply {
        items += Page(namespace, url, title, html.toByteArray(), "text/html", packing)
    }

    fun other(url: String, mime: String, data: ByteArray, namespace: Char = 'C') = apply {
        items += Page(namespace, url, "", data, mime, Packing.None)
    }

    fun redirect(url: String, title: String, target: String, namespace: Char = if (major >= 6 && minor >= 1) 'C' else 'A') = apply {
        items += Redirect(namespace, url, title, target)
    }

    private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(block).array()

    private fun bytesOf(item: Item) = item.url.toByteArray()
    private fun titleBytesOf(item: Item) = (item.title.ifEmpty { item.url }).toByteArray()

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun pack(body: ByteArray, packing: Packing): ByteArray = when (packing) {
        Packing.None -> byteArrayOf(1) + body
        Packing.Xz -> {
            val out = ByteArrayOutputStream()
            XZOutputStream(out, LZMA2Options()).use { it.write(body) }
            byteArrayOf(4) + out.toByteArray()
        }
        Packing.ZstdRaw -> {
            // A valid Zstandard frame whose single block is stored as it is.
            val head = le(4 + 1 + 4 + 3) {
                putInt(0xFD2FB528.toInt())
                put(0xA0.toByte()) // single segment, 4 bytes of content size
                putInt(body.size)
                val header = (body.size shl 3) or 1 // last block, raw
                put(header.toByte()); put((header shr 8).toByte()); put((header shr 16).toByte())
            }
            byteArrayOf(5) + head + body
        }
    }

    private fun clusterBody(data: ByteArray): ByteArray =
        le(8) { putInt(8); putInt(8 + data.size) } + data

    fun build(): ByteArray {
        val listingPlaceholder = Page('X', "listing/titleOrdered/v1", "", ByteArray(0), "application/octet-stream+zimlisting", Packing.None)
        val all = (items + listingPlaceholder).sortedWith { a, b ->
            val d = a.namespace.code - b.namespace.code
            if (d != 0) d else compare(bytesOf(a), bytesOf(b))
        }
        val indexOf = all.withIndex().associate { (i, item) -> item to i }
        fun target(r: Redirect) = all.indexOfFirst { it.namespace == r.namespace && it.url == r.target }.also { require(it >= 0) { "no ${r.target}" } }

        // Titles, in order, of the pages (not of the listing itself).
        val titled = all.filter { it.namespace != 'X' }.sortedWith { a, b -> compare(titleBytesOf(a), titleBytesOf(b)) }
        val listing = le(titled.size * 4) { titled.forEach { putInt(indexOf.getValue(it)) } }

        val content = all.map { item ->
            when (item) {
                listingPlaceholder -> Page('X', item.url, "", listing, "application/octet-stream+zimlisting", Packing.None)
                else -> item
            }
        }

        val mimes = (content.filterIsInstance<Page>().map { it.mime }).distinct()
        val mimeBytes = ByteArrayOutputStream().also { out -> mimes.forEach { out.write(it.toByteArray()); out.write(0) }; out.write(0) }.toByteArray()

        // Clusters: one per page.
        val pages = content.filterIsInstance<Page>()
        val clusterOf = pages.withIndex().associate { (i, p) -> p to i }
        val clusters = pages.map { pack(clusterBody(it.data), it.packing) }

        // Directory entries.
        val dirents = content.map { item ->
            when (item) {
                is Redirect -> le(12) {
                    putShort(0xFFFF.toShort()); put(0); put(item.namespace.code.toByte()); putInt(0); putInt(target(item))
                } + item.url.toByteArray() + byteArrayOf(0) + item.title.toByteArray() + byteArrayOf(0)
                is Page -> le(16) {
                    putShort(mimes.indexOf(item.mime).toShort()); put(0); put(item.namespace.code.toByte()); putInt(0)
                    putInt(clusterOf.getValue(item)); putInt(0)
                } + item.url.toByteArray() + byteArrayOf(0) + item.title.toByteArray() + byteArrayOf(0)
            }
        }

        // Layout.
        var cursor = 80L
        val mimeAt = cursor
        cursor += mimeBytes.size
        val direntAt = dirents.map { val at = cursor; cursor += it.size; at }
        val urlPtrAt = cursor
        cursor += 8L * dirents.size
        val titlePtrAt = if (oldTitleList) cursor else -1L
        if (oldTitleList) cursor += 4L * all.size
        val clusterAt = clusters.map { val at = cursor; cursor += it.size; at }
        val clusterPtrAt = cursor
        cursor += 8L * clusters.size
        val checksumAt = cursor

        val out = ByteArrayOutputStream()
        out.write(le(80) {
            putInt(0x44D495A); putShort(major.toShort()); putShort(minor.toShort())
            put(ByteArray(16))
            putInt(dirents.size); putInt(clusters.size)
            putLong(urlPtrAt); putLong(titlePtrAt); putLong(clusterPtrAt); putLong(mimeAt)
            putInt(0); putInt(-1); putLong(checksumAt)
        })
        out.write(mimeBytes)
        dirents.forEach(out::write)
        out.write(le(8 * dirents.size) { direntAt.forEach { putLong(it) } })
        if (oldTitleList) {
            val order = all.indices.sortedWith { a, b ->
                val x = all[a]
                val y = all[b]
                val d = x.namespace.code - y.namespace.code
                if (d != 0) d else compare(titleBytesOf(x), titleBytesOf(y))
            }
            out.write(le(4 * all.size) { order.forEach { putInt(it) } })
        }
        clusters.forEach(out::write)
        out.write(le(8 * clusters.size) { clusterAt.forEach { putLong(it) } })
        out.write(ByteArray(16))
        return out.toByteArray()
    }
}

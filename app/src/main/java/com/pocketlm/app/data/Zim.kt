package com.pocketlm.app.data

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** The file is not a ZIM file, or is damaged, or uses something this reader does not do. */
class ZimException(message: String) : IOException(message)

/** Random access to the bytes of a ZIM file. */
interface ZimSource {
    val size: Long

    /** [length] bytes from [position]; fewer only at the end of the file. */
    fun read(position: Long, length: Int): ByteArray
}

class ChannelZimSource(private val channel: FileChannel) : ZimSource {
    override val size: Long get() = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        val count = minOf(length.toLong(), size - position).coerceAtLeast(0).toInt()
        val buffer = ByteBuffer.allocate(count)
        var at = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) break
            at += n
        }
        return buffer.array().let { if (buffer.position() == it.size) it else it.copyOf(buffer.position()) }
    }
}

class BytesZimSource(private val bytes: ByteArray) : ZimSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position > bytes.size) return ByteArray(0)
        return bytes.copyOfRange(position.toInt(), minOf(bytes.size.toLong(), position + length).toInt())
    }
}

/**
 * A reader for ZIM files, the format Kiwix uses to keep Wikipedia and other sites for reading offline
 * (https://wiki.openzim.org/wiki/ZIM_file_format). Pages are found by their address or by title, and read out of
 * compressed clusters (Zstandard or xz). Nothing is loaded up front: a file of many gigabytes opens at once.
 */
class ZimFile private constructor(private val source: ZimSource, private val header: Header) {

    private class Header(
        val major: Int, val minor: Int, val entryCount: Int, val clusterCount: Int,
        val urlPointers: Long, val titlePointers: Long, val clusterPointers: Long, val mimeList: Long, val mainPage: Int, val checksum: Long,
    )

    /** One item of the file: a page or other file, or a redirect to another entry. */
    class Entry(
        val index: Int,
        val namespace: Char,
        val urlBytes: ByteArray,
        val titleBytes: ByteArray,
        val mimeType: String?,
        val cluster: Int,
        val blob: Int,
        /** For a redirect, the index of the entry it points to; otherwise -1. */
        val redirectTo: Int,
    ) {
        val isRedirect: Boolean get() = redirectTo >= 0
        val url: String get() = String(urlBytes, Charsets.UTF_8)

        /** The title; many entries have none and are known by their address. */
        val title: String get() = String(if (titleBytes.isEmpty()) urlBytes else titleBytes, Charsets.UTF_8)
    }

    /** The bytes of one item, read in pieces so a big one need not be loaded whole. */
    abstract class Blob {
        abstract val length: Int
        abstract fun read(offset: Int, count: Int): ByteArray
        fun all(): ByteArray = read(0, length)
    }

    private val mimeTypes: List<String> by lazy { readMimeTypes() }
    private val clusterCache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean = size > CACHED_CLUSTERS
    }

    val entryCount: Int get() = header.entryCount

    /** The namespace pages are in: "C" since version 6.1 of the format, "A" before. */
    val contentNamespace: Char get() = if (header.major > 6 || (header.major == 6 && header.minor >= 1)) 'C' else 'A'

    // ---- entries ---------------------------------------------------------------------------------

    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)

    private fun u64(b: ByteArray, p: Int): Long = u32(b, p) or (u32(b, p + 4) shl 32)

    private fun readU64(position: Long): Long = source.read(position, 8).also { if (it.size < 8) throw ZimException("The file is cut short.") }.let { u64(it, 0) }

    private fun readU32(position: Long): Long = source.read(position, 4).also { if (it.size < 4) throw ZimException("The file is cut short.") }.let { u32(it, 0) }

    private fun readMimeTypes(): List<String> {
        val raw = source.read(header.mimeList, MIME_LIST_BYTES)
        val out = ArrayList<String>()
        var start = 0
        for (i in raw.indices) {
            if (raw[i] == 0.toByte()) {
                if (i == start) break
                out += String(raw, start, i - start, Charsets.UTF_8)
                start = i + 1
            }
        }
        return out
    }

    /** The entry with this position in the list ordered by address. */
    fun entry(index: Int): Entry {
        if (index < 0 || index >= header.entryCount) throw ZimException("Entry $index does not exist.")
        val offset = readU64(header.urlPointers + 8L * index)
        var size = 512
        while (true) {
            val raw = source.read(offset, size)
            parse(index, raw)?.let { return it }
            if (raw.size < size || size >= MAX_ENTRY_BYTES) throw ZimException("An entry is damaged.")
            size *= 4
        }
    }

    /** The entry made from [raw], or null if more of the file is needed to see its whole address and title. */
    private fun parse(index: Int, raw: ByteArray): Entry? {
        if (raw.size < 16) return null
        val mime = u16(raw, 0)
        val redirect = mime == 0xFFFF
        if (mime == 0xFFFE || mime == 0xFFFD) return Entry(index, raw[3].toInt().toChar(), ByteArray(0), ByteArray(0), null, -1, -1, -1)
        val fixed = if (redirect) 12 else 16
        val namespace = raw[3].toInt().toChar()
        val urlEnd = (fixed until raw.size).firstOrNull { raw[it] == 0.toByte() } ?: return null
        val titleEnd = (urlEnd + 1 until raw.size).firstOrNull { raw[it] == 0.toByte() } ?: return null
        val url = raw.copyOfRange(fixed, urlEnd)
        val title = raw.copyOfRange(urlEnd + 1, titleEnd)
        return if (redirect) {
            Entry(index, namespace, url, title, null, -1, -1, u32(raw, 8).toInt())
        } else {
            val type = mimeTypes.getOrNull(mime)
            Entry(index, namespace, url, title, type, u32(raw, 8).toInt(), u32(raw, 12).toInt(), -1)
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun compareAddress(entry: Entry, namespace: Char, url: ByteArray): Int {
        val d = entry.namespace.code - namespace.code
        return if (d != 0) d else compare(entry.urlBytes, url)
    }

    /** The entry at this address (`Ada_Lovelace` in namespace [namespace]), or null. */
    fun find(namespace: Char, url: String): Entry? {
        val wanted = url.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = header.entryCount
        while (low < high) {
            val mid = (low + high) ushr 1
            val e = entry(mid)
            val c = compareAddress(e, namespace, wanted)
            if (c == 0) return e
            if (c < 0) low = mid + 1 else high = mid
        }
        return null
    }

    /** Follows redirects (at most a few) to the entry that holds the content. Null if they lead nowhere. */
    fun resolve(start: Entry): Entry? {
        var e = start
        repeat(MAX_REDIRECTS) {
            if (!e.isRedirect) return e
            e = entry(e.redirectTo)
        }
        return if (e.isRedirect) null else e
    }

    // ---- titles ----------------------------------------------------------------------------------

    /** Entry numbers in order of title, from the file's own list. Null if the file has none. */
    private val titleOrder: TitleOrder? by lazy {
        val listing = find('X', "listing/titleOrdered/v1")?.takeIf { !it.isRedirect }
        when {
            listing != null -> TitleOrder(blob(listing), includesNamespace = false)
            header.titlePointers != -1L && header.titlePointers > 0 ->
                TitleOrder(object : Blob() {
                    override val length: Int = (header.entryCount * 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    override fun read(offset: Int, count: Int) = source.read(header.titlePointers + offset, count)
                }, includesNamespace = true)
            else -> null
        }
    }

    private inner class TitleOrder(val list: Blob, val includesNamespace: Boolean) {
        val count: Int get() = list.length / 4

        fun entryAt(position: Int): Entry = entry(u32(list.read(position * 4, 4), 0).toInt())

        /** Compares the title of the entry at [position] with [wanted] (in the page namespace). */
        fun compareAt(position: Int, wanted: ByteArray): Int {
            val e = entryAt(position)
            if (includesNamespace) {
                val d = e.namespace.code - contentNamespace.code
                if (d != 0) return d
            }
            return compare(if (e.titleBytes.isEmpty()) e.urlBytes else e.titleBytes, wanted)
        }

        /** The first position whose title is not before [wanted]. */
        fun lowerBound(wanted: ByteArray): Int {
            var low = 0
            var high = count
            while (low < high) {
                val mid = (low + high) ushr 1
                if (compareAt(mid, wanted) < 0) low = mid + 1 else high = mid
            }
            return low
        }
    }

    /** True if titles can be searched (every file made since 2020 can). */
    val hasTitleIndex: Boolean get() = titleOrder != null

    /**
     * Pages whose title begins with [prefix], in title order (capital letters count: "Ada" and "ada" are different),
     * at most [limit] of them. They may be redirects.
     */
    fun searchTitles(prefix: String, limit: Int = 30): List<Entry> {
        val order = titleOrder ?: return emptyList()
        val wanted = prefix.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Entry>()
        var position = order.lowerBound(wanted)
        while (position < order.count && out.size < limit) {
            val e = order.entryAt(position)
            if (order.includesNamespace && e.namespace != contentNamespace) break
            val title = if (e.titleBytes.isEmpty()) e.urlBytes else e.titleBytes
            if (title.size < wanted.size || compare(title.copyOf(wanted.size), wanted) != 0) break
            out += e
            position++
        }
        return out
    }

    // ---- content ---------------------------------------------------------------------------------

    /** The content of [entry], which must not be a redirect. */
    fun blob(entry: Entry): Blob {
        if (entry.isRedirect || entry.cluster < 0) throw ZimException("That entry has no content.")
        if (entry.cluster >= header.clusterCount) throw ZimException("A cluster is missing.")
        val start = readU64(header.clusterPointers + 8L * entry.cluster)
        val end = if (entry.cluster + 1 < header.clusterCount) readU64(header.clusterPointers + 8L * (entry.cluster + 1)) else header.checksum
        if (end <= start + 1 || end > source.size) throw ZimException("A cluster is damaged.")
        val info = source.read(start, 1)[0].toInt() and 0xFF
        val extended = (info and 0x10) != 0
        val offsetSize = if (extended) 8 else 4
        return when (val compression = info and 0x0F) {
            0, 1 -> plainBlob(start + 1, end - start - 1, offsetSize, entry.blob)
            4, 5 -> {
                val data = synchronized(clusterCache) {
                    clusterCache.getOrPut(entry.cluster) { unpack(compression, source.read(start + 1, (end - start - 1).toInt()), start) }
                }
                blobIn(data, offsetSize, entry.blob)
            }
            else -> throw ZimException("This file compresses its pages in a way that is not supported.")
        }
    }

    /** The content of [entry] as bytes, refusing anything over [maxBytes]. */
    fun content(entry: Entry, maxBytes: Int = MAX_CONTENT_BYTES): ByteArray {
        val b = blob(entry)
        if (b.length > maxBytes) throw ZimException("That page is too large to read here.")
        return b.all()
    }

    private fun unpack(compression: Int, packed: ByteArray, at: Long): ByteArray = try {
        when (compression) {
            5 -> Zstd.decompress(packed, maxOutput = MAX_CLUSTER_BYTES)
            else -> XZInputStream(ByteArrayInputStream(packed)).use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_CLUSTER_BYTES) throw ZimException("A cluster is unexpectedly large.")
                }
                out.toByteArray()
            }
        }
    } catch (e: ZstdException) {
        throw ZimException("A cluster at $at could not be decompressed: ${e.message}")
    } catch (e: IOException) {
        if (e is ZimException) throw e
        throw ZimException("A cluster at $at could not be decompressed: ${e.message}")
    }

    private fun offsetAt(table: (Int, Int) -> ByteArray, size: Int, index: Int): Long {
        val raw = table(index * size, size)
        if (raw.size < size) throw ZimException("A cluster is damaged.")
        return if (size == 8) u64(raw, 0) else u32(raw, 0)
    }

    private fun blobIn(data: ByteArray, offsetSize: Int, number: Int): Blob {
        fun read(position: Int, count: Int) = data.copyOfRange(position.coerceAtMost(data.size), (position + count).coerceAtMost(data.size))
        val first = offsetAt(::read, offsetSize, 0)
        val offsets = (first / offsetSize).toInt()
        if (number < 0 || number + 1 >= offsets) throw ZimException("A page is missing from its cluster.")
        val from = offsetAt(::read, offsetSize, number)
        val to = offsetAt(::read, offsetSize, number + 1)
        if (to < from || to > data.size) throw ZimException("A cluster is damaged.")
        return object : Blob() {
            override val length: Int = (to - from).toInt()
            override fun read(offset: Int, count: Int) = data.copyOfRange(from.toInt() + offset, from.toInt() + minOf(offset + count, length))
        }
    }

    /** A page of a cluster stored without compression: only the part asked for is read from the file. */
    private fun plainBlob(dataStart: Long, dataSize: Long, offsetSize: Int, number: Int): Blob {
        fun read(position: Int, count: Int) = source.read(dataStart + position, count)
        val first = offsetAt(::read, offsetSize, 0)
        val offsets = (first / offsetSize).toInt()
        if (number < 0 || number + 1 >= offsets) throw ZimException("A page is missing from its cluster.")
        val from = offsetAt(::read, offsetSize, number)
        val to = offsetAt(::read, offsetSize, number + 1)
        if (to < from || to > dataSize) throw ZimException("A cluster is damaged.")
        return object : Blob() {
            override val length: Int = (to - from).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            override fun read(offset: Int, count: Int): ByteArray = source.read(dataStart + from + offset, minOf(count, length - offset).coerceAtLeast(0))
        }
    }

    companion object {
        private const val MAGIC = 0x44D495AL
        private const val HEADER_BYTES = 80
        private const val MIME_LIST_BYTES = 4096
        private const val MAX_ENTRY_BYTES = 64 * 1024
        private const val MAX_REDIRECTS = 8
        private const val CACHED_CLUSTERS = 3
        const val MAX_CONTENT_BYTES = 8 * 1024 * 1024
        private const val MAX_CLUSTER_BYTES = 128 * 1024 * 1024

        fun open(source: ZimSource): ZimFile {
            val raw = source.read(0, HEADER_BYTES)
            if (raw.size < HEADER_BYTES) throw ZimException("That is not a ZIM file.")
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            if ((b.getInt(0).toLong() and 0xFFFFFFFFL) != MAGIC) throw ZimException("That is not a ZIM file.")
            val header = Header(
                major = b.getShort(4).toInt() and 0xFFFF,
                minor = b.getShort(6).toInt() and 0xFFFF,
                entryCount = b.getInt(24),
                clusterCount = b.getInt(28),
                urlPointers = b.getLong(32),
                titlePointers = b.getLong(40),
                clusterPointers = b.getLong(48),
                mimeList = b.getLong(56),
                mainPage = b.getInt(64),
                checksum = b.getLong(72),
            )
            if (header.major !in 5..6) throw ZimException("This ZIM file has a version (${header.major}) that is not supported.")
            if (header.entryCount <= 0 || header.clusterCount < 0) throw ZimException("That ZIM file is empty or damaged.")
            if (header.urlPointers <= 0 || header.urlPointers >= source.size || header.clusterPointers <= 0 || header.clusterPointers >= source.size) {
                throw ZimException("That ZIM file is damaged.")
            }
            return ZimFile(source, header)
        }
    }
}

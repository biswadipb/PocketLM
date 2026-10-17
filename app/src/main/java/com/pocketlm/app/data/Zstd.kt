package com.pocketlm.app.data

import java.io.IOException

/** The data is not valid Zstandard, or uses something this decoder does not do (dictionaries). */
class ZstdException(message: String) : IOException(message)

/**
 * A decoder for Zstandard (RFC 8878), the compression used inside Kiwix's ZIM files. It is written in plain Kotlin so no
 * native library is needed, and handles everything except dictionaries: raw, run-length and compressed blocks, Huffman
 * and FSE coding, repeated offsets, several frames in a row and skippable frames. The checksum at the end of a frame is
 * skipped rather than checked.
 */
object Zstd {
    const val DEFAULT_MAX_OUTPUT = 256 * 1024 * 1024

    private const val MAGIC = 0xFD2FB528L
    private const val SKIPPABLE_LOW = 0x184D2A50L
    private const val MAX_BLOCK = 128 * 1024

    /** Every frame in [src] from [from] up to [to], decompressed and joined. */
    fun decompress(src: ByteArray, from: Int = 0, to: Int = src.size, maxOutput: Int = DEFAULT_MAX_OUTPUT): ByteArray = try {
        decode(src, from, to, maxOutput)
    } catch (e: IndexOutOfBoundsException) {
        throw ZstdException("The data is damaged")
    } catch (e: NegativeArraySizeException) {
        throw ZstdException("The data is damaged")
    } catch (e: ArithmeticException) {
        throw ZstdException("The data is damaged")
    }

    private fun decode(src: ByteArray, from: Int, to: Int, maxOutput: Int): ByteArray {
        val out = Out(maxOutput)
        var pos = from
        while (pos < to) {
            if (to - pos < 4) throw ZstdException("Data after the last frame")
            val magic = u32(src, pos)
            when {
                magic == MAGIC -> pos = frame(src, pos + 4, to, out)
                magic and 0xFFFFFFF0L == SKIPPABLE_LOW -> {
                    if (to - pos < 8) throw ZstdException("A skippable frame is cut short")
                    val size = u32(src, pos + 4)
                    if (size > to - pos - 8) throw ZstdException("A skippable frame is cut short")
                    pos += 8 + size.toInt()
                }
                else -> throw ZstdException("Not Zstandard data")
            }
        }
        return out.toArray()
    }

    // ---- small helpers ---------------------------------------------------------------------------

    private fun u8(b: ByteArray, p: Int): Int {
        if (p >= b.size) throw ZstdException("The data is cut short")
        return b[p].toInt() and 0xFF
    }

    private fun u16(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8)

    private fun u32(b: ByteArray, p: Int): Long =
        (u8(b, p).toLong()) or (u8(b, p + 1).toLong() shl 8) or (u8(b, p + 2).toLong() shl 16) or (u8(b, p + 3).toLong() shl 24)

    private fun highBit(v: Int): Int = 31 - Integer.numberOfLeadingZeros(v)

    /** The output so far, which later matches copy from. */
    private class Out(private val limit: Int) {
        var buf = ByteArray(1 shl 16)
        var size = 0

        fun ensure(extra: Int) {
            val need = size.toLong() + extra
            if (need > limit) throw ZstdException("The data expands to more than $limit bytes")
            if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2L).coerceAtMost(limit.toLong()).toInt())
        }

        fun toArray(): ByteArray = buf.copyOf(size)
    }

    // ---- reading bits ---------------------------------------------------------------------------

    /** Bits taken from the end of a byte range towards its start; the last byte carries a marker bit that ends the data. */
    private class BackBits(private val data: ByteArray, private val start: Int, end: Int) {
        /** How many bits are still unread. Goes below zero when more is asked for than there is. */
        var pos: Long

        init {
            if (end <= start) throw ZstdException("An empty bit stream")
            val last = data[end - 1].toInt() and 0xFF
            if (last == 0) throw ZstdException("A bit stream has no end marker")
            pos = (end - 1 - start) * 8L + highBit(last)
        }

        private fun extract(bitIndex: Long, n: Int): Long {
            val byteIndex = start + (bitIndex ushr 3).toInt()
            val shift = (bitIndex and 7).toInt()
            var v = 0L
            for (k in 0 until 5) {
                val i = byteIndex + k
                if (i < data.size) v = v or ((data[i].toLong() and 0xFF) shl (8 * k))
            }
            return (v ushr shift) and ((1L shl n) - 1)
        }

        /** The next [n] bits without taking them (the first bit is the highest). */
        fun peek(n: Int): Long {
            if (n == 0 || pos <= 0) return 0
            val low = pos - n
            return if (low >= 0) extract(low, n) else extract(0, pos.toInt()) shl (-low).toInt()
        }

        fun skip(n: Int) {
            pos -= n
        }

        fun read(n: Int): Long {
            val v = peek(n)
            pos -= n
            return v
        }

        val overflowed: Boolean get() = pos < 0
    }

    /** Bits taken from the start of a byte range, lowest first. */
    private class FwdBits(private val data: ByteArray, private val start: Int, private val end: Int) {
        var bit = 0

        fun peek(n: Int): Int {
            var v = 0L
            val byteIndex = start + (bit ushr 3)
            for (k in 0 until 5) {
                val i = byteIndex + k
                if (i < end) v = v or ((data[i].toLong() and 0xFF) shl (8 * k))
            }
            return ((v ushr (bit and 7)) and ((1L shl n) - 1)).toInt()
        }

        fun read(n: Int): Int = peek(n).also { bit += n }

        fun skip(n: Int) {
            bit += n
        }

        /** Bytes used so far, rounding a part-used byte up. */
        fun bytesUsed(): Int = (bit + 7) ushr 3
    }

    // ---- finite state entropy tables ---------------------------------------------------------------

    private class Fse(val tableLog: Int, val symbol: IntArray, val nbBits: IntArray, val baseline: IntArray)

    private fun buildFse(counts: IntArray, symbols: Int, tableLog: Int): Fse {
        val size = 1 shl tableLog
        val symbol = IntArray(size)
        val next = IntArray(symbols)
        var high = size - 1
        for (s in 0 until symbols) {
            if (counts[s] == -1) {
                symbol[high--] = s
                next[s] = 1
            } else {
                next[s] = counts[s]
            }
        }
        val step = (size shr 1) + (size shr 3) + 3
        val mask = size - 1
        var position = 0
        for (s in 0 until symbols) {
            for (i in 0 until counts[s].coerceAtLeast(0)) {
                symbol[position] = s
                position = (position + step) and mask
                while (position > high) position = (position + step) and mask
            }
        }
        if (position != 0) throw ZstdException("A coding table is inconsistent")
        val nbBits = IntArray(size)
        val baseline = IntArray(size)
        for (u in 0 until size) {
            val s = symbol[u]
            val nextState = next[s]++
            val bits = tableLog - highBit(nextState)
            nbBits[u] = bits
            baseline[u] = (nextState shl bits) - size
        }
        return Fse(tableLog, symbol, nbBits, baseline)
    }

    private class ReadTable(val fse: Fse, val symbols: Int, val used: Int)

    /** Reads a table description that starts at [pos]. */
    private fun readFseTable(data: ByteArray, pos: Int, end: Int, maxSymbol: Int, maxLog: Int): ReadTable {
        val r = FwdBits(data, pos, end)
        val accuracy = r.read(4) + 5
        if (accuracy > maxLog) throw ZstdException("A coding table is too large")
        var remaining = (1 shl accuracy) + 1
        var threshold = 1 shl accuracy
        var nbBits = accuracy + 1
        val counts = IntArray(maxSymbol + 1)
        var symbol = 0
        var previousZero = false
        while (remaining > 1 && symbol <= maxSymbol) {
            if (previousZero) {
                var zeros = 0
                while (true) {
                    val v = r.read(2)
                    zeros += v
                    if (v != 3) break
                }
                if (symbol + zeros > maxSymbol + 1) throw ZstdException("A coding table lists too many symbols")
                symbol += zeros
                previousZero = false
                if (symbol > maxSymbol) break
            }
            val max = (2 * threshold - 1) - remaining
            val low = r.peek(nbBits - 1)
            val count: Int
            if (low < max) {
                count = low
                r.skip(nbBits - 1)
            } else {
                var v = r.peek(nbBits) and (2 * threshold - 1)
                if (v >= threshold) v -= max
                count = v
                r.skip(nbBits)
            }
            val probability = count - 1
            remaining -= if (probability < 0) -probability else probability
            counts[symbol++] = probability
            previousZero = probability == 0
            while (remaining < threshold) {
                nbBits--
                threshold = threshold shr 1
            }
        }
        if (remaining != 1) throw ZstdException("A coding table does not add up")
        if (pos + r.bytesUsed() > end) throw ZstdException("The data is cut short")
        return ReadTable(buildFse(counts, symbol, accuracy), symbol, r.bytesUsed())
    }

    // ---- Huffman coded literals ------------------------------------------------------------------

    private class Huff(val maxBits: Int, val symbol: ByteArray, val bits: ByteArray)

    private fun buildHuff(weights: IntArray, count: Int): Huff {
        var total = 0L
        for (i in 0 until count) if (weights[i] > 0) total += 1L shl (weights[i] - 1)
        if (total == 0L) throw ZstdException("A Huffman table has no symbols")
        val maxBits = highBit(total.toInt()) + 1
        if (maxBits > 11) throw ZstdException("A Huffman table is too deep")
        val rest = (1L shl maxBits) - total
        if (rest == 0L || (rest and (rest - 1)) != 0L) throw ZstdException("A Huffman table does not add up")
        val lastWeight = highBit(rest.toInt()) + 1
        val all = weights.copyOf(count + 1)
        all[count] = lastWeight
        val symbols = count + 1

        val rankCount = IntArray(maxBits + 2)
        for (i in 0 until symbols) rankCount[all[i]]++
        val rankStart = IntArray(maxBits + 2)
        var next = 0
        for (w in 1..maxBits) {
            rankStart[w] = next
            next += rankCount[w] shl (w - 1)
        }
        val size = 1 shl maxBits
        val symbol = ByteArray(size)
        val bits = ByteArray(size)
        for (s in 0 until symbols) {
            val w = all[s]
            if (w == 0) continue
            val length = 1 shl (w - 1)
            val from = rankStart[w]
            for (u in from until from + length) {
                symbol[u] = s.toByte()
                bits[u] = (maxBits + 1 - w).toByte()
            }
            rankStart[w] += length
        }
        return Huff(maxBits, symbol, bits)
    }

    /** Reads the weights of a Huffman table starting at [pos]; returns the table and the position after it. */
    private fun readHuffTable(src: ByteArray, pos: Int, end: Int): Pair<Huff, Int> {
        val header = u8(src, pos)
        val weights = IntArray(256)
        val count: Int
        val after: Int
        if (header >= 128) {
            count = header - 127
            val bytes = (count + 1) / 2
            if (pos + 1 + bytes > end) throw ZstdException("The data is cut short")
            for (i in 0 until count) {
                val b = u8(src, pos + 1 + i / 2)
                weights[i] = if (i % 2 == 0) b shr 4 else b and 15
            }
            after = pos + 1 + bytes
        } else {
            val size = header
            if (size == 0 || pos + 1 + size > end) throw ZstdException("The data is cut short")
            val table = readFseTable(src, pos + 1, pos + 1 + size, 255, 6)
            val bits = BackBits(src, pos + 1 + table.used, pos + 1 + size)
            val fse = table.fse
            var s1 = bits.read(fse.tableLog).toInt()
            var s2 = bits.read(fse.tableLog).toInt()
            var n = 0
            fun emit(symbol: Int) {
                if (n >= 255) throw ZstdException("A Huffman table lists too many symbols")
                weights[n++] = symbol
            }
            while (true) {
                emit(fse.symbol[s1])
                s1 = fse.baseline[s1] + bits.read(fse.nbBits[s1]).toInt()
                if (bits.overflowed) {
                    emit(fse.symbol[s2])
                    break
                }
                emit(fse.symbol[s2])
                s2 = fse.baseline[s2] + bits.read(fse.nbBits[s2]).toInt()
                if (bits.overflowed) {
                    emit(fse.symbol[s1])
                    break
                }
            }
            count = n
            after = pos + 1 + size
        }
        if (count == 0) throw ZstdException("A Huffman table is empty")
        for (i in 0 until count) if (weights[i] > 12) throw ZstdException("A Huffman weight is out of range")
        return buildHuff(weights, count) to after
    }

    private fun huffDecode(huff: Huff, src: ByteArray, start: Int, end: Int, out: ByteArray, outFrom: Int, count: Int) {
        val bits = BackBits(src, start, end)
        for (i in 0 until count) {
            val index = bits.peek(huff.maxBits).toInt()
            out[outFrom + i] = huff.symbol[index]
            bits.skip(huff.bits[index].toInt())
        }
        if (bits.overflowed) throw ZstdException("A Huffman stream ran out of data")
    }

    // ---- one frame -------------------------------------------------------------------------------

    private class State {
        var huff: Huff? = null
        var ll: Fse? = null
        var of: Fse? = null
        var ml: Fse? = null
        var rep0 = 1
        var rep1 = 4
        var rep2 = 8
    }

    private fun frame(src: ByteArray, startPos: Int, end: Int, out: Out): Int {
        var pos = startPos
        val descriptor = u8(src, pos++)
        val contentSizeFlag = descriptor ushr 6
        val singleSegment = (descriptor and 0x20) != 0
        val checksum = (descriptor and 0x04) != 0
        val dictionaryFlag = descriptor and 3
        if ((descriptor and 0x08) != 0) throw ZstdException("Unsupported frame")
        if (!singleSegment) pos++ // the window size; the whole output is kept, so it is not needed
        val dictionaryBytes = intArrayOf(0, 1, 2, 4)[dictionaryFlag]
        var dictionary = 0L
        for (k in 0 until dictionaryBytes) dictionary = dictionary or (u8(src, pos + k).toLong() shl (8 * k))
        pos += dictionaryBytes
        if (dictionary != 0L) throw ZstdException("This data needs a dictionary, which is not supported")
        val sizeBytes = when (contentSizeFlag) {
            0 -> if (singleSegment) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        pos += sizeBytes

        val state = State()
        while (true) {
            if (pos + 3 > end) throw ZstdException("The data is cut short")
            val header = u8(src, pos) or (u8(src, pos + 1) shl 8) or (u8(src, pos + 2) shl 16)
            pos += 3
            val last = (header and 1) != 0
            val type = (header ushr 1) and 3
            val size = header ushr 3
            when (type) {
                0 -> {
                    if (size > MAX_BLOCK || pos + size > end) throw ZstdException("The data is cut short")
                    out.ensure(size)
                    System.arraycopy(src, pos, out.buf, out.size, size)
                    out.size += size
                    pos += size
                }
                1 -> {
                    if (size > MAX_BLOCK || pos >= end) throw ZstdException("The data is cut short")
                    out.ensure(size)
                    java.util.Arrays.fill(out.buf, out.size, out.size + size, src[pos])
                    out.size += size
                    pos += 1
                }
                2 -> {
                    if (size > MAX_BLOCK || pos + size > end) throw ZstdException("The data is cut short")
                    block(src, pos, pos + size, out, state)
                    pos += size
                }
                else -> throw ZstdException("A block has a type that does not exist")
            }
            if (last) break
        }
        if (checksum) pos += 4
        if (pos > end) throw ZstdException("The data is cut short")
        return pos
    }

    private fun block(src: ByteArray, start: Int, end: Int, out: Out, st: State) {
        var pos = start

        // -- literals
        val first = u8(src, pos)
        val type = first and 3
        val sizeFormat = (first ushr 2) and 3
        val literals: ByteArray
        when (type) {
            0, 1 -> {
                val regen: Int
                when (sizeFormat) {
                    0, 2 -> {
                        regen = first ushr 3
                        pos += 1
                    }
                    1 -> {
                        regen = (first ushr 4) or (u8(src, pos + 1) shl 4)
                        pos += 2
                    }
                    else -> {
                        regen = (first ushr 4) or (u8(src, pos + 1) shl 4) or (u8(src, pos + 2) shl 12)
                        pos += 3
                    }
                }
                if (regen > MAX_BLOCK) throw ZstdException("A block of literals is too large")
                if (type == 0) {
                    if (pos + regen > end) throw ZstdException("The data is cut short")
                    literals = src.copyOfRange(pos, pos + regen)
                    pos += regen
                } else {
                    val b = u8(src, pos)
                    literals = ByteArray(regen) { b.toByte() }
                    pos += 1
                }
            }
            else -> {
                val headerBytes = if (sizeFormat == 0 || sizeFormat == 1) 3 else if (sizeFormat == 2) 4 else 5
                var h = 0L
                for (k in 0 until headerBytes) h = h or (u8(src, pos + k).toLong() shl (8 * k))
                val regen: Int
                val compressed: Int
                when (sizeFormat) {
                    0, 1 -> {
                        regen = ((h ushr 4) and 0x3FF).toInt()
                        compressed = ((h ushr 14) and 0x3FF).toInt()
                    }
                    2 -> {
                        regen = ((h ushr 4) and 0x3FFF).toInt()
                        compressed = ((h ushr 18) and 0x3FFF).toInt()
                    }
                    else -> {
                        regen = ((h ushr 4) and 0x3FFFF).toInt()
                        compressed = ((h ushr 22) and 0x3FFFF).toInt()
                    }
                }
                if (regen > MAX_BLOCK) throw ZstdException("A block of literals is too large")
                pos += headerBytes
                val sectionEnd = pos + compressed
                if (sectionEnd > end) throw ZstdException("The data is cut short")
                var streamsAt = pos
                if (type == 2) {
                    val (table, after) = readHuffTable(src, pos, sectionEnd)
                    st.huff = table
                    streamsAt = after
                }
                val huff = st.huff ?: throw ZstdException("Literals refer to a Huffman table that was never sent")
                literals = ByteArray(regen)
                if (sizeFormat == 0) {
                    huffDecode(huff, src, streamsAt, sectionEnd, literals, 0, regen)
                } else {
                    if (streamsAt + 6 > sectionEnd) throw ZstdException("The data is cut short")
                    val size1 = u16(src, streamsAt)
                    val size2 = u16(src, streamsAt + 2)
                    val size3 = u16(src, streamsAt + 4)
                    val a = streamsAt + 6
                    val b = a + size1
                    val c = b + size2
                    val d = c + size3
                    if (d > sectionEnd) throw ZstdException("The data is cut short")
                    val each = (regen + 3) / 4
                    val rest = regen - 3 * each
                    if (rest < 0) throw ZstdException("Corrupt literals")
                    huffDecode(huff, src, a, b, literals, 0, each)
                    huffDecode(huff, src, b, c, literals, each, each)
                    huffDecode(huff, src, c, d, literals, 2 * each, each)
                    huffDecode(huff, src, d, sectionEnd, literals, 3 * each, rest)
                }
                pos = sectionEnd
            }
        }

        // -- sequences
        if (pos >= end) throw ZstdException("The data is cut short")
        var sequences = u8(src, pos++)
        if (sequences == 0) {
            out.ensure(literals.size)
            System.arraycopy(literals, 0, out.buf, out.size, literals.size)
            out.size += literals.size
            return
        }
        if (sequences >= 128) {
            sequences = if (sequences < 255) ((sequences - 128) shl 8) + u8(src, pos++) else {
                val v = u8(src, pos) + (u8(src, pos + 1) shl 8) + 0x7F00
                pos += 2
                v
            }
        }
        val modes = u8(src, pos++)
        st.ll = chooseTable((modes ushr 6) and 3, src, pos, end, st.ll, LL_DEFAULT, 35, 9).also { pos += it.second }.first
        st.of = chooseTable((modes ushr 4) and 3, src, pos, end, st.of, OF_DEFAULT, 31, 8).also { pos += it.second }.first
        st.ml = chooseTable((modes ushr 2) and 3, src, pos, end, st.ml, ML_DEFAULT, 52, 9).also { pos += it.second }.first
        val ll = st.ll!!
        val of = st.of!!
        val ml = st.ml!!

        val bits = BackBits(src, pos, end)
        var llState = bits.read(ll.tableLog).toInt()
        var ofState = bits.read(of.tableLog).toInt()
        var mlState = bits.read(ml.tableLog).toInt()

        var litPos = 0
        for (i in 0 until sequences) {
            val ofCode = of.symbol[ofState]
            val mlCode = ml.symbol[mlState]
            val llCode = ll.symbol[llState]
            if (ofCode > 31) throw ZstdException("An offset is too large")
            val offsetValue = (1L shl ofCode) + bits.read(ofCode)
            val matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode]).toInt()
            val literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode]).toInt()
            if (i < sequences - 1) {
                llState = ll.baseline[llState] + bits.read(ll.nbBits[llState]).toInt()
                mlState = ml.baseline[mlState] + bits.read(ml.nbBits[mlState]).toInt()
                ofState = of.baseline[ofState] + bits.read(of.nbBits[ofState]).toInt()
            }
            if (bits.overflowed) throw ZstdException("The sequences ran out of data")

            val offset: Int
            if (offsetValue > 3) {
                offset = (offsetValue - 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                st.rep2 = st.rep1
                st.rep1 = st.rep0
                st.rep0 = offset
            } else {
                val index = offsetValue.toInt() - 1 + (if (literalLength == 0) 1 else 0)
                offset = when (index) {
                    0 -> st.rep0
                    1 -> st.rep1
                    2 -> st.rep2
                    else -> st.rep0 - 1
                }
                if (offset <= 0) throw ZstdException("A repeated offset is zero")
                if (index == 1) {
                    st.rep1 = st.rep0
                    st.rep0 = offset
                } else if (index >= 2) {
                    st.rep2 = st.rep1
                    st.rep1 = st.rep0
                    st.rep0 = offset
                }
            }

            if (litPos + literalLength > literals.size) throw ZstdException("Sequences use more literals than there are")
            out.ensure(literalLength + matchLength)
            System.arraycopy(literals, litPos, out.buf, out.size, literalLength)
            out.size += literalLength
            litPos += literalLength
            if (offset > out.size) throw ZstdException("A match points before the start of the data")
            var from = out.size - offset
            val buf = out.buf
            var to = out.size
            for (k in 0 until matchLength) buf[to++] = buf[from++]
            out.size = to
        }
        if (bits.pos != 0L) throw ZstdException("The sequences left data unread")
        val remaining = literals.size - litPos
        out.ensure(remaining)
        System.arraycopy(literals, litPos, out.buf, out.size, remaining)
        out.size += remaining
    }

    /** One of the three sequence tables, as the block's mode says; returns it with the number of bytes it took. */
    private fun chooseTable(
        mode: Int, src: ByteArray, pos: Int, end: Int, previous: Fse?, default: Fse, maxSymbol: Int, maxLog: Int,
    ): Pair<Fse, Int> {
        return when (mode) {
            0 -> default to 0
            1 -> {
                val symbol = u8(src, pos)
                if (symbol > maxSymbol) throw ZstdException("A symbol is out of range")
                Fse(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0)) to 1
            }
            2 -> {
                val table = readFseTable(src, pos, end, maxSymbol, maxLog)
                table.fse to table.used
            }
            else -> (previous ?: throw ZstdException("A block repeats a table that was never sent")) to 0
        }
    }

    // ---- the fixed tables ----------------------------------------------------------------------------

    // The distributions the format fixes for blocks that do not send their own (RFC 8878, section 3.1.1.3.2.2).
    private val LL_COUNTS = IntArray(36) {
        when {
            it == 0 -> 4
            it == 1 -> 3
            it in 2..12 -> 2
            it in 13..15 -> 1
            it in 16..24 -> 2
            it == 25 -> 3
            it == 26 -> 2
            it in 27..31 -> 1
            else -> -1
        }
    }
    private val ML_COUNTS = IntArray(53) {
        when {
            it == 0 -> 1
            it == 1 -> 4
            it == 2 -> 3
            it in 3..8 -> 2
            it in 9..45 -> 1
            else -> -1
        }
    }
    private val OF_COUNTS = IntArray(29) {
        when {
            it < 6 -> 1
            it < 9 -> 2
            it < 24 -> 1
            else -> -1
        }
    }

    private val LL_DEFAULT: Fse by lazy { buildFse(LL_COUNTS, LL_COUNTS.size, 6) }
    private val ML_DEFAULT: Fse by lazy { buildFse(ML_COUNTS, ML_COUNTS.size, 6) }
    private val OF_DEFAULT: Fse by lazy { buildFse(OF_COUNTS, OF_COUNTS.size, 5) }

    private val LL_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private val LL_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
        8192, 16384, 32768, 65536,
    )
    private val ML_BITS = IntArray(53) { code ->
        when {
            code < 32 -> 0
            else -> intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)[code - 32]
        }
    }
    private val ML_BASE = IntArray(53) { code ->
        when {
            code < 32 -> code + 3
            else -> intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)[code - 32]
        }
    }
}

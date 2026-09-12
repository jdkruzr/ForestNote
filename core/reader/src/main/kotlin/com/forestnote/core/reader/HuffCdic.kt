package com.forestnote.core.reader

/** Bounded HUFF/CDIC validation. Resolves encoded symbols to expanded lengths, not whole-book
 * strings. Memoized lengths prevent repeated nested phrases from causing exponential work.
 * The existing renderer owns reconstruction of text. Format references and limits: README.
 */
internal class HuffCdic private constructor(
    private val fast: LongArray, private val minimum: LongArray, private val maximum: LongArray,
    private val symbols: List<Symbol>, private val checkpoint: suspend () -> Unit,
) {
    private data class Symbol(val bytes: ByteArray, val offset: Int, val length: Int, val literal: Boolean)
    private val lengths = IntArray(symbols.size) { -1 }
    private val visiting = BooleanArray(symbols.size)
    private var work = 0

    companion object {
        const val RECORD_BUDGET = 1024 * 1024
        const val DICTIONARY_BUDGET = 8 * 1024 * 1024
        const val SYMBOL_BUDGET = 262144
        const val WORK_BUDGET = 2 * 1024 * 1024 // Tokens per text record, including first-use phrase expansion.
        const val DEPTH_BUDGET = 32

        suspend fun load(count: Int, read: (Int) -> ByteArray, checkpoint: suspend () -> Unit = {}): HuffCdic {
            require(count in 2..1024) { "Invalid HUFF/CDIC record count" }
            var storedBytes = 0
            fun bounded(index: Int): ByteArray = read(index).also {
                require(it.size <= RECORD_BUDGET && storedBytes <= DICTIONARY_BUDGET - it.size) { "HUFF/CDIC dictionary byte budget exceeded" }
                storedBytes += it.size
            }
            val huff = bounded(0)
            require(huff.size >= 24 && magic(huff, "HUFF") && u32(huff, 4) == 24L) { "Invalid HUFF header" }
            val offset1 = u32(huff, 8); val offset2 = u32(huff, 12)
            require(offset1 >= 24 && offset1 <= huff.size - 1024 && offset2 >= 24 && offset2 <= huff.size - 256 &&
                (offset1 + 1024 <= offset2 || offset2 + 256 <= offset1)) { "Invalid HUFF table bounds" }
            val fast = LongArray(256) { u32(huff, offset1.toInt() + it * 4) }
            for (entry in fast) {
                val bits = (entry and 31).toInt()
                require(bits in 1..31 && (bits > 8 || entry and 128L != 0L)) { "Invalid HUFF prefix code length" }
                require(entry ushr 8 < (1L shl bits)) { "Invalid HUFF prefix maximum" }
            }
            val minimum = LongArray(33); val maximum = LongArray(33)
            for (bits in 1..32) {
                minimum[bits] = u32(huff, offset2.toInt() + (bits - 1) * 8)
                maximum[bits] = u32(huff, offset2.toInt() + (bits - 1) * 8 + 4)
                // Unused min/max slots can contain sentinels. Validate ranges when selected.
            }
            val symbols = ArrayList<Symbol>()
            var declared = -1L; var partitionBits = -1L
            for (i in 1 until count) {
                checkpoint()
                val cdic = bounded(i)
                require(cdic.size >= 16 && magic(cdic, "CDIC") && u32(cdic, 4) == 16L) { "Invalid CDIC header" }
                val total = u32(cdic, 8); val bits = u32(cdic, 12)
                require(total in 1..SYMBOL_BUDGET.toLong() && bits in 0..16) { "CDIC symbol/code-length budget exceeded" }
                if (declared < 0) { declared = total; partitionBits = bits }
                require(total == declared && bits == partitionBits && symbols.size < total) { "Inconsistent or excess CDIC records" }
                val n = minOf(1L shl bits.toInt(), total - symbols.size).toInt()
                require(n <= (cdic.size - 16) / 2) { "Truncated CDIC offset table" }
                repeat(n) { index ->
                    val offset = u16(cdic, 16 + index * 2)
                    require(offset >= n * 2 && offset <= cdic.size - 18) { "CDIC phrase overlaps table or exceeds record" }
                    val field = u16(cdic, 16 + offset)
                    val length = field and 32767
                    require(length <= cdic.size - 18 - offset) { "Truncated CDIC phrase" }
                    symbols.add(Symbol(cdic, 18 + offset, length, field and 32768 != 0))
                }
            }
            require(symbols.size.toLong() == declared) { "Missing CDIC dictionary entries" }
            return HuffCdic(fast, minimum, maximum, symbols, checkpoint)
        }

        private fun magic(bytes: ByteArray, name: String) = String(bytes, 0, 4, Charsets.US_ASCII) == name
        private fun u16(b: ByteArray, at: Int): Int {
            require(at >= 0 && at <= b.size - 2) { "Truncated HUFF/CDIC field" }
            return ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)
        }
        private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
    }

    suspend fun expandedLength(bytes: ByteArray, end: Int, limit: Int): Int {
        require(end in 0..minOf(bytes.size, 65536) && limit in 1..4096)
        work = 0
        return decode(bytes, 0, end, limit, 0)
    }

    private suspend fun decode(bytes: ByteArray, start: Int, size: Int, limit: Int, depth: Int): Int {
        require(depth <= DEPTH_BUDGET) { "HUFF/CDIC nesting budget exceeded" }
        var bit = 0; var output = 0
        val bitLength = size * 8
        while (bit < bitLength) {
            require(++work <= WORK_BUDGET) { "HUFF/CDIC work budget exceeded" }
            if (work and 1023 == 1) checkpoint()
            // At most five byte reads, including zero padding for an incomplete final code.
            var window = 0L
            for (i in 0..4) {
                val at = (bit ushr 3) + i
                window = (window shl 8) or if (at < size) (bytes[start + at].toLong() and 255) else 0L
            }
            val code = (window ushr (8 - (bit and 7))) and 0xffffffffL
            val entry = fast[(code ushr 24).toInt()]
            var bits = (entry and 31).toInt()
            var max = entry ushr 8
            if (entry and 128L == 0L) {
                while (bits <= 32 && (code ushr (32 - bits)) < minimum[bits]) bits++
                require(bits <= 32) { "HUFF code has no decoding range" }
                max = maximum[bits]
                require(minimum[bits] <= max && max < (1L shl bits)) { "Invalid HUFF code range" }
            }
            // MOBI pads the last byte; do not invent a symbol from an incomplete final code.
            if (bits > bitLength - bit) break
            bit += bits
            val index = max - (code ushr (32 - bits))
            require(index >= 0 && index < symbols.size) { "HUFF dictionary reference out of range" }
            val length = symbolLength(index.toInt(), depth + 1)
            require(length <= limit - output) { "HUFF/CDIC expansion budget exceeded" }
            output += length
        }
        return output
    }

    private suspend fun symbolLength(index: Int, depth: Int): Int {
        require(!visiting[index]) { "Cyclic CDIC dictionary reference" }
        val symbol = symbols[index]
        if (symbol.literal) return symbol.length
        if (lengths[index] >= 0) return lengths[index]
        visiting[index] = true
        return try {
            decode(symbol.bytes, symbol.offset, symbol.length, 4096, depth).also { lengths[index] = it }
        } finally { visiting[index] = false }
    }
}

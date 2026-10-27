// Port of src/measure/image.rs (T137): what an `image` content block costs. Pixel size comes from the PNG `IHDR` or
// the JPEG `SOF` header (fixed-offset parse, no image dependency); tokens follow the provider's published formula.
@file:Suppress("MagicNumber")

package rtok.measure

object ImageCost {
    /** HEADER_B64: base64 characters decoded to find the header (a JPEG `SOF` sits after EXIF/ICC segments). */
    private const val HEADER_B64 = 64 * 1024

    /** Anthropic vision docs: `⌈w/28⌉ × ⌈h/28⌉` tokens; high-resolution tier long edge 2576 px, 4784 tokens. */
    private const val PATCH = 28L
    private const val LONG_EDGE = 2576L
    private const val MAX_TOKENS = 4784L

    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 0x1a, 10)
    private val IHDR = "IHDR".encodeToByteArray()

    /** `tokens`: estimated visual tokens for a `w`×`h` image, at most the cap. */
    fun tokens(
        w0: Int,
        h0: Int,
    ): Long {
        var w = w0.toLong()
        var h = h0.toLong()
        val long = maxOf(w, h)
        if (long > LONG_EDGE) {
            w = w * LONG_EDGE / long
            h = h * LONG_EDGE / long
        }
        return minOf(ceilDiv(w, PATCH) * ceilDiv(h, PATCH), MAX_TOKENS)
    }

    private fun ceilDiv(
        a: Long,
        b: Long,
    ) = (a + b - 1) / b

    /** `decoded_len`: decoded byte length of base64 [data] (padding and whitespace skipped). */
    fun decodedLen(data: String): Long = data.count { sextet(it.code) >= 0 }.toLong() * 3 / 4

    /** `dims`: `(width, height)` of a base64 PNG or JPEG; null for other formats or a truncated header. */
    @Suppress("MagicNumber", "CyclomaticComplexMethod", "ReturnCount")
    fun dims(data: String): Pair<Int, Int>? {
        val b = decode(data.substring(0, minOf(data.length, HEADER_B64)))
        if (b.size >= 16 && b.copyOfRange(0, 8).contentEquals(PNG) && b.copyOfRange(12, 16).contentEquals(IHDR)) {
            return be32(b, 16)?.let { w -> be32(b, 20)?.let { h -> w to h } }
        }
        if (b.size < 2 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 3 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val m = b[i + 1].toInt() and 0xFF
            when {
                m == 0xFF -> {
                    i += 1
                }

                m == 0x01 || m in 0xD0..0xD8 -> {
                    i += 2
                }

                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC -> {
                    val h = be16(b, i + 5) ?: return null
                    val w = be16(b, i + 7) ?: return null
                    return w to h
                }

                else -> {
                    i += 2 + (be16(b, i + 2) ?: return null)
                }
            }
        }
        return null
    }

    @Suppress("MagicNumber")
    private fun be16(
        b: ByteArray,
        i: Int,
    ): Int? = if (i + 2 > b.size) null else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    @Suppress("MagicNumber")
    private fun be32(
        b: ByteArray,
        i: Int,
    ): Int? = if (i + 4 > b.size) null else (be16(b, i)!! shl 16) or be16(b, i + 2)!!

    @Suppress("MagicNumber")
    private fun sextet(c: Int): Int =
        when (c) {
            in 'A'.code..'Z'.code -> c - 'A'.code
            in 'a'.code..'z'.code -> c - 'a'.code + 26
            in '0'.code..'9'.code -> c - '0'.code + 52
            '+'.code, '-'.code -> 62
            '/'.code, '_'.code -> 63
            else -> -1
        }

    /** Standard or URL-safe base64; anything outside the alphabet is skipped. */
    @Suppress("MagicNumber")
    private fun decode(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length / 4 * 3)
        var acc = 0
        var bits = 0
        for (ch in s) {
            val v = sextet(ch.code)
            if (v < 0) continue
            acc = ((acc shl 6) or v) and 0xFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += (acc shr bits).toByte()
            }
        }
        return out.toByteArray()
    }
}

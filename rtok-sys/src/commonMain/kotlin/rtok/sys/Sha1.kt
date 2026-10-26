// SHA-1 (FIPS 180-4) in pure Kotlin — json_tree's content ids (Rust keeps its own copy too); not for security.
package rtok.sys

object Sha1 {
    fun hex(data: ByteArray): String {
        val h = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476, -0x3c2d1e10)
        val bitLen = data.size.toLong() * 8
        val padLen = ((data.size + 8) / 64 + 1) * 64
        val msg = data.copyOf(padLen)
        msg[data.size] = 0x80.toByte()
        for (i in 0 until 8) msg[padLen - 1 - i] = (bitLen ushr (8 * i)).toByte()
        val w = IntArray(80)
        for (chunk in 0 until padLen step 64) {
            for (i in 0 until 16) w[i] = (0 until 4).fold(0) { acc, k -> (acc shl 8) or (msg[chunk + i * 4 + k].toInt() and 0xff) }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            for (i in 0 until 80) {
                val (f, k) =
                    when (i) {
                        in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                        in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                        in 40..59 -> ((b and c) or (b and d) or (c and d)) to -0x70e44324
                        else -> (b xor c xor d) to -0x359d3e2a
                    }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = t
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
        }
        return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }
}

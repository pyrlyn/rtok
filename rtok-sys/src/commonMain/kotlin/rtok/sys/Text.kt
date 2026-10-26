// Rust `str` semantics Kotlin's String does not share: ASCII-only case folding and byte-order
// comparison (Rust compares UTF-8 bytes; Kotlin compares UTF-16 units, which differ above U+FFFF).
package rtok.sys

/** Rust `to_ascii_lowercase`: only A–Z change. */
fun String.asciiLowercase(): String = buildString(length) { for (c in this@asciiLowercase) append(if (c in 'A'..'Z') c + 32 else c) }

/** Rust `str::cmp`: code-point (= UTF-8 byte) order. */
fun compareUtf8(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i); val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += if (ca > 0xFFFF) 2 else 1; j += if (cb > 0xFFFF) 2 else 1
    }
    return when { i < a.length -> 1; j < b.length -> -1; else -> 0 }
}

/** Comparator form of [compareUtf8]. */
val Utf8Order: Comparator<String> = Comparator(::compareUtf8)

private fun String.codePointAt(i: Int): Int {
    val c = this[i]
    if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) return 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
    return c.code
}

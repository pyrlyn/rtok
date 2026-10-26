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

/** Rust `str::lines`: split on `\n`, drop one trailing `\r` per line, no empty last line after a final `\n`. */
fun String.rustLines(): List<String> {
    if (isEmpty()) return emptyList()
    val parts = split('\n').let { if (endsWith('\n')) it.dropLast(1) else it }
    return parts.map { it.removeSuffix("\r") }
}

/** Unicode scalar values (Rust `chars()`), each as a 1- or 2-unit string. */
fun String.scalarList(): List<String> {
    val out = ArrayList<String>(length); var i = 0
    while (i < length) { val n = if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1; out += substring(i, i + n); i += n }
    return out
}

/** The first [n] Unicode scalar values (Rust `chars().take(n)`). */
fun String.takeScalars(n: Int): String { var i = 0; var k = 0
    while (i < length && k < n) { i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1; k++ }
    return substring(0, i) }

/** `serde_json::to_string(&str)`: `"`, `\`, \b \f \n \r \t, other C0 controls as lowercase `\u00xx`; nothing else escaped. */
fun jsonQuote(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        '\b' -> append("\\b"); '\u000C' -> append("\\f")
        else -> if (c < ' ') append("\\u00").append(c.code.toString(16).padStart(2, '0')) else append(c)
    }
    append('"')
}

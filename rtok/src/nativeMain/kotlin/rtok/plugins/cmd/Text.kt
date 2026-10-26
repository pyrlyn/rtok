// Text helpers shared by the cmd formatters (Rust: `formatters::strip_ansi` and the
// `(got.len() < output.len()).then_some(got)` guard repeated in every formatter).
package rtok.plugins.cmd

/** UTF-8 byte length, the unit of Rust `str::len`. */
internal val String.byteLen: Int get() = encodeToByteArray().size

/** [got] when it is strictly shorter than [output] in bytes, else null. */
internal fun shorterOrNull(
    got: String,
    output: String,
): String? = got.takeIf { it.byteLen < output.byteLen }

/** Drop CSI escape sequences (`ESC [` … up to the first ASCII letter). */
fun stripAnsi(s: String): String =
    buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\u001b' && i + 1 < s.length && s[i + 1] == '[') {
                i += 2
                while (i < s.length) {
                    val d = s[i++]
                    if (d in 'a'..'z' || d in 'A'..'Z') break
                }
                continue
            }
            append(c)
            i++
        }
    }

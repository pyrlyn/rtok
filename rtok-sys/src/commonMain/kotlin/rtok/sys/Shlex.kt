// POSIX-shell word splitting with the semantics of the Rust `shlex` crate (`shlex::split`).
package rtok.sys

object Shlex {
    /** Words of [line], or null on an unterminated quote or a trailing backslash. */
    fun split(line: String): List<String>? {
        val words = ArrayList<String>(); var i = 0; val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == ' ' || c == '\t' || c == '\n') { i++; continue }
            if (c == '#') { while (i < n && line[i] != '\n') i++; continue }
            val w = StringBuilder()
            while (i < n) {
                val ch = line[i]
                if (ch == ' ' || ch == '\t' || ch == '\n') break
                i++
                when (ch) {
                    '\\' -> { if (i >= n) return null; val e = line[i++]; if (e != '\n') w.append(e) }
                    '\'' -> { val end = line.indexOf('\'', i); if (end < 0) return null; w.append(line, i, end); i = end + 1 }
                    '"' -> {
                        while (true) {
                            if (i >= n) return null
                            val d = line[i++]
                            if (d == '"') break
                            if (d == '\\') {
                                if (i >= n) return null
                                when (val e = line[i++]) { '$', '`', '"', '\\' -> w.append(e); '\n' -> {}; else -> w.append('\\').append(e) }
                            } else w.append(d)
                        }
                    }
                    else -> w.append(ch)
                }
            }
            words += w.toString()
        }
        return words
    }
}

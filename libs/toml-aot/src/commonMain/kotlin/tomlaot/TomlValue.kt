// A TOML value as the editor reads and writes it: the scalars hook tables hold, anything else kept as its raw text.
package tomlaot

sealed interface TomlValue {
    /** The value as TOML source. */
    fun render(): String

    data class Str(
        val value: String,
    ) : TomlValue {
        override fun render(): String = quote(value)
    }

    data class Int(
        val value: Long,
    ) : TomlValue {
        override fun render(): String = value.toString()
    }

    data class Bool(
        val value: Boolean,
    ) : TomlValue {
        override fun render(): String = value.toString()
    }

    /** Arrays, inline tables, floats, dates, multi-line strings: read as written, never reinterpreted. */
    data class Raw(
        val text: String,
    ) : TomlValue {
        override fun render(): String = text
    }

    companion object {
        private const val HEX_WIDTH = 4
        private const val HEX = 16
        private const val LAST_CONTROL = 0x1f
        private const val DEL = 0x7f

        /** A TOML basic string for [s]. */
        fun quote(s: String): String =
            buildString {
                append('"')
                for (c in s) {
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c == '\n' -> append("\\n")
                        c == '\t' -> append("\\t")
                        c == '\r' -> append("\\r")
                        c.code <= LAST_CONTROL || c.code == DEL -> append("\\u").append(c.code.toString(HEX).padStart(HEX_WIDTH, '0'))
                        else -> append(c)
                    }
                }
                append('"')
            }

        /** Parse one value's source text (comment already cut). */
        fun parse(text: String): TomlValue {
            val t = text.trim()
            return when {
                t == "true" -> Bool(true)
                t == "false" -> Bool(false)
                t.startsWith("\"\"\"") || t.startsWith("'''") -> Raw(t)
                t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0] -> string(t)
                else -> number(t)
            }
        }

        private fun string(t: String): TomlValue {
            val body = t.substring(1, t.length - 1)
            if (t[0] == '\'') return Str(body)
            return runCatching { Str(unescape(body)) }.getOrDefault(Raw(t))
        }

        private fun number(t: String): TomlValue {
            val signedDigit = t.firstOrNull()?.let { it.isDigit() || it == '-' || it == '+' } == true
            val n = t.replace("_", "").toLongOrNull()
            return if (signedDigit && n != null) Int(n) else Raw(t)
        }

        private val SIMPLE = mapOf('"' to '"', '\\' to '\\', 'n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000c')

        /** Basic-string escapes; a bad escape or a code point outside the BMP throws (the caller keeps the raw text). */
        private fun unescape(body: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                val e = if (c == '\\') body[i + 1] else null
                i +=
                    when {
                        e == null -> {
                            1.also { out.append(c) }
                        }

                        e in SIMPLE -> {
                            2.also { out.append(SIMPLE.getValue(e)) }
                        }

                        e == 'u' || e == 'U' -> {
                            val n = if (e == 'u') HEX_WIDTH else HEX_WIDTH * 2
                            val code = body.substring(i + 2, i + 2 + n).toInt(HEX)
                            require(code <= Char.MAX_VALUE.code) { "outside the BMP" }
                            out.append(code.toChar())
                            2 + n
                        }

                        else -> {
                            throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
            }
            return out.toString()
        }
    }
}

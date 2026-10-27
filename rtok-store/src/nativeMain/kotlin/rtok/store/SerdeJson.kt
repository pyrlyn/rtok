// serde_json's `Value` round trip, as rtok writes it: object keys sorted (no `preserve_order`),
// compact, control characters as lowercase `\u00xx`, non-ASCII and `/` kept. Numbers keep the
// literal they were parsed from (serde re-prints floats through ryu; equal for ordinary values).
package rtok.store

import kotlinx.serialization.json.*
import rtok.json.StrictJson

object SerdeJson {
    fun parse(text: String): JsonElement? = StrictJson.parseOrNull(text)

    fun encode(e: JsonElement): String = StringBuilder().also { write(it, e) }.toString()

    private fun write(
        sb: StringBuilder,
        e: JsonElement,
    ) {
        when (e) {
            is JsonNull -> {
                sb.append("null")
            }

            is JsonObject -> {
                sb.append('{')
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    str(sb, k)
                    sb.append(':')
                    write(sb, e.getValue(k))
                }
                sb.append('}')
            }

            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    write(sb, v)
                }
                sb.append(']')
            }

            is JsonPrimitive -> {
                if (e.isString) str(sb, e.content) else sb.append(e.content)
            }
        }
    }

    fun str(
        sb: StringBuilder,
        s: String,
    ) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u00").append("0123456789abcdef"[c.code shr 4]).append("0123456789abcdef"[c.code and 15]) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

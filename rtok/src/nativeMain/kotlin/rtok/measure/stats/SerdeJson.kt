// serde_json's output for `rtok stats --json` and the baseline files: two-space pretty printing, `[]`/`{}` for empty
// containers, and f64 written the way serde (ryu) writes it (`0.0`, `12345678.9`, `1e16`, `1.5e-7`).
@file:Suppress("MagicNumber", "ReturnCount")

package rtok.measure.stats

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object SerdeJson {
    private const val SCI_MIN = -5
    private const val SCI_MAX = 16

    /** `serde_json::to_string_pretty`. */
    fun pretty(e: JsonElement): String = StringBuilder().also { write(it, e, 0) }.toString()

    /** An f64 JSON number as serde writes it; non-finite values are `null`, as in serde_json. */
    fun f64(v: Double): JsonElement = if (v.isFinite()) JsonPrimitive(v) else JsonNull

    /** ryu's `format64`: the shortest round-trip digits, decimal for exponents in -5..15, else `<d>e<exp>`. */
    fun ryu(v: Double): String {
        if (v == 0.0) return if (1.0 / v < 0) "-0.0" else "0.0"
        val sign = if (v < 0) "-" else ""
        val (digits, exp) = shortest(kotlin.math.abs(v))
        // value = 0.digits × 10^exp
        val k = digits.length
        val body =
            when {
                exp in (SCI_MIN + 1)..0 -> "0." + "0".repeat(-exp) + digits
                exp in 1..SCI_MAX -> if (exp >= k) digits + "0".repeat(exp - k) + ".0" else digits.substring(0, exp) + "." + digits.substring(exp)
                else -> (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + (exp - 1)
            }
        return sign + body
    }

    /** Kotlin's shortest round-trip text, as `(digits, exponent)` with value `0.digits × 10^exponent`. */
    private fun shortest(v: Double): Pair<String, Int> {
        val s = v.toString()
        val e = s.indexOfFirst { it == 'E' || it == 'e' }
        val mant = if (e < 0) s else s.substring(0, e)
        val exp10 = if (e < 0) 0 else s.substring(e + 1).toInt()
        val dot = mant.indexOf('.')
        val intPart = if (dot < 0) mant else mant.substring(0, dot)
        val frac = if (dot < 0) "" else mant.substring(dot + 1)
        val all = intPart + frac
        val lead = all.indexOfFirst { it != '0' }
        val digits = all.substring(lead).trimEnd('0').ifEmpty { "0" }
        return digits to intPart.length - lead + exp10
    }

    /** `serde_json::Value::to_string`: compact, keys in order (no `preserve_order`), serde's string escapes. */
    fun compact(e: JsonElement?): String = StringBuilder().also { compactTo(it, e ?: JsonNull) }.toString()

    private fun compactTo(
        sb: StringBuilder,
        e: JsonElement,
    ) {
        when (e) {
            is JsonObject -> {
                sb.append('{')
                e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    sb.append(rtok.sys.jsonQuote(k)).append(':')
                    compactTo(sb, v)
                }
                sb.append('}')
            }

            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    compactTo(sb, v)
                }
                sb.append(']')
            }

            is JsonPrimitive -> {
                sb.append(scalar(e))
            }

            else -> {
                sb.append("null")
            }
        }
    }

    private fun scalar(e: JsonPrimitive): String =
        when {
            e is JsonNull -> "null"
            e.isString -> rtok.sys.jsonQuote(e.content)
            e.content.any { it == '.' || it == 'E' || it == 'e' } -> e.content.toDoubleOrNull()?.let(::ryu) ?: e.content
            else -> e.content
        }

    private fun indent(
        sb: StringBuilder,
        n: Int,
    ) = repeat(n) { sb.append("  ") }

    private fun write(
        sb: StringBuilder,
        e: JsonElement,
        level: Int,
    ) {
        when (e) {
            is JsonObject -> {
                if (e.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append("{\n")
                    e.entries.forEachIndexed { i, (k, v) ->
                        indent(sb, level + 1)
                        sb.append(rtok.sys.jsonQuote(k)).append(": ")
                        write(sb, v, level + 1)
                        sb.append(if (i < e.size - 1) ",\n" else "\n")
                    }
                    indent(sb, level)
                    sb.append('}')
                }
            }

            is JsonArray -> {
                if (e.isEmpty()) {
                    sb.append("[]")
                } else {
                    sb.append("[\n")
                    e.forEachIndexed { i, v ->
                        indent(sb, level + 1)
                        write(sb, v, level + 1)
                        sb.append(if (i < e.size - 1) ",\n" else "\n")
                    }
                    indent(sb, level)
                    sb.append(']')
                }
            }

            is JsonPrimitive -> {
                sb.append(scalar(e))
            }

            else -> {
                sb.append("null")
            }
        }
    }
}

/** Rust `format!("{:.N}", v)`: fixed decimals, ties to even like the exact binary value mostly rounds. */
fun fixed(
    v: Double,
    decimals: Int,
): String {
    if (!v.isFinite()) {
        return if (v.isNaN()) {
            "NaN"
        } else if (v > 0) {
            "inf"
        } else {
            "-inf"
        }
    }
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val r = kotlin.math.round(kotlin.math.abs(v) * scale).toLong()
    val sign = if (v < 0 && r != 0L) "-" else ""
    val whole = r / scale
    if (decimals == 0) return "$sign$whole"
    val frac = (r % scale).toString().padStart(decimals, '0')
    return "$sign$whole.$frac"
}

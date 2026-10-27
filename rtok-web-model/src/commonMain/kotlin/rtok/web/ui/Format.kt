// Port of web/src/pages/format.ts: number and time formatting shared by the pages.
package rtok.web.ui

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** [x] with exactly [digits] decimals (JS `toFixed`, half away from zero). */
fun fixed(
    x: Double,
    digits: Int,
): String {
    val scale = 10.0.pow(digits)
    val r = (abs(x) * scale).roundToLong()
    val sign = if (x < 0 && r != 0L) "-" else ""
    if (digits == 0) return "$sign$r"
    val whole = r / scale.toLong()
    val frac = (r % scale.toLong()).toString().padStart(digits, '0')
    return "$sign$whole.$frac"
}

private const val GROUP = 3

/** `1,234,567`, `-` for null (Intl en-US grouping). */
fun fmt(n: Double?): String =
    when {
        n == null -> {
            "-"
        }

        n != kotlin.math.floor(n) -> {
            n.toString()
        }

        else -> {
            (if (n < 0) "-" else "") +
                abs(n)
                    .toLong()
                    .toString()
                    .reversed()
                    .chunked(GROUP)
                    .joinToString(",")
                    .reversed()
        }
    }

private const val K = 1e3
private const val TEN_K = 1e4
private const val M = 1e6
private const val TEN_M = 1e7
private const val B = 1e9
private const val TEN_B = 1e10

/** `950`, `1.2k`, `12k`, `3.4M`, `12B`; `-` for null or non-finite. */
fun compact(n: Double?): String =
    when {
        n == null || !n.isFinite() -> "-"
        abs(n) >= B -> "${fixed(n / B, if (abs(n) >= TEN_B) 0 else 1)}B"
        abs(n) >= M -> "${fixed(n / M, if (abs(n) >= TEN_M) 0 else 1)}M"
        abs(n) >= TEN_K -> "${fixed(n / K, 0)}k"
        abs(n) >= K -> "${fixed(n / K, 1)}k"
        n == kotlin.math.floor(n) -> n.toLong().toString()
        else -> n.toString()
    }

private const val KB = 1024.0
private val UNITS = listOf("KB", "MB", "GB", "TB")

/** `1023 B`, `1.0 KB`, `1.5 MB`: the CLI's binary units. */
fun bytes(n: Double): String {
    if (n < KB) return "${n.toLong()} B"
    var value = n / KB
    var unit = 0
    while (value >= KB && unit < UNITS.size - 1) {
        value /= KB
        unit++
    }
    return "${fixed(value, 1)} ${UNITS[unit]}"
}

private const val PERCENT = 100

/** `12.5%`; `-` when [x] is not finite. */
fun pct(
    x: Double,
    digits: Int = 1,
): String = if (x.isFinite()) "${fixed(x * PERCENT, digits)}%" else "-"

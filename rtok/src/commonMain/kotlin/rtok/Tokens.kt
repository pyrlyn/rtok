// Port of src/tokens.rs — token estimator: chars / chars-per-token per text class.
package rtok

import kotlin.math.ceil
import rtok.config.Estimator
import rtok.plugin.sdk.Class

/** Estimated tokens for [text]. Empty text → 0. Counts Unicode scalar values like Rust `chars()`. */
fun estimate(text: String, cls: Class, rates: Estimator): Int {
    if (text.isEmpty()) return 0
    val cpt = when (cls) {
        Class.Code -> rates.code; Class.Prose -> rates.prose
        Class.Json -> rates.json; Class.Cjk -> rates.cjk
    }.coerceAtLeast(0.1f)
    val chars = text.codePointCountCompat().toFloat()
    return ceil(chars / cpt).toInt()
}

/** Tokens saved by shortening before→after; never negative. */
fun tokensSaved(before: Int, after: Int): Int = (before - after).coerceAtLeast(0)

/** T1.5: skip without a key. */
fun calibrateOrSkip(@Suppress("UNUSED_PARAMETER") rates: Estimator): String = "skipped"

internal fun String.codePointCountCompat(): Int {
    var n = 0; var i = 0
    while (i < length) { if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i += 2 else i++; n++ }
    return n
}

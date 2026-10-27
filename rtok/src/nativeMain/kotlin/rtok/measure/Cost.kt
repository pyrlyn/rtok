// Port of `row_cost` from src/measure/stats.rs: the one price arithmetic `rtok stats --price` and
// `rtok agents usage` share (T49.1), over a `[stats.prices]` row in $ per MTok.
package rtok.measure

import rtok.config.ModelPrice
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

private const val MTOK = 1e6

private const val HALF = 0.5

/** Rust `f64::round`: half away from zero (Kotlin's `round` is half to even). */
fun roundHalfAway(x: Double): Double = sign(x) * floor(abs(x) + HALF)

/** Dust below a microdollar rounds away so JSON goldens stay exact. */
fun round6(v: Double): Double = roundHalfAway(v * MTOK) / MTOK

/** USD for one model's counters: `(cost, saved)`, `saved` being what cache reads saved versus the input price. */
fun rowCost(
    input: Long,
    cacheCreate: Long,
    cacheRead: Long,
    output: Long,
    price: ModelPrice,
): Pair<Double, Double> {
    fun leg(
        n: Long,
        rate: Double,
    ) = n / MTOK * rate
    val cost = leg(input, price.input) + leg(cacheCreate, price.cacheWrite) + leg(cacheRead, price.cacheRead) + leg(output, price.output)
    val saved = leg(cacheRead, (price.input - price.cacheRead).coerceAtLeast(0.0))
    return round6(cost) to round6(saved)
}

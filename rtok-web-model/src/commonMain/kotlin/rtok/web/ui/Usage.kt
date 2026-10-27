// Port of web/src/pages/Usage.tsx logic: dollars, table lines and the period kind of an `agents usage` report.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToLong

private const val CENTS = 100

/** Dollars like the CLI prints them; no price is `-`, never `$0.00` (that would mean free). */
fun usd(cost: Double?): String {
    if (cost == null) return "-"
    val cents = (abs(cost) * CENTS).roundToLong()
    val sign = if (cost < 0 && cents > 0) "-" else ""
    return "$sign$${fmt((cents / CENTS).toDouble())}.${(cents % CENTS).toString().padStart(2, '0')}"
}

/** One table line, whether the report grouped by agent or (`--by model`) by model. */
data class UsageLine(
    val id: String,
    val label: String,
    val tokens: Double,
    val cost: Double?,
    val throughRtok: Double? = null,
    val coverage: Double? = null,
    val savedTokens: Double? = null,
    val savedUsd: Double? = null,
)

/** The middle table's rows: the models when the report asked for them, else the agents. */
fun usageLines(r: JsonObject): List<UsageLine> {
    if (r["models"] is kotlinx.serialization.json.JsonArray) {
        return r.objects("models").map { m ->
            val id = m.str("model") ?: ""
            UsageLine(id, id, m.num("tokens") ?: 0.0, m.num("cost_usd"))
        }
    }
    return r.objects("agents").map { a ->
        UsageLine(
            a.str("host") ?: "",
            a.str("name") ?: "",
            a.num("tokens") ?: 0.0,
            a.num("cost_usd"),
            a.num("through_rtok_tokens"),
            a.num("coverage"),
            a.num("saved_tokens"),
            a.num("saved_usd"),
        )
    }
}

/** Tokens the estimate leaves out because their model has no price. */
fun unpricedTokens(r: JsonObject): Double = r.objects("unpriced").sumOf { it.num("tokens") ?: 0.0 }

private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")

/** The report keeps no flag for `--daily`: a day period is a full date, a month is not. */
fun periodKind(r: JsonObject): String = if (r.objects("periods").any { DAY.matches(it.str("period") ?: "") }) "Daily" else "Monthly"

/** A whole percent, `-` for null. */
fun wholePct(c: Double?): String = if (c == null) "-" else "${(c * CENTS).roundToLong()}%"

/** Usage.tsx `sources`. */
fun usageSource(s: String?): String =
    when (s) {
        "logs" -> "agent logs"
        "rtok" -> "through rtok"
        "both" -> "agent logs and through rtok"
        else -> s ?: ""
    }

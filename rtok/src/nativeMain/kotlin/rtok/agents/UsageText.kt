// The text and JSON faces of `rtok agents usage` (agents/usage.rs `to_text`, `unpriced_text`, `middle_table` and the
// serde shape with its flattened rows, in struct order).
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.Col
import rtok.agents.UsageFormat.grouped
import rtok.agents.UsageFormat.units
import rtok.agents.UsageFormat.usd
import rtok.table

object UsageText {
    private const val PERCENT = 100.0
    private val THREE = listOf(Col.left(0), Col.right(0), Col.right(0))

    /** Where the numbers come from, as the header says it. */
    private fun from(r: UsageReport): String {
        val logs = "logs from ${r.agents.size} agent${if (r.agents.size == 1) "" else "s"}"
        return when (r.source) {
            "logs" -> logs
            "both" -> "$logs and through rtok"
            else -> "through rtok"
        }
    }

    /** One row per model with `--by model`, else one per agent, with the `both` and saved columns. */
    private fun middleTable(r: UsageReport): String {
        r.models?.let { models ->
            return table(THREE, listOf(listOf("Model", "Tokens", "Estimated cost")) + models.map { (m, row) -> listOf(m, units(row.tokens), usd(row.costUsd)) })
        }
        val both = r.source == "both"
        val head = mutableListOf("Agent", "Tokens", "Estimated cost")
        if (both) head += listOf("Through rtok", "Coverage")
        if (r.source != "logs") head += listOf("Saved tokens", "Saved est.")
        val cols = listOf(Col.left(0)) + List(head.size - 1) { Col.right(0) }
        val rows =
            r.agents.map { a ->
                buildList {
                    addAll(listOf(a.name, units(a.row.tokens), usd(a.row.costUsd)))
                    if (both) {
                        add(units(a.throughRtokTokens ?: 0))
                        add(a.coverage?.let { "${UsageFormat.percent(it * PERCENT)}%" } ?: "-")
                    }
                    a.saved?.let { addAll(listOf(units(it.tokens), usd(it.usd))) }
                }
            }
        return table(cols, listOf(head) + rows)
    }

    /** The default screen: header, summary, the unpriced warning, the middle table, then the month (or day) rows. */
    fun text(r: UsageReport): String {
        val from = from(r)
        val through = r.through ?: return "rtok agents usage: $from, no usage recorded (${r.tz})\n"
        val t = r.totals
        return buildString {
            append("rtok agents usage: $from, up to $through (${r.tz})\n\n")
            append("  ${units(t.row.tokens)} tokens\n  ${usd(t.row.costUsd)} estimated cost\n")
            append("  ${grouped(t.sessions.toLong())} sessions\n  ${grouped(t.dailyRows.toLong())} daily rows\n")
            t.saved?.let { s -> append("  rtok saved ${units(s.tokens)} tokens (${s.usd?.let { "≈ ${usd(it)}" } ?: "no price for an estimate"})\n") }
            if (r.unpricedModels > 0) {
                val one = r.unpricedModels == 1
                append(
                    "\n! Cost is incomplete: ${r.unpricedModels} model${if (one) " has" else "s have"} no price in [stats.prices], so " +
                        "${if (one) "its" else "their"} tokens are not in\n  the estimate. `rtok agents usage --unpriced` lists them.\n",
                )
            }
            append("\n${middleTable(r)}")
            val (label, head) = if (r.daily) "Daily totals" to "Day" else "Monthly totals" to "Month"
            val rows = listOf(listOf(head, "Tokens", "Estimated cost")) + r.periods.map { (p, row) -> listOf(p, units(row.tokens), usd(row.costUsd)) }
            append("\n$label\n${table(THREE, rows)}")
        }
    }

    /** `--unpriced`: the models the estimate leaves out, with agent and tokens. */
    fun unpriced(r: UsageReport): String {
        if (r.unpriced.isEmpty()) return "every model has a price in [stats.prices]\n"
        val cols = listOf(Col.left(0), Col.left(0), Col.right(0))
        return table(cols, listOf(listOf("Model", "Agent", "Tokens")) + r.unpriced.map { listOf(it.model, it.host, units(it.tokens)) })
    }

    private fun obj(fields: List<Pair<String, JsonElement>>) = JsonObject(linkedMapOf(*fields.toTypedArray()))

    private fun str(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull

    /** The `--json` document: the Rust struct order, rows and savings flattened, `models` only with `--by model`. */
    fun json(r: UsageReport): JsonObject {
        val totals =
            r.totals.row.fields() + listOf("sessions" to JsonPrimitive(r.totals.sessions), "daily_rows" to JsonPrimitive(r.totals.dailyRows)) +
                r.totals.saved
                    ?.fields()
                    .orEmpty()
        val agents =
            r.agents.map { a ->
                obj(
                    listOf("host" to str(a.host), "name" to str(a.name)) + a.row.fields() +
                        listOfNotNull(a.throughRtokTokens?.let { "through_rtok_tokens" to JsonPrimitive(it) }, a.coverage?.let { "coverage" to JsonPrimitive(it) }) +
                        a.saved?.fields().orEmpty(),
                )
            }
        return obj(
            listOfNotNull(
                "source" to str(r.source),
                "tz" to str(r.tz),
                "through" to str(r.through),
                "totals" to obj(totals),
                "unpriced_models" to JsonPrimitive(r.unpricedModels),
                "unpriced" to JsonArray(r.unpriced.map { obj(listOf("model" to str(it.model), "host" to str(it.host), "tokens" to JsonPrimitive(it.tokens))) }),
                "agents" to JsonArray(agents),
                r.models?.let { ms -> "models" to JsonArray(ms.map { (m, row) -> obj(listOf("model" to str(m)) + row.fields()) }) },
                "periods" to JsonArray(r.periods.map { (p, row) -> obj(listOf("period" to str(p)) + row.fields()) }),
                "skipped" to JsonArray(r.skipped.map { obj(listOf("host" to str(it.host), "reason" to str(it.reason), "path" to str(it.path))) }),
            ),
        )
    }
}

// Port of web/src/pages/text.ts `parseStats` and the logic of Stats.tsx and Skills.tsx: the `stats` Snapshot field
// (the text of `rtok stats --price` plus `--cache`) and the `skills` page rows. Lines the parser does not chart stay in
// `other`, verbatim.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** `key 123` pairs, as in `sessions 42  compact 3` (text.ts `kvSpaced`). */
fun kvSpaced(line: String): Map<String, String> = Regex("""([a-z_]+) (\d+)""").findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

private fun numOf(v: String?): Double? = if (v == null || v == "-" || v.isEmpty()) null else v.toDoubleOrNull()?.takeIf { it.isFinite() }

data class PricedRow(
    val model: String,
    val input: Double?,
    val cacheCreate: Double?,
    val cacheRead: Double?,
    val output: Double?,
    /** `null` is the `-` the report prints for a model without a price row. */
    val cost: Double?,
    val saved: Double?,
)

data class HealthRow(
    val session: String,
    val turns: Double?,
    val cacheRead: Double?,
    val cacheCreate: Double?,
    val busts: Double?,
)

data class Bust(
    val turn: String,
    val cause: String,
    val cacheCreate: Double?,
    val cacheRead: Double?,
)

data class CostTotal(
    val cost: Double,
    val saved: Double,
)

data class StatsView(
    val totals: Map<String, String>,
    val usage: Map<String, String>,
    val priced: List<PricedRow>,
    val costTotal: CostTotal?,
    val health: List<HealthRow>,
    val busts: List<Bust>,
    val other: List<String>,
)

private const val PRICED_CELLS = 7
private const val HEALTH_CELLS = 5
private val COST_TOTAL = Regex("""^cost total \$([\d.]+) \(cache reads saved \$([\d.]+)""")

@Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements", "MagicNumber")
fun parseStats(text: String): StatsView {
    val l = lines(text)
    val used = mutableSetOf<Int>()

    fun take(i: Int) = i.also { if (it >= 0) used += it }

    fun find(prefix: String) = take(l.indexOfFirst { it.startsWith(prefix) })

    fun at(i: Int) = if (i >= 0) l[i] else ""

    val totals = kvSpaced(at(find("sessions ")))
    val usage = kvPairs(at(find("usage ")))

    val priced = mutableListOf<PricedRow>()
    var costTotal: CostTotal? = null
    val iModel = find("model ")
    take(l.indexOfFirst { it.startsWith("cost (USD") })
    if (iModel >= 0) {
        for (i in iModel + 1 until l.size) {
            val line = l[i]
            val total = COST_TOTAL.find(line)
            if (total != null) {
                costTotal = CostTotal(total.groupValues[1].toDouble(), total.groupValues[2].toDouble())
                used += i
                break
            }
            val c = cells(line)
            if (line.isBlank() || c.size < PRICED_CELLS) break
            used += i
            priced += PricedRow(c[0], numOf(c[1]), numOf(c[2]), numOf(c[3]), numOf(c[4]), numOf(c[5]), numOf(c[6]))
        }
    }

    val health = mutableListOf<HealthRow>()
    val busts = mutableListOf<Bust>()
    val iCache = take(l.indexOf("cache health"))
    if (iCache >= 0) {
        take(iCache + 1)
        for (i in iCache + 2 until l.size) {
            val line = l[i]
            if (line.isBlank()) continue
            if (line.startsWith("  bust")) {
                val k = kvPairs(line)
                busts += Bust(Regex("""turn (\d+)""").find(line)?.groupValues?.get(1) ?: "?", k["cause"] ?: "?", numOf(k["cache_create"]), numOf(k["cache_read"]))
                used += i
                continue
            }
            val c = cells(line)
            if (c.size < HEALTH_CELLS) continue
            used += i
            health += HealthRow(c[0], numOf(c[1]), numOf(c[2]), numOf(c[3]), numOf(c[4]))
        }
    }
    val other = l.filterIndexed { i, s -> i !in used && s.isNotBlank() }
    return StatsView(totals, usage, priced, costTotal, health, busts, other)
}

/** Stats.tsx `costTotals`: the report's own `cost total` line, else the sum of priced rows. */
fun costTotals(v: StatsView): CostTotal = v.costTotal ?: CostTotal(v.priced.sumOf { it.cost ?: 0.0 }, v.priced.sumOf { it.saved ?: 0.0 })

// -- skills

/** Skills.tsx thresholds: a description past 180 chars or a body past 8 KB makes a skill expensive to keep resident. */
const val LONG_DESC_CHARS = 180
const val LONG_BODY_BYTES = 8192

/** A `SkillPageRow` off the wire. */
data class SkillRow(
    val name: String,
    val source: String,
    val descChars: Long,
    val bodyBytes: Long,
    val invocations: Long,
    val resident: Long,
    val lastInvoked: String,
    val never: Boolean,
)

fun skillRow(o: JsonObject) =
    SkillRow(
        name = o.str("name").orEmpty(),
        source = o.str("source").orEmpty(),
        descChars = o.num("desc_chars")?.toLong() ?: 0,
        bodyBytes = o.num("body_bytes")?.toLong() ?: 0,
        invocations = o.num("invocations")?.toLong() ?: 0,
        resident = o.num("resident")?.toLong() ?: 0,
        lastInvoked = o.str("last_invoked") ?: "-",
        never = o.flag("never"),
    )

fun skillWarnings(r: SkillRow): List<String> =
    listOfNotNull(
        "long description".takeIf { r.descChars > LONG_DESC_CHARS },
        "body > 8 KB".takeIf { r.bodyBytes > LONG_BODY_BYTES },
        "never invoked".takeIf { r.never },
    )

fun matchesSkill(
    r: SkillRow,
    neverOnly: Boolean,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    return (!neverOnly || r.never) && (q.isEmpty() || "${r.name} ${r.source}".lowercase().contains(q))
}

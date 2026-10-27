// Port of src/agents/usage.rs: `rtok agents usage` (T358), tokens and estimated cost per agent, day and month, from
// the agents' own logs (`measure.UsageLogs`), from what passed through rtok (the store's `usage` rows), or both.
// Costs use `[stats.prices]` through `rowCost`, as `rtok stats --price`; a model without a price counts in every
// token total and is named in `unpriced`, never guessed.
package rtok.agents

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.config.ModelPrice
import rtok.config.parseSinceFrom
import rtok.measure.Skipped
import rtok.measure.UsageLogs
import rtok.measure.round6
import rtok.measure.roundHalfAway
import rtok.measure.rowCost
import rtok.store.Store
import rtok.store.UsageSlice
import rtok.store.measurementSavedByHost
import rtok.store.usageSlices
import rtok.sys.CivilDate
import rtok.sys.Zone

/** Token legs and the estimated cost of one row; [costUsd] null when no model in it has a price (`-`, never `$0.00`). */
data class UsageRow(
    val tokens: Long = 0,
    val input: Long = 0,
    val cacheWrite: Long = 0,
    val cacheRead: Long = 0,
    val output: Long = 0,
    val costUsd: Double? = null,
) {
    fun add(
        b: UsageSlice,
        cost: Double?,
    ) = UsageRow(
        tokens + b.input + b.cacheCreate + b.cacheRead + b.output,
        input + b.input,
        cacheWrite + b.cacheCreate,
        cacheRead + b.cacheRead,
        output + b.output,
        if (cost == null) costUsd else (costUsd ?: 0.0) + cost,
    )

    fun rounded() = copy(costUsd = costUsd?.let(::round6))

    /** The flattened JSON fields, in the Rust struct order. */
    fun fields(): List<Pair<String, JsonElement>> =
        listOf(
            "tokens" to JsonPrimitive(tokens),
            "input" to JsonPrimitive(input),
            "cache_write" to JsonPrimitive(cacheWrite),
            "cache_read" to JsonPrimitive(cacheRead),
            "output" to JsonPrimitive(output),
            "cost_usd" to num(costUsd),
        )
}

private fun num(v: Double?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

/** Tokens rtok saved (net of `expand`) and their worth at the host's average input price; an estimate. */
data class Saved(
    val tokens: Long,
    val usd: Double?,
) {
    fun fields() = listOf("saved_tokens" to JsonPrimitive(tokens), "saved_usd" to num(usd))
}

data class UsageTotals(
    val row: UsageRow,
    val sessions: Int,
    val dailyRows: Int,
    val saved: Saved?,
)

data class UsageAgent(
    val host: String,
    val name: String,
    val row: UsageRow,
    /** `both` only: this agent's tokens that also passed through rtok. */
    val throughRtokTokens: Long?,
    /** `both` only: [throughRtokTokens] over the logs' tokens; null when the logs hold none. */
    val coverage: Double?,
    val saved: Saved?,
)

data class Unpriced(
    val model: String,
    val host: String,
    val tokens: Long,
)

data class UsageReport(
    val source: String,
    val tz: String,
    /** The last day with usage, in [tz]. */
    val through: String?,
    val totals: UsageTotals,
    val unpricedModels: Int,
    val unpriced: List<Unpriced>,
    val agents: List<UsageAgent>,
    /** `--by model` only. */
    val models: List<Pair<String, UsageRow>>?,
    val periods: List<Pair<String, UsageRow>>,
    val skipped: List<Skipped>,
    val daily: Boolean,
)

class UsageException(
    message: String,
) : Exception(message)

object Usage {
    private const val MONTH = 7
    private const val COVERAGE_SCALE = 1e4
    private const val MTOK = 1e6
    private val SOURCES = listOf("logs", "rtok", "both")

    /** `rtok agents usage`: open the store and build the report as of now. */
    fun openReport(cfg: Config): UsageReport = Store.open(cfg.core.dbPath).use { report(cfg, it, rtok.log.now()) }

    private fun fail(msg: String): Nothing = throw UsageException(msg)

    /** The window and grouping `[agents.usage]` describes, checked. */
    private class Options(
        cfg: Config,
        now: Long,
    ) {
        val o = cfg.agents.usage
        val source = o.source.takeIf { it in SOURCES } ?: fail("agents.usage.source `${o.source}`: expected `logs`, `rtok` or `both`")
        val daily =
            when (o.period) {
                "monthly" -> false
                "daily" -> true
                else -> fail("agents.usage.period `${o.period}`: expected `monthly` or `daily`")
            }
        val byModel =
            when (o.by) {
                "agent" -> false
                "model" -> true
                else -> fail("agents.usage.by `${o.by}`: expected `agent` or `model`")
            }
        val zone = zone(o.tz)
        val since: Long
        val until: Long

        init {
            // Droid is not a host rtok installs into, but its sessions are on the machine.
            val known = Hosts.ALL.map { it.id } + "droid"
            o.hosts.firstOrNull { it !in known }?.let { fail("unknown host `$it` in agents.usage.hosts; known: ${known.joinToString(", ")}") }
            since = bound(o.since, zone, now) ?: 0
            until =
                if (o.until.isEmpty()) {
                    Long.MAX_VALUE
                } else {
                    val day = CivilDate.parse(o.until) ?: fail("agents.usage.until `${o.until}`: expected a date such as 2026-09-30")
                    zone.startOf(day.tomorrow())
                }
        }

        fun wanted(h: String) = o.hosts.isEmpty() || h in o.hosts
    }

    /** `tz` as an IANA zone; empty is the system zone. */
    fun zone(tz: String): Zone = if (tz.isEmpty()) Zone.system() else Zone.named(tz) ?: fail("agents.usage.tz `$tz`: expected an IANA zone such as Europe/Kyiv")

    /** A window start: empty is unbounded, a date is its midnight in [zone], anything else a duration back from [now]. */
    private fun bound(
        value: String,
        zone: Zone,
        now: Long,
    ): Long? {
        val day = CivilDate.parse(value)
        return when {
            value.isEmpty() -> null
            day != null -> zone.startOf(day)
            else -> now - (runCatching { parseSinceFrom(value, "agents.usage.since") }.getOrNull() ?: fail("agents.usage.since `$value`: expected a date or a duration such as 30d"))
        }
    }

    /** What passed through rtok per host, the input those requests paid for, and the ledger's savings. */
    private class Through(
        val tokens: MutableMap<String, Long> = HashMap(),
        val inputRate: MutableMap<String, Pair<Long, Double>> = HashMap(),
        val savedByHost: MutableMap<String, Long> = HashMap(),
        var savedUnattributed: Long = 0,
    ) {
        fun saved(
            host: String,
            tokens: Long,
        ) = Saved(tokens, inputRate[host]?.takeIf { it.first > 0 }?.let { (n, cost) -> round6(tokens * (cost / n) / MTOK) })
    }

    private fun through(
        cfg: Config,
        store: Store,
        op: Options,
        slices: List<UsageSlice>,
    ): Through {
        val t = Through()
        for (b in slices) {
            val host = hostLabel(b).takeIf(op::wanted) ?: continue
            t.tokens[host] = (t.tokens[host] ?: 0) + b.input + b.cacheCreate + b.cacheRead + b.output
            UsageFormat.price(cfg.stats.prices, b.model ?: "unknown")?.let { p ->
                val (n, cost) = t.inputRate[host] ?: (0L to 0.0)
                t.inputRate[host] = n + b.input to cost + b.input * p.input
            }
        }
        for ((host, saved) in store.measurementSavedByHost(op.since, op.until)) {
            when {
                host != null && op.wanted(host) -> t.savedByHost[host] = (t.savedByHost[host] ?: 0) + saved
                host == null && op.o.hosts.isEmpty() -> t.savedUnattributed += saved
            }
        }
        return t
    }

    /** The sums over the primary rows. */
    private class Sums {
        var totals = UsageRow()
        val sessions = HashSet<String>()
        val days = HashSet<Pair<String, String>>()
        val agents = HashMap<String, UsageRow>()
        val periods = HashMap<String, UsageRow>()
        val models = HashMap<String, UsageRow>()
        val unpriced = HashMap<Pair<String, String>, Long>()

        fun add(
            b: UsageSlice,
            host: String,
            model: String,
            cost: Double?,
            day: String,
            period: String,
        ) {
            if (cost == null) unpriced[model to host] = (unpriced[model to host] ?: 0) + b.input + b.cacheCreate + b.cacheRead + b.output
            totals = totals.add(b, cost)
            for ((map, key) in listOf(agents to host, periods to period, models to model)) map[key] = (map[key] ?: UsageRow()).add(b, cost)
            sessions += b.session
            days += host to day
        }
    }

    private fun sum(
        cfg: Config,
        op: Options,
        primary: List<UsageSlice>,
    ): Sums {
        val sums = Sums()
        for (b in primary) {
            val host = hostLabel(b).takeIf(op::wanted) ?: continue
            val model = b.model ?: "unknown"
            val cost = UsageFormat.price(cfg.stats.prices, model)?.let { rowCost(b.input, b.cacheCreate, b.cacheRead, b.output, it).first }
            val day = op.zone.date(b.ts).toString()
            sums.add(b, host, model, cost, day, if (op.daily) day else day.take(MONTH))
        }
        return sums
    }

    private fun agent(
        op: Options,
        via: Through,
        host: String,
        row: UsageRow,
    ): UsageAgent {
        val passed = if (op.source == "both") via.tokens[host] ?: 0 else null
        val coverage = passed?.takeIf { row.tokens > 0 }?.let { roundHalfAway(it.toDouble() / row.tokens * COVERAGE_SCALE) / COVERAGE_SCALE }
        return UsageAgent(host, name(host), row.rounded(), passed, coverage, if (op.source != "logs") via.saved(host, via.savedByHost[host] ?: 0) else null)
    }

    fun report(
        cfg: Config,
        store: Store,
        now: Long,
    ): UsageReport {
        val op = Options(cfg, now)
        var skipped = emptyList<Skipped>()

        fun fromLogs(): List<UsageSlice> {
            val l = UsageLogs.read(cfg, op.since)
            skipped = l.skipped.filter { op.wanted(it.host) }
            return l.slices.filter { it.ts < op.until }
        }
        val rtok = op.source != "logs"
        val primary = if (op.source == "rtok") store.usageSlices(op.since, op.until) else fromLogs()
        val via = if (rtok) through(cfg, store, op, if (op.source == "rtok") primary else store.usageSlices(op.since, op.until)) else Through()
        val sums = sum(cfg, op, primary)
        // `both` lists an agent that only passed through rtok too, with no logged tokens.
        if (op.source == "both") via.tokens.keys.forEach { sums.agents.getOrPut(it) { UsageRow() } }
        val agents =
            sums.agents.entries
                .sortedBy { it.key }
                .map { (host, row) -> agent(op, via, host, row) }
                .sortedWith { a, b -> dearest(a.row, b.row) }
        val models =
            sums.models.entries
                .sortedBy { it.key }
                .map { it.key to it.value.rounded() }
                .sortedWith { a, b -> dearest(a.second, b.second) }
        val totalSaved =
            if (rtok) {
                Saved(via.savedByHost.values.sum() + via.savedUnattributed, agents.mapNotNull { it.saved?.usd }.reduceOrNull(Double::plus)?.let(::round6))
            } else {
                null
            }
        val unpriced = sums.unpriced.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        return UsageReport(
            op.source,
            op.zone.iana ?: "local",
            sums.days.maxOfOrNull { it.second },
            UsageTotals(sums.totals.rounded(), sums.sessions.size, sums.days.size, totalSaved),
            unpriced.map { it.key.first }.toSet().size,
            unpriced.map { Unpriced(it.key.first, it.key.second, it.value) },
            agents,
            if (op.byModel) models else null,
            sums.periods.entries
                .sortedBy { it.key }
                .map { it.key to it.value.rounded() },
            skipped,
            op.daily,
        )
    }

    /** Dearest first, then most tokens; an unpriced row sorts below every priced one. */
    private fun dearest(
        a: UsageRow,
        b: UsageRow,
    ): Int = (b.costUsd ?: -1.0).compareTo(a.costUsd ?: -1.0).takeIf { it != 0 } ?: b.tokens.compareTo(a.tokens)

    /** The host id, else `unattributed (<api>)` for a proxy request whose session has no host. */
    private fun hostLabel(b: UsageSlice) = b.host ?: "unattributed (${b.api})"

    /** The name a person knows the host by (`Claude Code`), else the id. */
    private fun name(host: String): String =
        Hosts
            .host(host)
            ?.variants
            ?.let { vs -> vs.firstOrNull { it.kind == Kind.Cli } ?: vs.firstOrNull() }
            ?.name ?: host
}

/** The price lookup and number faces of `rtok agents usage` (usage.rs `price`, `units`, `usd`, `grouped`). */
object UsageFormat {
    private const val DATE_SUFFIX = 8

    /** The price for [model]: the exact id, else without a provider prefix and a trailing `-YYYYMMDD`. */
    fun price(
        prices: Map<String, ModelPrice>,
        model: String,
    ): ModelPrice? {
        val bare = model.substringAfterLast('/')
        val tail = bare.substringAfterLast('-', "")
        val undated = if (tail.length == DATE_SUFFIX && tail.all(Char::isDigit) && '-' in bare) bare.substringBeforeLast('-') else bare
        return prices[model] ?: prices[bare] ?: prices[undated]
    }

    private const val PERCENT = 100.0

    private const val K = 1_000L
    private const val M = 1_000_000L
    private const val B = 1_000_000_000L

    /** `22.1K`, `155.45M`, `26.52B`: decimal SI, two decimals, trailing zeros dropped. */
    fun units(n: Long): String {
        val (v, suffix) =
            when {
                n >= B -> n.toDouble() / B to "B"
                n >= M -> n.toDouble() / M to "M"
                n >= K -> n.toDouble() / K to "K"
                else -> return n.toString()
            }
        return dollarsCents(roundHalfAway(v * PERCENT).toLong(), grouped = false).trimEnd('0').trimEnd('.') + suffix
    }

    /** `12.34` from a count of hundredths; [grouped] puts thousands separators in the whole part. */
    private fun dollarsCents(
        cents: Long,
        grouped: Boolean = true,
    ): String {
        val whole = cents / PERCENT.toLong()
        return "${if (grouped) grouped(whole) else whole.toString()}.${(cents % PERCENT.toLong()).toString().padStart(2, '0')}"
    }

    private const val GROUP = 3

    /** Rust `{:.0}` for a non-negative value. */
    fun percent(v: Double): String = roundHalfAway(v).toLong().toString()

    fun grouped(n: Long): String =
        n
            .toString()
            .reversed()
            .chunked(GROUP)
            .joinToString(",")
            .reversed()

    fun usd(cost: Double?): String {
        cost ?: return "-"
        // A negative estimate (more `expand` than savings) keeps its sign.
        val cents = roundHalfAway(kotlin.math.abs(cost) * PERCENT).toLong()
        val sign = if (cost < 0 && cents > 0) "-" else ""
        return "$sign$" + dollarsCents(cents)
    }
}

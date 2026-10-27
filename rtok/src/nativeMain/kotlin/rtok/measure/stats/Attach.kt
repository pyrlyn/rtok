// Port of the store-backed `attach_*` steps of src/measure/stats.rs: API and lane counters, Bash filter labels, checkpoint
// notes, the Codex row and `--price` USD.
@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements")

package rtok.measure.stats

import rtok.config.ModelPrice
import rtok.measure.estTokens
import rtok.measure.requests
import rtok.measure.round6
import rtok.measure.rowCost
import rtok.plugins.cmd.Formatters
import rtok.plugins.cmd.Settings
import rtok.store.Store
import rtok.store.checkpointSessionIds
import rtok.store.listMeasurements
import rtok.store.usageByApi
import rtok.store.usageByLaneModelTier
import rtok.store.usageByLaneTier
import rtok.store.usageByModelTier

/** `lane::lane_of_kind`: the lane a `kind` names; plain agent turns are `agent`. */
fun laneOfKind(kind: String): String = if (kind.startsWith("api_request:")) kind.removePrefix("api_request:") else "agent"

/** One bucket's counters with its prompt-cache hit rate over every billed input token. */
fun apiRow(
    input: Long,
    cacheCreate: Long,
    cacheRead: Long,
    output: Long,
): ApiRow {
    val denom = cacheRead + cacheCreate + input
    return ApiRow(input, cacheCreate, cacheRead, output, if (denom == 0L) 0.0 else cacheRead.toDouble() / denom.toDouble())
}

fun Report.attachApi(store: Store) {
    for (r in store.usageByApi()) api[r.api] = apiRow(r.input, r.cacheCreate, r.cacheRead, r.output)
}

private fun foldRows(rows: List<Pair<String, LongArray>>): SortedMap<ApiRow> {
    val sums = HashMap<String, LongArray>()
    for ((key, legs) in rows) {
        val sum = sums.getOrPut(key) { LongArray(4) }
        for (i in 0 until 4) sum[i] += legs[i]
    }
    return SortedMap<ApiRow>().also { m -> sums.forEach { (k, s) -> m[k] = apiRow(s[0], s[1], s[2], s[3]) } }
}

/** Per-lane and lane/tier counters (T385.6, T385.12.2); both empty while every request ran on the agent lane at the ordinary tier. */
fun laneRows(store: Store): Pair<SortedMap<ApiRow>, SortedMap<ApiRow>> {
    val rows = store.usageByLaneTier()

    fun legs(r: rtok.store.LaneUsage) = longArrayOf(r.input, r.cacheCreate, r.cacheRead, r.output)
    val lanes = foldRows(rows.map { laneOfKind(it.kind) to legs(it) })
    val tiers = foldRows(rows.map { "${laneOfKind(it.kind)}/${it.tier ?: "-"}" to legs(it) })
    val offAgent = lanes.keys.any { it != "agent" }
    val tiered = rows.any { r -> r.tier.let { it != null && it != "standard" && it != "default" } }
    return (if (offAgent) lanes else SortedMap()) to (if (offAgent || tiered) tiers else SortedMap())
}

fun Report.attachLanes(store: Store) {
    val (l, t) = laneRows(store)
    lanes = l
    laneTiers = t
}

/** T50.1: label each transcript Bash family and rank default-rule `cmd` savings; T177 splits the unmarked bytes. */
fun Report.attachBashCmd(store: Store) {
    val settings = Settings.builtin()
    for (name in bashFamilies.keys) bashFilter[name] = Formatters.filterKind(settings, name)
    val samples = RowSamples()
    for (r in store.listMeasurements("cmd")) {
        when (r.kind) {
            "raw" -> {
                bashUnmarked.gateCalls++
                bashUnmarked.gateBytes += r.beforeBytes.coerceAtLeast(0)
            }

            "unmatched" -> {
                bashUnmarked.unmatchedCalls++
                bashUnmarked.unmatchedBytes += r.beforeBytes.coerceAtLeast(0)
            }
        }
        if (r.kind != "rule") continue
        val fam = r.refId?.substringBefore(':').orEmpty()
        if (fam.isEmpty() || Formatters.filterKind(settings, fam) != "default") continue
        val bytes = r.afterBytes.coerceAtLeast(0)
        samples.add(bashDefaultRule, fam, bytes, estTokens(bytes), 0)
    }
    samples.finish(bashDefaultRule)
}

/** T71.2: counted transcript stems matched to `checkpoint:<id>` / `session:<id>` notes. */
fun Report.attachCheckpointNotes(store: Store) {
    val ids = store.checkpointSessionIds().toHashSet()
    val with = sessionStems.count { it in ids }.toLong()
    checkpoint = with
    noCheckpoint = (sessions - with).coerceAtLeast(0)
}

/** `codex::collect`: Codex CLI `token_count` requests since [cutoffSeconds] as one `api` row; none without a request. */
fun codexRow(
    dir: String,
    cutoffSeconds: Long,
): ApiRow? {
    val reqs = requests(dir, cutoffSeconds).first
    if (reqs.isEmpty()) return null
    return apiRow(reqs.sumOf { it.input }, reqs.sumOf { it.cacheCreate }, reqs.sumOf { it.cacheRead }, reqs.sumOf { it.output })
}

/** T49.2: Codex sessions as one more `api` row, same `since` window as the transcripts. */
fun Report.attachCodex(
    dir: String,
    cutoffSeconds: Long,
) {
    codexRow(dir, cutoffSeconds)?.let { api["codex"] = it }
}

/** `rtok stats --price` (T49.1): models without a `[stats.prices]` row land in `unknown`, out of the totals. */
fun Report.attachCosts(
    store: Store,
    prices: Map<String, ModelPrice>,
) {
    val costs = CostReport()
    for (row in store.usageByModelTier()) {
        val priced = prices[row.model]?.let { rowCost(row.input, row.cacheCreate, row.cacheRead, row.output, it) }
        if (priced == null) costs.unknown += row.model
        costs.totalCost += priced?.first ?: 0.0
        costs.totalSaved += priced?.second ?: 0.0
        costs.models[row.model] = CostRow(row.input, row.cacheCreate, row.cacheRead, row.output, priced?.first, priced?.second)
    }
    val unknown = costs.unknown.distinct().sorted()
    costs.unknown.clear()
    costs.unknown += unknown
    val laneRows = store.usageByLaneModelTier()

    fun laneOf(kind: String?) = kind?.let(::laneOfKind) ?: "agent"
    if (laneRows.any { laneOf(it.kind) != "agent" }) {
        for (row in laneRows) {
            val lane = costs.lanes.getOrPut(laneOf(row.kind)) { LaneCost() }
            val u = row.usage
            val price = prices[u.model]
            if (price != null) lane.cost += rowCost(u.input, u.cacheCreate, u.cacheRead, u.output, price).first else lane.partial = true
        }
        costs.lanes.values.forEach { it.cost = round6(it.cost) }
    }
    costs.totalCost = round6(costs.totalCost)
    costs.totalSaved = round6(costs.totalSaved)
    cost = costs
}

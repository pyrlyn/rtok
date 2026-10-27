// Port of the report types of src/measure/stats.rs (`rtok stats`, plan T1.2): per-tool sizes, Bash families, MCP groups,
// CTT and the row families each T-task added. `toJson` is the `--json` shape: field order as declared in Rust, and every
// row Rust skips when empty stays out, so the T15.11 goldens hold.
@file:Suppress("MagicNumber")

package rtok.measure.stats

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import rtok.measure.DictionaryRow
import rtok.measure.Subagents
import rtok.measure.stats.SortedMap

private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.filter { it.second !== Skip }.associate { (k, v) -> k to json(v) })

/** A field serde leaves out (`skip_serializing_if`). */
internal object Skip

internal fun json(v: Any?): JsonElement =
    when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Double -> SerdeJson.f64(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is SortedMap<*> -> JsonObject(v.sortedEntries().associate { (k, x) -> k to json(x) })
        is Map<*, *> -> JsonObject(v.entries.sortedBy { it.key.toString() }.associate { (k, x) -> k.toString() to json(x) })
        is List<*> -> JsonArray(v.map(::json))
        is Row -> v.toJson()
        else -> error("no JSON for ${v::class}")
    }

/** A report row that writes its own JSON. */
interface Row {
    fun toJson(): JsonObject
}

data class SizeRow(
    var count: Long = 0,
    var totalBytes: Long = 0,
    var mean: Long = 0,
    var p95: Long = 0,
    var max: Long = 0,
    var estTokens: Long = 0,
    var ctt: Long = 0,
) : Row {
    override fun toJson() = obj("count" to count, "total_bytes" to totalBytes, "mean" to mean, "p95" to p95, "max" to max, "est_tokens" to estTokens, "ctt" to ctt)
}

/** T58.3: the spans `Edit`/`MultiEdit` re-type, and every tool input serialized (the denominator). */
data class EditRow(
    var calls: Long = 0,
    var oldBytes: Long = 0,
    var newBytes: Long = 0,
    var toolInputBytes: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "old_bytes" to oldBytes, "new_bytes" to newBytes, "tool_input_bytes" to toolInputBytes)
}

/** T58.1: re-reads a unified diff could shorten. */
data class ReadDeltaRow(
    var calls: Long = 0,
    var bytes: Long = 0,
    var readBytes: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "bytes" to bytes, "read_bytes" to readBytes)
}

/** T136: whole-file Reads an outline could have answered. */
data class ReadWholeRow(
    var calls: Long = 0,
    var bytes: Long = 0,
    var edited: Long = 0,
    var editedBytes: Long = 0,
    var readBytes: Long = 0,
    var resultBytes: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "bytes" to bytes, "edited" to edited, "edited_bytes" to editedBytes, "read_bytes" to readBytes, "result_bytes" to resultBytes)
}

/** T383/T384: native Reads the read hook denied, and what followed. */
data class ReadDenyRow(
    var calls: Long = 0,
    var tokens: Long = 0,
    var thenMcp: Long = 0,
    var thenNative: Long = 0,
    var thenToolsearch: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "tokens" to tokens, "then_mcp" to thenMcp, "then_native" to thenNative, "then_toolsearch" to thenToolsearch)
}

/** T369.1: native Greps the guard answered from the symbol index. */
data class GrepSymbolRow(
    var calls: Long = 0,
    var tokens: Long = 0,
    var followed: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "tokens" to tokens, "followed" to followed)
}

/** T65.1: content-hash repeats within a session. */
data class RepeatRow(
    var calls: Long = 0,
    var bytes: Long = 0,
    var resultBytes: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "bytes" to bytes, "result_bytes" to resultBytes)
}

data class ClassCounts(
    var calls: Long = 0,
    var bytes: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "bytes" to bytes)
}

/** T179: repeat native Reads by why `read/dedup` + `read/delta` missed them. */
data class RepeatReadsRow(
    val fired: ClassCounts = ClassCounts(),
    val changed: ClassCounts = ClassCounts(),
    val ranged: ClassCounts = ClassCounts(),
    val subagent: ClassCounts = ClassCounts(),
    val hookAbsent: ClassCounts = ClassCounts(),
    val declined: ClassCounts = ClassCounts(),
) : Row {
    fun classes() = listOf("fired" to fired, "changed" to changed, "ranged" to ranged, "subagent" to subagent, "hook_absent" to hookAbsent, "declined" to declined)

    fun isEmpty() = classes().all { it.second.calls == 0L }

    override fun toJson() = JsonObject(classes().associate { (k, c) -> k to c.toJson() })
}

/** T176: expands of an id this session was shown. */
data class ExpandAfterRow(
    var calls: Long = 0,
    var bytes: Long = 0,
    var shownBytes: Long = 0,
) : Row {
    override fun toJson() = obj("calls" to calls, "bytes" to bytes, "shown_bytes" to shownBytes)
}

/** T177: unmarked `cmd` bytes, below the size gate vs no rule matched. */
data class BashUnmarkedRow(
    var gateCalls: Long = 0,
    var gateBytes: Long = 0,
    var unmatchedCalls: Long = 0,
    var unmatchedBytes: Long = 0,
) : Row {
    fun isEmpty() = gateCalls == 0L && unmatchedCalls == 0L

    override fun toJson() = obj("gate_calls" to gateCalls, "gate_bytes" to gateBytes, "unmatched_calls" to unmatchedCalls, "unmatched_bytes" to unmatchedBytes)
}

/** T125: assistant thinking blocks. */
data class ThinkingRow(
    var blocks: Long = 0,
    var bytes: Long = 0,
) : Row {
    override fun toJson() = obj("blocks" to blocks, "bytes" to bytes)
}

data class ImageSource(
    var blocks: Long = 0,
    var bytes: Long = 0,
    var tokens: Long = 0,
) : Row {
    override fun toJson() = obj("blocks" to blocks, "bytes" to bytes, "tokens" to tokens)
}

/** T137: `image` blocks; `resident` multiplies each block's tokens by the requests at or after its turn. */
data class ImageRow(
    var blocks: Long = 0,
    var bytes: Long = 0,
    var tokens: Long = 0,
    var resident: Long = 0,
    var noSize: Long = 0,
    val bySource: SortedMap<ImageSource> = SortedMap(),
) : Row {
    override fun toJson() = obj("blocks" to blocks, "bytes" to bytes, "tokens" to tokens, "resident" to resident, "no_size" to noSize, "by_source" to bySource)
}

/** T61.1: one skill's injected bodies; `resident` = body bytes × the session's later API requests. */
data class SkillRow(
    var count: Long = 0,
    var bytes: Long = 0,
    var mean: Long = 0,
    var p95: Long = 0,
    var max: Long = 0,
    var estTokens: Long = 0,
    var resident: Long = 0,
) : Row {
    override fun toJson() = obj("count" to count, "bytes" to bytes, "mean" to mean, "p95" to p95, "max" to max, "est_tokens" to estTokens, "resident" to resident)
}

data class ApiRow(
    var input: Long = 0,
    var cacheCreate: Long = 0,
    var cacheRead: Long = 0,
    var output: Long = 0,
    var hit: Double = 0.0,
) : Row {
    override fun toJson() = obj("input" to input, "cache_create" to cacheCreate, "cache_read" to cacheRead, "output" to output, "hit" to hit)
}

/** One model's USD (`--price`, T49.1); `cost`/`saved` are null without a `[stats.prices]` row. */
data class CostRow(
    val input: Long = 0,
    val cacheCreate: Long = 0,
    val cacheRead: Long = 0,
    val output: Long = 0,
    val cost: Double? = null,
    val saved: Double? = null,
) : Row {
    override fun toJson() = obj("input" to input, "cache_create" to cacheCreate, "cache_read" to cacheRead, "output" to output, "cost" to cost, "saved" to saved)
}

/** One lane's USD (T385.10); `partial` when some model on it has no price row. */
data class LaneCost(
    var cost: Double = 0.0,
    var partial: Boolean = false,
) : Row {
    override fun toJson() = obj("cost" to cost, "partial" to partial)
}

data class CostReport(
    val models: SortedMap<CostRow> = SortedMap(),
    val unknown: MutableList<String> = mutableListOf(),
    var totalCost: Double = 0.0,
    var totalSaved: Double = 0.0,
    val lanes: SortedMap<LaneCost> = SortedMap(),
) : Row {
    override fun toJson() =
        obj(
            "models" to models,
            "unknown" to unknown,
            "total_cost" to totalCost,
            "total_saved" to totalSaved,
            "lanes" to if (lanes.isEmpty()) Skip else lanes,
        )
}

/** The config knobs the fold needs: the `[plugins.archive]` replay and the T136 thresholds. */
data class Replay(
    val keepTurns: Long = 0,
    val minTokens: Long = 0,
    val headLines: Int = 0,
    val tailLines: Int = 0,
    /** `[plugins.read] native_max_bytes`. */
    val readWholeMin: Long = 0,
    /** `[plugins.guard] window_turns`. */
    val editWindowTurns: Long = 0,
) {
    companion object {
        fun fromCfg(cfg: rtok.config.Config) =
            with(cfg.plugins) {
                Replay(
                    archive.keepTurns.toLong(),
                    archive.minTokens.toLong(),
                    archive.headLines.toInt(),
                    archive.tailLines.toInt(),
                    read.nativeMaxBytes.toLong(),
                    guard.windowTurns.toLong(),
                )
            }
    }
}

class Report {
    var sessions = 0L

    /** T58.2: `compact_boundary` events. */
    var compact = 0L

    /** T71.2: sessions with / without a `checkpoint:*` or `session:*` note. */
    var checkpoint = 0L
    var noCheckpoint = 0L

    /** File stems of counted sessions; matched against notes. Not in JSON. */
    internal val sessionStems = ArrayList<String>()
    var lines = 0L
    var malformed = 0L
    val tools = SortedMap<SizeRow>()
    val bashFamilies = SortedMap<SizeRow>()

    /** T50.1: `formatter`, named `rule`, or `default` per Bash stem. */
    val bashFilter = SortedMap<String>()

    /** T50.1: `cmd` rule measurements on stems still on the default rule. */
    val bashDefaultRule = SortedMap<SizeRow>()
    val mcpGroups = SortedMap<SizeRow>()
    var usageInput = 0L
    var usageCacheCreate = 0L
    var usageCacheRead = 0L
    var usageOutput = 0L
    var cacheHitRate = 0.0
    var medianFinalContext = 0L

    /** Gate P5 replay: context-token-turns as recorded, and with the `archive` policy on every result. */
    var cttTotal = 0L
    var cttArchive = 0L
    var archiveCandidates = 0L
    var api = SortedMap<ApiRow>()
    var lanes = SortedMap<ApiRow>()
    var laneTiers = SortedMap<ApiRow>()
    var cost: CostReport? = null
    val edits = EditRow()
    val readDelta = ReadDeltaRow()
    val readWhole = ReadWholeRow()
    val readDeny = ReadDenyRow()
    val grepSymbol = GrepSymbolRow()
    val repeat = RepeatRow()
    val expandAfter = ExpandAfterRow()
    val bashUnmarked = BashUnmarkedRow()
    val thinking = ThinkingRow()
    val images = ImageRow()
    val dictionary = DictionaryRow()
    var skills: SortedMap<SkillRow>? = null
    var subagents: Subagents? = null
    val repeatReads = RepeatReadsRow()

    private fun <T> skipIf(
        empty: Boolean,
        v: T,
    ): Any? = if (empty) Skip else v

    fun toJson(): JsonObject =
        obj(
            "sessions" to sessions,
            "compact" to skipIf(compact == 0L, compact),
            "checkpoint" to skipIf(checkpoint == 0L, checkpoint),
            "no_checkpoint" to skipIf(noCheckpoint == 0L, noCheckpoint),
            "lines" to lines,
            "malformed" to malformed,
            "tools" to tools,
            "bash_families" to bashFamilies,
            "bash_filter" to skipIf(bashFilter.isEmpty(), bashFilter),
            "bash_default_rule" to skipIf(bashDefaultRule.isEmpty(), bashDefaultRule),
            "mcp_groups" to mcpGroups,
            "usage_input" to usageInput,
            "usage_cache_create" to usageCacheCreate,
            "usage_cache_read" to usageCacheRead,
            "usage_output" to usageOutput,
            "cache_hit_rate" to cacheHitRate,
            "median_final_context" to medianFinalContext,
            "ctt_total" to cttTotal,
            "ctt_archive" to cttArchive,
            "archive_candidates" to archiveCandidates,
            "api" to api,
            "lanes" to skipIf(lanes.isEmpty(), lanes),
            "lane_tiers" to skipIf(laneTiers.isEmpty(), laneTiers),
            "cost" to (cost ?: Skip),
            "edits" to skipIf(edits.calls == 0L, edits),
            "read_delta" to skipIf(readDelta.calls == 0L, readDelta),
            "read_whole" to skipIf(readWhole.calls == 0L, readWhole),
            "read_deny" to skipIf(readDeny.calls == 0L, readDeny),
            "grep_symbol" to skipIf(grepSymbol.calls == 0L, grepSymbol),
            "repeat" to skipIf(repeat.calls == 0L, repeat),
            "expand_after" to skipIf(expandAfter.calls == 0L, expandAfter),
            "bash_unmarked" to skipIf(bashUnmarked.isEmpty(), bashUnmarked),
            "thinking" to skipIf(thinking.blocks == 0L, thinking),
            "images" to skipIf(images.blocks == 0L, images),
            "dictionary" to skipIf(dictionary.isEmpty(), STATS_JSON.encodeToJsonElement(dictionary)),
            "skills" to (skills ?: Skip),
            "subagents" to (subagents?.let { STATS_JSON.encodeToJsonElement(it) } ?: Skip),
            "repeat_reads" to skipIf(repeatReads.isEmpty(), repeatReads),
        )

    /** `to_json`: `serde_json::to_string_pretty`. */
    fun toJsonText(): String = SerdeJson.pretty(toJson())
}

/** kotlinx for the `@Serializable` rows shared with other readers (dictionary, sub-agents). */
internal val STATS_JSON = kotlinx.serialization.json.Json { encodeDefaults = true }

/** Rust's `BTreeMap<String, V>`: a hash map that iterates (JSON, tables) in key order. */
class SortedMap<V> private constructor(
    private val m: HashMap<String, V>,
) : MutableMap<String, V> by m {
    constructor() : this(HashMap())

    fun sortedEntries(): List<Map.Entry<String, V>> = m.entries.sortedBy { it.key }

    fun getOrPut(
        key: String,
        make: () -> V,
    ): V = m[key] ?: make().also { m[key] = it }
}

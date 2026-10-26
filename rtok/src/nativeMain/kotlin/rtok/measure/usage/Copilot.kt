// Port of src/measure/usage/copilot.rs: Copilot CLI's `session-state/<id>/events.jsonl`. Only the `session.shutdown`
// line carries totals, per model; a resumed session's later shutdown is session-wide, so only the last one counts.
package rtok.measure.usage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import rtok.measure.HostSlices
import rtok.measure.Legs
import rtok.measure.UsageLogs
import rtok.measure.field
import rtok.measure.int
import rtok.measure.lineTs
import rtok.measure.parseJson
import rtok.measure.pointer
import rtok.measure.str
import rtok.store.UsageSlice
import rtok.sys.fileName
import rtok.sys.parent

internal object Copilot {
    fun slices(
        dirs: List<String>,
        since: Long,
        cutoff: Long,
    ): HostSlices = readEach(sessionFiles(dirs, cutoff) { fileName(it) == "events.jsonl" }) { p, text -> session(p, text, since) }

    private fun session(
        path: String,
        text: String,
        since: Long,
    ): List<UsageSlice> {
        val id = parent(path)?.let(::fileName) ?: ""
        val last =
            text
                .lines()
                .filter { "\"session.shutdown\"" in it }
                .mapNotNull(::parseJson)
                .filter { it.field("type").str() == "session.shutdown" }
                .mapNotNull { v -> (v.pointer("/data/modelMetrics") as? JsonObject)?.let { lineTs(v) to it } }
                .lastOrNull() ?: return emptyList()
        val (ts, models) = last
        return models.mapNotNull { (model, metric) -> UsageLogs.slice("copilot", "github", id, model, ts, legs(metric), since) }
    }

    /** `tokenDetails` when it has both input and output (it includes compaction requests), else `usage`. */
    private fun legs(metric: JsonElement): Legs {
        fun count(p: String) = metric.pointer(p).int()?.coerceAtLeast(0)

        fun detail(k: String) = count("/tokenDetails/$k/tokenCount")

        fun usage(k: String) = count("/usage/$k") ?: 0
        val input = detail("input")
        val output = detail("output")
        if (input != null && output != null) return Legs(input, detail("cache_write") ?: 0, detail("cache_read") ?: 0, output)
        val cacheRead = usage("cacheReadTokens")
        val cacheWrite = usage("cacheWriteTokens")
        return Legs((usage("inputTokens") - cacheRead - cacheWrite).coerceAtLeast(0), cacheWrite, cacheRead, usage("outputTokens"))
    }
}

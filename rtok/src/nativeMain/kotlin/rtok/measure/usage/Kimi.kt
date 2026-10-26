// Port of src/measure/usage/kimi.rs: Kimi Code's `sessions/<key>/<session>/agents/<agent>/wire.jsonl`; each
// `usage.record` line is one request (the `step.end` event repeats it and is not counted).
package rtok.measure.usage

import rtok.measure.HostSlices
import rtok.measure.Legs
import rtok.measure.UsageLogs
import rtok.measure.count
import rtok.measure.field
import rtok.measure.int
import rtok.measure.parseJson
import rtok.measure.str
import rtok.store.UsageSlice
import rtok.sys.components
import rtok.sys.fileName

internal object Kimi {
    private const val MS = 1000L

    fun slices(
        dirs: List<String>,
        since: Long,
        cutoff: Long,
    ): HostSlices = readEach(sessionFiles(dirs, cutoff) { fileName(it) == "wire.jsonl" }) { p, text -> wire(p, text, since) }

    /** The component before the last `agents`. */
    private fun sessionOf(path: String): String {
        val parts = components(path)
        return parts.lastIndexOf("agents").takeIf { it > 0 }?.let { parts[it - 1] } ?: ""
    }

    fun wire(
        path: String,
        text: String,
        since: Long,
    ): List<UsageSlice> {
        val id = sessionOf(path)
        return text
            .lines()
            .filter { "\"usage.record\"" in it }
            .mapNotNull(::parseJson)
            .filter { it.field("type").str() == "usage.record" }
            .mapNotNull { v ->
                val u = v.field("usage") ?: return@mapNotNull null
                val legs = Legs(u.count("inputOther"), u.count("inputCacheCreation"), u.count("inputCacheRead"), u.count("output"))
                UsageLogs.slice("kimi", "moonshot", id, v.field("model").str(), (v.field("time").int() ?: 0) / MS, legs, since)
            }
    }
}

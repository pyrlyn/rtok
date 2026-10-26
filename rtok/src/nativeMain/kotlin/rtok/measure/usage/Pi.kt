// Port of src/measure/usage/pi.rs: pi's session JSONL. Each assistant message (and a compaction summary) carries
// `usage` with the four legs; an entry copied into a forked session (same id and timestamp) counts once.
package rtok.measure.usage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import rtok.measure.HostSlices
import rtok.measure.Legs
import rtok.measure.UsageLogs
import rtok.measure.count
import rtok.measure.field
import rtok.measure.lineTs
import rtok.measure.parseJson
import rtok.measure.pointer
import rtok.measure.str
import rtok.store.UsageSlice

internal object Pi {
    fun slices(
        dirs: List<String>,
        since: Long,
        cutoff: Long,
    ): HostSlices {
        val seen = HashSet<Pair<String, String>>()
        return readEach(sessionFiles(dirs, cutoff)) { p, text -> session(p, text, since, seen) }
    }

    /** One session file's requests; null when it is not empty and holds no JSON line. */
    fun session(
        path: String,
        text: String,
        since: Long,
        seen: MutableSet<Pair<String, String>>,
    ): List<UsageSlice>? {
        var id = stem(path)
        val values = text.lines().filter { it.isNotBlank() }.mapNotNull(::parseJson)
        val out = ArrayList<UsageSlice>()
        for (v in values) {
            if (v.field("type").str() == "session") {
                v.field("id").str()?.let { id = it }
            } else {
                entry(id, v, since, seen)?.let(out::add)
            }
        }
        return out.takeIf { values.isNotEmpty() || text.isBlank() }
    }

    private fun entry(
        session: String,
        v: JsonElement,
        since: Long,
        seen: MutableSet<Pair<String, String>>,
    ): UsageSlice? {
        val usage = (v.field("usage") ?: v.pointer("/message/usage")) as? JsonObject
        val stamp = v.field("timestamp").str()
        if (usage == null || stamp == null || v.field("id").str()?.let { seen.add(it to stamp) } == false) return null

        fun text(k: String) = (v.field(k) ?: v.field("message").field(k)).str()
        val legs = Legs(usage.count("input"), usage.count("cacheWrite"), usage.count("cacheRead"), usage.count("output"))
        return UsageLogs.slice("pi", text("provider") ?: "pi", session, text("model"), lineTs(v), legs, since)
    }
}

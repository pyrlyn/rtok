// Port of src/measure/baseline.rs: `rtok stats --save-baseline` / `--compare` (plan T1.3).
package rtok.measure.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.sys.createDirAll
import rtok.sys.readToString
import rtok.sys.writeString

object Baseline {
    fun dir(home: String) = "$home/measurements"

    fun path(
        home: String,
        name: String,
    ) = "${dir(home)}/$name.json"

    fun save(
        home: String,
        name: String,
        report: Report,
    ): String {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name) { "bad baseline name" }
        createDirAll(dir(home))
        return path(home, name).also { writeString(it, report.toJsonText()) }
    }

    /** The counters `compare` reads back; a file that is not a report is an error, as serde's is. */
    private class Saved(
        val o: JsonObject,
    ) {
        fun n(key: String): Long = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: error("missing field `$key`")

        fun rows(key: String): Map<String, Pair<Long, Long>> =
            (o[key] as? JsonObject ?: error("missing field `$key`")).mapValues { (_, v) ->
                val r = v as? JsonObject ?: error("invalid type for `$key`")

                fun f(k: String) = (r[k] as? JsonPrimitive)?.longOrNull ?: error("missing field `$k`")
                f("est_tokens") to f("ctt")
            }
    }

    private fun load(
        home: String,
        name: String,
    ): Saved {
        val p = path(home, name)
        val raw = runCatching { readToString(p) }.getOrElse { throw IllegalStateException(p, it) }
        return Saved(Json.parseToJsonElement(raw) as? JsonObject ?: error("invalid type: expected struct Report"))
    }

    /** Per-field deltas; all zeros after a save of the same report. */
    fun compare(
        home: String,
        name: String,
        now: Report,
    ): String {
        val old = load(home, name)
        return buildString {
            fun line(
                key: String,
                was: Long,
                cur: Long,
            ) = append("$key $cur Δ${cur - was}\n")
            line("sessions", old.n("sessions"), now.sessions)
            line("lines", old.n("lines"), now.lines)
            line("usage_input", old.n("usage_input"), now.usageInput)
            line("usage_output", old.n("usage_output"), now.usageOutput)
            for ((label, key, cur) in listOf(Triple("tools", "tools", now.tools), Triple("bash", "bash_families", now.bashFamilies), Triple("mcp", "mcp_groups", now.mcpGroups))) {
                val a = old.rows(key)
                for (n in (a.keys + cur.keys).sorted()) {
                    line("$label.$n.est_tokens", a[n]?.first ?: 0, cur[n]?.estTokens ?: 0)
                    line("$label.$n.ctt", a[n]?.second ?: 0, cur[n]?.ctt ?: 0)
                }
            }
        }
    }
}

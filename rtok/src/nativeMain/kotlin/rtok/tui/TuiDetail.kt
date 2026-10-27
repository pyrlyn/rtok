// Port of the detail panes of src/tui/view.rs: `call_detail` (Calls, Enter/z, T15.5) and `session_pane`
// (Sessions, Enter, T60.3, over model::session_detail). Every value is the snapshot's row (D23), never a second query.
package rtok.tui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.log.stamp

/** Rust's `{}` of a number: whole values without a fraction. */
private fun num(v: Double?): String =
    when {
        v == null -> "-"
        v == kotlin.math.floor(v) && !v.isInfinite() -> v.toLong().toString()
        else -> v.toString()
    }

private fun utc(v: Double?) = stamp((v ?: 0.0).toLong().coerceAtLeast(0))

private fun JsonObject.d(k: String) = s(k) ?: "-"

/** view.rs `call_detail`: title and lines of the selected call's full fields. */
internal fun callDetail(
    c: JsonObject,
    refIds: JsonObject?,
): Pair<String, List<String>> {
    val id = (c["id"] as? JsonPrimitive)?.content.orEmpty()
    val ok =
        (c["ok"] as? JsonPrimitive)?.content.let {
            if (it == "true") {
                "1"
            } else if (it == "false") {
                "0"
            } else {
                it ?: "-"
            }
        }
    val api = c.s("api")
    val input = c.n("input")
    val usage =
        if (api != null && input != null) {
            val cc = c.n("cache_create") ?: 0.0
            val cr = c.n("cache_read") ?: 0.0
            val out = c.n("output") ?: 0.0
            "usage ($api) input ${num(input)} cache create ${num(cc)} cache read ${num(cr)} output ${num(out)} = ${num(input + cc + cr + out)} tok"
        } else {
            "usage no row linked (only an api request records one)"
        }
    return "call $id" to
        listOf(
            "ts ${utc(c.n("ts"))} UTC · ok $ok · error ${c.d("error")}",
            "session ${c.d("session")} · surface ${c.d("surface")} · kind ${c.d("kind")}",
            "name ${c.d("name")} · plugin ${c.d("plugin")} · parent ${num(c.n("parent_id"))}",
            "host ${c.d("host")} · provider ${c.d("provider")} · model ${c.d("model")}",
            "ms ${num(c.n("ms"))}",
            "ref_id ${refIds?.s(id) ?: "-"}",
            usage,
        )
}

/** model::session_detail + view.rs `session_pane`: the session's hidden fields, then the snapshot's calls for its id. */
internal fun sessionDetail(
    snapshot: JsonObject,
    id: String,
    clock: (Double) -> String,
): List<String>? {
    val s = (snapshot["sessions"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull { it.s("id") == id } ?: return null
    val calls = (snapshot["calls"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.s("session") == id }
    val ended = s["ended_at"].let { if (it == null || it is JsonNull) "live" else utc(s.n("ended_at")) }
    val api = s.d("api")
    val n = { k: String -> num(s.n(k) ?: 0.0) }
    return listOf(
        "project ${s.d("project")} · api $api",
        "started ${utc(s.n("started_at"))} · last ${utc(s.n("last_activity"))} · ended $ended",
        "usage ($api) input ${n("input")} cache create ${n("cache_create")} cache read ${n("cache_read")} output ${n("output")}",
        "calls ${calls.size}",
    ) + calls.map { "${it.n("ts")?.let(clock) ?: "-"} ${it.d("surface")} ${it.d("kind")} ${it.d("name")}" }
}

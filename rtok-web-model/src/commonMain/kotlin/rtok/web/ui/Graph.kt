// Port of web/src/pages/text.ts `parseGraph` and projectLogic.ts `stateOf`, plus the `project` requests of
// Projects.tsx: the graph page's pure logic.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A file changed since the last index; [project] is set once the lists span linked projects. */
data class PendingFile(
    val project: String?,
    val path: String,
)

/** One unreferenced definition from `rtok graph dead`. */
data class DeadSymbol(
    val project: String?,
    val path: String,
    val line: String,
    val kind: String,
    val name: String,
)

/** `rtok graph status` plus `rtok graph dead`, parsed. */
data class GraphView(
    val root: String,
    val rows: Long?,
    val files: Long?,
    val pending: List<PendingFile>,
    val watch: Boolean,
    val indexedAt: String,
    val dead: List<DeadSymbol>,
    val deadNote: String?,
    val other: List<String>,
)

private val PROJECT = Regex("""^\[([^\]]+)] (.*)$""")
private val DEAD = Regex("""^(\S+):(\d+) (\S+) (.+)$""")
private val NOTE = Regex("""^ (…|dead scan failed)""")

private fun splitProject(text: String): Pair<String?, String> = PROJECT.find(text)?.let { it.groupValues[1] to it.groupValues[2] } ?: (null to text)

private fun numOrNull(v: String): Long? = if (v == "-" || v.isEmpty()) null else v.toLongOrNull()

/** text.ts `parseGraph`: known lines into fields, the rest kept verbatim in [GraphView.other]. */
fun parseGraph(text: String): GraphView {
    val l = lines(text)
    val used = HashSet<Int>()

    fun get(key: String): String {
        val i = l.indexOfFirst { it.startsWith("$key ") }
        if (i < 0) return "-"
        used += i
        return l[i].substring(key.length + 1)
    }
    val root = get("root")
    val rows = numOrNull(get("rows"))
    val files = numOrNull(get("files"))
    val watch = get("watch") == "true"
    val indexedAt = get("indexed_at")
    val pending = mutableListOf<PendingFile>()
    val iPending = l.indexOfFirst { it.startsWith("pending ") }
    if (iPending >= 0) {
        used += iPending
        var i = iPending + 1
        while (i < l.size && l[i].startsWith("  ")) {
            used += i
            val (project, rest) = splitProject(l[i].trim())
            pending += PendingFile(project, rest)
            i++
        }
    }
    val dead = mutableListOf<DeadSymbol>()
    var deadNote: String? = null
    val iDead = l.indexOf("dead symbols")
    if (iDead >= 0) {
        used += iDead
        for (i in iDead + 1 until l.size) {
            val line = l[i]
            val (project, rest) = splitProject(line.drop(1))
            val m = DEAD.find(rest)
            when {
                line.startsWith(" ") && m != null -> {
                    used += i
                    val (path, ln, kind) = m.destructured
                    dead += DeadSymbol(project, path, ln, kind, m.groupValues.last())
                }

                line.trim() == "none" -> {
                    used += i
                }

                NOTE.containsMatchIn(line) -> {
                    used += i
                    deadNote = line.trim()
                }
            }
        }
    }
    val other = l.filterIndexed { i, s -> i !in used && s.isNotBlank() }
    return GraphView(root, rows, files, pending, watch, indexedAt, dead, deadNote, other)
}

/** projectLogic.ts `STATES`: the pill tone and hint of a project state. */
fun projectState(state: String): Pair<String, String> =
    when (state) {
        "ok" -> "ok" to "index is fresh"
        "not indexed" -> "muted" to "nothing indexed yet"
        "indexing" -> "info" to "index is being built"
        "stale" -> "warn" to "files changed since the last index"
        "failed" -> "fail" to "the last index run failed"
        "missing" -> "fail" to "the directory no longer exists"
        else -> "muted" to ""
    }

/** A `{"project":{...}}` request: `select`, `reindex`, `remove` (with [confirmed]), `link`/`unlink`. */
fun projectRequest(
    action: String,
    fields: Map<String, Any>,
): String =
    buildJsonObject {
        putJsonObject("project") {
            put("action", action)
            fields.forEach { (k, v) ->
                when (v) {
                    is Boolean -> put(k, v)
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }
    }.toString()

/** The health score of a project row, null while it has none. */
fun healthScore(p: JsonObject): Double? = p.obj("health")?.num("score")

// Port of web/src/pages/Calls.tsx, Sessions.tsx and Logs.tsx: filterable tables over the snapshot.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.js.Date

private const val SEC = 1000.0
private const val SESSION_ID = 12

/** Local `HH:MM:SS` of unix seconds. */
fun hms(ts: Double): String {
    val d = Date(ts * SEC)

    fun pad(n: Int) = n.toString().padStart(2, '0')
    return "${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}"
}

private fun FlowContent.toolbar(body: FlowContent.() -> Unit) = div("toolbar") { body() }

private fun FlowContent.count(
    shown: Int,
    total: Int,
) = span("muted") { +"$shown of $total" }

/** The Calls page. */
val CallsPage =
    PageView { out, snap, app ->
        val calls = snap.objects("calls")
        val surface = param("surface", "all")
        val result = param("result", "any")
        val rows = calls.filter { matchesCall(it, surface, result, param("q", "")) }
        out.panel("calls", "the last ledger rows, newest first") {
            toolbar {
                toggles(app, "surface", listOf("all") + SURFACES)
                toggles(app, "result", listOf("any", "ok", "failed"))
                searchBox(app, "calls-q", "filter calls")
                count(rows.size, calls.size)
            }
            dataTable(
                "calls",
                rows.sortedByDescending { it.num("ts") ?: 0.0 },
                listOf(
                    Column("time") { c -> +(c.num("ts")?.let(::hms) ?: "-") },
                    Column("surface") { c -> pill("info", c.str("surface") ?: "-") },
                    Column("name") { c -> +(c.str("name") ?: c.str("plugin") ?: "Unknown") },
                    Column("ms", right = true) { c -> +(c.num("ms")?.let { fixed(it, 1) } ?: "Unknown") },
                    Column("tokens", right = true) { c -> +(tokensOf(c)?.let(::compact) ?: "Unknown") },
                    Column("ok") { c -> if (c.flag("ok")) span("ok") { +"✓" } else pill("fail", "fail") },
                ),
                if (calls.isEmpty()) "No calls yet." else "No call matches the filter.",
            )
        }
    }

private fun sessionTokens(s: JsonObject) = listOf("input", "cache_create", "cache_read", "output").sumOf { s.num(it) ?: 0.0 }

/** The Sessions page. */
val SessionsPage =
    PageView { out, snap, app ->
        val sessions = snap.objects("sessions")
        val live = param("show", "all") == "live"
        val rows = sessions.filter { matchesSession(it, live, param("q", "")) }.sortedByDescending { it.num("last_activity") ?: 0.0 }
        out.panel("sessions", "${sessions.count { it.num("ended_at") == null }} live") {
            toolbar {
                toggles(app, "show", listOf("all", "live"))
                searchBox(app, "sessions-q", "filter sessions")
                count(rows.size, sessions.size)
            }
            dataTable(
                "sessions",
                rows,
                listOf(
                    Column("status") { s -> if (s.num("ended_at") == null) pill("ok", "live") else pill("info", "ended") },
                    Column("session") { s ->
                        span {
                            attributes["title"] = s.str("id") ?: ""
                            +(s.str("id")?.take(SESSION_ID) ?: "")
                        }
                        span("muted") { +" ${listOfNotNull(s.str("host"), s.str("model"), s.str("project")).joinToString(" · ")}" }
                    },
                    Column("tokens", right = true) { s -> +compact(sessionTokens(s)) },
                    Column("last", right = true) { s -> +(s.num("last_activity")?.let(::hms) ?: "-") },
                ),
                if (sessions.isEmpty()) "No sessions yet. A session appears with its first hook." else "No session matches the filter.",
            )
        }
    }

private fun levelTone(level: String) =
    when (level) {
        "warn" -> "warn"
        "error" -> "fail"
        else -> "info"
    }

/** The Logs page. */
val LogsPage =
    PageView { out, snap, app ->
        val lines = (snap["logs"] as? JsonArray).orEmpty().mapIndexed { i, e -> parseLog((e as? JsonPrimitive)?.content ?: e.toString(), i) }
        val level = param("level", "all")
        val rows = lines.filter { matchesLog(it, level, param("q", "")) }
        out.panel("logs", "newest first") {
            toolbar {
                toggles(app, "level", LEVELS)
                searchBox(app, "logs-q", "filter logs")
                count(rows.size, lines.size)
            }
            dataTable(
                "logs",
                rows,
                listOf(
                    Column("#", right = true) { l -> +"${l.i + 1}" },
                    Column("time") { l -> +l.ts },
                    Column("level") { l -> pill(levelTone(l.level), l.level) },
                    Column("source") { l -> +(if (l.source.isEmpty()) "" else "${l.source}/${l.name}") },
                    Column("message") { l -> span("wrap") { +l.msg } },
                ),
                if (lines.isEmpty()) "No log lines yet." else "No line matches the filter.",
            )
        }
    }

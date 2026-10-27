// Calls, Sessions and Logs in Compose: filterable tables over the snapshot (same filters as the kotlinx.html UI).
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.web.ui.LEVELS
import rtok.web.ui.SURFACES
import rtok.web.ui.compact
import rtok.web.ui.fixed
import rtok.web.ui.flag
import rtok.web.ui.matchesCall
import rtok.web.ui.matchesLog
import rtok.web.ui.matchesSession
import rtok.web.ui.num
import rtok.web.ui.objects
import rtok.web.ui.parseLog
import rtok.web.ui.str
import rtok.web.ui.tokensOf

private const val SESSION_ID = 12

/** The Calls page. */
val CallsPage =
    PageView { snap, app ->
        val calls = snap.objects("calls")
        val rows = calls.filter { matchesCall(it, app.param("surface", "all"), app.param("result", "any"), app.param("q", "")) }
        Panel("calls", "the last ledger rows, newest first") {
            Toolbar {
                Toggles(app, "surface", listOf("all") + SURFACES)
                Toggles(app, "result", listOf("any", "ok", "failed"))
                SearchBox(app, "filter calls")
                Muted("${rows.size} of ${calls.size}")
            }
            Table(
                rows.sortedByDescending { it.num("ts") ?: 0.0 },
                listOf(
                    Col<JsonObject>("time") { Txt(it.num("ts")?.let(::hms) ?: "-") },
                    Col("surface") { Pill("info", it.str("surface") ?: "-") },
                    Col("name", 2f) { Txt(it.str("name") ?: it.str("plugin") ?: "Unknown") },
                    Col("ms") { Txt(it.num("ms")?.let { m -> fixed(m, 1) } ?: "Unknown") },
                    Col("tokens") { Txt(tokensOf(it)?.let(::compact) ?: "Unknown") },
                    Col("ok") { if (it.flag("ok")) Txt("✓", color = LocalPalette.current.ok) else Pill("fail", "fail") },
                ),
                if (calls.isEmpty()) "No calls yet." else "No call matches the filter.",
            )
        }
    }

private fun sessionTokens(s: JsonObject) = listOf("input", "cache_create", "cache_read", "output").sumOf { s.num(it) ?: 0.0 }

/** The Sessions page. */
val SessionsPage =
    PageView { snap, app ->
        val sessions = snap.objects("sessions")
        val live = app.param("show", "all") == "live"
        val rows = sessions.filter { matchesSession(it, live, app.param("q", "")) }.sortedByDescending { it.num("last_activity") ?: 0.0 }
        Panel("sessions", "${sessions.count { it.num("ended_at") == null }} live") {
            Toolbar {
                Toggles(app, "show", listOf("all", "live"))
                SearchBox(app, "filter sessions")
                Muted("${rows.size} of ${sessions.size}")
            }
            Table(
                rows,
                listOf(
                    Col<JsonObject>("status") { if (it.num("ended_at") == null) Pill("ok", "live") else Pill("info", "ended") },
                    Col("session", 3f) {
                        Txt("${it.str("id")?.take(SESSION_ID) ?: ""}  " + listOfNotNull(it.str("host"), it.str("model"), it.str("project")).joinToString(" · "))
                    },
                    Col("tokens") { Txt(compact(sessionTokens(it))) },
                    Col("last") { Txt(it.num("last_activity")?.let(::hms) ?: "-") },
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
    PageView { snap, app ->
        val lines = (snap["logs"] as? JsonArray).orEmpty().mapIndexed { i, e -> parseLog((e as? JsonPrimitive)?.content ?: e.toString(), i) }
        val rows = lines.filter { matchesLog(it, app.param("level", "all"), app.param("q", "")) }
        Panel("logs", "newest first") {
            Toolbar {
                Toggles(app, "level", LEVELS)
                SearchBox(app, "filter logs")
                Muted("${rows.size} of ${lines.size}")
            }
            Table(
                rows,
                listOf(
                    Col("#", 0.4f) { Txt("${it.i + 1}") },
                    Col("time") { Txt(it.ts) },
                    Col("level") { Pill(levelTone(it.level), it.level) },
                    Col("source") { Txt(if (it.source.isEmpty()) "" else "${it.source}/${it.name}") },
                    Col("message", 4f) { Txt(it.msg) },
                ),
                if (lines.isEmpty()) "No log lines yet." else "No line matches the filter.",
            )
        }
    }

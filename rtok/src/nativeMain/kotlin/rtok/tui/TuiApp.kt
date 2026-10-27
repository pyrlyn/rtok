// Port of src/tui/app.rs, first slice: the pure state of `rtok tui` (tab, row cursors, help) over the same
// snapshot `rtok web` serves, the global keys, the Plugins toggle (T15.4) and the Hosts junk panel (T479). Doctor
// fix follows; the Graph tab's registry keys and drill are TuiProjects.kt and TuiDrill.kt; the filters and the Calls expand pane live in TuiFilters.kt.
package rtok.tui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import rtok.web.ProjectRequest
import tui.Key

/** model::pages(): every tab with the snapshot field it renders (the web UI's sidebar is the same list). */
val TABS: List<Pair<String, String>> =
    listOf(
        "overview" to "usage",
        "plugins" to "plugins",
        "calls" to "calls",
        "sessions" to "sessions",
        "doctor" to "doctor",
        "logs" to "logs",
        "skills" to "skills",
        "stats" to "stats",
        "graph" to "graph",
        "hosts" to "hosts",
        "config" to "config",
        "services" to "services",
        "worktrees" to "worktrees",
        "usage" to "agent_usage",
        "tasks" to "tasks",
    )

private const val PAGE_ROWS = 10
private const val MAX_DIGIT = 9

/** What a key asked the loop to do beyond redrawing. */
enum class Effect { NONE, QUIT, REFRESH }

/** `rtok tui` state; [snapshot] is replaced on every tick. */
@Suppress("TooManyFunctions")
class TuiApp(
    startTab: String,
    /** The writes keys may make; null for a read-only app (the tests of navigation). */
    val actions: TuiActions? = null,
) {
    /** app.rs `new`: `[tui] tab` when it names a page, else the first tab. */
    var tab: Int = TABS.indexOfFirst { it.first == startTab }.coerceAtLeast(0)
        private set
    var help = false
        private set
    var snapshot: JsonObject = JsonObject(emptyMap())
        set(v) {
            field = v
            // app.rs `refresh`: a watched re-index reports its end on the next read.
            projects.settle(projectEntries(v))
        }

    /** The Graph tab's registry cursor and the plan being made (T476). */
    val projects = ProjectsState()

    /** The Graph tab's compare view (T485). */
    val compare = TuiCompare()

    /** The Graph tab's one-project view (T561). */
    val drill = TuiDrill()

    /** The Plugins tab's status line: the last toggle's outcome, `""` until one. */
    var pluginStatus = ""
        private set

    /** The Hosts tab's clear-safe-junk panel. */
    val junk = TuiJunk()

    /** The Sessions tab shows only sessions still running (`l`, T60.10). */
    var liveOnly = false
        private set

    /** The Config tab's `/` filter (T228). */
    val configFilter = LineFilter()

    /** The Calls tab's open expand pane (`e`, T60.4), null when closed. */
    var expand: ExpandPane? = null
        private set

    /** The Calls tab's detail pane (Enter/z, T15.5). */
    var callsDetail = false
        private set

    /** The Sessions tab's detail pane (Enter, T60.3). */
    var sessionsDetail = false
        private set
    private val cursors = IntArray(TABS.size)

    val page: String get() = TABS[tab].first
    val field: String get() = TABS[tab].second

    /** The row cursor of the current tab. */
    val cursor: Int get() = cursors[tab].coerceIn(0, (rows() - 1).coerceAtLeast(0))

    /** Rows the current tab lists (the cursor's range). */
    fun rows(): Int = if (page == "sessions") sessionRows().size else fieldRows()

    private fun fieldRows(): Int =
        when (val v = snapshot[field]) {
            is JsonArray -> v.size
            is kotlinx.serialization.json.JsonPrimitive -> if (v.isString) v.content.lines().size else 0
            is JsonObject -> (v["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lines()?.size ?: v.size
            else -> 0
        }

    /** Moves to tab [i], wrapping. */
    fun select(i: Int) {
        tab = Math.floorMod(i, TABS.size)
    }

    private fun move(d: Int) {
        if (page == "calls") expand = null
        cursors[tab] = (cursor + d).coerceIn(0, (rows() - 1).coerceAtLeast(0))
    }

    /** app.rs `key`: Ctrl+C / q / Esc quit, ←/→ (Tab, Shift+Tab) wrap, 1..9 jump, `?` help, `r` re-read, rows move. */
    fun key(k: Key): Effect {
        if (k == Key.Ctrl('c')) return Effect.QUIT
        return (if (page == "graph") graph(k) else null) ?: pageKey(k)
    }

    private fun pageKey(k: Key): Effect {
        val pageKeyed =
            when (page) {
                "calls" -> {
                    calls(k)
                }

                // app.rs `sessions_key`: `l` toggles live-only, Enter the detail pane when rows exist.
                "sessions" -> {
                    when {
                        k == Key.Char("l") -> true.also { liveOnly = !liveOnly }
                        k == Key.Enter && rows() > 0 -> true.also { sessionsDetail = !sessionsDetail }
                        else -> false
                    }
                }

                "config" -> {
                    configFilter.key(k)
                }

                else -> {
                    false
                }
            }
        return if (pageKeyed) Effect.NONE else rest(k)
    }

    /**
     * app.rs `graph_key` (T476): a confirmed plan is written through the web page's function, then the model is
     * re-read so the table shows what the registry now holds. Null when the key is not the registry's.
     */
    private fun graph(k: Key): Effect? {
        val a = actions ?: return null
        val rows = projectEntries(snapshot)
        val target = rows.getOrNull(projects.cursor(rows))?.let { it.id to it.name }
        // Typing goes to the field first; `?` and `r` are the shell's otherwise, then the panel, then the registry.
        return when {
            compare.typing && compare.key(k, target, a) -> Effect.NONE
            k == Key.Char("?") || k == Key.Char("r") -> null
            compare.key(k, target, a) -> Effect.NONE
            else -> registry(k, a, rows)
        }
    }

    private fun registry(
        k: Key,
        a: TuiActions,
        rows: List<rtok.plugins.graph.ProjectRow>,
    ): Effect? =
        when (val r = projects.key(k, rows)) {
            ProjectKey.Ignored -> {
                null
            }

            ProjectKey.Handled -> {
                Effect.NONE
            }

            is ProjectKey.Plan -> {
                runCatching { a.projectWrite(ProjectRequest.Remove(r.id.toString(), false)) }
                    .fold({ projects.confirmRemove(r.id, it) }, { projects.setStatus(it.message ?: it.toString()) })
                Effect.NONE
            }

            is ProjectKey.Write -> {
                val reindexed = (r.req as? ProjectRequest.Reindex)?.project?.toIntOrNull()
                val out = runCatching { a.projectWrite(r.req) }
                if (reindexed != null && out.isSuccess) {
                    // The worker is running: the status line follows it from the next read.
                    projects.watch(reindexed, rows)
                } else {
                    projects.setStatus(
                        out.fold(
                            { if (r.plan.startsWith("select ")) "${r.plan}: done" else it.trim().lines().joinToString("; ") },
                            { it.message ?: it.toString() },
                        ),
                    )
                }
                Effect.REFRESH
            }

            is ProjectKey.Drill -> {
                drill.toggle(r.id, r.name, a::drill)
                Effect.NONE
            }
        }

    /** app.rs `calls_key` with the pane keys: `e` opens / closes, Esc closes, the pane takes `/` and ↑/↓. */
    private fun calls(k: Key): Boolean =
        when {
            expand?.key(k) == true -> true
            expand != null && (k == Key.Esc || k == Key.Char("e")) -> true.also { expand = null }
            k == Key.Char("e") -> true.also { openExpand() }
            (k == Key.Enter || k == Key.Char("z")) && rows() > 0 -> true.also { callsDetail = !callsDetail }
            else -> false
        }

    /** app.rs `open_expand`: the selected call's archive through `expand_payload`; nothing when it has none. */
    private fun openExpand() {
        val row = (snapshot["calls"] as? JsonArray)?.getOrNull(cursor) as? JsonObject ?: return
        val ref = (snapshot["ref_ids"] as? JsonObject)?.s(row["id"].toString().trim('"')) ?: return
        actions?.expand(ref)?.let { expand = ExpandPane(ref, it) }
    }

    private fun rest(k: Key): Effect {
        val a = actions
        val junkKeyed = if (page == "hosts" && a != null) junk.key(k, a) else Keyed.PASS
        return when (junkKeyed) {
            Keyed.TAKEN -> Effect.NONE
            Keyed.WROTE -> Effect.REFRESH
            Keyed.PASS -> (if (page == "plugins") plugins(k) else null) ?: global(k)
        }
    }

    /** app.rs `plugins_key`: ↑/↓ clear the status line; Space/Enter toggle the row under the cursor. */
    private fun plugins(k: Key): Effect? {
        if (k == Key.Up || k == Key.Down) pluginStatus = ""
        val row = (snapshot["plugins"] as? JsonArray)?.getOrNull(cursor) as? JsonObject
        val id = row?.s("id")
        val a = actions
        return when {
            k != Key.Char(" ") && k != Key.Enter -> {
                null
            }

            id == null || a == null -> {
                pluginStatus = "no plugin to toggle"
                Effect.NONE
            }

            else -> {
                pluginStatus = a.toggle(id, !row.on("enabled"))
                if (pluginStatus.startsWith("config set ")) Effect.NONE else Effect.REFRESH
            }
        }
    }

    private fun global(k: Key): Effect =
        when {
            k == Key.Ctrl('c') || k == Key.Char("q") || k == Key.Esc -> {
                Effect.QUIT
            }

            k == Key.Char("r") -> {
                Effect.REFRESH
            }

            else -> {
                nav(k)
                Effect.NONE
            }
        }

    private fun nav(k: Key) {
        when (k) {
            Key.Char("?") -> {
                help = !help
            }

            Key.Left, Key.BackTab -> {
                select(tab - 1)
            }

            Key.Right, Key.Tab -> {
                select(tab + 1)
            }

            Key.Up, Key.Char("k") -> {
                move(-1)
            }

            Key.Down, Key.Char("j") -> {
                move(1)
            }

            Key.PageUp -> {
                move(-PAGE_ROWS)
            }

            Key.PageDown -> {
                move(PAGE_ROWS)
            }

            Key.Home -> {
                cursors[tab] = 0
            }

            Key.End -> {
                cursors[tab] = (rows() - 1).coerceAtLeast(0)
            }

            is Key.Char -> {
                k.c
                    .toIntOrNull()
                    ?.takeIf { it in 1..MAX_DIGIT }
                    ?.let { select(it - 1) }
            }

            else -> {
                Unit
            }
        }
    }
}

private object Math {
    fun floorMod(
        a: Int,
        n: Int,
    ) = ((a % n) + n) % n
}

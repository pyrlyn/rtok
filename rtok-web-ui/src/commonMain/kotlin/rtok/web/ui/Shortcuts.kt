// Port of web/src/palette/shortcuts.ts and Palette.tsx's entry groups: `g <key>` page keys and the palette's
// jump targets (pages, plugins, recent sessions, hosts) with a case-insensitive filter.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** `g <key>` opens a page; picked by hand since first letters collide. */
val GO: Map<String, String> =
    mapOf(
        "overview" to "o",
        "plugins" to "p",
        "calls" to "c",
        "sessions" to "s",
        "doctor" to "d",
        "logs" to "l",
        "skills" to "k",
        "stats" to "t",
        "graph" to "g",
        "hosts" to "h",
        "config" to "f",
        "services" to "v",
        "worktrees" to "w",
        "usage" to "u",
        "tasks" to "b",
    )

/** How long `g` waits for its second key. */
const val GO_WINDOW_MS = 1000.0

/** The page id a second key opens, or null. */
fun pageForKey(key: String): String? = PAGES.firstOrNull { GO[it.id] == key }?.id

/** One palette row: [text] is what the filter matches, [href] where it goes. */
data class Entry(
    val id: String,
    val text: String,
    val label: String,
    val hint: String,
    val href: String,
)

private const val SESSIONS = 30
private const val SESSION_HINT = 8

/** Palette.tsx groups, in order; empty groups are dropped by the view. */
fun paletteGroups(snap: JsonObject?): List<Pair<String, List<Entry>>> {
    val all = snap?.objects("sessions").orEmpty()
    val sessions = all.sortedByDescending { it.num("last_activity") ?: 0.0 }.take(SESSIONS)
    val hosts = all.mapNotNull { it.str("host") }.distinct().sorted()
    return listOf(
        "Pages" to PAGES.map { Entry("page:${it.id}", it.id, it.id, "g ${GO[it.id]}", "/${it.id}") },
        "Plugins" to
            snap?.objects("plugins").orEmpty().map { p ->
                val id = p.str("id") ?: ""
                Entry("plugin:$id", "${p.str("title")} $id", p.str("title") ?: id, id, "/plugins?sel=$id")
            },
        "Sessions" to
            sessions.map { s ->
                val id = s.str("id") ?: ""
                val label = listOfNotNull(s.str("host"), s.str("project")).joinToString(" · ").ifEmpty { "session" }
                Entry("session:$id", listOfNotNull(id, s.str("host"), s.str("project")).joinToString(" "), label, id.take(SESSION_HINT), "/sessions?q=$id")
            },
        "Hosts" to hosts.map { Entry("host:$it", it, it, "", "/hosts") },
    )
}

/** React Aria's `contains` filter, base sensitivity: case-insensitive substring of [Entry.text]. */
fun filterGroups(
    groups: List<Pair<String, List<Entry>>>,
    query: String,
): List<Pair<String, List<Entry>>> {
    val q = query.trim().lowercase()
    return groups
        .map { (t, es) -> t to es.filter { q.isEmpty() || q in it.text.lowercase() } }
        .filter { it.second.isNotEmpty() }
}

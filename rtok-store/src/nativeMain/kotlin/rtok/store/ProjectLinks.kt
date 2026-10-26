// Port of crates/rtok-store/src/project_links.rs (T329.3): directed "A sees into B" links and
// the graph scope they define (the selected project plus everything reachable).
package rtok.store

import rtok.db.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Chains longer than this stop; the visited set already makes cycles safe. */
private const val MAX_LINK_DEPTH = 64

@Serializable
enum class LinkKind(val str: String) {
    @SerialName("manual") Manual("manual"), @SerialName("auto") Auto("auto");
    companion object { fun parse(s: String) = if (s == "auto") Auto else Manual }
}

/** An active link; a remembered (`unlinked`) auto link is not one. */
@Serializable
data class Link(val from: Int, val to: Int, val kind: LinkKind, val reason: String?)

/** `(kind, unlinked)` of the `(from, to)` row, if any. */
private fun Db.linkState(from: Int, to: Int) =
    queryOne("SELECT kind, unlinked FROM project_links WHERE from_id = ? AND to_id = ?", from, to) { LinkKind.parse(it.string(0)) to (it.int(1) == 1) }

/**
 * Link `from` to `to`; true when the registry changed. A duplicate is a no-op, manual takes an auto
 * link over, and an auto link the user removed stays removed until a manual link brings it back.
 */
fun Store.linkProjects(from: Int, to: Int, kind: LinkKind, reason: String?): Boolean {
    if (from == to) throw StoreException("a project cannot link to itself")
    return conn { db -> db.transaction("IMMEDIATE") {
        val cur = db.linkState(from, to)
        when {
            cur == null -> db.execute("INSERT INTO project_links (from_id, to_id, kind, reason) VALUES (?, ?, ?, ?)", from, to, kind.str, reason)
            kind == LinkKind.Manual && (cur.second || cur.first == LinkKind.Auto) -> {
                db.execute("UPDATE project_links SET kind = 'manual', unlinked = 0 WHERE from_id = ? AND to_id = ?", from, to)
                if (reason != null) db.execute("UPDATE project_links SET reason = ? WHERE from_id = ? AND to_id = ?", reason, from, to)
            }
            else -> return@transaction false
        }
        true
    } }
}

/** True when there was an active link: a manual one is deleted, an auto one remembered as removed. */
fun Store.unlinkProjects(from: Int, to: Int): Boolean = conn { db -> db.transaction("IMMEDIATE") {
    val cur = db.linkState(from, to)
    when {
        cur == null || cur.second -> return@transaction false
        cur.first == LinkKind.Manual -> db.execute("DELETE FROM project_links WHERE from_id = ? AND to_id = ?", from, to)
        else -> db.execute("UPDATE project_links SET unlinked = 1 WHERE from_id = ? AND to_id = ?", from, to)
    }
    true
} }

/** Delete `from`'s active auto links whose target is not in `keep`; how many were dropped. */
fun Store.dropAutoLinks(from: Int, keep: List<Int>): Int = conn { db ->
    val notKept = if (keep.isEmpty()) "" else " AND to_id NOT IN (${inList(keep.size)})"
    db.execute("DELETE FROM project_links WHERE from_id = ? AND kind = 'auto' AND unlinked = 0$notKept", from, *keep.toTypedArray())
}

/** Every active link, by `(from, to)`. */
fun Store.projectLinks(): List<Link> = conn {
    it.query("SELECT from_id, to_id, kind, reason FROM project_links WHERE unlinked = 0 ORDER BY from_id, to_id") { r -> Link(r.int(0), r.int(1), LinkKind.parse(r.string(2)), r.stringOrNull(3)) }
}

/** `root` first, then what it links to level by level, each once; a missing project is skipped and not walked through. */
fun Store.projectScope(root: Int): List<Project> = scopeOf(projects(), projectLinks(), root)

internal fun scopeOf(projects: List<Project>, all: List<Link>, root: Int): List<Project> {
    val byId = projects.associateBy { it.id }
    val seen = hashSetOf(root); val out = ArrayList<Project>(); var level = listOf(root)
    repeat(MAX_LINK_DEPTH + 1) {
        val next = ArrayList<Int>()
        for (id in level) {
            val p = byId[id]?.takeIf { !it.missing() } ?: continue
            out += p; all.filter { it.from == id && seen.add(it.to) }.mapTo(next) { it.to }
        }
        if (next.isEmpty()) return out
        level = next
    }
    return out
}

// Port of src/plugins/graph/follow.rs (T329.8): follow the references of a project into other
// projects. Each referenced directory is registered with origin `reference`, linked `auto` from
// the project that names it, and followed up to `reference_depth` levels; `max_auto_projects`
// caps the rows references may add. Both limits are reported, never silent.
package rtok.plugins.graph

import db.isLocked
import rtok.appendLog
import rtok.config.Graph
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.project.Refs
import rtok.store.LinkKind
import rtok.store.Origin
import rtok.store.Project
import rtok.store.Store
import rtok.store.dropAutoLinks
import rtok.store.dropToollessSessionProjects
import rtok.store.linkProjects
import rtok.store.projectByRoot
import rtok.store.projectLinks
import rtok.store.projects
import rtok.store.registerProject
import rtok.store.retryLocked

/** Why a branch of references was not followed. */
sealed class Stop {
    data class Depth(
        val project: String,
        val limit: UInt,
    ) : Stop() {
        override fun toString() = "reference_depth $limit reached at $project: its references are not followed"
    }

    data class Cap(
        val dir: String,
        val limit: UInt,
    ) : Stop() {
        override fun toString() = "max_auto_projects $limit reached: $dir was not added"
    }
}

class Followed {
    /** Projects this run added to the registry; they still need an index. */
    val registered = ArrayList<Project>()
    var linked = 0
    var dropped = 0
    val stops = ArrayList<Stop>()
    val warnings = ArrayList<String>()
}

object Follow {
    /** Same bound as a worktree claim (T934). */
    private const val LOCK_ATTEMPTS = 8
    private const val LOCK_PAUSE_MS = 250L

    /** Walk the references of the project registered for [root]; nothing when off or unregistered. */
    fun follow(
        store: Store,
        cfg: Graph,
        root: String,
    ): Followed {
        val out = Followed()
        val start = store.projectByRoot(root)?.takeIf { cfg.autoLinkReferences } ?: return out
        var auto = store.projects().count { it.origin == Origin.Reference }
        val seen = hashSetOf(start.id)
        var level = listOf(start)
        var depth = 0u
        while (level.isNotEmpty()) {
            val active = store.projectLinks()
            val next = ArrayList<Project>()
            for (p in level) {
                val found = Refs.discover(p.root)
                if (depth >= cfg.referenceDepth) {
                    if (found.refs.isNotEmpty()) out.stops += Stop.Depth(p.displayName(), cfg.referenceDepth)
                    continue
                }
                val unread = found.warnings.any { it.contains(": not read:") }
                found.warnings.mapTo(out.warnings) { "${p.displayName()}: $it" }
                val keep = ArrayList<Int>()
                for (r in found.refs) {
                    val target =
                        store.projectByRoot(r.dir) ?: if (auto >= cfg.maxAutoProjects.toInt()) {
                            out.stops += Stop.Cap(r.dir, cfg.maxAutoProjects)
                            continue
                        } else {
                            auto++
                            store.registerProject(r.dir, Origin.Reference).also { out.registered += it }
                        }
                    if (target.id == p.id) continue
                    val made = store.linkProjects(p.id, target.id, LinkKind.Auto, r.reason)
                    if (made) out.linked++
                    keep += target.id
                    // A link the user removed neither comes back nor leads anywhere.
                    val live = made || active.any { it.from == p.id && it.to == target.id }
                    if (live && seen.add(target.id)) next += target
                }
                // A manifest that could not be read says nothing about what it references.
                if (!unread) out.dropped += store.dropAutoLinks(p.id, keep)
            }
            level = next
            depth++
        }
        return out
    }

    /** Index the projects a [follow] added. One failing project is logged and skipped. */
    fun indexNew(
        rt: Runtime,
        followed: Followed,
    ) {
        for (p in followed.registered) {
            try {
                Index.run(Ctx(rt), p.root, false)
            } catch (e: Exception) {
                appendLog(rt.config, "warn", "graph", "references", "indexing reference ${p.root} failed: ${e.message}")
            }
        }
    }

    /** [follow] with limits and failures logged; fails open, and a lock that stays is skipped silently. */
    fun refresh(
        rt: Runtime,
        root: String,
    ): Followed = refreshWith(rt, root, LOCK_ATTEMPTS, LOCK_PAUSE_MS)

    internal fun refreshWith(
        rt: Runtime,
        root: String,
        attempts: Int,
        pauseMs: Long,
    ): Followed {
        val cfg = rt.config.plugins.graph
        return try {
            val f =
                retryLocked(attempts, pauseMs) {
                    rt.store.dropToollessSessionProjects()
                    follow(rt.store, cfg, root)
                }
            for (line in f.stops.map { it.toString() } + f.warnings) appendLog(rt.config, "warn", "graph", "references", line)
            f
        } catch (e: Exception) {
            if (!isLocked(e)) appendLog(rt.config, "warn", "graph", "references", "following references of $root failed: ${e.message}")
            Followed()
        }
    }

    /** What `rtok graph index` prints after its own index: follow, index what was added, say what changed. */
    fun report(
        rt: Runtime,
        root: String,
    ): List<String> {
        val f = refresh(rt, root)
        indexNew(rt, f)
        if (f.registered.isEmpty() && f.linked == 0 && f.dropped == 0) return emptyList()
        return listOf("references: ${f.registered.size} projects added, ${f.linked} links made, ${f.dropped} dropped") + f.stops.map { it.toString() }
    }
}

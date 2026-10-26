// Port of crates/rtok-store/src/projects.rs (T329.1): the graph's project registry, keyed by
// canonical root so the symbol index never mixes repos.
package rtok.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.sys.canonicalize
import rtok.sys.homeDir
import rtok.sys.isDir
import rtok.sys.isUnwalkableRoot

/** Canonical absolute path as one string: the index key of a root. */
fun canonRoot(p: String): String = (canonicalize(p) ?: p).replace('\\', '/')

/** How a project got into the registry (T329 §1). */
@Serializable
enum class Origin(val str: String) {
    @SerialName("manual") Manual("manual"), @SerialName("session") Session("session"), @SerialName("worktree") Worktree("worktree"),
    @SerialName("mcp") Mcp("mcp"), @SerialName("reference") Reference("reference");
    companion object {
        /** An unknown value (a hand-edited db) reads as `manual`, so the registry stays listable. */
        fun parse(s: String) = entries.firstOrNull { it.str == s } ?: Manual
    }
}

@Serializable
data class Project(
    val id: Int, val root: String, val name: String?, val origin: Origin,
    @SerialName("created_at") val createdAt: Long, @SerialName("last_used_at") val lastUsedAt: Long, val selected: Boolean,
) {
    fun displayName(): String = name ?: root.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() && it != ".." } ?: root
    /** Read from the filesystem each time, never stored. */
    fun missing(): Boolean = !isDir(root)
}

/** The project to answer for, and whether it replaced a stored selection that is gone. */
data class Resolved(val project: Project?, val fellBack: Boolean)

private const val PROJECT_COLS = "id, root, name, origin, created_at, last_used_at, selected"
private fun project(r: Row) = Project(r.int(0), r.string(1), r.stringOrNull(2), Origin.parse(r.string(3)), r.long(4), r.long(5), r.int(6) == 1)
private fun Db.projectWhere(where: String, vararg args: Any?) = queryOne("SELECT $PROJECT_COLS FROM projects WHERE $where LIMIT 1", *args, map = ::project)

/** Register `path` (canonicalised) or only refresh last-used; the first origin stands. */
fun Store.registerProject(path: String, origin: Origin): Project {
    val root = canonRoot(path)
    return conn { db ->
        db.execute("INSERT INTO projects (root, origin) VALUES (?, ?) ON CONFLICT (root) DO UPDATE SET last_used_at = unixepoch()", root, origin.str)
        db.projectWhere("root = ?", root)!!
    }
}

/** T329.6: register a directory seen in use; `/`, `$HOME` and non-directories are skipped. `name` labels only a row this call created. */
fun Store.autoAddProject(path: String, origin: Origin, name: String?) {
    if (!isDir(path) || isUnwalkableRoot(path, homeDir())) return
    val p = registerProject(path, origin)
    if (p.origin == origin && p.name == null && name != null && name.isNotBlank()) renameProject(p.id, name)
}

fun Store.projects(): List<Project> = conn { it.query("SELECT $PROJECT_COLS FROM projects ORDER BY id", map = ::project) }
fun Store.project(id: Int): Project? = conn { it.projectWhere("id = ?", id) }
fun Store.projectByRoot(path: String): Project? = conn { it.projectWhere("root = ?", canonRoot(path)) }

/** Set or (null / blank) clear the display name; false for an unknown id. */
fun Store.renameProject(id: Int, name: String?): Boolean {
    val n = name?.trim()?.takeIf { it.isNotEmpty() }
    return conn { it.execute("UPDATE projects SET name = ? WHERE id = ?", n, id) == 1 }
}

fun Store.selectedProject(): Project? = conn { it.projectWhere("selected = 1") }

/** Make `id` the one selected project; false (selection untouched) for an unknown id. */
fun Store.selectProject(id: Int): Boolean = conn { db ->
    db.transaction("IMMEDIATE") {
        if (db.longOf("SELECT EXISTS (SELECT 1 FROM projects WHERE id = ?)", id) != 1L) return@transaction false
        db.execute("UPDATE projects SET selected = 0 WHERE selected = 1")
        db.execute("UPDATE projects SET selected = 1, last_used_at = unixepoch() WHERE id = ?", id)
        true
    }
}

/** A live stored selection stands; otherwise `cwd`'s project is selected and `fellBack` says a stored one was replaced. */
fun Store.resolveProject(cwd: String): Resolved {
    val stored = selectedProject()
    if (stored != null && !stored.missing()) return Resolved(stored, false)
    val here = projectByRoot(cwd)?.takeIf { !it.missing() } ?: return Resolved(stored, false)
    selectProject(here.id)
    return Resolved(project(here.id), stored != null)
}

/** Drop the project's index rows and its registry row; files are never touched. False for an unknown id. */
fun Store.removeProject(id: Int): Boolean = conn { db ->
    db.transaction("IMMEDIATE") {
        val root = db.stringOf("SELECT root FROM projects WHERE id = ?", id) ?: return@transaction false
        for (t in listOf("symbols", "extractor", "file_rank", "symbol_stale")) db.execute("DELETE FROM $t WHERE root = ?", root)
        db.execute("DELETE FROM projects WHERE id = ?", id)
        true
    }
}

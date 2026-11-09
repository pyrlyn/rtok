// `rtok task migrate`: turn a legacy `files` task directory into a board and the old `[tasks.disk]` keys of the
// checkout's `.rtok.toml` into `[tasks]`.
package rtok.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pyrlyn.taskboard.BoardOptions
import pyrlyn.taskboard.Commit
import pyrlyn.taskboard.files.FileStore
import rtok.config.ProjectRoot
import rtok.config.Tasks
import rtok.config.writeFile
import rtok.sys.IoException
import rtok.sys.exists
import rtok.sys.readToString
import rtok.sys.removeFile
import tomlaot.TomlDoc
import tomlaot.TomlValue

@Serializable
data class Moved(
    val id: String,
    val from: String,
    val to: String,
)

@Serializable
data class MigrateReport(
    @SerialName("dry_run") val dryRun: Boolean,
    val moved: List<Moved>,
    val config: List<String>,
) {
    fun text(): String {
        if (moved.isEmpty() && config.isEmpty()) return "nothing to migrate\n"
        val verb = if (dryRun) "would move" else "moved"
        return buildString {
            for (m in moved) append("${m.id}: ${m.from} -> ${m.to}\n")
            for (c in config) append("config: $c\n")
            append("$verb ${moved.size} ${if (moved.size == 1) "task" else "tasks"}${if (config.isEmpty()) "" else ", ${config.size} config ${if (config.size == 1) "change" else "changes"}"}\n")
        }
    }
}

fun migrateTasks(
    cfg: Tasks,
    cwd: String,
    dryRun: Boolean,
): MigrateReport {
    if (cfg.adapter != "disk") bail("rtok task migrate: [tasks] adapter is ${cfg.adapter}, and only the disk tasks have a layout to migrate")
    val root = ProjectRoot.find(cwd) ?: bail("rtok task migrate: not inside a git checkout")
    val dir = boardDirOf(cfg, root)
    val wanted = cfg.layoutName()
    val layout = if (wanted == "auto") detectLayout(dir) else wanted
    if (layout == "plan") bail("rtok task migrate: $dir is the workspace plan/ convention and stays as it is")
    val base = if (cfg.shared) root.main else root.checkout
    val moved = if (layout == "files") moveFiles(dir, base, dryRun) else emptyList()
    return MigrateReport(dryRun, moved, migrateConfig("${root.checkout}/.rtok.toml", dryRun))
}

private fun moveFiles(
    dir: String,
    base: String,
    dryRun: Boolean,
): List<Moved> {
    val entries = DiskAdapter(dir).entries()

    fun rel(path: String) = path.removePrefix("$base/")
    if (!dryRun && entries.isNotEmpty()) {
        // One commit moves every task, `updated` kept: a migration moves tasks, it does not edit them. The board reads
        // the old names in `done/` (the old archive's name) and replaces them in the same commit.
        onBoard { FileStore(dir).locked(BoardOptions().lockTimeout) { it.commit(Commit(put = entries.map { (_, task) -> task.lib() })) } }
        for ((old, _) in entries) if (exists(old)) removeFile(old)
        val oldLock = "$dir/.claim.lock"
        if (exists(oldLock)) removeFile(oldLock)
    }
    return entries.map { (old, task) -> Moved(task.id.toString(), rel(old), rel("$dir/${task.status.str}/${task.id}-${unicodeSlug(task.title)}.md")) }
}

/** Rewrite `[tasks.disk] dir/layout` as `[tasks]` keys and drop `layout = "files"`, keeping every other line. */
private fun migrateConfig(
    path: String,
    dryRun: Boolean,
): List<String> {
    val text =
        try {
            readToString(path)
        } catch (e: IoException) {
            if (e.kind == IoException.Kind.NotFound) return emptyList() else bail("$path: ${e.message}")
        }
    val doc = runCatching { TomlDoc.parse(text) }.getOrElse { bail("$path: not valid TOML") }
    val changes = mutableListOf<String>()
    val disk = doc.table("tasks.disk")
    if (disk != null) {
        disk.str("dir")?.let { dir ->
            val tasks = doc.ensureTable("tasks")
            if (!tasks.containsKey("dir")) tasks["dir"] = TomlValue.Str(dir)
            disk.remove("dir")
            changes += "[tasks.disk] dir is now [tasks] dir"
        }
        disk.str("layout")?.let { layout ->
            if (layout == "folders") doc.ensureTable("tasks").takeIf { !it.containsKey("layout") }?.set("layout", TomlValue.Str("plan"))
            disk.remove("layout")
            changes += if (layout == "folders") "[tasks.disk] layout = \"folders\" is now [tasks] layout = \"plan\"" else "[tasks.disk] layout = \"$layout\" dropped: auto picks the board"
        }
        if (disk.keys().isEmpty()) doc.removeTable("tasks.disk")
    }
    doc.table("tasks")?.takeIf { it.str("layout") == "files" }?.let {
        it.remove("layout")
        changes += "[tasks] layout = \"files\" dropped: auto picks the board"
    }
    if (changes.isNotEmpty() && !dryRun) writeFile(path, doc.toString())
    return changes
}

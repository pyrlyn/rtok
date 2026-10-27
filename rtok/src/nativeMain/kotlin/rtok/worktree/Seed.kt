// Port of src/worktree/seed.rs (T953): a new worktree starts with the build caches of the sibling checkout that built
// them last, cloned copy-on-write where the file system shares blocks. Best effort: the worktree exists before this runs.
package rtok.worktree

import rtok.sys.Cloned
import rtok.sys.EntryKind
import rtok.sys.canon
import rtok.sys.cloneTree
import rtok.sys.components
import rtok.sys.eprint
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDirOrEmpty
import rtok.sys.parent
import rtok.sys.removeTree
import rtok.sys.symlinkMeta

object Seed {
    /** One cache filled: where in the checkout ([dir], relative), from which sibling, and how the files went. */
    data class Seeded(
        val dir: String,
        val source: String,
        val files: Cloned,
    )

    /** Seeds [checkout] from the other worktrees of its repository; a listing git refuses leaves the caches empty. */
    fun fromSiblings(
        cwd: String,
        checkout: String,
        dirs: List<String>,
    ) {
        runCatching { Git.list(cwd) }
            .onSuccess { list -> run(list.filter { it.prunable == null }.map { it.path }, checkout, dirs) }
            .onFailure { eprint("warning: caches not seeded: ${it.message}\n") }
    }

    /** Fills each [dirs] position of [checkout] a sibling has and [checkout] lacks; a failed cache is removed. */
    fun run(
        siblings: List<String>,
        checkout: String,
        dirs: List<String>,
    ): List<Seeded> {
        val seeded = mutableListOf<Seeded>()
        for (dir in positions(checkout, dirs)) {
            val source = freshest(siblings, checkout, dir) ?: continue
            val dest = join(checkout, dir)
            runCatching { cloneTree(join(source, dir), dest) }
                .onSuccess { files ->
                    eprint("seeded $dir from $source: ${files.cloned} cloned, ${files.copied} copied\n")
                    seeded += Seeded(dir, source, files)
                }.onFailure { e ->
                    // A half-copied cache could hold outputs whose mtimes were not kept, which a build would trust.
                    runCatching { removeTree(dest) }
                    eprint("warning: $dir not seeded: ${e.message}\n")
                }
        }
        return seeded
    }

    /**
     * The relative paths [dirs] names in [checkout]: each entry, and for a bare name the same name inside every
     * visible top-level directory. Only free places count (parent exists, nothing there yet); absolute entries and
     * ones with `..` are refused, so a project config cannot point the copy outside the checkout.
     */
    internal fun positions(
        checkout: String,
        dirs: List<String>,
    ): List<String> {
        val tops = listDirOrEmpty(checkout).filter { !it.startsWith('.') && isRealDir(join(checkout, it)) }.sorted()
        val out = mutableListOf<String>()
        for (dir in dirs) {
            val parts = components(dir)
            if (!plain(dir, parts)) continue
            val nested = if (parts.size == 1) tops.map { join(it, dir) } else emptyList()
            for (rel in listOf(dir) + nested) {
                val at = join(checkout, rel)
                val free = parent(at)?.let(::isDir) == true && runCatching { symlinkMeta(at) }.isFailure
                if (free && rel !in out) out += rel
            }
        }
        return out
    }

    /** Relative and never climbing: no `..`, `.` or root component. */
    private fun plain(
        dir: String,
        parts: List<String>,
    ) = dir.isNotEmpty() && !dir.startsWith('/') && parts.none { it == ".." || it == "." || it == "/" }

    /** The sibling whose [dir] was built most recently; a link is never a source. */
    internal fun freshest(
        siblings: List<String>,
        checkout: String,
        dir: String,
    ): String? {
        val self = canon(checkout)
        return siblings
            .filter { canon(it) != self && isRealDir(join(it, dir)) }
            .maxByOrNull { built(join(it, dir)) }
    }

    /** The newest mtime of [dir], its entries and theirs: two levels reach what every build touches. */
    private fun built(dir: String): Long {
        fun mtime(p: String) = runCatching { symlinkMeta(p).mtimeNanos }.getOrDefault(0L)
        var newest = mtime(dir)
        for (child in listDirOrEmpty(dir).map { join(dir, it) }) {
            newest = maxOf(newest, mtime(child))
            if (isRealDir(child)) listDirOrEmpty(child).forEach { newest = maxOf(newest, mtime(join(child, it))) }
        }
        return newest
    }

    private fun isRealDir(p: String) = runCatching { symlinkMeta(p).kind == EntryKind.Dir }.getOrDefault(false)
}

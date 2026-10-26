// Port of src/plugins/graph/tally.rs (T329.36): what a `symbol`, `callers` or `impact` call found, counted by the
// backends where they hold structured rows. The record is thread-local and armed only by [Tally.arm] (the MCP
// call), so the CLI and the hooks record nothing.
package rtok.plugins.graph

import kotlin.native.concurrent.ThreadLocal

/** What one call returned: asked symbols listed, distinct files, contributing projects. */
data class TallyCounts(val symbols: Int = 0, val files: Int = 0, val projects: Int = 0)

@ThreadLocal
object Tally {
    private class Hit(val root: String, var name: String, val file: String?)
    private var hits: MutableList<Hit>? = null

    /** Starts a record for the call on this thread, dropping any earlier one. */
    fun arm() { hits = ArrayList() }

    /** Stops recording and returns the counts; zeros when nothing was armed. */
    fun take(): TallyCounts {
        val h = hits.orEmpty(); hits = null
        return TallyCounts(
            symbols = h.filter { it.name.isNotEmpty() }.map { it.name }.toSet().size,
            files = h.mapNotNull { x -> x.file?.let { x.root to it } }.toSet().size,
            projects = h.map { it.root }.toSet().size,
        )
    }

    /** The record's length now, for [rewind]. */
    fun mark(): Int = hits?.size ?: 0

    /** Drops what was recorded since [mark]: an LSP answer the tags index replaces must not count twice. */
    fun rewind(mark: Int) { hits?.let { while (it.size > mark) it.removeAt(it.lastIndex) } }

    /** Credits the rows recorded since [mark] to [name]; an empty name keeps the rows without crediting a symbol. */
    fun rename(mark: Int, name: String) { hits?.drop(mark)?.forEach { it.name = name } }

    /** [name] has rows in [root], in [files] (none for a call chain, which names no file). */
    fun hit(root: String, name: String, files: Iterable<String>) {
        val h = hits ?: return
        val before = h.size
        for (f in files) h += Hit(root, name, f)
        if (h.size == before) h += Hit(root, name, null)
    }
}

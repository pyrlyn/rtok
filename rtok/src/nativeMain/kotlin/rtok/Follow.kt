// Port of src/log.rs `Follow` (T24.3, T589): the incremental reader `rtok logs watch` follows the live log with. It keeps
// the file it was reading open, so a rotation (`path` renamed to `path.1`, the next write recreating `path`) is told
// by file identity, not by what the lines say — two generations can open with the same line — and the unread tail of
// the old file still comes off the open handle, wherever the rename left it or once retention has deleted it. Also
// `watch`: `rtok logs watch`, the last-n screen and then the live file followed through it.
package rtok

import rtok.config.Config
import rtok.log.WatchTick
import rtok.log.watchLoop
import rtok.sys.FileHandle

class Follow private constructor(
    private var reading: FileHandle?,
    private var consumed: Int,
) : AutoCloseable {
    constructor() : this(null, 0)

    /**
     * The lines of [path] written since the last call, oldest first. A file renamed away and not yet recreated reads
     * as empty and keeps the state, so whatever appears at [path] next is read as the new file it is.
     */
    fun poll(path: String): List<String> {
        // Not there (renamed away) or unreadable now: a delay, not a rotation.
        val (handle, lines) = openLines(path) ?: return emptyList()
        return if (handle.sameFile(reading)) {
            handle.close()
            // Fewer lines than consumed: the same file was truncated in place; start over.
            val at = if (lines.size >= consumed) consumed else 0
            consumed = lines.size
            lines.drop(at)
        } else {
            rotated(path, handle, lines)
        }
    }

    /**
     * A rotation: carry the tail of the file we were reading — a rotation between two polls must cost no line — then
     * the generations that rotated past us, then the newcomer [handle] from its start.
     */
    private fun rotated(
        path: String,
        handle: FileHandle,
        lines: List<String>,
    ): List<String> {
        val fresh = mutableListOf<String>()
        reading?.readAll()?.let { fresh += wholeLines(it).drop(consumed) }
        rotatedPast(path).forEach { fresh += it }
        reading?.close()
        // The newcomer is returned whole, so all of it is consumed.
        consumed = lines.size
        reading = handle
        fresh += lines
        return fresh
    }

    /** The siblings newer than the file we were reading, oldest first; older ones were fully returned before they rotated. */
    private fun rotatedPast(path: String): List<List<String>> {
        val pieces = ArrayDeque<List<String>>()
        var i = 1
        // Rotation is contiguous (`logTail` counts on that too); siblings run newest-old first, so prepend.
        var next = openLines(rtok.log.rotatedPath(path, i))
        while (next != null) {
            val (sibling, siblingLines) = next
            val ours = sibling.sameFile(reading)
            sibling.close()
            if (ours) break
            pieces.addFirst(siblingLines)
            next = openLines(rtok.log.rotatedPath(path, ++i))
        }
        return pieces
    }

    override fun close() {
        reading?.close()
        reading = null
    }

    companion object {
        /**
         * A follow already caught up with [path], and the lines it holds: every one counts as shown, matching the first
         * screen built from the same read, so the two cannot disagree about a line written between two reads. A file
         * that is not there yet primes an empty follow.
         */
        fun primed(path: String): Pair<Follow, List<String>> {
            val (handle, live) = openLines(path) ?: return Follow() to emptyList()
            return Follow(handle, live.size) to live
        }

        /** Open [path] and read its whole lines through that one handle, so identity and lines describe the same file. */
        private fun openLines(path: String): Pair<FileHandle, List<String>>? {
            val handle = FileHandle.open(path) ?: return null
            return handle.readAll()?.let { handle to wholeLines(it) } ?: null.also { handle.close() }
        }
    }
}

/**
 * `rtok logs watch`: [tty] decides repaint-in-place over plain appending. Runs until killed or reparented; a reader
 * that left ([write] false, e.g. `| head`) ends it quietly.
 */
fun logsWatch(
    cfg: Config,
    n: Int?,
    tty: Boolean,
    write: (String) -> Boolean,
) {
    val want = n ?: cfg.logLines()
    val path = cfg.fileLog().path
    // One read of the live file feeds both the first screen and the follow state: a line written after it is new to
    // both, a line in it old to both — never shown twice, never skipped.
    val (follow, live) = Follow.primed(path)
    follow.use {
        val window = logTailWith(live, path, want).toMutableList() // newest first
        var shown = window.size // rows already printed; piped rows keep counting past them
        var first = true
        watchLoop(tty, write = write) {
            if (first) {
                first = false
                val rows = logScreen(window)
                return@watchLoop WatchTick(rows, rows)
            }
            val fresh = follow.poll(path) // arrival order, oldest first
            if (fresh.isEmpty()) return@watchLoop WatchTick(emptyList(), logScreen(window))
            for (line in fresh) window.add(0, line) // newest goes to the top
            while (window.size > want) window.removeLast()
            // A TTY repaint renumbers everything, `1` newest; a pipe cannot renumber what it printed, so its rows keep
            // counting past the screen: each line once, each number once.
            val rows = fresh.mapIndexed { i, line -> "${shown + i + 1} $line" }
            shown += fresh.size
            WatchTick(rows, logScreen(window))
        }
    }
}

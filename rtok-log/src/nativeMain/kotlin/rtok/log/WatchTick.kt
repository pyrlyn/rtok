// Port of src/log.rs `WatchTick`, `watch_loop` and `repaint` (T24.3, T25.3): the one poll-and-print loop every
// `watch` command runs. A TTY repaints the screen in place (cursor up, clear below; no raw mode, no alternate screen);
// a pipe appends only the fresh rows.
package rtok.log

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getppid
import platform.posix.usleep

/** One step: [fresh] is what a pipe prints now, [screen] what a terminal shows once the step lands. */
data class WatchTick(
    val fresh: List<String>,
    val screen: List<String>,
)

/** `WATCH_POLL`: 200 ms between steps. */
const val WATCH_POLL_MS = 200L

private const val US_PER_MS = 1000L

/** Redraw in place: up [prev] rows to column 1 (CPL), clear to the end of the screen (ED), print [rows]. */
fun repaint(
    prev: Int,
    rows: List<String>,
): String =
    buildString {
        if (prev > 0) append("\u001b[${prev}F")
        append("\u001b[J")
        rows.forEach { append(it).append('\n') }
    }

/**
 * Every [intervalMs], [step] says what changed; null ends the watch, and so does being reparented (T235.3: a watch
 * whose terminal went away would poll forever). [write] returns false once the reader left (a closed pipe).
 */
@OptIn(ExperimentalForeignApi::class)
fun watchLoop(
    tty: Boolean,
    intervalMs: Long = WATCH_POLL_MS,
    parent: () -> Int = { getppid() },
    sleep: (Long) -> Unit = { usleep((it * US_PER_MS).toUInt()) },
    write: (String) -> Boolean,
    step: () -> WatchTick?,
) {
    var prev = 0
    val first = parent()
    var tick = step()
    while (tick != null && parent().let { it == first && it != 1 }) {
        if (tick.fresh.isNotEmpty() || tick.screen.size != prev) {
            val text = if (tty) repaint(prev, tick.screen) else tick.fresh.joinToString("") { "$it\n" }
            if (!write(text)) break
            prev = tick.screen.size
        }
        sleep(intervalMs)
        tick = step()
    }
}

// Port of src/tui/mod.rs: the `rtok tui` event loop. Alternate screen and raw mode until q / Esc / Ctrl+C;
// the terminal is restored on every exit path. Every `[tui] tick_secs` (and on `r`) the model is re-read
// through the same Snapshot `rtok web` serves, on a background worker so keys never wait on the model.
@file:OptIn(ExperimentalForeignApi::class)

package rtok.tui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.time_tVar
import platform.posix.tm
import rtok.config.Config
import rtok.model.Snapshot
import tui.Buffer
import tui.Terminal
import tui.isInteractive

/** How long the loop waits for a key before it checks the tick. */
private const val FRAME_MS = 100
private const val MS = 1000L

/** mod.rs `check_tty`: the guard decides on the two tty flags alone. */
fun checkTty(
    stdin: Boolean,
    stdout: Boolean,
): String? = if (stdin && stdout) null else "rtok tui needs a terminal on stdin and stdout; run it interactively, not piped"

/** Local `HH:MM:SS` of unix seconds. */
fun localHms(ts: Double): String =
    memScoped {
        val t = alloc<time_tVar>().apply { value = ts.toLong().convert() }
        val out = alloc<tm>()
        localtime_r(t.ptr, out.ptr)
        val p = { n: Int -> n.toString().padStart(2, '0') }
        "${p(out.tm_hour)}:${p(out.tm_min)}:${p(out.tm_sec)}"
    }

/** `rtok tui`: runs until a quit key. Throws [IllegalStateException] when not on a terminal. */
fun run(cfg: Config) {
    checkTty(isInteractive(), isInteractive())?.let { error(it) }
    // `max(1)`: a zero cadence would busy-read; `config validate` rejects it in the file.
    val tick =
        cfg.tui.tickSecs
            .toLong()
            .coerceAtLeast(1) * MS
    val actions = TuiActions(cfg)
    val app = TuiApp(cfg.tui.tab, actions)
    app.background(Snapshot::build)
    Terminal().use { term ->
        term.open()
        val start =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        var next = 0L
        var size = 0 to 0
        while (true) {
            val now = start.elapsedNow().inWholeMilliseconds
            if (now >= next) {
                app.request(actions.cfg, Snapshot::build)
                next = now + tick
            }
            val s = term.size()
            if (s != size) {
                term.invalidate()
                size = s
            }
            app.poll()
            app.tick()
            val b = Buffer(s.first, s.second)
            draw(b, app, ::localHms)
            term.draw(b)
            val effects = term.poll(FRAME_MS).map(app::key)
            if (Effect.QUIT in effects) break
            if (Effect.REFRESH in effects) next = 0
        }
    }
}

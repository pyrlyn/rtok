// Port of src/agents/restart.rs, the process check other code uses so far (`Procs`, `RealProcs` for macOS, `host_running`).
// The quit/write/reopen flow of `agents install|uninstall` comes with the full restart port.
package rtok.agents

import rtok.config.Config
import rtok.sys.ProgressRunner
import rtok.sys.fileName

/** Process control seam (T141): the real impl shells out; tests fake it. */
interface Procs {
    /** True if a desktop app named [name] runs now; never launches it. Not for CLI binaries (see [binRunning]). */
    fun appRunning(name: String): Boolean

    /** True if a CLI binary named [bin] runs now (`pgrep -x`, never `osascript`'s app-name check). */
    fun binRunning(bin: String): Boolean
}

/** Real process checks: `osascript` for a desktop app on macOS, `pgrep -x` for a CLI binary. */
object RealProcs : Procs {
    override fun appRunning(name: String): Boolean =
        runCatching {
            val (out, _) = ProgressRunner("", "osascript", listOf("-e", "application \"$name\" is running")).quiet().runCapped(CAP)
            out.stdout.decodeToString().trim() == "true"
        }.getOrDefault(false)

    override fun binRunning(bin: String): Boolean = pgrepRunning(bin)

    /** `pgrep -x <name>`: an exact process-name match; only the exit status is read. */
    private fun pgrepRunning(name: String): Boolean =
        runCatching {
            ProgressRunner("", "pgrep", listOf("-x", name))
                .quiet()
                .runCapped(CAP)
                .first.exitCode == 0
        }.getOrDefault(false)

    private const val CAP = 64L * 1024
}

/** The display/process name read from one app path: its file name without `.app`, else [fallback]. */
internal fun desktopAppName(
    path: String,
    fallback: String,
): Pair<String, String> = (fileName(path)?.removeSuffix(".app") ?: fallback) to path

/** The first app path of a desktop variant that [procs] reports as running. */
internal fun runningDesktopApp(
    cfg: Config,
    host: String,
    v: Variant,
    procs: Procs,
): Pair<String, String>? =
    Locate
        .appPaths(cfg, host, v)
        .map { desktopAppName(it, v.name) }
        .firstOrNull { procs.appRunning(it.first) }

/** The first CLI binary of [host] that [procs] reports as running. */
internal fun runningCliBin(
    host: Host,
    procs: Procs,
): String? =
    host.variants
        .filter { it.kind == Kind.Cli }
        .firstNotNullOfOrNull { v -> v.bins.firstOrNull(procs::binRunning) }

/** Whether a CLI binary or an installed desktop app of [host] runs now; only installed apps are asked. */
fun hostRunning(
    cfg: Config,
    host: Host,
    procs: Procs,
): Boolean =
    runningCliBin(host, procs) != null ||
        host.variants
            .filter { it.kind == Kind.Desktop }
            .filter { Locate.present(cfg, host.id, it) }
            .any { runningDesktopApp(cfg, host.id, it, procs) != null }

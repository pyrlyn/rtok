// Port of src/agents/restart.rs (T141): close a running desktop app before `agents install|uninstall|update` writes its
// config and reopen it after. `Procs`/`RealProcs` (macOS: `osascript`, `pgrep -x`, `open -a`), `host_running` and the
// quit/write/reopen flow (`Restart.run`, `withRestart`).
package rtok.agents

import rtok.config.Config
import rtok.sys.ProgressRunner
import rtok.sys.fileName

/** A process call that failed: it did not start or exited non-zero. */
class ProcError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Process control seam (T141): the real impl shells out; tests fake it. */
interface Procs {
    /** True if a desktop app named [name] runs now; never launches it. Not for CLI binaries (see [binRunning]). */
    fun appRunning(name: String): Boolean

    /** True if a CLI binary named [bin] runs now (`pgrep -x`, never `osascript`'s app-name check). */
    fun binRunning(bin: String): Boolean

    /** Ask a running desktop app to quit. @throws ProcError when it could not be asked. */
    fun quit(name: String)

    /** Launch a desktop app again. @throws ProcError when it did not start. */
    fun open(
        name: String,
        path: String,
    )
}

/** Real process checks: `osascript` for a desktop app on macOS, `pgrep -x` for a CLI binary. */
object RealProcs : Procs {
    override fun appRunning(name: String): Boolean =
        runCatching {
            val (out, _) = ProgressRunner("", "osascript", listOf("-e", "application \"$name\" is running")).quiet().runCapped(CAP)
            out.stdout.decodeToString().trim() == "true"
        }.getOrDefault(false)

    override fun binRunning(bin: String): Boolean = pgrepRunning(bin)

    override fun quit(name: String) {
        // Defense in depth: never let this path quit the process it runs under.
        if (name.equals("rtok", ignoreCase = true)) return
        runOk(ProgressRunner("closing $name…", "osascript", listOf("-e", "tell application \"$name\" to quit")), "osascript quit $name")
    }

    override fun open(
        name: String,
        path: String,
    ) = runOk(ProgressRunner("reopening $name…", "open", listOf("-a", name)), "open -a $name")

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

/**
 * Run [cmd] to completion and fail on a non-zero exit, carrying its stderr: `open -a` on an app that is still shutting
 * down exits non-zero (T434).
 */
internal fun runOk(
    cmd: ProgressRunner,
    what: String,
) {
    val out = runCatching { cmd.runCapped(RUN_CAP).first }.getOrElse { throw ProcError("$what: ${it.message}", it) }
    if (out.exitCode != 0) {
        val status = out.exitCode?.let { "exit status: $it" } ?: "signal"
        throw ProcError("$what: $status ${out.stderr.decodeToString().trim()}")
    }
}

private const val RUN_CAP = 64L * 1024

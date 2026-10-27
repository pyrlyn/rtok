@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.demon

import rtok.config.Config
import rtok.fileLog
import rtok.sys.IoException
import rtok.sys.ProgressRunner
import rtok.sys.StreamedChild
import rtok.sys.createDirAll
import rtok.sys.currentExe
import rtok.sys.exists

/**
 * The verbs that start things (Rust `demon::start/restart/upgrade`): one detached `rtok demon supervise <svc>` per
 * service, passing [configFile] down so the service reads the file the operator started the demon with.
 */
class Lifecycle(
    val cfg: Config,
    val configFile: String?,
    val say: (String) -> Unit = ::println,
    val warn: (String) -> Unit = { platform.posix.fputs("$it\n", platform.posix.stderr) },
) {
    val store = Store(cfg.demon.stateDir)
    val demon = Demon(store, OsProcs, say)

    fun targets(
        named: List<Service>,
        runningFirst: Boolean,
    ) = demon.targets(named, cfg.demon.services, runningFirst)

    /** `rtok [--config <file>] <rest>`. */
    fun argv(rest: List<String>): List<String> = configFile?.let { listOf("--config", it) }.orEmpty() + rest

    /** Detach one supervisor per service; one already up is left alone rather than given a second owner. */
    fun start(named: List<Service>) {
        createDirAll(store.dir)
        val exe = currentExe()?.takeIf(::exists) ?: throw DemonException("cannot find the rtok binary")
        for (service in targets(named, false)) {
            if (store.held(service)) {
                say("$service already running")
                continue
            }
            store.read(service)?.let { retireStale(service, it) }
            store.remove(service, "stop")
            if (service == Service.MCP) {
                val msg = "$service: no stdin client under the demon — `rtok mcp` exits at EOF and is restarted"
                warn(msg)
                rtok.log.append(cfg.fileLog(), "warn", "demon", service.id, msg)
            }
            val pid = StreamedChild.spawnDaemon(exe, argv(listOf("demon", "supervise", service.id))) ?: throw DemonException("spawn supervisor for $service")
            say("$service started (supervisor $pid)")
        }
    }

    /** The lock just proved no supervisor is up: an orphan child of this boot is retired, a stale one never signalled. */
    private fun retireStale(
        service: Service,
        st: State,
    ) {
        when (demon.ownership(service, st)) {
            Owner.STALE -> say(Demon.staleLine(service, st))
            Owner.ORPHAN -> demon.bringDown(service, st, signalable(st, Owner.ORPHAN), false)
            Owner.LIVE -> Unit
        }
        demon.retire(service, st)
    }

    fun stop(
        named: List<Service>,
        force: Boolean,
    ) = demon.stop(targets(named, true), force)

    fun restart(named: List<Service>) {
        val services = targets(named, true)
        demon.stop(services, false)
        start(services)
    }

    /** Stop every live surface, replace the binary, start the same set; a failed replace still starts. */
    fun upgrade() {
        val up = store.rows(emptyList(), "").filter { it.running }.map { Service.parse(it.service) }
        val replaced =
            runCatching {
                if (up.isNotEmpty()) {
                    demon.stop(up, false)
                    demon.quiesce(up)
                }
                replaceBinary()
            }
        val started = runCatching { if (up.isNotEmpty()) start(up) }
        val e = replaced.exceptionOrNull()
        val s = started.exceptionOrNull()
        val failure = if (e != null && s != null) DemonException("${e.message}: and restart failed: ${s.message}") else e ?: s
        failure?.let { throw it }
    }

    /** `RTOK_UPDATE_CMD`, else `ketch upgrade rtok --yes`, else `rtok-update`. */
    private fun replaceBinary() {
        val cmd = rtok.config.envVar("RTOK_UPDATE_CMD")
        val error = if (cmd != null) "update command failed".takeIf { update(cmd, emptyList()) != 0 } else viaUpdaters()
        error?.let { throw DemonException(it) }
    }

    /** The installed updaters in turn; the error, or null when one replaced the binary. */
    private fun viaUpdaters(): String? {
        val ketch = runCatching { update("ketch", listOf("upgrade", "rtok", "--yes")) }
        ketch.exceptionOrNull()?.takeUnless(::missing)?.let { throw it }
        ketch.getOrNull()?.let { return "ketch upgrade rtok failed".takeIf { _ -> it != 0 } }
        val code = runCatching { update("rtok-update", emptyList()) }.getOrNull()
        return when (code) {
            null -> "no ketch or rtok-update — install with ketch (`ketch install pyrlyn/rtok`)"
            0 -> null
            else -> "rtok-update failed"
        }
    }

    private fun missing(e: Throwable) = e is IoException && e.kind == IoException.Kind.NotFound

    /** Runs an updater and forwards what it printed; its exit code. */
    private fun update(
        program: String,
        args: List<String>,
    ): Int {
        val (out, _) = ProgressRunner("", program, args).quiet().runCapped(UPDATE_CAP)
        if (out.stdout.isNotEmpty()) say(out.stdout.decodeToString().trimEnd('\n'))
        if (out.stderr.isNotEmpty()) warn(out.stderr.decodeToString().trimEnd('\n'))
        return out.exitCode ?: 1
    }

    private companion object {
        const val UPDATE_CAP = 1L shl 20
    }
}

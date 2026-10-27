// Port of src/store/mod.rs `spawn_retention`: housekeeping on its own worker and connection, so it neither delays
// a long-lived surface's first answer nor dies on a contended store (T75, T352).
package rtok

import rtok.config.Config
import rtok.store.RetentionJob
import rtok.store.Store
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

object Retention {
    /** Starts housekeeping in the background; each warning goes to stderr and the file log as `rtok <surface>: …`. */
    @OptIn(ObsoleteWorkersApi::class)
    fun spawn(
        cfg: Config,
        surface: String,
    ) {
        val job = RetentionJob(cfg.core.dbPath, cfg.core.retainCallsDays, cfg.core.retainHookBodiesDays)
        val started =
            runCatching {
                val w = Worker.start(name = "rtok-retention")
                w.execute(TransferMode.SAFE, { Triple(cfg, surface, job) }) { (c, s, j) -> Retention.run(c, s, j) }
                w.requestTermination(processScheduledJobs = true)
            }
        started.exceptionOrNull()?.let { stderr("rtok $surface: retention thread not started: ${it.message}") }
    }

    /** One housekeeping pass, reporting its warnings; returns them for tests. */
    fun run(
        cfg: Config,
        surface: String,
        job: RetentionJob,
    ): List<String> =
        Store.housekeeping(job).map { w ->
            stderr("rtok $surface: ${w.message}")
            appendLog(cfg, "warn", surface, "retention", w.message)
            w.message
        }

    private fun stderr(line: String) {
        runCatching { rtok.sys.writeFd(2, "$line\n".encodeToByteArray()) }
    }
}

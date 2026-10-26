// Port of src/plugins/graph/jobs.rs (T329.54): re-index jobs of this process, the `indexing` and `failed`
// states of a project row. The CLI blocks on `ProjectAction.Reindex` and needs no tracker.
package rtok.plugins.graph

import rtok.config.Config
import rtok.plugin.Runtime
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

sealed interface Job {
    data object Running : Job

    /** The last run's error; it stays until the next run starts. */
    data class Failed(
        val error: String,
    ) : Job
}

object Jobs {
    // Keyed by root, not id: ids repeat across stores, and one process can open several.
    private val jobs = HashMap<String, Job>()
    private val lock = rtok.sys.Mutex()

    fun get(root: String): Job? = lock.hold { jobs[root] }

    internal fun put(
        root: String,
        job: Job?,
    ) = lock.hold { if (job == null) jobs.remove(root) else jobs[root] = job }

    internal fun finish(
        root: String,
        outcome: Result<String>,
    ) = put(root, outcome.exceptionOrNull()?.let { Job.Failed(it.message ?: it.toString()) })

    /** Re-indexes [target] on a worker and returns at once; until it ends the old rows keep answering. */
    fun start(
        rt: Runtime,
        target: String,
    ): String {
        val p = Projects.resolve(rt.store, target)
        if (p.missing()) throw IllegalStateException("${p.root} no longer exists; remove it or restore the directory")
        lock.hold {
            if (jobs[p.root] == Job.Running) throw IllegalStateException("${p.root} is already being indexed")
            jobs[p.root] = Job.Running
        }
        val worker =
            runCatching { Worker.start(name = "graph-reindex") }.getOrElse {
                put(p.root, null)
                throw IllegalStateException("cannot start the index worker: ${it.message}")
            }
        val args = Triple(rt.config, p.root, p.id.toString())
        worker.execute(TransferMode.SAFE, { args }) { (config, root, id) ->
            // A crash must not leave the row `indexing` for good.
            finish(root, runCatching { work(config, id) })
        }
        return "indexing ${p.root}\n"
    }

    private fun work(
        config: Config,
        id: String,
    ): String = Runtime.open(config, "graph-reindex").use { Projects.run(it, ProjectAction.Reindex(id), false) }
}

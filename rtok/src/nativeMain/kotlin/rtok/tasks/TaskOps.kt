// Port of src/tasks/ops.rs: the store-backed `rtok task` operations. The CLI parses flags and prints; the open lives
// here. `export` waits for the export adapters.
package rtok.tasks

import rtok.config.Config
import rtok.store.Store
import rtok.store.TaskId

object TaskOps {
    private fun <T> withStore(
        cfg: Config,
        body: (Store) -> T,
    ): T = Store.open(cfg.core.dbPath).use(body)

    fun seed(
        cfg: Config,
        project: TaskProject,
    ) = withStore(cfg) { project.seed(it, null) }

    fun create(
        cfg: Config,
        project: TaskProject,
        new: NewTask,
    ) = withStore(cfg) { project.create(it, new) }

    fun setStatus(
        cfg: Config,
        project: TaskProject,
        id: TaskId,
        status: Status?,
        force: Boolean,
        report: String?,
    ) = withStore(cfg) { project.status(it, id, status, force, report) }

    fun sync(
        cfg: Config,
        project: TaskProject,
    ) = withStore(cfg) { syncTasks(project, it) }

    /** `rtok task export`: the disk tasks, all statuses, mirrored into [backend]. */
    fun export(
        cfg: Config,
        cwd: String,
        backend: String,
        dryRun: Boolean,
    ): ExportReport {
        if (cfg.tasks.adapter != "disk") {
            bail("rtok task export mirrors the disk adapter, but [tasks] adapter is ${rtok.plugin.sdk.debugStr(cfg.tasks.adapter)}")
        }
        val project = TaskProject.open(cfg.tasks, cwd)
        return openExporter(cfg.tasks, project.key, backend).use { runExport(it, project.adapter.list(Filter(all = true)), dryRun) }
    }

    fun next(
        cfg: Config,
        project: TaskProject,
    ) = withStore(cfg) { project.next(it) }

    fun ready(
        cfg: Config,
        project: TaskProject,
    ) = withStore(cfg) { project.ready(it) }

    fun claim(
        cfg: Config,
        project: TaskProject,
        id: String?,
        agent: String?,
    ) = withStore(cfg) { project.claim(it, id?.let(TaskId::parse), requireAgent(it, agent)) }

    fun release(
        cfg: Config,
        project: TaskProject,
        id: String,
        agent: String?,
        force: Boolean,
    ) = withStore(cfg) { project.release(it, TaskId.parse(id), requireAgent(it, agent), force) }
}

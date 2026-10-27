// Port of src/tasks/export/github.rs: the GitHub Issues exporter. It drives the `github` task adapter, so labels,
// titles, sub-issue links and close reasons are exactly what that adapter writes and reads: an exported repository
// can later be adopted as the project's task store.

package rtok.tasks

class GithubExporter(
    private val adapter: GithubAdapter,
    private val repo: String,
) : Exporter {
    override val name = "github"
    override val target get() = repo

    override fun remote() = adapter.list(Filter(all = true))

    override fun apply(
        action: ExportAction,
        task: Task,
    ): String? {
        val issue =
            when (action.kind) {
                ExportKind.Create -> {
                    val created = adapter.create(NewTask(task.title, task.description, task.parent), task.id)
                    // `create` opens a bare issue; labels and a finished state need one more write.
                    if (drifted(task, created, true).isEmpty()) created else adapter.patch(task, true)
                }

                else -> {
                    adapter.patch(task, true)
                }
            }
        return issue.external?.url
    }

    override fun close() = adapter.close()
}

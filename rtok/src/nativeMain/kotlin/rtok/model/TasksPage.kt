// Port of src/model/tasks.rs (T441.13.7): the Tasks page, `rtok task list --all` for the project `rtok web` or
// `rtok tui` was started in, read through the same TaskProject and Filter the CLI uses, so a board card is a row of
// that list. Read-only: nothing here writes a task. Read off the tick on a Background worker.
package rtok.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.config.Layers
import rtok.sys.cwd
import rtok.tasks.Filter
import rtok.tasks.Status
import rtok.tasks.Task
import rtok.tasks.TaskProject
import rtok.tasks.taskTable
import kotlin.time.Duration.Companion.seconds

object TasksPage {
    /** FINISHED_SHOWN: finished tasks shown per column; the frame goes to every page every two seconds. */
    const val FINISHED_SHOWN = 50

    /** DISK_TTL: local files, an agent's status change should show soon. */
    private val DISK_TTL = 5.seconds

    /** REMOTE_TTL: a GitHub or GitLab read costs API requests against the host's rate limit. */
    private val REMOTE_TTL = 120.seconds

    /** `TasksPage`: `state` (`reading`, `unconfigured`, `failed`, `ready`), the CLI's `text`, the `board` as data. */
    data class Page(
        val state: String,
        val text: String,
        val board: JsonObject? = null,
    ) {
        fun toJson() = JsonObject(mapOf("state" to JsonPrimitive(state), "text" to JsonPrimitive(text), "board" to (board ?: JsonNull)))
    }

    private val cache = Background<Page>("web-tasks")

    /** `page`: the page for the process's working directory; "reading tasks…" until the first read lands. */
    fun get(cfg: Config): Page {
        val ttl = if (cfg.tasks.adapter == "disk") DISK_TTL else REMOTE_TTL
        val home = cfg.home
        val file = cfg.loadedFrom
        return cache.get(ttl) {
            // Re-read the layers each time: `rtok task init` edits `.rtok.toml` while the page is open.
            val fresh = runCatching { Layers.load(home, file, null) }.getOrDefault(cfg)
            runCatching { cwd() }.fold(
                { read(fresh, it, configured(fresh)) },
                { Page("failed", "tasks: cannot read the current directory: ${it.message}\n") },
            )
        } ?: Page("reading", "reading tasks…\n")
    }

    /** Whether a config layer above the built-in defaults sets a `[tasks]` key. */
    internal fun configured(cfg: Config): Boolean =
        Layers.sourced(Layers.figment(cfg.home, cfg.loadedFrom, null)).any { (key, _, source) ->
            key.startsWith("tasks.") && source != "default"
        }

    /** `read`: what `rtok task list --all` prints for the project at [cwd], as a page. */
    fun read(
        cfg: Config,
        cwd: String,
        configured: Boolean,
    ): Page {
        if (!configured) return Page("unconfigured", "no [tasks] section: run `rtok task init` in the project\n")
        return runCatching {
            val project = TaskProject.open(cfg.tasks, cwd)
            project.adapter.name to project.adapter.list(Filter(all = true))
        }.fold(
            { (adapter, tasks) -> ready(adapter, tasks) },
            { Page("failed", "tasks did not answer: ${it.message ?: it}\n") },
        )
    }

    internal fun ready(
        adapter: String,
        all: List<Task>,
    ): Page {
        val seen = mutableMapOf<Status, Int>()
        val kept =
            all
                .sortedByDescending { it.updatedAt }
                .filter { t -> t.status.isActive || (seen.getOrElse(t.status) { 0 } + 1).also { seen[t.status] = it } <= FINISHED_SHOWN }
                .sortedBy { it.id }
        val hidden = all.size - kept.size
        val text =
            buildString {
                append(taskTable(kept).ifEmpty { "no tasks yet: rtok task create \"<title>\"\n" })
                if (hidden > 0) append("$hidden older done or closed tasks not shown\n")
            }
        val board =
            JsonObject(
                mapOf(
                    "adapter" to JsonPrimitive(adapter),
                    "tasks" to JsonArray(kept.map(::card)),
                    "hidden" to JsonPrimitive(hidden),
                ),
            )
        return Page("ready", text, board)
    }

    /** `TaskCard::from`: the list's fields minus the description; only an http(s) issue link reaches the page. */
    internal fun card(t: Task): JsonObject {
        fun ids(list: List<rtok.store.TaskId>) = JsonArray(list.map { JsonPrimitive(it.toString()) })
        val link =
            t.external?.takeIf { it.url.startsWith("https://") || it.url.startsWith("http://") }?.let {
                JsonObject(mapOf("adapter" to JsonPrimitive(it.adapter), "number" to JsonPrimitive(it.number), "url" to JsonPrimitive(it.url)))
            }
        val fields: Map<String, JsonElement> =
            mapOf(
                "id" to JsonPrimitive(t.id.toString()),
                "title" to JsonPrimitive(t.title),
                "status" to JsonPrimitive(t.status.str),
                "parent" to (t.parent?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
                "priority" to JsonPrimitive(t.priority),
                "assignee" to (t.assignee?.let(::JsonPrimitive) ?: JsonNull),
                "blocked_by" to ids(t.blockedBy),
                "conflicts_with" to ids(t.conflictsWith),
                "parallel" to JsonPrimitive(t.parallel),
                "external" to (link ?: JsonNull),
                "updated_at" to JsonPrimitive(t.updatedAt),
            )
        return JsonObject(fields)
    }
}

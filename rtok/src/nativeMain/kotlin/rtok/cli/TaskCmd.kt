// Port of src/cli/task.rs: `rtok task …` (T441.5). Every subcommand but `init` opens the project's adapter.
// `export` lands with the export adapters.
package rtok.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import rtok.store.StoreException
import rtok.store.TaskId
import rtok.sys.IoException
import rtok.sys.readToString
import rtok.tasks.ClaimConflict
import rtok.tasks.NewTask
import rtok.tasks.Status
import rtok.tasks.SyncReport
import rtok.tasks.TASK_JSON
import rtok.tasks.Taken
import rtok.tasks.Task
import rtok.tasks.TaskException
import rtok.tasks.TaskOps
import rtok.tasks.TaskProject
import rtok.tasks.block
import rtok.tasks.conflict
import rtok.tasks.initTasks
import rtok.tasks.readyText
import rtok.tasks.setParallel
import rtok.tasks.setPriority
import rtok.tasks.syncText
import rtok.tasks.taskDetails
import rtok.tasks.taskFilter
import rtok.tasks.taskTable

/** serde_json `to_string_pretty` over the task JSON shape (its skips kept). */
private val TASK_PRETTY =
    Json(TASK_JSON) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

/** The sync report keeps every field, a missing stray id as null. */
private val REPORT_PRETTY =
    Json(TASK_PRETTY) { encodeDefaults = true }

private fun Invocation.json(value: JsonElement) = io.println(TASK_PRETTY.encodeToString(JsonElement.serializer(), value))

private fun Invocation.task(t: Task) = json(TASK_JSON.encodeToJsonElement(t))

private val JSON = OptSpec.Flag("json", "Print the task as JSON")

@Suppress("SwallowedException") // the message is the whole error, as anyhow prints it
private fun <T> taskCall(body: () -> T): T =
    try {
        body()
    } catch (e: TaskException) {
        throw CliFailure(e.message.orEmpty())
    } catch (e: ClaimConflict) {
        throw CliFailure(e.message.orEmpty())
    } catch (e: Taken) {
        throw CliFailure(e.message.orEmpty())
    } catch (e: StoreException) {
        throw CliFailure(e.message.orEmpty())
    } catch (e: IoException) {
        throw CliFailure(e.message.orEmpty())
    } catch (e: IllegalArgumentException) {
        throw CliFailure(e.message.orEmpty())
    }

private fun cwd() = rtok.sys.cwd()

private fun Invocation.open() = TaskProject.open(loadConfig().tasks, cwd())

private fun id(s: String) = TaskId.parse(s)

private fun action(body: (Invocation) -> Unit): (Invocation) -> Unit = { inv -> taskCall { body(inv) } }

private val INIT =
    CommandSpec(
        "init",
        "Write `[tasks]` into this checkout's `.rtok.toml` and seed the counter from existing tasks",
        options =
            listOf(
                OptSpec.Value("adapter", "disk, github or gitlab (default: disk, or what the file already says)", "ADAPTER"),
                OptSpec.Value("prefix", "Task id prefix, 1–8 ASCII letters (default: the project name's first letter)", "PREFIX"),
            ),
        action =
            action { inv ->
                val path = initTasks(cwd(), inv.value("adapter"), inv.value("prefix"))
                // Re-read: the file just changed what `[tasks]` says.
                val cfg = inv.loadConfig()
                val project = TaskProject.open(cfg.tasks, cwd())
                val last = TaskOps.seed(cfg, project)
                inv.io.println("$path: adapter ${cfg.tasks.adapter}, next id ${project.prefix}${last + 1u}")
            },
    )

private val CREATE =
    CommandSpec(
        "create",
        "Create a task under the next free id and print the id",
        options =
            listOf(
                OptSpec.Value("description", "Description: why the task exists and what done means", "DESCRIPTION", short = 'd'),
                OptSpec.Value("body-file", "Read the description from this file (`-` for stdin)", "PATH"),
                OptSpec.Value("parent", "Make it a subtask of this task, e.g. `R2` → `R2.1`", "ID"),
                OptSpec.Flag("json", "Print the task as JSON instead of the id"),
            ),
        args = listOf(ArgSpec("title", "One-line title")),
        action =
            action { inv ->
                val file = inv.value("body-file")
                if (file != null && inv.value("description") != null) {
                    throw CliFailure("the argument '--description <DESCRIPTION>' cannot be used with '--body-file <PATH>'", ExitCode.USAGE)
                }
                val description =
                    when (file) {
                        null -> inv.value("description").orEmpty()
                        "-" -> inv.io.readStdin().decodeToString()
                        else -> readToString(file)
                    }
                val new = NewTask(inv.requireArg("title"), description, inv.value("parent")?.let(::id))
                val cfg = inv.loadConfig()
                val t = TaskOps.create(cfg, TaskProject.open(cfg.tasks, cwd()), new)
                if (inv.flag("json")) inv.task(t) else inv.io.println(t.id.toString())
            },
    )

private val LIST =
    CommandSpec(
        "list",
        "The plan: open and in-progress tasks, subtasks under their parent",
        options =
            listOf(
                OptSpec.Value("status", "Only these statuses (comma-separated: open, in-progress, done, closed)", "STATUS", repeat = true),
                OptSpec.Flag("all", "Done and closed tasks too"),
                OptSpec.Value("parent", "Only the subtasks of this task", "ID"),
                OptSpec.Flag("json", "JSON instead of the table"),
            ),
        action =
            action { inv ->
                val statuses = inv.values("status").flatMap { it.split(',') }
                val tasks = inv.open().adapter.list(taskFilter(statuses, inv.flag("all"), inv.value("parent")))
                if (inv.flag("json")) inv.json(JsonArray(tasks.map { TASK_JSON.encodeToJsonElement(it) })) else inv.io.out(taskTable(tasks))
            },
    )

private val SHOW =
    CommandSpec(
        "show",
        "One task: title, status, parent, subtasks, link and description",
        options = listOf(OptSpec.Flag("json", "JSON instead of the text")),
        args = listOf(ArgSpec("id", "Task id, e.g. `R12` or `R2.1`")),
        action =
            action { inv ->
                val shown = inv.open().show(id(inv.requireArg("id")))
                if (inv.flag("json")) inv.json(shown.toJson()) else inv.io.out(taskDetails(shown))
            },
    )

private val STATUS =
    CommandSpec(
        "status",
        "Print a task's status, or set it; done and closed move it out of the plan",
        options =
            listOf(
                OptSpec.Flag("force", "Finish a parent even though subtasks are still open"),
                OptSpec.Value("report", "Closing report of a task set to done or closed (`-` for stdin); the plan/ layout keeps it in done.md", "TEXT"),
                JSON,
            ),
        args = listOf(ArgSpec("id", "Task id, e.g. `R12` or `R2.1`"), ArgSpec("status", "open, in-progress, done or closed", required = false)),
        action =
            action { inv ->
                val status = inv.arg("status")?.let(Status::parse)
                val report = inv.value("report")?.let { if (it == "-") inv.io.readStdin().decodeToString() else it }
                val cfg = inv.loadConfig()
                val t = TaskOps.setStatus(cfg, TaskProject.open(cfg.tasks, cwd()), id(inv.requireArg("id")), status, inv.flag("force"), report)
                if (inv.flag("json")) inv.task(t) else inv.io.println("${t.id}: ${t.status}")
            },
    )

private val NEXT =
    CommandSpec(
        "next",
        "The first ready task: free or stale, not blocked; a leaf when nothing is blocked",
        options = listOf(OptSpec.Flag("json", "JSON instead of the text")),
        action =
            action { inv ->
                val cfg = inv.loadConfig()
                val next = TaskOps.next(cfg, TaskProject.open(cfg.tasks, cwd()))
                when {
                    inv.flag("json") -> inv.json(next?.let { TASK_JSON.encodeToJsonElement(it) } ?: JsonNull)
                    next != null -> inv.io.println("${next.id}  ${next.title}")
                    else -> inv.io.println("no ready task")
                }
            },
    )

private val READY =
    CommandSpec(
        "ready",
        "Tasks that can be claimed, highest priority first",
        options = listOf(OptSpec.Flag("json", "JSON instead of the text")),
        action =
            action { inv ->
                val cfg = inv.loadConfig()
                val ready = TaskOps.ready(cfg, TaskProject.open(cfg.tasks, cwd()))
                if (inv.flag("json")) inv.json(JsonArray(ready.map { it.toJson() })) else inv.io.out(readyText(ready))
            },
    )

private val AGENT = OptSpec.Value("agent", "The rtok agent id; defaults to `RTOK_AGENT_ID`", "AGENT")

private val CLAIM =
    CommandSpec(
        "claim",
        "Claim a task for this agent, or the first ready task when no id is given.\n" +
            "On GitHub and GitLab the assignee write is last-write-wins, not compare-and-set.",
        options = listOf(AGENT, OptSpec.Flag("json", "Print the task as JSON, with `changed`")),
        args = listOf(ArgSpec("id", "Task id; omit to take the first ready task", required = false)),
        action =
            action { inv ->
                val cfg = inv.loadConfig()
                val o = TaskOps.claim(cfg, TaskProject.open(cfg.tasks, cwd()), inv.arg("id"), inv.value("agent"))
                when {
                    inv.flag("json") -> inv.json(o.toJson())
                    o.changed -> inv.io.println("${o.task.id}  ${o.task.title}")
                    else -> inv.io.println("${o.task.id}  ${o.task.title}  (already yours)")
                }
            },
    )

private val RELEASE =
    CommandSpec(
        "release",
        "Clear the assignee and set the task open. Only the holder, unless --force.",
        options = listOf(AGENT, OptSpec.Flag("force", "Release a task held by someone else"), JSON),
        args = listOf(ArgSpec("id", "Task id, e.g. `R12`")),
        action =
            action { inv ->
                val cfg = inv.loadConfig()
                val t = TaskOps.release(cfg, TaskProject.open(cfg.tasks, cwd()), inv.requireArg("id"), inv.value("agent"), inv.flag("force"))
                if (inv.flag("json")) inv.task(t) else inv.io.println("${t.id}: ${t.status}")
            },
    )

private val DEP =
    CommandSpec(
        "dep",
        "Record that this task waits on another. A cycle is refused and nothing is written.",
        options = listOf(JSON),
        args = listOf(ArgSpec("id", "The task that waits"), ArgSpec("blocker", "The task that must finish first")),
        action =
            action { inv ->
                val (a, b) = inv.requireArg("id") to inv.requireArg("blocker")
                val t = inv.open().block(id(a), id(b))
                if (inv.flag("json")) inv.task(t) else inv.io.println("$a: blocked by $b")
            },
    )

private val PRIORITY =
    CommandSpec(
        "priority",
        "Set priority from 0 (highest) to 4. 2 is the default and is not stored.",
        options = listOf(JSON),
        args = listOf(ArgSpec("id", "Task id, e.g. `R12`"), ArgSpec("level", "0 to 4")),
        action =
            action { inv ->
                val raw = inv.requireArg("level")
                val level = raw.toUByteOrNull()?.toInt() ?: throw CliFailure("invalid value '$raw' for '<LEVEL>': invalid digit found in string", ExitCode.USAGE)
                val t = inv.open().setPriority(id(inv.requireArg("id")), level)
                if (inv.flag("json")) inv.task(t) else inv.io.println("${t.id}: priority $level")
            },
    )

private val CONFLICT =
    CommandSpec(
        "conflict",
        "Record that two tasks touch the same files, so they should not run at once. Symmetric;\n" +
            "`ready` flags a task whose partner is in progress.",
        options = listOf(OptSpec.Flag("remove", "Take the conflict off instead"), JSON),
        args = listOf(ArgSpec("id", "One task"), ArgSpec("other", "The task it conflicts with")),
        action =
            action { inv ->
                val (a, b) = inv.requireArg("id") to inv.requireArg("other")
                val remove = inv.flag("remove")
                val t = inv.open().conflict(id(a), id(b), remove)
                when {
                    inv.flag("json") -> inv.task(t)
                    remove -> inv.io.println("$a: no longer conflicts with $b")
                    else -> inv.io.println("$a: conflicts with $b")
                }
            },
    )

private val PARALLEL =
    CommandSpec(
        "parallel",
        "Mark a task safe to run beside any other task.",
        options = listOf(OptSpec.Flag("off", "Take the mark off instead"), JSON),
        args = listOf(ArgSpec("id", "Task id, e.g. `R12`")),
        action =
            action { inv ->
                val off = inv.flag("off")
                val t = inv.open().setParallel(id(inv.requireArg("id")), !off)
                when {
                    inv.flag("json") -> inv.task(t)
                    off -> inv.io.println("${t.id}: not parallel")
                    else -> inv.io.println("${t.id}: parallel")
                }
            },
    )

private val SYNC =
    CommandSpec(
        "sync",
        "Raise the id counters to the adapter's highest ids and report drift; never writes the adapter",
        options = listOf(OptSpec.Flag("json", "JSON instead of the text")),
        action =
            action { inv ->
                val cfg = inv.loadConfig()
                val r = TaskOps.sync(cfg, TaskProject.open(cfg.tasks, cwd()))
                if (inv.flag("json")) inv.io.println(REPORT_PRETTY.encodeToString(SyncReport.serializer(), r)) else inv.io.out(syncText(r))
            },
    )

val TASK_CMD =
    CommandSpec(
        "task",
        "This project's tasks: numbered by rtok, stored by the `[tasks]` adapter",
        subcommands = listOf(CREATE, LIST, SHOW, STATUS, NEXT, READY, CLAIM, RELEASE, DEP, PRIORITY, CONFLICT, PARALLEL, SYNC, INIT),
    )

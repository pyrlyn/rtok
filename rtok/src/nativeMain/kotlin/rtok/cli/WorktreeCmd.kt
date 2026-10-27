// Port of src/cli/worktree.rs (subset): `rtok worktree list|claim|adopt|remove|gc`. add, finish, whoami, clean and
// migrate land with their modules.
package rtok.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import rtok.store.parseIdleFrom
import rtok.worktree.Gc
import rtok.worktree.GcPolicy
import rtok.worktree.Ops
import rtok.worktree.WorktreeList
import rtok.worktree.Worktrees

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val IDLE_DEFAULT = "24h"

/** serde_json `to_string_pretty`: two-space indent, every field (nulls included) unless the type skips it. */
private val PRETTY =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

private inline fun <reified T> Invocation.printJson(value: T) = io.println(PRETTY.encodeToString(serializer<T>(), value))

private val AGENT_OPT = OptSpec.Value("agent", "The rtok agent id (any unique prefix); defaults to `RTOK_AGENT_ID`", "AGENT")
private val OWNER_OPT =
    OptSpec.Value("owner", "The owner an old lock names, as `<provider> / <model>`. Defaults to `<host> / <model>` of the agent", "OWNER")

/** One gate for every subcommand, `list` included (T410). */
private fun gated(body: (Invocation, rtok.config.Config) -> Unit): (Invocation) -> Unit =
    { inv ->
        val cfg = inv.loadConfig()
        if (!cfg.worktree.enabled) throw CliFailure(Worktrees.DISABLED)
        body(inv, cfg)
    }

private val CLAIM =
    CommandSpec(
        "claim",
        "Bind an existing worktree to an agent: rewrites its lock only when unlocked or already yours",
        options = listOf(AGENT_OPT, OWNER_OPT),
        args = listOf(ArgSpec("path", "The worktree")),
        action =
            gated { inv, cfg ->
                inv.io.println(Ops.claim(cfg, inv.requireArg("path"), inv.value("agent"), inv.value("owner")).path)
            },
    )

private val ADOPT =
    CommandSpec(
        "adopt",
        "Bind the worktree you are in (made by a host's own tool) to your agent. A worktree in a pool its host evicts " +
            "(Cursor, Codex, Windsurf, Devin) is claimed in the store only. With no agent (a post-create script) the one live " +
            "agent of that host in the repository takes it; with none or several the claim waits for the next agent that " +
            "adopts or starts there",
        options =
            listOf(
                OptSpec.Value("task", "The task id, when the lock and the branch do not name one (a detached HEAD)", "TASK"),
                AGENT_OPT,
                OWNER_OPT,
                OptSpec.Flag("json", "Print the result as JSON"),
            ),
        args = listOf(ArgSpec("path", "The worktree, or a directory inside it; defaults to the current directory", required = false)),
        action =
            gated { inv, cfg ->
                val done = Ops.adopt(cfg, inv.arg("path"), inv.value("task"), inv.value("agent"), inv.value("owner"))
                if (inv.flag("json")) inv.printJson(done) else inv.io.println(done.path)
                if (done.pending) {
                    inv.io.eprintln("pending: no single agent matched; the next agent to adopt or start a session in this worktree takes it")
                }
            },
    )

private val REMOVE =
    CommandSpec(
        "remove",
        "Remove your own finished worktree: unlock, `git worktree remove`, delete the branch when merged, release the " +
            "claim; refuses dirty or current worktrees, and a foreign lock unless the task is finished (merged, clean, with " +
            "commits of its own)",
        options =
            listOf(
                AGENT_OPT,
                OWNER_OPT,
                OptSpec.Flag("keep-branch", "Remove an unmerged worktree too and keep its branch (a merged branch is kept as well)"),
                OptSpec.Flag("dry-run", "Show the path, branch, size and file count that would go; remove nothing"),
                OptSpec.Flag("json", "JSON instead of the text line"),
            ),
        args = listOf(ArgSpec("target", "The worktree's path, or its task id (the lock's task or the branch `<task>[-<slug>]`)")),
        action =
            gated { inv, cfg ->
                val dry = inv.flag("dry-run")
                val done = Ops.remove(cfg, inv.requireArg("target"), inv.value("agent"), inv.value("owner"), inv.flag("keep-branch"), dry)
                when {
                    inv.flag("json") -> inv.printJson(done)
                    dry -> inv.io.out(done.previewText())
                    else -> inv.io.println("${done.path}: ${done.note}")
                }
            },
    )

private val LIST =
    CommandSpec(
        "list",
        "Every worktree and orphan with its owner, state and source bytes",
        options =
            listOf(
                OptSpec.Flag("json", "JSON instead of the table"),
                OptSpec.Flag("sizes", "Also walk every build cache for its bytes (slow with large target/ folders)"),
            ),
        action =
            gated { inv, cfg ->
                val rows = Ops.list(cfg, rtok.sys.cwd(), inv.flag("sizes"))
                if (inv.flag("json")) inv.printJson(rows) else inv.io.out(WorktreeList.toTable(rows, rtok.log.now()))
            },
    )

private fun idle(
    inv: Invocation,
    name: String,
    default: String,
) = try {
    parseIdleFrom(inv.value(name) ?: default, "--$name")
} catch (
    @Suppress("SwallowedException") e: rtok.store.StoreException,
) {
    throw CliFailure(e.message ?: "bad --$name")
}

private val GC =
    CommandSpec(
        "gc",
        "Remove merged, clean, idle worktrees with their branches; drop records of deleted ones",
        options =
            listOf(
                OptSpec.Flag("yes", "Apply; without it this is a dry run that changes nothing"),
                OptSpec.Flag("dry-run", "Say the dry run out loud: print what would go and change nothing (the default)"),
                OptSpec.Flag("stat", "The same preview as the dry run: a removal's line already is its stat"),
                OptSpec.Value("owner", "Open locks whose reason starts with this owner; any other lock holds until `--stale-lock`", "OWNER"),
                OptSpec.Value("idle", "Keep worktrees modified within this window (`30m`, `24h`, `7d`)", "IDLE"),
                OptSpec.Value(
                    "stale-lock",
                    "Treat someone else's lock as abandoned once its merged, clean worktree is untouched this long (`30m`, `7d`)",
                    "STALE_LOCK",
                ),
                OptSpec.Flag("json", "JSON instead of the table"),
            ),
        action =
            gated { inv, cfg ->
                inv.conflict("dry-run", "yes")
                val yes = inv.flag("yes")
                // T285: a live agent's worktree is never removed unless its task is finished (T453).
                val policy =
                    GcPolicy(
                        owner = inv.value("owner"),
                        idle = idle(inv, "idle", IDLE_DEFAULT),
                        staleLock = idle(inv, "stale-lock", Gc.STALE_LOCK_DEFAULT),
                        now = rtok.log.now() * NANOS_PER_SECOND,
                        live = Ops.liveIds(cfg),
                        ended = Ops.endedIds(cfg),
                        pending = Ops.pendingPaths(cfg),
                    )
                val outcomes = Gc.run(rtok.sys.cwd(), policy, yes)
                if (inv.flag("json")) inv.printJson(outcomes) else inv.io.out(Gc.toTable(outcomes, yes))
                if (outcomes.any { it.failed }) throw CliFailure("some worktrees could not be removed")
            },
    )

val WORKTREE =
    CommandSpec(
        "worktree",
        "Git worktrees of this repository: owner, state and disk cost",
        subcommands = listOf(CLAIM, ADOPT, REMOVE, LIST, GC),
    )

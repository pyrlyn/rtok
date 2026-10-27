// Port of src/worktree/claim.rs (T285, T289.5, T527, T934): bind a worktree to the calling rtok agent (T282), in the
// git lock reason (v2, the source of truth) and in the store's `worktree_claims` (the fast join). `add`/`add_with`
// come with add.rs.
package rtok.worktree

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import rtok.agents.AgentLink
import rtok.agents.SessionsTable
import rtok.config.parseSince
import rtok.store.AgentDetail
import rtok.store.Store
import rtok.store.addPendingWorktree
import rtok.store.agentDetail
import rtok.store.autoAddProject
import rtok.store.claimWorktree
import rtok.store.hostId
import rtok.store.liveAgents
import rtok.store.pendingWorktrees
import rtok.store.releaseWorktreeClaim
import rtok.store.resolveAgent
import rtok.store.retryLocked
import rtok.store.sessionModel
import rtok.store.takePendingWorktree
import rtok.store.worktreeClaimHolder
import rtok.sys.canon
import rtok.sys.canonicalize
import rtok.sys.eprint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A refused or failed worktree step; [message] is what the CLI and MCP print. */
class WorktreeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun fail(message: String): Nothing = throw WorktreeException(message)

/** anyhow's `with_context` printed with `{:#}`: `context: cause`. */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> context(
    ctx: () -> String,
    op: () -> T,
): T =
    try {
        op()
    } catch (e: Exception) {
        throw WorktreeException("${ctx()}: ${e.message}", e)
    }

/** Someone else's lock on a worktree, and what the store last saw of its holder — what `remove` and `adopt` refuse with. */
data class Held(
    /** The lock's `Owner.reason()`, or `"an unknown owner"`. */
    val by: String,
    /** Seconds since the store last saw the holder; null when it cannot say. */
    val seenAgo: Long?,
    /** False once the holder is gone — an ended agent, or one unseen for gc's `--stale-lock`. Such a claim holds nothing. */
    val holds: Boolean,
) {
    /** `locked by …`, with how long ago the holder was seen when the store can say. */
    fun locked(): String = seenAgo?.let { "locked by $by, holder seen ${SessionsTable.duration(it)} ago" } ?: "locked by $by"
}

/** What [Claim.run] bound: the worktree, its task and origin, and whether a git lock was written. */
@Serializable
data class Adopted(
    val path: String,
    val task: String,
    val origin: String,
    val locked: Boolean,
    /** No agent was bound: the claim waits for the next agent in the worktree (T289.5). Omitted when false. */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val pending: Boolean = false,
    /** The branch the worktree has checked out; the project's name when auto-added. Not serialized. */
    @Transient val branch: String? = null,
)

/** T934: a claim write waits out another process's write lock for about `LOCK_ATTEMPTS × (busy + LOCK_PAUSE)`. */
private const val LOCK_ATTEMPTS = 8
private const val LOCK_PAUSE_MS = 250L
private const val DATE_LEN = 10

/** The main checkout and the linked worktree that holds [path]; the deepest one wins. */
private fun linked(path: String): Pair<String, WorktreeRecord> {
    val target = canon(path)
    val worktrees = Git.list(target)
    val main = worktrees.firstOrNull()?.path ?: fail("git lists no worktree")
    val record = Worktrees.deepest(target, worktrees.drop(1)) { it.path } ?: fail("$path is not a linked worktree")
    return main to record
}

/** [task], else the old lock's, else the branch's first `-` segment. */
private fun taskOf(
    path: String,
    task: String?,
    record: WorktreeRecord,
): String =
    task
        ?: record.owner()?.task
        ?: record.branch?.substringBefore('-')
        ?: fail("$path: no lock and no branch to name its task; name it with --task (MCP: `task`)")

/** add.rs `today`: the local date of `log::stamp`. */
internal fun today(): String = rtok.log.stamp(rtok.log.now()).take(DATE_LEN)

/** fs.rs `same_dir` (fs-kit `same_file`): both exist and resolve to one directory. */
private fun sameDir(
    a: String,
    b: String,
): Boolean {
    val x = canonicalize(a) ?: return false
    return x == canonicalize(b)
}

object Claim {
    /** gc.rs `stale_lock`. */
    fun staleLock(): Duration = parseSince(Gc.STALE_LOCK_DEFAULT).seconds

    /**
     * The lock [record] has against `(owner, agent)` — null when there is none. Otherwise the holder it names, with
     * [Held.holds] saying whether that claim still stands: an ended holder, or one unseen for gc's `--stale-lock`,
     * blocks nothing; a lock that names no agent, or a holder the store has no row for, keeps holding.
     */
    fun heldAgainst(
        record: WorktreeRecord,
        owner: String,
        agent: String,
        store: Store?,
    ): Held? {
        if (record.claimableBy(owner, agent)) return null
        val owned = record.owner()
        val by = owned?.reason() ?: "an unknown owner"
        val holder = owned?.agent?.let { id -> store?.let { s -> runCatching { s.worktreeClaimHolder(id) }.getOrNull() } }
        val now = rtok.log.now()
        return if (holder != null) Held(by, holder.seenAgo(now), holder.holds(now, staleLock())) else Held(by, null, true)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun env(k: String): String? = platform.posix.getenv(k)?.toKString()

    /**
     * The calling agent: `--agent <prefix>` (must resolve), else `RTOK_AGENT_ID` (T283; an id the store does not know is
     * ignored) or the host session's agent (T473), else none.
     */
    fun caller(
        store: Store?,
        flag: String?,
        envOf: (String) -> String? = ::env,
    ): AgentDetail? {
        val explicit = flag != null
        val raw = flag ?: AgentLink.shellAgent(store, envOf) ?: return null
        // T973: agents copy a plan's `agent: <provider> / <model>` into the flag.
        if (explicit && (raw.contains('/') || raw.contains(' '))) {
            fail(
                "--agent $raw: not an rtok agent id (that looks like a plan's `<provider> / <model>`); " +
                    "pass your id or a prefix of it, see `rtok agents whoami`",
            )
        }
        val found =
            runCatching {
                val s = store ?: throw WorktreeException("no store")
                s.agentDetail(s.resolveAgent(raw))
            }
        val e = found.exceptionOrNull()
        val detail = found.getOrNull()
        return when {
            detail != null -> {
                detail
            }

            !explicit && e == null -> {
                null
            }

            !explicit -> {
                eprint("warning: RTOK_AGENT_ID $raw is not in the store; no agent bound\n")
                null
            }

            e == null -> {
                fail("--agent $raw: unknown")
            }

            else -> {
                fail("--agent $raw: ${e.message}")
            }
        }
    }

    /** `--owner`, else `<host> / <model>` of the agent (`<host>` alone while no call named a model), else an error. */
    fun owner(
        flag: String?,
        agent: AgentDetail?,
        store: Store?,
    ): String {
        if (flag != null) return flag
        agent ?: fail("--owner is required when no agent is known (--agent or RTOK_AGENT_ID)")
        val model = store?.let { s -> runCatching { s.sessionModel(agent.hostSessionId) }.getOrNull() }
        return if (model != null) "${agent.host} / $model" else agent.host
    }

    /**
     * `rtok worktree claim` / `adopt` and MCP `worktree_adopt`: bind the linked worktree that holds [path] to [agent]
     * when it has no lock, the lock is theirs, or the holder is gone (T527). With [spareEvicting] a worktree in a pool
     * its host evicts (T289) gets no git lock: the store claim alone binds it.
     */
    fun run(
        path: String,
        owner: String,
        agent: String,
        task: String?,
        spareEvicting: Boolean,
        store: Store?,
    ): Adopted {
        val (main, record) = linked(path)
        heldAgainst(record, owner, agent, store)?.takeIf { it.holds }?.let { fail("$path is ${it.locked()}; not taken") }
        val t = taskOf(path, task, record)
        val reason = Owner(owner, t, today(), agent).reason()
        if (Owner.parse(reason)?.owner != owner || !owner.all { it.code < ASCII }) {
            fail("owner `$owner` must be non-empty ASCII without ` | `")
        }
        val origin = Origin.of(record.path)
        val locked = !(spareEvicting && Origin.evicts(origin))
        if (locked) relock(main, record, reason)
        return Adopted(record.path, t, origin, locked, false, record.branch)
    }

    private const val ASCII = 128

    /** Write [reason] as the lock; a failed rewrite puts the old lock back. */
    private fun relock(
        main: String,
        record: WorktreeRecord,
        reason: String,
    ) {
        val old = record.locked
        if (old == null) {
            Git.lock(main, record.path, reason)
            return
        }
        Git.unlock(main, record.path)
        try {
            Git.lock(main, record.path, reason)
        } catch (e: GitError) {
            Git.lock(main, record.path, old)
            throw e
        }
    }

    /** [run] for a resolved agent, then the store claim: the one path of the CLI and MCP. */
    @Suppress("LongParameterList")
    fun bind(
        store: Store?,
        path: String,
        agent: AgentDetail,
        ownerFlag: String?,
        task: String?,
        spareEvicting: Boolean,
        autoAdd: Boolean,
    ): Adopted {
        val owner = owner(ownerFlag, agent, store)
        val done = run(path, owner, agent.id, task, spareEvicting, store)
        ClaimStore.remember(store, done.path, agent.id, done.task)
        ClaimStore.registerProject(store, autoAdd, done.path, done.branch)
        return done
    }

    /**
     * `adopt` with no agent to name, as from a host's post-create script (T289.3): the one live agent of the pool's host
     * working in the repository takes the worktree. With none or several the claim is parked without an agent and
     * without a git lock (T289.5) for the next agent to adopt or start in it.
     */
    fun bindUnattended(
        store: Store,
        path: String,
        task: String?,
        idle: String,
        autoAdd: Boolean,
    ): Adopted {
        val (main, record) = linked(path)
        val hosts = Origin.hosts(Origin.of(record.path))
        // Only a pool whose host runs a post-create script has a caller without a session.
        if (hosts.isEmpty()) fail("no agent to bind: pass --agent or set RTOK_AGENT_ID")
        val hostIds = hosts.mapNotNull { h -> runCatching { store.hostId(h) }.getOrNull() }
        // The cwd rule of `agents::link::resolve` (T283.1); only top-level sessions.
        val here =
            store
                .liveAgents(idle)
                .filter { it.hostId in hostIds && it.parentKey.isEmpty() }
                .filter { a -> a.cwd?.let { sameDir(it, main) } == true }
        if (here.size == 1) {
            val agent = store.agentDetail(here[0].id) ?: fail("the live agent vanished")
            return bind(store, path, agent, null, task, true, autoAdd)
        }
        val t = taskOf(path, task, record)
        store.addPendingWorktree(canon(record.path), t)
        ClaimStore.registerProject(store, autoAdd, record.path, record.branch)
        return Adopted(record.path, t, Origin.of(record.path), locked = false, pending = true, branch = record.branch)
    }
}

/** The store half of claim.rs: `remember`, `release`, `complete_pending`, `register_project`. */
object ClaimStore {
    /**
     * Record the claim in the store, retrying while another process holds the write lock. A store error that outlasts
     * the retries fails the command; the git lock already holds the worktree. Settles a pending claim (T289.5).
     */
    fun remember(
        store: Store?,
        path: String,
        agent: String,
        task: String,
    ) = rememberWith(store, path, agent, task, LOCK_ATTEMPTS, LOCK_PAUSE_MS)

    internal fun rememberWith(
        store: Store?,
        path: String,
        agent: String,
        task: String,
        attempts: Int,
        pauseMs: Long,
    ) = context({ "claim not stored; $path stays locked, run `rtok worktree adopt` once the store is free" }) {
        storeClaim(store, path, agent, task, attempts, pauseMs)
    }

    /** [remember] without the advice: a caller that takes the worktree back words its own. */
    internal fun storeClaim(
        store: Store?,
        path: String,
        agent: String,
        task: String,
        attempts: Int = LOCK_ATTEMPTS,
        pauseMs: Long = LOCK_PAUSE_MS,
    ) {
        val p = canon(path)
        store ?: return
        retryLocked(attempts, pauseMs) {
            store.claimWorktree(p, agent, task)
            store.takePendingWorktree(p)
        }
    }

    /** Release the open claim on [path], retrying like [remember]; an unreleased claim keeps its holder looking busy. */
    fun release(
        store: Store,
        path: String,
    ) = context({ "claim not released" }) { retryLocked(LOCK_ATTEMPTS, LOCK_PAUSE_MS) { store.releaseWorktreeClaim(path) } }

    /**
     * `SessionStart` (T289.5): the session [agent], whose working directory is [cwd], takes the worktree around it that
     * a post-create script left pending. Fail open: no git call, and every store error means nothing was completed.
     */
    fun completePending(
        store: Store,
        cwd: String,
        agent: String,
    ): String? =
        runCatching {
            val parked = store.pendingWorktrees()
            val (path, task) = Worktrees.deepest(canon(cwd), parked) { it.first } ?: return@runCatching null
            // Taking the row first lets only one of two sessions starting together win it.
            if (!store.takePendingWorktree(path)) return@runCatching null
            if (runCatching { store.claimWorktree(path, agent, task) }.isFailure) {
                runCatching { store.addPendingWorktree(path, task) }
                return@runCatching null
            }
            path
        }.getOrNull()

    /** T329.6: with `[plugins.graph] auto_add_projects` the worktree becomes a project named by its branch; best effort. */
    internal fun registerProject(
        store: Store?,
        autoAdd: Boolean,
        path: String,
        branch: String?,
    ) {
        if (store == null || !autoAdd) return
        runCatching { store.autoAddProject(path, rtok.store.Origin.Worktree, branch) }
            .onFailure { eprint("warning: project not registered: ${it.message}\n") }
    }
}

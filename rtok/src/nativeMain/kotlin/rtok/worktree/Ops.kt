// Port of src/worktree/ops.rs: the store-backed `rtok worktree` operations. The CLI parses flags and prints; the
// store open lives here. `finish` and `migrate` come with their modules.
package rtok.worktree

import rtok.config.Config
import rtok.store.Store
import rtok.store.endedAgentIds
import rtok.store.liveAgents
import rtok.store.pendingWorktrees
import rtok.sys.canon

object Ops {
    /** `opened`: the store, or null when it cannot be opened (every operation works without one). */
    private fun <T> withStore(
        cfg: Config,
        body: (Store?) -> T,
    ): T {
        val store = runCatching { Store.open(cfg.core.dbPath) }.getOrNull()
        return try {
            body(store)
        } finally {
            store?.close()
        }
    }

    /** `rtok worktree add`: the caller's agent (or `--owner` alone), `--no-seed` for this call only. */
    fun add(
        cfg: Config,
        cwd: String,
        task: String,
        slug: String?,
        owner: String?,
        agent: String?,
        noSeed: Boolean,
    ): AddPlan =
        withStore(cfg) { store ->
            val a = Claim.caller(store, agent)
            val wt = cfg.worktree.copy(seed = cfg.worktree.seed && !noSeed)
            AddClaim.add(store, cwd, wt, task to slug, a to owner, cfg.plugins.graph.autoAddProjects)
        }

    /** `rtok worktree whoami`: an unknown `RTOK_AGENT_ID` still leaves the root and the cwd worth printing. */
    fun whoami(
        cfg: Config,
        cwd: String,
    ): Whoami =
        withStore(cfg) { store ->
            val a = runCatching { Claim.caller(store, null) }.getOrNull()
            WhoamiRun.run(cwd, cfg.worktree.root, a, store)
        }

    fun claim(
        cfg: Config,
        path: String,
        agent: String?,
        owner: String?,
    ): Adopted =
        withStore(cfg) { store ->
            val a = Claim.caller(store, agent) ?: fail("no agent to bind: pass --agent or set RTOK_AGENT_ID")
            Claim.bind(store, path, a, owner, null, false, cfg.plugins.graph.autoAddProjects)
        }

    fun adopt(
        cfg: Config,
        path: String?,
        task: String?,
        agent: String?,
        owner: String?,
    ): Adopted =
        withStore(cfg) { store ->
            val p = path ?: rtok.sys.cwd()
            val a = Claim.caller(store, agent)
            if (a == null) {
                store ?: fail("no agent to bind: pass --agent or set RTOK_AGENT_ID")
                Claim.bindUnattended(store, p, task, cfg.agents.idle, cfg.plugins.graph.autoAddProjects)
            } else {
                Claim.bind(store, p, a, owner, task, true, cfg.plugins.graph.autoAddProjects)
            }
        }

    @Suppress("LongParameterList")
    fun remove(
        cfg: Config,
        target: String,
        agent: String?,
        owner: String?,
        keepBranch: Boolean,
        dryRun: Boolean,
    ): Removed =
        withStore(cfg) { store ->
            val a = Claim.caller(store, agent)
            val cwd = rtok.sys.cwd()
            if (dryRun) {
                WorktreeRemove.previewForAgent(store, cwd, target, a, owner, keepBranch)
            } else {
                WorktreeRemove.forAgent(store, cwd, target, a, owner, keepBranch)
            }
        }

    /** [sized] also walks the build caches for their bytes (T936). The scan runs before the store opens. */
    fun list(
        cfg: Config,
        cwd: String,
        sized: Boolean,
    ): List<Row> {
        val rows = if (sized) WorktreeList.rowsSized(cwd) else WorktreeList.rows(cwd)
        withStore(cfg) { WorktreeList.attributeWithStore(rows, it, cfg.agents.idle) }
        return rows
    }

    /** Live agent ids for `worktree gc`; no store means no live agents (T285). */
    fun liveIds(cfg: Config): Set<String> = withStore(cfg) { s -> s?.let { runCatching { it.liveAgents(cfg.agents.idle).map { a -> a.id }.toSet() }.getOrNull() } ?: emptySet() }

    /** Ended agent ids for `worktree gc` (T949); no store means none ended, so every lock holds. */
    fun endedIds(cfg: Config): Set<String> = withStore(cfg) { s -> s?.let { runCatching { it.endedAgentIds().toSet() }.getOrNull() } ?: emptySet() }

    /** Worktrees `adopt` parked for the next agent (T289.5). */
    fun pendingPaths(cfg: Config): List<String> = withStore(cfg) { s -> s?.let { runCatching { it.pendingWorktrees().map { (p, _) -> canon(p) } }.getOrNull() } ?: emptyList() }
}

// Port of claim.rs `add`/`add_with` (T159, T970) and `copy_symbol_index`: `rtok worktree add`, MCP `worktree_add` and the
// `WorktreeCreate` hook create the worktree for an agent (or an owner alone) and record the claim, all or nothing.
package rtok.worktree

import rtok.store.AgentDetail
import rtok.store.Store
import rtok.store.canonRoot
import rtok.store.copySymbolRows
import rtok.sys.eprint

object AddClaim {
    /** [add] on the host's volume and git. */
    fun add(
        store: Store?,
        cwd: String,
        wt: rtok.config.Worktree,
        id: Pair<String, String?>,
        who: Pair<AgentDetail?, String?>,
        autoAdd: Boolean,
    ): AddPlan = addWith(Seams.HOST, store, cwd, wt, id, who, autoAdd)

    /** All or nothing: a step that fails after the worktree exists takes back the worktree, its branch and the claim. */
    fun addWith(
        seams: Seams,
        store: Store?,
        cwd: String,
        wt: rtok.config.Worktree,
        id: Pair<String, String?>,
        who: Pair<AgentDetail?, String?>,
        autoAdd: Boolean,
        temp: List<String> = listOf(rtok.sys.tempDir(), "/tmp"),
    ): AddPlan {
        val (agent, ownerFlag) = who
        val owner = Claim.owner(ownerFlag, agent, store)
        val (plan, undo) = Add.run(cwd, wt, seams, id, owner to agent?.id, temp)
        if (agent != null) {
            store?.let { undo.claim(it, plan.path) }
            undo.guard { context({ "claim not stored; ${plan.path} removed again" }) { ClaimStore.storeClaim(store, plan.path, agent.id, plan.task) } }
        }
        // Both best effort, so the claim goes first: it is the one step that can still fail.
        copySymbolIndex(store, cwd, plan.path)
        if (wt.seed) Seed.fromSiblings(cwd, plan.path, wt.seedDirs)
        ClaimStore.registerProject(store, autoAdd, plan.path, plan.branch)
        undo.keep()
        return plan
    }

    /** Main's symbol rows onto the new worktree, so its graph starts warm; main's `.rtok/` holds other worktrees. */
    internal fun copySymbolIndex(
        store: Store?,
        cwd: String,
        dest: String,
    ) {
        if (store == null) return
        runCatching {
            val main = Git.list(cwd).firstOrNull()
            if (main != null) store.copySymbolRows(canonRoot(main.path), canonRoot(dest), listOf(".rtok"))
        }.onFailure { eprint("warning: symbol index not copied: ${it.message}\n") }
    }
}

// Placeholder for src/worktree/seed.rs (T953): seeding a new worktree's build caches from a sibling checkout. Until the
// copy-on-write clone lands, a new worktree starts with empty caches (a rebuild, never a wrong build).
package rtok.worktree

object Seed {
    /** Best effort and currently a no-op; see port-plan.md "worktree seed". */
    @Suppress("UNUSED_PARAMETER")
    fun fromSiblings(
        cwd: String,
        checkout: String,
        dirs: List<String>,
    ) = Unit
}

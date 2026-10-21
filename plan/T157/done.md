No product code. The 18 GB orphan came from absolute worktree links breaking when the repository moved; git ≥ 2.48 can write relative links, but doing so sets `extensions.relativeWorktrees`, and a tool that does not know the extension refuses to open the repository (`research.md` §18.2).

Plan: in a scratch clone, enable `worktree.useRelativePaths`, add a worktree, then open the repository with every git reader in `toolchain.md` and the workspace (git CLI, `gh`, cargo's VCS check in `cargo package --list`, the editors' git integrations, any `git2`/`gix`-based tool found in `toolchain.md`). Move the clone and confirm the link survives and `git worktree repair` is not needed.

Check: `research.md` §18.2 gains a dated compatibility table; if every reader passes, the `worktrees` skill (T155) and `AGENTS.md` gain the one-line setting; if any fails, the finding is recorded and the setting stays off.

Result: research.md §18.2 gains a dated probe table: git 2.54, cargo 1.97.1 (VCS dirty check) and gh 2.101 all open a relative-link worktree (extensions.relativeWorktrees, format v1); moving the common parent keeps relative links working without repair while the absolute control breaks. Editors are untested (interactive), so the setting stays off and the worktrees skill/AGENTS.md are unchanged.

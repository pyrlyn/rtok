Part of T660: worktrees move into `<project>/.rtok/worktrees`, and rtok gets one way to find a project root. Today six sites decide it differently: `git_root` (`crates/rtok-config/src/layers.rs`) stops at a linked worktree's `.git` file, `common_git_dir` (`src/project.rs`) resolves to the main checkout, `src/doctor.rs` has a private copy of `git_root`, and `checkpoint`/`tasks/run.rs` mix both. Nothing checks that a path rtok builds stays inside the project.

Done: one module in the lowest crate that needs it (the config crate reads `.rtok.toml` through it), lexical, no git subprocess (hook budget), over the existing `ReadFs`:

- `ProjectRoot::find(cwd)` → `checkout` (toplevel of the checkout `cwd` is in, a linked worktree's own dir) and `main` (the main checkout, through `commondir`; a submodule is its own project), plus the common `.git` dir.
- `contain(root, path)`: the path made absolute against `root`, lexically normalized, then resolved through its deepest existing ancestor (symlinks); `Err` when it leaves `root`. Reuse `path_starts_with` (`crates/rtok-store/src/paths.rs`) and `worktree/add.rs::resolved` instead of new code.
- `src/project.rs` (`project_name`, `project_key`) runs on it; no behaviour change.
- Docs: a "Project root" section in `architecture.md` (which root each caller wants and why).

Execution plan:

1. Move `common_git_dir`/`common_dir` and `git_root` into the new module; `ReadFs` goes wherever both crates can see it.
2. `contain` on top of `resolved` + `path_starts_with`.
3. Tests (Vfs and a tempdir with real symlinks): main checkout; linked worktree with absolute and relative `gitdir`/`commondir`; submodule; no repo; cwd deep inside; `..` escape; absolute path outside; symlink escape; symlink inside that stays inside; root itself.
4. `just check`.

Check: the tests above pass and `project_name`/`project_key` tests are unchanged.

Result: `crates/rtok-config/src/root.rs` holds `ReadFs`/`HostFs` (moved from `src/fs.rs`, re-exported there), `ProjectRoot { checkout, main, git_dir }`, `resolved` (moved from `worktree/add.rs`) and `contain`. `src/project.rs` and `layers.rs::git_root` run on it; callers are unchanged until T660.2. Review fix: `contain` first normalized lexically and then followed symlinks, so `link/../secret` with `link` pointing outside passed as `root/secret`; it now follows symlinks through the deepest existing ancestor first and applies `..` lexically only to the missing tail (test `contain_follows_symlinks`, failing before the fix). "Project root" subsection in `architecture.md` §7.

Status: done 2026-10-13
Model: Claude Code / claude-opus-5-5 (code by a claude-sonnet-5-5 subagent, reviewed)

Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` using the actual [build script](build.rs#L39) in a minimal temporary Cargo package and a real linked Git worktree. `.git` is a file there, so none of `.git/HEAD`, `.git/refs/heads`, `.git/packed-refs` exists and no Git rerun hint is emitted. After an initial build and an empty commit advancing HEAD, a second `cargo run` still prints the old embedded SHA. The fixture reported new HEAD `86a42649a` while the binary retained `81b95850e`. Other rerun directives keep Cargo from using its broad default source scan. T10.4's version embedding and T310's SPA hints do not cover linked-worktree Git metadata.

Execution plan:

1. Add an isolated incremental-build regression with a linked worktree: build, advance HEAD without touching sources, build again and compare the embedded SHA with Git.
2. Resolve the actual per-worktree Git directory and common directory through Git, and watch HEAD plus the current loose ref/packed refs as appropriate. Support detached HEAD and ordinary checkouts.
3. Keep source archives without Git working, avoid always-dirty missing-path hints, and cover checkout/ref changes as well as ordinary commits.
4. Run build/version tests and `just check`.

Check: an incremental worktree build embeds its current HEAD rather than a stale commit. The reproduction ran Cargo and Git with the production build.rs; the full rtok binary and release packaging were not rebuilt.

Result: `build.rs` asks `git rev-parse --git-dir --git-common-dir` and hints `<gitdir>/HEAD`, `<common>/refs/heads` and `<common>/packed-refs`, so a linked worktree's embedded sha follows commits.

Check: `tests/build_git_sha.rs`: `the_sha_follows_commits_and_detached_checkouts_in_a_linked_worktree`, `the_sha_follows_commits_in_an_ordinary_checkout`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5

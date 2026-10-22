Found 2026-09-22 in the docs pass: `Cargo.lock` holds `windows-sys` 0.52.0, 0.60.2 and 0.61.2 simultaneously (transitive users at 0.52/0.60 beside `rtok-sys`'s 0.61) — the only multi-version crate of note (the tree-sitter grammar family is single-version). On Windows three copies of the bindings compile and link, growing the binary and the T178 cold-start cost that is already over the 10 ms hook budget.

Plan: `cargo tree -d` to find the 0.52/0.60 holders, bump those transitive parents within existing semver ranges (no direct dep version bumps) or nudge the lockfile (`cargo update -p windows-sys@…`); record the reason per the dependency rule.

Check: `grep -c 'name = "windows-sys"' Cargo.lock` = 1 (or `mise exec -- cargo tree -d` shows no windows-sys entry); `just check` green on windows-latest.

Blocked (2026-09-24, checked against the lockfile): no in-range update removes a copy. `windows-sys` 0.52.0 comes from `ring` 0.17.14 (latest release; pulled by `rustls-webpki` / `quinn-proto`), 0.60.2 from `notify` 8.2.0 (latest stable; 9 is `9.0.0-rc.5`). Needs a creator decision: `notify` 9 once it leaves RC (drops 0.60), and a rustls crypto provider other than `ring` or a new `ring` release (drops 0.52).

Do (Claude Code / claude-opus-5-5, 2026-09-24): with the creator's permission (2026-09-24), `notify` 8 → `9.0.0-rc.5`, the latest release, still an RC. 9 moved to `windows-sys` 0.61, so the 0.60.2 copy and its `windows-targets` 0.53 family leave `Cargo.lock`. `graph/watch.rs` compiles unchanged, because the `recommended_watcher` / `Event` API is the same. 0.52.0 stays in the lockfile only as `ring`'s requirement, and `ring` is an optional dependency of `rustls-webpki` / `quinn-proto` that no active feature enables: `cargo tree -i windows-sys@0.52.0 --target all` prints nothing, so it is never compiled. Swapping the rustls crypto provider was not needed.

Check result: `cargo tree -d --target all -e normal,build` lists no `windows-sys` entry (the Check's second form); `Cargo.lock` holds 0.52.0 (unbuilt) and 0.61.2. `just check` green (1750 tests); the `windows` CI job builds the new `notify`.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5

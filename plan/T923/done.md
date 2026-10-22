# T923. Relative MCP paths resolve against the server's checkout, not the caller's worktree

Done. A relative `path` of `read`, `search`, `tree` and `outline` now starts at the caller's root
when the server can know it, and the answer names the root it used wherever a wrong root shows.

- **Caller root.** `rtok mcp` takes the linked agent's registered cwd (the hooks refresh it on
  every event) as the base of relative paths for the duration of one tool call
  (`src/mcp.rs` `Server::caller_dir`, read through a 5 s cache so an unlinked shared server does
  not redo the link rules per call). Client roots already became the process cwd (T263).
- **Trust boundary.** The registered cwd is untrusted input, so `roots::enter_caller`
  (`src/plugins/read/roots.rs`) accepts it only when it is a directory inside the server's cwd,
  a client root or a worktree of either's repository, and never under `/` or `$HOME`. The path
  guard (`resolve_with`) is unchanged and still runs on the resulting path.
- **One base, no parallel resolver.** `read`, `search` and `tree` take their cwd from
  `roots::cwd()` (a thread-local set by a guard, so concurrent calls never share a root);
  `outline`, which resolves inside the graph plugin, gets its relative path joined onto the caller
  root in `src/mcp/paths.rs`.
- **Visible root.** A failed relative call, an empty `search`, and (when the caller root is
  unknown and the server's repository has other worktrees) any relative answer, end with
  `relative paths resolved against <root>`. Absolute paths are never annotated.
- **Descriptions.** `read`, `search` and `tree` say "From a worktree, pass absolute paths."
  `outline` does not: the graph tools' description budget (170 tokens) has no room, so its
  answer carries the root note instead.

Reuse: the worktree listing and client roots in `roots.rs` (T351, T435), `fs_kit::path::canonicalize`
and `crate::fs::is_unwalkable_root`; no new dependency.

Check: `roots::tests::relative_paths_follow_the_caller_worktree_not_the_server_checkout` failed
first with `No such file or directory: a.txt` (call sites on `current_dir()`), then passed;
`a_caller_root_outside_the_server_project_is_refused`, `mcp::paths::tests::*` and
`mcp::tests::a_missed_relative_read_names_the_root_it_used` cover the guard and the notes;
`tests/trycmd/mcp.toml` and `report-md.toml` carry the new description text. `just check`: fmt, clippy and 4143 of 4144 tests pass; the one failure, `host_docs::agents_md_stays_under_its_350_token_budget`, fails on `main` too and is fixed by #1230.

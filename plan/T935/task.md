---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T935. MCP `read`/`outline` fail with a bare ENOENT once the server's cwd is deleted

16 calls failed with `No such file or directory (os error 2)` and no path, in 5 MCP sessions,
the latest burst 2026-10-18 04:28–05:03, for relative and absolute paths alike. The worktree
that was the server's cwd had been removed; `src/plugins/read/mod.rs` calls
`std::env::current_dir()?` before the file read, so every read, search and tree fails until the
server restarts.

Done when: a vanished server cwd falls back to a roots/list root (or refuses with "server cwd
no longer exists; pass an absolute path"), and absolute paths still work.

Check: a test that removes the server's cwd and reads an absolute path; `just check`.

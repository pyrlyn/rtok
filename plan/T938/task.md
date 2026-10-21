---
status: todo
priority: P3
complexity: 2
readiness: 0%
agent:
---

# T938. `read` errors for binary files, directories and `~/.cargo` sources give no next step

`read` of a PNG fails with `stream did not contain valid UTF-8` (4, weft), a directory with
`Is a directory (os error 21)` (3), and `~/.cargo/registry/src/*` or `~/.cargo/git/checkouts`
paths with `path outside cwd` (17). None says what to do.

Done when: the binary case says "binary file; use the host's file tool", the directory case
points to `tree`/`outline`, and the cargo case names `[plugins.read] allow_paths`.

Check: read-plugin tests for the three messages; `just check`.

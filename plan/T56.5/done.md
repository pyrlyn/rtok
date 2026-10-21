**T56.5 ReadFs trait + optional HostFs walk swap** · T56.4 · `src/plugins/read/fs.rs`, `src/plugins/read/mod.rs` (close-out; code landed earlier unclaimed)
Do: production `read`/`resolve` run through `ReadFs`/`read_with`/`resolve_with` with `HostFs`; `Vfs` gains optional symlinks; disk e2e stays, with Vfs twins for three-lines / range / caps / symlink escape. The optional half — swapping production `search`/`tree` from `ignore::WalkBuilder` to `WalkFs`+`HostFs` — only if gitignore parity is measured and the swap stays small.
Check: the audit closes the remainder as not needed: `read` carries the Vfs twins (three_lines/range/numbered/caps/symlink via `read_with`, plus cache twins `*_from_vfs`) and keeps the disk e2e D29 wants; the walk swap stays undone on its own gate — gitignore parity was never measured, and `ignore::WalkBuilder` handles the `.gitignore` semantics (`overrides`, exclude/include from T68.10) a hand-rolled `WalkFs` would have to re-prove, so a swap is a rewrite with no measured win.
Complexity: 2/5 — the landed half was the task; the optional half fails its own precondition.
Status: done 2026-09-18 (close-out; the trait work landed in the T56.x series unclaimed)
Check result: no code change; evidence is the coverage audit above plus the green cmd/tui/doctor scope runs in this session's worktree passes.
Model: ZCode / GLM-5.3-Flash

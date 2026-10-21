**All tests must prefer a virtual filesystem** over host `TempDir` / raw `std::fs` as the primary approach. Goal: unit tests run against an in-memory FS so they do not depend on real disk layout, and Windows/macOS path quirks (case fold, spaced profiles) can be simulated. `src/testutil.rs` ships `Vfs` (path → bytes) with `write` / `read` / `read_str` / `len` / `exists` / `paths` / `paths_under`.

**Result.** D29 recorded, AGENTS.md notes the rule, Vfs covers the API above, and read (`search_max_bytes_gate_uses_vfs_sizes`), cmd (`Settings::from_vfs` rules tests), and graph (`file_uri_encodes_spaces`) unit tests use it with no host temp dir. Moved out of `plan.md` on 2026-09-18 (the row had sat there as `done` / 100 %).

---

If search/tree keep needing real walks, introduce a narrow trait (metadata + read bytes + list dir) with a `Vfs` impl so oversized-file and relative-path tests run without host disk.
Done when search/tree unit tests for the size-cap and relative-path cases can run against `Vfs`, or the card closes with a measured reason to keep WalkBuilder-on-disk.

**Result.** `plugins::read::walk::{WalkFs, walk, search_hits, tree_rows}` + `Vfs::{list_dir, meta}` (dirs inferred). Production `search`/`tree` keep host `WalkBuilder` (gitignore); disk e2e kept. Size-cap / relative-path / skip-`.git` twins run on the adapter. `HostFs: WalkFs` stub landed under test (T56.5); prod swap of WalkBuilder is the T56.5 follow-up, not required here. Moved out of `plan.md` on 2026-09-18 (the row had sat there as `done` / 100 %).

---

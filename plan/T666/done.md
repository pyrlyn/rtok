`tests/commands_e2e.rs`'s `cmd()` helper isolated `HOME`/`RTOK_HOME` but left the cwd at the checkout, so `doctor_reports_the_chain` ran `rtok doctor` there and its junk section (`agents::junk::report` → `junk_worktrees` → `worktree::list::walk`) walked every real worktree of the repository. On 2026-10-14, with ~10 sibling worktrees of 20+ GB `target/` each and load ~80, the test hit nextest's 180 s terminate-after twice. The other harnesses that reach the junk report (`agents_junk.rs`, `web_e2e.rs`, `doctor_checklist_pty.rs`, the `doctor --fix` cases in this file) already ran in the temp home.

Result: `cmd()` sets `current_dir(home)`; the two per-test `current_dir` calls in the `doctor --fix` cases are gone. A time budget for `doctor`'s junk section for real users is I-132 in `ideas.md`.

Check: `commands_run_in_the_temp_home_not_the_checkout` (`rtok run pwd -P` through `cmd()` prints the temp home) failed before the fix with the checkout path and passes after; `cargo nextest run --test commands_e2e` 22/22, `doctor_reports_the_chain` 4.7 s; `just check`.

Status: done 2026-10-14
Model: Claude Code / claude-opus-5-5

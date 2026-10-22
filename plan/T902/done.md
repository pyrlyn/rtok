`cfg(windows)` lint debt (the T273/T274 class) is only found by the first local Windows gate. Done = the `windows` CI job runs `cargo clippy --workspace` and is green, so such debt fails CI.

Check: the `windows` job of this PR runs the clippy step and is green.

Plan: add `cargo clippy --workspace` to the `windows` job in the CI workflow (reusing the lint flags the Linux job uses); fix any `cfg(windows)` lint it surfaces in this PR if small; proof is the green CI run of the PR.

Outcome: the `windows-build` job runs `cargo clippy --workspace --all-targets` after the nextest archive step; the first run found 56 dead-code warnings in test helpers that only unix-only tests use (src/doctor/{fix,web,mcp_dupes,hooks}.rs, src/tui/{doctor_fix,app}.rs, tests/agents_junk.rs), now allowed on Windows only. Proof: the green `windows-build` job of #1094.

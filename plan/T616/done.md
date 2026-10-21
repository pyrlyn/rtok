Inventory and audit remaining Rust and production non-Rust modules across `src/`, `crates/`, `web/`, `plugins/`, and project scripts, using prior T530-and-newer audit records to avoid treating a filename mention as full coverage. Work from grouped subsystem inventories, inspect uncovered call paths and invariants, and report only confirmed findings with evidence and explicit limits.

Execution plan:

1. Inventory Rust and tracked production non-Rust source modules by directory and compare exact path citations in T530+ plan/done records, treating these as aids rather than proof of full or absent coverage.
2. Inspect remaining subsystem groups and their callers, then run focused real-source reproductions and controls for concrete candidates.
3. Deduplicate reproduced cases across `plan.md`, `done.md`, `roadmap.md`, and `ideas.md`; file only distinct findings, preserving the probe and integration limits.
4. Record exact reviewed coverage and evidence, verify task parity and the docs diff, run scoped `just check`, and prepare the PR #986 update.

Check: the inventory groups Rust and production non-Rust modules, with tests, fixtures, generated files and dependencies identified or excluded; each new finding has source evidence, a distinct reproduced invariant failure, and ordered fix/check steps; the final audit record states scope and limits, and scoped `just check` passes.

Result: completed the remaining module-family source pass and recorded its exact depth in [audit-coverage.md](audit-coverage.md). The earlier T616 checkpoint filed T617–T626; this continuation adds T627–T639, thirteen distinct findings with source evidence, reproduction limits and ordered fixes. It closes the checkpoint's shared agent helpers, CLI wrappers, plugin SDK, TUI, frontend page/graph rendering and packaging-source review. Large subsystems retain the targeted depth identified in the ledger; completion of this audit pass is not a claim that every function or runtime integration has been verified.

Evidence revision for the final thirteen findings: `ea999781d7a48733e3a5c92e711cfad291c13536`. Reproductions used actual/extracted production functions, the real config crate, a minimal Cargo package with the real build script and linked Git worktree, synthetic shell children, fake publisher/process executables and Ratatui TestBackend. PDF findings are source/arithmetic proofs, not visual renderer tests. No product source or dependency changed. The seven existing ideas remain unimplemented.

Validation: scoped `CHECK_BASE=085b49e9450de9326e7738dc441718e1bd667363 mise exec -- just check` passed and explicitly skipped Cargo tests for the documentation-only diff. Runtime boundaries are recorded in the coverage ledger and individual cards; this gate does not establish full Cargo, browser, host, release or cross-platform runtime coverage.

Status: done 2026-10-12

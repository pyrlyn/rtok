Source-confirmed: [members](src/project/refs.rs#L122) discards patterns beginning with ! instead of subtracting their matches. With packages/* and !packages/skip, an excluded packages/skip/package.json is still read by [npm discovery](src/project/refs/npm.rs#L44); its external file: dependency is returned and can be automatically registered by graph following. The [pnpm packages specification](https://pnpm.io/settings#packages) explicitly supports negative exclusions. This reproduces with single-level globs, independently of the recursive-glob limitation already acknowledged by T329.7.

Execution plan:

1. Add included and excluded sibling packages with distinct external dependencies; verify discovery returns only the included package's dependency.
2. Apply negative pnpm patterns to the selected member set before recording members or reading their dependencies, following pnpm semantics rather than silently dropping exclusions.
3. Cover multiple positives/negatives, overlapping patterns, nested exclusions and root-package inclusion; keep behavior of other manifest formats explicit.

Check: excluded members contribute neither references nor missing-reference warnings/auto-projects, included members still resolve, and `just check` passes.

Result: `members()` subtracts `!pattern` matches wherever they appear in the list (pnpm and npm); the root package is always kept. Cargo and uv have no negative syntax.

Check: `negative_workspace_patterns_subtract_members` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5

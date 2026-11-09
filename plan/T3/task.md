---
status: in progress
priority: P1
complexity: 2
readiness: 80%
agent: Claude Code / claude-sonnet-5-5
---

# T3. Docs and plan layout

The docs drifted from the code: `PORTING.md` repeats part of its table and marks ported modules ⬜, `README.md` says the TUI and web UI are excluded, `port-plan.md` lists fixed bugs as open, and the repo lacks the workspace project layout (`plan/`, `roadmap.md`, `ideas.md`, `toolchain.md` in the root).

Done means:

- `PORTING.md` has one table, and its rtok-sys, rtok-mcp, CLI, plugins, proxy, mcp and hooks rows match the code.
- `README.md` describes the TUI and web UI as they are.
- `port-plan.md` ticks the items fixed by merged PRs, says at its top that open work lives in `roadmap.md` and `plan/`, and has no unchecked boxes.
- `roadmap.md`, `ideas.md` and `plan/README.md` exist; `toolchain.md` sits in the root and every link to it works.
- `AGENTS.md` carries the rule to follow a higher-level `AGENTS.md` or `CLAUDE.md`.

Check: `git grep -n 'docs/toolchain'` prints nothing, `grep -c '\- \[ \]' port-plan.md` prints 0, and the table in `PORTING.md` appears once.

## Execution plan

1. Create `plan/README.md` and this task.
2. Remove the repeated table in `PORTING.md` and fix the stale rows against the code.
3. Fix the `README.md` TUI and web UI statement.
4. Tick the merged fixes in `port-plan.md`, move the open items to `roadmap.md`.
5. `git mv docs/toolchain.md toolchain.md` and fix the links.
6. Add `ideas.md` with the promoted ideas marked; check the `AGENTS.md` rule.
7. Write `done.md` and open the PR.

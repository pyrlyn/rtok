# T3. Docs and plan layout: done

PR: https://github.com/pyrlyn/rtok/pull/254

- `PORTING.md`: the table repeated after "Totals" is merged into the main table; the rtok-sys, rtok-mcp, CLI, plugins, proxy, mcp, hooks and TUI rows now match the code (checked against the `cli/`, `mcp/`, `proxy/` and `rtok-mcp` sources).
- `README.md`: the TUI and web UI are described as ported and in progress (docs/web.md).
- `port-plan.md`: #242 to #249 ticked after `git log` confirmed them; the unchecked items moved to the new `roadmap.md`; the file says at its top that open work lives in `roadmap.md` and `plan/`.
- `toolchain.md` moved to the root with `git mv`; links in README, `mise.toml`, `build.gradle.kts`, `gradle.properties` and `port-plan.md` fixed.
- Added `plan/README.md`, `plan/T3/`, `roadmap.md`, `ideas.md` (I-1 to I-6, I-9, I-10 marked promoted) and the higher-level `AGENTS.md` rule.

Checked: `git grep 'docs/toolchain'` finds nothing outside `ideas.md`, `port-plan.md` has no unchecked boxes, and `PORTING.md` has one table header. Docs only, so no test run.

Two things were not checked mechanically. The "now ported" CLI and MCP rows come from file names, not from running the commands. The extra ticks for #242 to #245 and #247 go beyond the three PRs named in the task; their commit messages match the items.

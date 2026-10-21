In the plan, T276 (plan.md@966f067 lines 248, 266) says `src/proc/` "is the only place in rtok that calls `std::process::Command::new`", enforced by a `clippy.toml` ban "including `crates/`". T277 (plan.md@966f067 lines 289, 298) says `crates/rtok-mcp` has "no dependency on the `rtok` crate" yet takes over "the MCP probe (`spawn_mcp`, `mcp_command`, using T276's `ProgressRunner` for the spawn)" and the T275.1 ping spawn. These contradict each other because the MCP crate can neither import `ProgressRunner` from the `rtok` crate nor call `Command::new` itself under the ban.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Decision (creator, 2026-10-10): `ProgressRunner` lives in the existing crate `crates/rtok-sys` (OS process shims), which both `rtok` and `crates/rtok-mcp` depend on. The clippy `Command::new` ban covers everything except that crate. T276 and T277 say so; no new crate.

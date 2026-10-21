**T25.0 a session knows whose it is** · - · `migrations/0010.sql` (new), `src/plugin.rs`, `src/hooks/mod.rs`, `src/store/mod.rs`, `src/config/layers.rs`
Do: `Ctx` learns the host from `[hook] host` and passes it to `upsert_session` instead of `None`,
with `project` (git root basename) and `cwd`; `SessionStart` writes the row rather than leaving it
to the first call that happens to arrive. The migration seeds the two host slugs `rtok agent setup`
can install but `hosts` never had — `pi` and `claude-code`-style additions belong in data, not in a
match arm. `ended_at` gains a companion: sessions are live until a `SessionEnd` *or* silence longer
than `[agents] idle_secs`, because the proxy and every non-Claude host never send one.
Check: a hook run leaves a `sessions` row with a non-NULL `host_id` and `project`; `pi` records as
`pi` and not as `other`; an existing DB migrates with no row rewritten.
Status: done 2026-09-09 · Model: Opus 5 (subagent)
Check result: green. `Runtime` resolves `host_id` once at open from `[hook] host` (falling back to
`other`, the shape `proxy::ProxyState::new` already uses) and carries the event's `cwd`, which
`dispatch_owned` sets before dispatch — so the row is attributed from the run's first hook, normally
`SessionStart`. `insert_call` now passes `host_id`, `project` and `cwd` instead of four `None`s.
`hooks::tests::hook_run_attributes_the_session` reads the row back and finds slug `claude` and
project `myproj`; `pi_host_resolves_to_pi_not_other` finds `pi`;
`store::tests::schema_0002_seeds_hosts_and_rejects_bad_fk` counts 7 hosts where it counted 6, and
`migration_is_idempotent` still passes — 0010.sql is one `INSERT OR IGNORE`, so an existing DB gains
the row and nothing is rewritten. 71 plugin, 9 hooks and 10 store tests pass; clippy clean.
Deviations: three. (1) `src/store/mod.rs` was outside the planned file list but a migration is inert
until it is listed in `MIGRATIONS`; it also holds the `#[cfg(test)] session_row` reader the Check
needs. (2) `src/config/layers.rs`: `git_root` became `pub(crate)` so `project_of` calls it instead of
walking the tree a second time — five files rather than three, but the alternative was the copy the
no-duplication rule forbids and `just dup` gates. (3) The `[agents] idle_secs` liveness companion is
**not** implemented: it needs `config/mod.rs` and `config/default.toml`, and T25.1 computes last
activity with a `GROUP BY` over `calls`/`usage` joined to `sessions`, so no stored column is needed
for it. That sentence of the Do stays open and belongs with T25.1.

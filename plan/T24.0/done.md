**T24.0 `[log]`: a sink that rotates** · - · `src/log.rs` (new), `src/config/mod.rs`, `config/default.toml`
Do: the section (`path` `~/.rtok/logs/rtok.log`, `max_bytes` 1048576, `files` 5, `lines` 200,
`level` `info`, `to_db` true) and one `append(cfg, level, source, name, message)` that writes a
line and rotates when the file would pass `max_bytes`: `rtok.log.4` → `.5`, current → `.1`, and
whatever falls past `files` is deleted. Lines below `level` are dropped before any I/O. The three
`[core]` keys migrate in `finish()`, `log_file` → `log.path`, and `[log] path` joins the `~`
expansion list.
Check: a sink with `max_bytes` 200 and `files` 2 keeps exactly `rtok.log`, `.1`, `.2` after 50
writes and the newest line is in `rtok.log`; an old config with `[core] log_file` loads and warns;
`default_toml_is_the_defaults` green.
Status: done 2026-09-09 · Model: Opus 5
Check result: green — 5 new unit tests in `src/log.rs`, `config::` 19 green including
`default_toml_is_the_defaults`, `cargo test --lib` 173 passed. The rotation test asserts exactly
three files (`rtok.log`, `.1`, `.2`) after 50 writes at `max_bytes` 200, that `line 49` is in the
live file, and that the live file is under the cap. `stamp()` is checked against `date -u -r` on
three epochs: a leap year, the day after a leap day, and 2100-03-01 — the century that is not a
leap year, which is where a hand-rolled calendar goes wrong.
Deviations: three. (1) The `[core] log_file` / `log_level` / `log_to_db` migration is not here. It
is one line in `finish()` but it also changes the type of `core.log_file`, whose only reader is
`Ctx::log` in `src/plugin.rs` — T24.1's file, and a file another session is holding open right now.
Doing it here would have made a fourth file and a commit that reaches into the next task's code.
`[log]` and `[core]` therefore coexist for one commit, with `[log]` the one that is read.
(2) Four files, not three: `src/lib.rs` gains `pub mod log;`. (3) `stamp()` is 12 lines of
`civil_from_days` rather than a date crate. std has no calendar and the binary needed one line
formatted; a dependency for that is what the dependency rule is about.

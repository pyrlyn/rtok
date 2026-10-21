**T15.12 the parity test enumerates commands, not pages** · T15.11, T15.10 · `tests/surface_parity.rs`
Do: extend T15.10's test from "the two surfaces expose the same pages" to "every reading command has
a page", walking `Cli::command()` the way `config_coverage` already walks it, with an explicit
allow-list for the streaming and writing commands D27 exempts.
Check: adding a reading command with no page fails `just check` naming the command; the allow-list
entries each carry the reason they are exempt.
Complexity: 2/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent; policy tier GLM-5.3-Flash, effort Low)
Check result: green. `every_command_is_exempt_or_renders_a_page_of_the_model` walks `Cli::command()`
the way `config_coverage` does (leaves only; a parent that needs a verb is navigation; `logs` is a
leaf beside its subcommands; clap's `help` skipped). Two const data lists, one line per entry:
`COMMAND_PAGES` (command → page, asserted ∈ `model::pages()`) and `EXEMPT` (command, reason).
Anti-rot asserts: unclassified fails by name; stale entries (renamed/removed commands), empty
reasons, and mapped-and-exempt overlaps all fail. Check evidence (scratch command, reverted, not
committed): `unclassified command 'sessions': a reading command renders a page of the model
(COMMAND_PAGES); everything else needs a reason in EXEMPT (D27, T15.12)`. Both parity tests pass;
`just check` exit 0.
Deviations: none to the spec. Judgment calls recorded: the lists live in the test, not the model,
so the frame stays byte-stable; `otel status` exempted as an exporter echo (the decision T15.11
left open); `config get`/`logs export`/`demon list` classified alongside their siblings. At
integration the coordinator added this round's new commands: `report` (reading, on-demand P22
document), `logs watch` (streaming), `tui` (surface) to `EXEMPT`, and `agent sessions` — a real
snapshot page since T25.1 — to `COMMAND_PAGES`: 2 mapped, 37 exempt of 39 paths.

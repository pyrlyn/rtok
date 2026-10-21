Confirmed through actual aggregation and recommendation functions: [report_sinks](src/model/report.rs#L284) groups all catalogue plugins, sorts by before_bytes and truncates to ten. Only afterwards [top_sinks](src/report/advice.rs#L253) skips classes other than read/cmd/mcp. Ten larger archive rows with distinct references therefore displace a smaller Read sink, yielding no top-sinks recommendation; the Read-only control yields one. The archive rows are valid measurements, but deliberately have no actionable switch in this rule. T59.8 and docs/report.md promise the ten largest file/command/MCP sinks; T302 changed ranking units, not filtering before truncation.

Execution plan:

1. Add an integration fixture with ten distinct positive-byte archive measurements and a smaller Read measurement. Assert that the Read recommendation remains; include cmd and MCP wrap controls and more than ten eligible sinks.
2. Apply the actionable-class predicate before truncating to ten, or retain all grouped sinks until the recommendation layer filters and limits them. Share the eligibility rule so aggregation and presentation cannot drift; preserve deterministic tie ordering and existing switch labels.
3. Cover mixed actionable/non-actionable classes, repeated references that aggregate into one sink, nonpositive byte counts and empty ledgers. Verify that recommendations expose at most ten eligible sinks and keep their Measurement evidence.

Check: unrelated archive/injection rows cannot hide eligible read/cmd/MCP recommendations, the largest eligible sinks remain ranked correctly, and `just check` passes. The scratch probe compiled actual report_sinks/sink_label/sink_switch and top_sinks with an in-memory Store fixture and a catalogue subset containing the relevant real plugins; it was not a Diesel or CLI integration test.

Result: `model::is_actionable_sink` filters report sinks before the top-ten cut, and `src/report/advice.rs` uses the same predicate, so large archive rows no longer hide read, cmd and mcp sinks.

Check: `tests/report.rs::top_sinks_survive_larger_non_actionable_rows` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5

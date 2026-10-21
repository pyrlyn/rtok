Source-confirmed on `0b14b88e`: [`dedup_line`](src/plugins/read/cache.rs#L65) formats a 64-character archive id, then always shortens it to eight characters to meet the 80-character output limit. Even a one-line file produces a full form longer than 80 characters. [`Store::get_archive`](crates/rtok-store/src/archive_rows.rs#L402) performs an exact primary-key lookup; neither CLI nor MCP expand resolves prefixes. Read a file twice in one session, copy the id from `unchanged since <8 chars>`, then expand it: the lookup has no matching row. The bytes exist, but the returned reference cannot retrieve them. T4.4 introduced the short display; T354 documents full ids but does not make this reference usable.

Execution plan:

1. Add a regression using the id parsed from the second read response, not the full id fetched from the database.
2. Prefer a compact dedup message containing the full id within the existing budget; reuse the archive reference convention instead of introducing ambiguous prefix lookup.
3. Exercise CLI and MCP expansion of that exact reference, including large line counts; retain one dedup Measurement.

Check: the second read's reference expands to the archived bytes, the response meets its length contract, and `just check` passes.

Result: the dedup line is `unchanged id <64-char id>` (77 bytes). The line count is dropped because it no longer fits beside the full id; the agent has it from the first read. `DEDUP_PREFIX` is shared with `measure::stats::has_rtok_marker`.

Check: `dedup_reference_expands_to_the_archived_bytes` failed before the change and passes after (CLI `expand::run`, MCP `expand::fetch`, one `read` Measurement); `plugins::read` 91/91, `measure::stats` 31/31, `plugins_e2e` 22/22; clippy clean.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5

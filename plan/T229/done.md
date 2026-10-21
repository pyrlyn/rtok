Found 2026-09-23 in the D27 audit: `demon status` and `otel status` are exempt (`tests/surface_parity.rs:371-374,413`) while `Model::demon` and `model::otel_status` already produce their JSON; an operator cannot see supervisor or exporter health without a shell.

Plan: page `("services", "services")` — one row per supervised service (name, state, pid, uptime, last error) and an OTel block (endpoint, per-stream watermark, pending rows, last flush); both commands move to `COMMAND_PAGES`. Read-only; `demon start/stop` and `otel flush` stay CLI.

Check: `services_page_exists_on_both_surfaces`; `tests/web.rs` fixture with a stopped service and a non-zero watermark; `just check` green.

Result: New Services page ("services","services") on both surfaces: model::services_page_text folds demon::rows (service, running/stopped, pid, uptime, log path) with otel_status (endpoint, per-stream marks, pending rows, last flush) each tick; no last_error column since nothing records one per service, so each row points at its log file. TUI tab and Slint page render it read-only; demon status and otel status moved from EXEMPT to COMMAND_PAGES. Tests: services_page_exists_on_both_surfaces, services_page_reflects_a_stopped_service_and_a_pending_otel_row, webui snapshot parse and missing_services_is_a_failed_tick_not_empty.

Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)

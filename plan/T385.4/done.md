optimization.md §2.2 L3 (roadmap S2/S3). Tag Batch create/poll/list/cancel/results calls on the `batch` lane; with `parse_results = true`, parse result lines into `usage` rows. Fail open on malformed lines.

Check: fixture result streams (Anthropic and OpenAI) produce the expected `usage` rows; a malformed line is skipped, not fatal; `just check`.

Result: `[proxy.batch] parse_results` (default `false`, needs `[proxy.lanes] enabled`) reads a forwarded results file after the fact, so the body is byte-for-byte unchanged. Anthropic `GET /v1/messages/batches/{id}/results` gives one `usage` row per `succeeded` line through the existing Anthropic wire parser. OpenAI `GET /v1/files/{id}/content` gives one row per 2xx `response.body` line through the Chat Completions or Responses parser, and a download that held such lines is re-tagged `api_request:batch` (any other download stays `files`). Errored, expired, cancelled, malformed and truncated lines are skipped; a store error is logged, never fatal. Rows are written in one transaction (`Store::insert_usage_rows`, `Store::set_call_kind`, Diesel); no `tokens` rows. Create, poll, list and cancel calls were already tagged `batch` by T385.1. Code in `src/proxy/batch_results.rs`; tests there and in `tests/proxy_batch_results.rs`. Config template, `docs/config.md` (en, ru, uk) and trycmd snapshots updated. `just check`: 2722 tests run, 2722 passed, 8 skipped. #829.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5

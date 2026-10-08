// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Provider Batch result files as `usage` rows (T385.4, `[proxy.batch] parse_results`).
//!
//! A results body is JSONL, one finished request per line. The proxy only reads it after the
//! bytes were forwarded, so parsing here is observation: a line that is not JSON, a request
//! that errored, expired or was cancelled, or a body without counters simply yields no row.
//!
//! Line shapes (checked 2026-10-08):
//! - Anthropic, `GET /v1/messages/batches/{id}/results`:
//!   `{"custom_id":…,"result":{"type":"succeeded","message":{"model":…,"usage":{…}}}}`
//!   <https://platform.claude.com/docs/en/build-with-claude/batch-processing>
//! - OpenAI, `GET /v1/files/{output_file_id}/content`:
//!   `{"id":…,"custom_id":…,"response":{"status_code":200,"body":{…,"usage":{…}}},"error":null}`
//!   where `body` is whatever the batched endpoint answers (Chat Completions or Responses).
//!   <https://developers.openai.com/api/docs/guides/batch>

use anyhow::Result;
use axum::http::Method;
use serde_json::Value;

use super::anthropic::ANTHROPIC;
use super::lane::Lane;
use super::openai_chat::OPENAI_CHAT;
use super::openai_responses::OPENAI_RESPONSES;
use super::wire::{API_ANTHROPIC, API_OPENAI_CHAT, API_OPENAI_RESPONSES, Usage, Wire};
use crate::store::Store;

/// Where a response may hold a results file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// Anthropic `…/batches/{id}/results`: already a Batch-lane call.
    Batch,
    /// OpenAI `/v1/files/{id}/content`: any file download takes this path, so only the lines
    /// found in it say the call was a Batch output.
    File,
}

/// Whether a successful `GET path` on `lane` is worth reading as a results file.
pub fn source(lane: Lane, method: &Method, path: &str) -> Option<Source> {
    if method != Method::GET {
        return None;
    }
    match lane {
        Lane::Batch if path.ends_with("/results") => Some(Source::Batch),
        Lane::Files if path.ends_with("/content") => Some(Source::File),
        _ => None,
    }
}

/// Write the `usage` rows of a results `body` under the call that fetched it, and re-tag a
/// file download that turned out to be Batch output. Best-effort: a store error logs and the
/// response, already forwarded, is untouched. Returns how many rows were written.
pub fn record(
    store: &Store,
    session: &str,
    call_id: i32,
    fallback_model: Option<&str>,
    source: Source,
    body: &[u8],
) -> Result<usize> {
    let parsed = parse(body);
    if parsed.is_empty() {
        return Ok(0);
    }
    let rows: Vec<_> = parsed
        .iter()
        .map(|r| {
            let u = r.usage;
            let model = r.model.as_deref().or(fallback_model);
            (
                model,
                r.api,
                [u.input, u.cache_create, u.cache_read, u.output],
            )
        })
        .collect();
    store.insert_usage_rows(session, call_id, &rows)?;
    if source == Source::File {
        store.set_call_kind(call_id, Lane::Batch.kind())?;
    }
    Ok(rows.len())
}

/// One result line's counters: the model that answered, the `usage.api` the shared wire
/// parser belongs to, and the four disjoint counters.
#[derive(Debug, PartialEq, Eq)]
pub struct ResultUsage {
    pub model: Option<String>,
    pub api: &'static str,
    pub usage: Usage,
}

/// Every line of `body` that carries usage. Splits on bytes so a malformed (or, past the
/// recording cap, truncated) line costs only itself.
pub fn parse(body: &[u8]) -> Vec<ResultUsage> {
    body.split(|b| *b == b'\n')
        .filter_map(|line| serde_json::from_slice::<Value>(line).ok())
        .filter_map(|line| line_usage(&line))
        .collect()
}

fn line_usage(line: &Value) -> Option<ResultUsage> {
    if let Some(result) = line.get("result") {
        if result.get("type").and_then(Value::as_str) != Some("succeeded") {
            return None;
        }
        // The Anthropic wire reads `message.usage` from the object that holds `message`.
        return found(&ANTHROPIC, API_ANTHROPIC, result, result.get("message")?);
    }
    let response = line.get("response")?;
    let ok = response
        .get("status_code")
        .and_then(Value::as_i64)
        .is_none_or(|code| (200..300).contains(&code));
    let body = response.get("body").filter(|_| ok)?;
    // The batched endpoint decides the shape; the Responses object says so, anything else
    // (Chat Completions, embeddings) reports `prompt_tokens` / `completion_tokens`.
    let (wire, api): (&dyn Wire, _) =
        if body.get("object").and_then(Value::as_str) == Some("response") {
            (&OPENAI_RESPONSES, API_OPENAI_RESPONSES)
        } else {
            (&OPENAI_CHAT, API_OPENAI_CHAT)
        };
    found(wire, api, body, body)
}

fn found(
    wire: &dyn Wire,
    api: &'static str,
    usage_in: &Value,
    model_in: &Value,
) -> Option<ResultUsage> {
    Some(ResultUsage {
        model: model_in
            .get("model")
            .and_then(Value::as_str)
            .map(str::to_string),
        api,
        usage: wire.usage_from_body(usage_in)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn jsonl(lines: &[Value]) -> Vec<u8> {
        lines
            .iter()
            .map(|l| l.to_string())
            .collect::<Vec<_>>()
            .join("\n")
            .into_bytes()
    }

    #[test]
    fn anthropic_succeeded_lines_become_rows_and_the_rest_do_not() {
        let body = jsonl(&[
            json!({"custom_id":"a","result":{"type":"succeeded","message":{
                "model":"claude-sonnet-5-5",
                "usage":{"input_tokens":10,"cache_creation_input_tokens":3,
                         "cache_read_input_tokens":4,"output_tokens":5}}}}),
            json!({"custom_id":"b","result":{"type":"errored","error":{"type":"error"}}}),
            json!({"custom_id":"c","result":{"type":"expired"}}),
            json!({"custom_id":"d","result":{"type":"canceled"}}),
        ]);
        assert_eq!(
            parse(&body),
            vec![ResultUsage {
                model: Some("claude-sonnet-5-5".into()),
                api: "anthropic",
                usage: Usage {
                    input: 10,
                    cache_create: 3,
                    cache_read: 4,
                    output: 5
                },
            }]
        );
    }

    #[test]
    fn openai_lines_pick_their_wire_from_the_body_and_skip_failures() {
        let body = jsonl(&[
            json!({"id":"r1","custom_id":"a","error":null,"response":{"status_code":200,"body":{
                "object":"chat.completion","model":"gpt-5",
                "usage":{"prompt_tokens":100,"completion_tokens":7,
                         "prompt_tokens_details":{"cached_tokens":40}}}}}),
            json!({"id":"r2","custom_id":"b","error":null,"response":{"status_code":200,"body":{
                "object":"response","model":"gpt-5",
                "usage":{"input_tokens":9,"output_tokens":2}}}}),
            json!({"id":"r3","custom_id":"c","error":null,"response":{"status_code":429,"body":{
                "usage":{"prompt_tokens":1,"completion_tokens":1}}}}),
            json!({"id":"r4","custom_id":"d","error":{"code":"batch_expired"},"response":null}),
        ]);
        let rows = parse(&body);
        let got: Vec<_> = rows
            .iter()
            .map(|r| (r.api, r.usage.input, r.usage.cache_read, r.usage.output))
            .collect();
        assert_eq!(
            got,
            vec![("openai_chat", 60, 40, 7), ("openai_responses", 9, 0, 2)]
        );
    }

    #[test]
    fn a_malformed_or_truncated_line_costs_only_itself() {
        let ok = json!({"custom_id":"a","result":{"type":"succeeded","message":{
            "model":"m","usage":{"input_tokens":1,"output_tokens":2}}}})
        .to_string();
        let mut body = format!("not json\n{ok}\n\u{0}\n{{\"custom_id\":\"z\",\"res").into_bytes();
        body.extend_from_slice(b"\n\xff\xfe\n");
        body.extend_from_slice(ok.as_bytes());
        assert_eq!(parse(&body).len(), 2);
        assert!(parse(b"").is_empty());
        assert!(parse(b"{}\n[]\n42\n").is_empty());
    }
}

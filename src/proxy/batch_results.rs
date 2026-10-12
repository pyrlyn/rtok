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
use crate::store::{NewUsage, Store};

/// Where a response may hold a results file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Source {
    /// Anthropic `…/batches/{id}/results`: already a Batch-lane call. `id` scopes each line's
    /// `custom_id`, which is only unique inside its batch; `None` when the path has no id.
    Batch { id: Option<String> },
    /// OpenAI `/v1/files/{id}/content`: any file download takes this path, so only the lines
    /// found in it say the call was a Batch output. `id` is the output file, which scopes each
    /// line's `custom_id` (unique per batch); `None` when the path has no id.
    File { id: Option<String> },
}

/// Whether a successful `GET path` on `lane` is worth reading as a results file.
pub fn source(lane: Lane, method: &Method, path: &str) -> Option<Source> {
    if method != Method::GET {
        return None;
    }
    match lane {
        Lane::Batch if path.ends_with("/results") => Some(Source::Batch {
            id: path_id(path, "/results"),
        }),
        Lane::Files if path.ends_with("/content") => Some(Source::File {
            id: path_id(path, "/content"),
        }),
        _ => None,
    }
}

/// The `{id}` of `…/batches/{id}/results` or `…/files/{id}/content`.
fn path_id(path: &str, suffix: &str) -> Option<String> {
    let id = path.strip_suffix(suffix)?.rsplit('/').next()?;
    (!id.is_empty()).then(|| id.to_string())
}

/// Write the `usage` rows of a results `body` under the call that fetched it, and re-tag a
/// file download that turned out to be Batch output. A result already recorded from an earlier
/// download is not written again (T583.1). Best-effort: a store error logs and the response,
/// already forwarded, is untouched. Returns how many rows were written.
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
    let keys: Vec<Option<String>> = parsed.iter().map(|r| result_key(&source, r)).collect();
    let rows: Vec<_> = parsed
        .iter()
        .zip(&keys)
        .map(|(r, key)| {
            let u = r.usage;
            NewUsage {
                key: key.as_deref(),
                model: r.model.as_deref().or(fallback_model),
                api: r.api,
                legs: [u.input, u.cache_create, u.cache_read, u.output],
            }
        })
        .collect();
    let written = store.insert_usage_rows(session, call_id, &rows)?;
    if matches!(source, Source::File { .. }) {
        store.set_call_kind(call_id, Lane::Batch.kind())?;
    }
    Ok(written)
}

/// The provider-scoped identity of one result, `None` when the source or line has none (a
/// legacy-shaped row nothing can dedupe against). Batch and file ids hold no `/`, so the parts
/// cannot run into each other. An OpenAI output file is keyed by file id, not by the line's
/// chat/responses wire, which a replay reads identically anyway.
fn result_key(source: &Source, r: &ResultUsage) -> Option<String> {
    let line = r.custom_id.as_deref()?;
    match source {
        Source::Batch { id: Some(batch) } if r.api == API_ANTHROPIC => {
            Some(format!("{API_ANTHROPIC}/{batch}/{line}"))
        }
        Source::File { id: Some(file) } if r.api != API_ANTHROPIC => {
            Some(format!("openai/{file}/{line}"))
        }
        _ => None,
    }
}

/// One result line's counters: the model that answered, the `usage.api` the shared wire
/// parser belongs to, and the four disjoint counters.
#[derive(Debug, PartialEq, Eq)]
pub struct ResultUsage {
    /// The line's identity inside its batch or output file: Anthropic's `custom_id`, OpenAI's
    /// `custom_id` (unique per batch) or, failing that, its `batch_req_` line `id`.
    pub custom_id: Option<String>,
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
        let custom_id = line.get("custom_id").and_then(Value::as_str);
        return found(
            custom_id,
            &ANTHROPIC,
            API_ANTHROPIC,
            result,
            result.get("message")?,
        );
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
    let id = ["custom_id", "id"]
        .iter()
        .find_map(|k| line.get(k).and_then(Value::as_str));
    found(id, wire, api, body, body)
}

fn found(
    custom_id: Option<&str>,
    wire: &dyn Wire,
    api: &'static str,
    usage_in: &Value,
    model_in: &Value,
) -> Option<ResultUsage> {
    Some(ResultUsage {
        custom_id: custom_id.map(str::to_string),
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
                custom_id: Some("a".into()),
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

    fn anthropic_line(custom_id: &str, input: i64) -> Value {
        json!({"custom_id":custom_id,"result":{"type":"succeeded","message":{
            "model":"claude-sonnet-5-5","usage":{"input_tokens":input,"output_tokens":1}}}})
    }

    fn call(store: &Store) -> i32 {
        store
            .upsert_session("s", None, None, None, Some("proxy"))
            .unwrap();
        store
            .insert_call(
                "s",
                "proxy",
                "api_request:batch",
                None,
                None,
                None,
                None,
                None,
            )
            .unwrap()
    }

    fn batch(id: &str) -> Source {
        source(
            Lane::Batch,
            &Method::GET,
            &format!("/v1/messages/batches/{id}/results"),
        )
        .unwrap()
    }

    /// Input tokens of every stored row and the Batch-lane / per-model totals built on them.
    fn totals(store: &Store) -> (usize, i64, i64) {
        let lane: i64 = store
            .usage_by_lane_tier()
            .unwrap()
            .iter()
            .map(|l| l.input)
            .sum();
        let model: i64 = store
            .usage_by_model_tier()
            .unwrap()
            .iter()
            .map(|m| m.input)
            .sum();
        (store.count_usage().unwrap() as usize, lane, model)
    }

    #[test]
    fn the_batch_id_comes_from_the_results_path() {
        assert_eq!(
            batch("msgbatch_1"),
            Source::Batch {
                id: Some("msgbatch_1".into())
            }
        );
        assert_eq!(path_id("/results", "/results"), None);
    }

    #[test]
    fn replaying_a_batch_adds_nothing_and_a_distinct_batch_or_result_does() {
        let store = Store::open_in_memory().unwrap();
        let body = jsonl(&[anthropic_line("a", 100), anthropic_line("b", 7)]);
        assert_eq!(
            record(&store, "s", call(&store), None, batch("b1"), &body).unwrap(),
            2
        );
        assert_eq!(totals(&store), (2, 107, 107));

        // The same file fetched again, under its own call: nothing is billed twice.
        assert_eq!(
            record(&store, "s", call(&store), None, batch("b1"), &body).unwrap(),
            0
        );
        assert_eq!(totals(&store), (2, 107, 107));

        // The same custom_ids in another batch are other requests.
        assert_eq!(
            record(&store, "s", call(&store), None, batch("b2"), &body).unwrap(),
            2
        );
        assert_eq!(totals(&store), (4, 214, 214));
    }

    #[test]
    fn a_longer_download_tops_up_only_the_results_it_adds() {
        let store = Store::open_in_memory().unwrap();
        let first = jsonl(&[anthropic_line("a", 100)]);
        let full = jsonl(&[anthropic_line("a", 100), anthropic_line("b", 7)]);
        record(&store, "s", call(&store), None, batch("b1"), &first).unwrap();
        assert_eq!(
            record(&store, "s", call(&store), None, batch("b1"), &full).unwrap(),
            1
        );
        assert_eq!(totals(&store), (2, 107, 107));
    }

    fn openai_line(custom_id: &str, input: i64) -> Value {
        json!({"id":format!("batch_req_{custom_id}"),"custom_id":custom_id,"error":null,
            "response":{"status_code":200,"body":{"object":"chat.completion","model":"gpt-5",
            "usage":{"prompt_tokens":input,"completion_tokens":1}}}})
    }

    fn file(id: &str) -> Source {
        source(
            Lane::Files,
            &Method::GET,
            &format!("/v1/files/{id}/content"),
        )
        .unwrap()
    }

    #[test]
    fn the_output_file_id_comes_from_the_content_path() {
        assert_eq!(
            file("file-out"),
            Source::File {
                id: Some("file-out".into())
            }
        );
        assert_eq!(path_id("/content", "/content"), None);
    }

    #[test]
    fn replaying_an_output_file_adds_nothing_and_a_distinct_file_or_result_does() {
        let store = Store::open_in_memory().unwrap();
        let body = jsonl(&[openai_line("a", 100), openai_line("b", 7)]);
        let rec = |source| record(&store, "s", call(&store), None, source, &body).unwrap();
        assert_eq!(rec(file("f1")), 2);
        assert_eq!(totals(&store), (2, 107, 107));
        assert_eq!(rec(file("f1")), 0, "the same file is not billed twice");
        assert_eq!(totals(&store), (2, 107, 107));
        assert_eq!(
            rec(file("f2")),
            2,
            "the same custom_ids in another file count"
        );
        assert_eq!(totals(&store), (4, 214, 214));
    }

    #[test]
    fn a_longer_output_file_download_tops_up_only_the_results_it_adds() {
        let store = Store::open_in_memory().unwrap();
        let full = jsonl(&[openai_line("a", 100), openai_line("b", 7)]);
        // A download cut inside the second line: the first line and the torn tail.
        let cut = &full[..full.len() - 20];
        assert_eq!(
            record(&store, "s", call(&store), None, file("f1"), cut).unwrap(),
            1
        );
        assert_eq!(
            record(&store, "s", call(&store), None, file("f1"), &full).unwrap(),
            1
        );
        assert_eq!(totals(&store), (2, 107, 107));
    }

    #[test]
    fn an_openai_line_without_custom_id_is_keyed_by_its_line_id() {
        let store = Store::open_in_memory().unwrap();
        let mut line = openai_line("a", 5);
        line.as_object_mut().unwrap().remove("custom_id");
        let body = jsonl(&[line]);
        record(&store, "s", call(&store), None, file("f1"), &body).unwrap();
        record(&store, "s", call(&store), None, file("f1"), &body).unwrap();
        assert_eq!(store.count_usage().unwrap(), 1);
    }

    #[test]
    fn results_without_an_identity_are_still_appended() {
        let store = Store::open_in_memory().unwrap();
        let body = jsonl(&[anthropic_line("a", 100)]);
        let no_id = Source::Batch { id: None };
        record(&store, "s", call(&store), None, no_id.clone(), &body).unwrap();
        record(&store, "s", call(&store), None, no_id, &body).unwrap();
        // No file id in the path, or neither `custom_id` nor `id` on the line: nothing to key.
        let openai = jsonl(&[openai_line("a", 5)]);
        let no_file = Source::File { id: None };
        record(&store, "s", call(&store), None, no_file.clone(), &openai).unwrap();
        record(&store, "s", call(&store), None, no_file, &openai).unwrap();
        let mut anonymous = openai_line("a", 5);
        let obj = anonymous.as_object_mut().unwrap();
        obj.remove("custom_id");
        obj.remove("id");
        let anonymous = jsonl(&[anonymous]);
        record(&store, "s", call(&store), None, file("f1"), &anonymous).unwrap();
        record(&store, "s", call(&store), None, file("f1"), &anonymous).unwrap();
        assert_eq!(store.count_usage().unwrap(), 6);
    }

    /// Rows written before keys existed stay valid next to keyed ones: both are summed, and
    /// a keyed download never collapses into a keyless row.
    #[test]
    fn legacy_keyless_rows_still_aggregate_beside_keyed_ones() {
        let store = Store::open_in_memory().unwrap();
        let call_id = call(&store);
        let legacy = NewUsage {
            key: None,
            model: Some("gpt-5"),
            api: API_OPENAI_CHAT,
            legs: [100, 0, 0, 1],
        };
        store.insert_usage_rows("s", call_id, &[legacy]).unwrap();
        let body = jsonl(&[openai_line("a", 100)]);
        record(&store, "s", call(&store), None, file("f1"), &body).unwrap();
        record(&store, "s", call(&store), None, file("f1"), &body).unwrap();
        assert_eq!(totals(&store), (2, 200, 200));
    }
}

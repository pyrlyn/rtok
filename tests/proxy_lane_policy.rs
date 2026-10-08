// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T385.2: the per-lane policy table through a real proxy against a mock upstream, with every
//! rewrite switch on globally. Bulk, batch and the other non-agent lanes are forwarded byte
//! for byte; the agent lane is rewritten exactly as it was before lanes; a lane switch opens
//! one rewrite at a time.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use httpmock::HttpMockRequest;
use httpmock::prelude::*;
use rtok::config::Config;
use serde_json::{Value, json};

mod common;
use common::proxy::proxy_server;

const SESSION: &str = "sess-lane-policy";
/// Well over `proxy.tools_rewrite.max_description_tokens` (60), so the rewrite has work to do.
const LONG_DESCRIPTION: &str = "Runs a shell command and returns its output. It accepts a working directory, a timeout and an environment, streams long output back in chunks, and reports the exit status separately. Prefer it over ad hoc scripts, and keep each command short and idempotent whenever possible.";

/// Six user turns of a 400-line tool result (past `archive.min_tokens`), a long tool
/// description for `tools_rewrite`, and a pretty-printed body so any re-serialisation shows.
fn request() -> Vec<u8> {
    let mut messages = Vec::new();
    for t in 1..=6 {
        let text = (1..=400)
            .map(|i| format!("t{t} line {i}: some shell output with words"))
            .collect::<Vec<_>>()
            .join("\n");
        messages.push(json!({"role":"user","content":[
            {"type":"tool_result","tool_use_id":format!("tu-{t}"),"content":text}]}));
        messages.push(json!({"role":"assistant","content":[
            {"type":"tool_use","id":format!("tu-{}", t + 1),"name":"Bash","input":{}}]}));
    }
    serde_json::to_vec_pretty(&json!({
        "model": "claude-test", "max_tokens": 8, "system": "sys",
        "tools": [{"name": "Bash", "description": LONG_DESCRIPTION,
                   "input_schema": {"type": "object"}}],
        "messages": messages, "metadata": {"user_id": SESSION}
    }))
    .expect("request json")
}

/// Every global rewrite switch on: what the agent lane has always been subject to.
fn all_switches_on(cfg: &mut Config, base: &str) {
    cfg.proxy.upstream = base.to_string();
    cfg.proxy.mode = "compress".to_string();
    cfg.proxy.tools_rewrite.enabled = true;
    cfg.proxy.context_management = true;
    cfg.plugins.toon.enabled = true;
}

/// One request as upstream saw it: the body and whether it carried `anthropic-beta`.
type Seen = (Vec<u8>, bool);

/// An upstream that answers `{}` and keeps every request body and its `anthropic-beta` header.
struct Sink {
    server: MockServer,
    seen: Arc<Mutex<Vec<Seen>>>,
}

impl Sink {
    fn new() -> Self {
        let server = MockServer::start();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let sink = seen.clone();
        server.mock(move |when, then| {
            when.is_true(move |req: &HttpMockRequest| {
                let beta = req.headers().get("anthropic-beta").is_some();
                sink.lock().expect("sink").push((req.body_vec(), beta));
                true
            });
            then.status(200)
                .header("content-type", "application/json")
                .body("{}");
        });
        Self { server, seen }
    }

    fn base(&self) -> String {
        self.server.base_url()
    }

    fn last(&self) -> Seen {
        self.seen
            .lock()
            .expect("sink")
            .last()
            .cloned()
            .expect("a request reached upstream")
    }
}

async fn post(addr: &str, path: &str, lane: Option<&str>, body: Vec<u8>) -> reqwest::StatusCode {
    let mut req = reqwest::Client::new()
        .post(format!("http://{addr}{path}"))
        .header("content-type", "application/json")
        .body(body);
    if let Some(lane) = lane {
        req = req.header("x-rtok-lane", lane);
    }
    req.send()
        .await
        .expect("request through the proxy")
        .status()
}

#[tokio::test]
async fn non_agent_lanes_are_forwarded_byte_for_byte_in_compress_mode() {
    let up = Sink::new();
    let (addr, _state, task) = proxy_server("lane-policy-bytes", |cfg| {
        all_switches_on(cfg, &up.base());
    })
    .await;
    let body = request();
    let cases = [
        ("/v1/messages", Some("bulk")),
        ("/lane/internal/v1/messages", None),
        ("/v1/messages/count_tokens", None),
        ("/v1/embeddings", None),
        ("/v1/messages/batches", None),
        // A marker never opens a path that names a pass-through lane.
        ("/v1/messages/batches", Some("bulk")),
        ("/v1/files", None),
    ];
    for (path, lane) in cases {
        assert!(post(&addr, path, lane, body.clone()).await.is_success());
        let (sent, beta) = up.last();
        assert_eq!(sent, body, "{path} {lane:?} was rewritten");
        assert!(!beta, "{path} {lane:?} carries the context-management beta");
    }
    task.abort();
}

#[tokio::test]
async fn agent_lane_is_rewritten_as_before_lanes_existed() {
    let before = Sink::new();
    let (addr, _s, task) = proxy_server("lane-policy-agent-off", |cfg| {
        all_switches_on(cfg, &before.base());
        // Lanes off: every request is the agent lane, which is the pre-T385 behaviour.
        cfg.proxy.lanes.enabled = false;
    })
    .await;
    assert!(
        post(&addr, "/v1/messages", None, request())
            .await
            .is_success()
    );
    let (reference, reference_beta) = before.last();
    task.abort();

    let up = Sink::new();
    let (addr, _s, task) = proxy_server("lane-policy-agent-on", |cfg| {
        all_switches_on(cfg, &up.base());
    })
    .await;
    for lane in [None, Some("agent")] {
        assert!(
            post(&addr, "/v1/messages", lane, request())
                .await
                .is_success()
        );
        let (sent, beta) = up.last();
        assert_ne!(sent, request(), "the agent lane must still be rewritten");
        assert_eq!(sent, reference, "agent bytes drifted ({lane:?})");
        assert_eq!(beta, reference_beta);
        assert!(beta, "the agent lane must still arm context management");
    }
    let sent: Value = serde_json::from_slice(&up.last().0).expect("json");
    assert!(sent["context_management"].is_object());
    assert!(
        sent["tools"][0]["description"]
            .as_str()
            .expect("description")
            .len()
            < LONG_DESCRIPTION.len()
    );
    task.abort();
}

#[tokio::test]
async fn a_lane_switch_opens_exactly_one_rewrite() {
    let up = Sink::new();
    let (addr, _s, task) = proxy_server("lane-policy-opt-in", |cfg| {
        all_switches_on(cfg, &up.base());
        cfg.proxy.lanes.bulk.tools_rewrite = true;
        cfg.proxy.lanes.internal.context_management = true;
    })
    .await;
    let body = request();

    assert!(
        post(&addr, "/v1/messages", Some("bulk"), body.clone())
            .await
            .is_success()
    );
    let (sent, beta) = up.last();
    let sent: Value = serde_json::from_slice(&sent).expect("json");
    assert!(!beta && sent.get("context_management").is_none());
    assert!(
        sent["tools"][0]["description"]
            .as_str()
            .expect("description")
            .len()
            < LONG_DESCRIPTION.len(),
        "bulk opted in to tools_rewrite"
    );
    assert!(
        !sent["messages"][0]["content"][0]["content"]
            .as_str()
            .expect("tool result")
            .starts_with("[archived "),
        "bulk did not opt in to compress"
    );

    assert!(
        post(&addr, "/v1/messages", Some("internal"), body.clone())
            .await
            .is_success()
    );
    let (sent, beta) = up.last();
    let sent: Value = serde_json::from_slice(&sent).expect("json");
    assert!(beta && sent["context_management"].is_object());
    assert_eq!(sent["tools"][0]["description"], LONG_DESCRIPTION);
    task.abort();
}

fn tabular_request() -> Vec<u8> {
    let table = serde_json::to_string(
        &(1..=8)
            .map(|i| json!({"a": i, "b": i, "c": i}))
            .collect::<Vec<_>>(),
    )
    .expect("table");
    let mut messages = Vec::new();
    for t in 1..=6 {
        messages.push(json!({"role":"user","content":[
            {"type":"tool_result","tool_use_id":format!("tu-{t}"),"content":table}]}));
        messages.push(json!({"role":"assistant","content":[
            {"type":"tool_use","id":format!("tu-{}", t + 1),"name":"Bash","input":{}}]}));
    }
    serde_json::to_vec(&json!({
        "model": "claude-test", "max_tokens": 8, "messages": messages,
        "metadata": {"user_id": "sess-lane-toon"}
    }))
    .expect("request json")
}

#[tokio::test]
async fn toon_needs_its_own_lane_switch_inside_a_compressing_lane() {
    let up = Sink::new();
    let (addr, state, task) = proxy_server("lane-policy-toon", |cfg| {
        all_switches_on(cfg, &up.base());
        cfg.proxy.lanes.bulk.compress = true;
        cfg.proxy.lanes.internal.compress = true;
        cfg.proxy.lanes.internal.toon = true;
    })
    .await;
    assert!(
        post(&addr, "/v1/messages", Some("bulk"), tabular_request())
            .await
            .is_success()
    );
    assert_eq!(state.store.measurement_count("toon").expect("count"), 0);
    assert!(
        post(&addr, "/v1/messages", Some("internal"), tabular_request())
            .await
            .is_success()
    );
    assert!(state.store.measurement_count("toon").expect("count") > 0);
    task.abort();
}

#[tokio::test]
async fn the_semantic_cache_serves_the_agent_lane_only_by_default() {
    let server = MockServer::start();
    let mock = server.mock(|when, then| {
        when.method(POST).path("/v1/messages");
        then.status(200)
            .header("content-type", "application/json")
            .body(r#"{"type":"message","usage":{"input_tokens":1,"output_tokens":2}}"#);
    });
    let (addr, _s, task) = proxy_server("lane-policy-cache", |cfg| {
        cfg.proxy.upstream = server.base_url();
        cfg.plugins.proxy.semantic_cache.enabled = true;
    })
    .await;
    let body = br#"{"model":"claude-test","messages":[{"role":"user","content":"hi"}]}"#.to_vec();
    // Bulk neither reads nor fills the cache, so both calls reach upstream.
    for _ in 0..2 {
        assert!(
            post(&addr, "/v1/messages", Some("bulk"), body.clone())
                .await
                .is_success()
        );
    }
    mock.assert_calls(2);
    // The agent lane starts cold even though bulk sent the same prompt, then hits.
    for _ in 0..2 {
        assert!(
            post(&addr, "/v1/messages", None, body.clone())
                .await
                .is_success()
        );
    }
    mock.assert_calls(3);
    task.abort();
}

#[tokio::test]
async fn a_lane_timeout_bounds_only_that_lane() {
    let server = MockServer::start();
    server.mock(|when, then| {
        when.method(POST).path("/v1/messages");
        then.status(200)
            .delay(Duration::from_millis(2500))
            .body("{}");
    });
    let (addr, _s, task) = proxy_server("lane-policy-timeout", |cfg| {
        cfg.proxy.upstream = server.base_url();
        cfg.proxy.lanes.bulk.timeout_s = 1;
    })
    .await;
    let body = br#"{"model":"claude-test","messages":[]}"#.to_vec();
    assert_eq!(
        post(&addr, "/v1/messages", Some("bulk"), body.clone()).await,
        reqwest::StatusCode::BAD_GATEWAY
    );
    assert!(post(&addr, "/v1/messages", None, body).await.is_success());
    task.abort();
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T385.7: per-lane upstream and in-flight cap through a real proxy. The bulk lane goes to its
//! own upstream, which holds every request until the test lets it go — the slowest upstream
//! there is — so "the agent turn is not queued behind the burst" is shown by the agent turn
//! finishing while every bulk slot is still taken, not by comparing timings.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use axum::Router;
use axum::extract::State;
use axum::http::header::CONTENT_TYPE;
use httpmock::prelude::*;
use rtok::proxy::lane::Lane;
use tokio::sync::Semaphore;

mod common;
use common::proxy::proxy_server;

const BODY: &str = r#"{"model":"m","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"#;

/// An upstream that counts each request and answers it only once the test releases it.
struct Held {
    arrived: AtomicUsize,
    release: Semaphore,
}

impl Held {
    async fn start() -> (String, Arc<Held>) {
        let held = Arc::new(Held {
            arrived: AtomicUsize::new(0),
            release: Semaphore::new(0),
        });
        let app = Router::new()
            .fallback(|State(h): State<Arc<Held>>| async move {
                h.arrived.fetch_add(1, Ordering::SeqCst);
                h.release.acquire().await.expect("open").forget();
                ([(CONTENT_TYPE, "application/json")], "{}")
            })
            .with_state(held.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let base = format!("http://{}", listener.local_addr().expect("addr"));
        tokio::spawn(axum::serve(listener, app).into_future());
        (base, held)
    }

    fn arrived(&self) -> usize {
        self.arrived.load(Ordering::SeqCst)
    }
}

/// `server` answers `{}` at once on every path.
fn instant(server: &MockServer) -> httpmock::Mock<'_> {
    server.mock(|_, then| {
        then.status(200)
            .header("content-type", "application/json")
            .body("{}");
    })
}

async fn post(addr: &str, path: &str, lane: Option<&str>) -> reqwest::Response {
    let mut req = reqwest::Client::new()
        .post(format!("http://{addr}{path}"))
        .header("content-type", "application/json")
        .body(BODY);
    if let Some(lane) = lane {
        req = req.header("x-rtok-lane", lane);
    }
    req.send().await.expect("proxy answers")
}

/// Polls observable state; panics instead of hanging when it never comes.
async fn until(what: &str, ready: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(30);
    while !ready() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn agent_turn_is_not_queued_behind_a_bulk_burst() {
    let agent_up = MockServer::start();
    let answered = instant(&agent_up);
    let (bulk_up, held) = Held::start().await;
    let (addr, state, _task) = proxy_server("lane-gate-burst", |cfg| {
        cfg.proxy.upstream = agent_up.base_url();
        cfg.proxy.lanes.bulk.upstream = bulk_up.clone();
        cfg.proxy.lanes.bulk.max_in_flight = 2;
        cfg.proxy.lanes.bulk.max_queued = 1;
    })
    .await;

    // Two bulk requests take both slots upstream, a third waits in line.
    let mut burst = Vec::new();
    for _ in 0..3 {
        let addr = addr.clone();
        burst.push(tokio::spawn(async move {
            post(&addr, "/v1/messages", Some("bulk")).await.status()
        }));
        if burst.len() <= 2 {
            let n = burst.len();
            until("a bulk slot upstream", || held.arrived() == n).await;
        }
    }
    until("a queued bulk request", || state.queued(Lane::Bulk) == 1).await;

    // The line is full: the next bulk request is told to come back and never goes upstream.
    let turned_away = post(&addr, "/v1/messages", Some("bulk")).await;
    assert_eq!(turned_away.status(), reqwest::StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        turned_away
            .headers()
            .get("retry-after")
            .and_then(|v| v.to_str().ok()),
        Some("1")
    );
    let err: serde_json::Value =
        serde_json::from_slice(&turned_away.bytes().await.expect("body")).expect("json error");
    assert_eq!(err["error"]["type"], "rate_limit_error");

    // Every bulk slot is still held: an agent turn answers anyway, from its own upstream.
    let agent = tokio::time::timeout(Duration::from_secs(30), post(&addr, "/v1/messages", None))
        .await
        .expect("the agent turn is not queued behind bulk");
    assert_eq!(agent.status(), reqwest::StatusCode::OK);
    // A Batch call marked bulk keeps the provider upstream, never the bulk lane's.
    let batch = post(&addr, "/v1/messages/batches", Some("bulk")).await;
    assert_eq!(batch.status(), reqwest::StatusCode::OK);
    assert_eq!((held.arrived(), state.queued(Lane::Bulk)), (2, 1));

    held.release.add_permits(3);
    for request in burst {
        assert_eq!(request.await.expect("join"), reqwest::StatusCode::OK);
    }
    assert_eq!(
        held.arrived(),
        3,
        "the turned-away request never went upstream"
    );
    assert_eq!(state.queued(Lane::Bulk), 0);
    // Only the agent turn and the Batch call reached the default upstream.
    assert_eq!(answered.calls(), 2);
}

#[tokio::test(flavor = "multi_thread")]
async fn proxy_switched_off_caps_nothing() {
    let (bulk_up, held) = Held::start().await;
    let (addr, state, _task) = proxy_server("lane-gate-off", |cfg| {
        cfg.proxy.enabled = false;
        cfg.proxy.lanes.bulk.upstream = bulk_up.clone();
        cfg.proxy.lanes.bulk.max_in_flight = 1;
        cfg.proxy.lanes.bulk.max_queued = 0;
    })
    .await;

    let mut burst = Vec::new();
    for _ in 0..3 {
        let addr = addr.clone();
        burst.push(tokio::spawn(async move {
            post(&addr, "/v1/messages", Some("bulk")).await.status()
        }));
    }
    until("all three bulk requests upstream at once", || {
        held.arrived() == 3
    })
    .await;
    assert_eq!(state.queued(Lane::Bulk), 0);
    held.release.add_permits(3);
    for request in burst {
        assert_eq!(request.await.expect("join"), reqwest::StatusCode::OK);
    }
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.15: graph call events on `/ws`. The "other process" is a second `Runtime` on the same
//! database file: it writes through the same store API an MCP server process uses, and the
//! web server only ever sees the file.

use std::future::IntoFuture;
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use rtok::config::Config;
use rtok::plugin::Runtime;
use rtok::plugins::graph::events::Call;
use rtok::testutil::config_file_in;
use rtok::web::calls_store::FEED_ROWS;
use rtok::web::{DashState, app};
use rtok_plugin_sdk::Measurement;
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::Message;

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

struct Server {
    addr: String,
    cfg: Config,
    dir: std::path::PathBuf,
    task: tokio::task::JoinHandle<std::io::Result<()>>,
}

impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

async fn serve(label: &str) -> Server {
    let dir = std::env::temp_dir().join(format!("rtok-live-{label}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let cfg = config_file_in(&dir);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr").to_string();
    let state = Arc::new(DashState::new(cfg.clone()));
    let task = tokio::spawn(axum::serve(listener, app(state)).into_future());
    Server {
        addr,
        cfg,
        dir,
        task,
    }
}

/// The next JSON frame within `wait`, skipping pings.
async fn frame(ws: &mut Ws, wait: Duration) -> Option<Value> {
    let deadline = Instant::now() + wait;
    loop {
        let left = deadline.checked_duration_since(Instant::now())?;
        match tokio::time::timeout(left, ws.next()).await.ok()?? {
            Ok(Message::Text(t)) => return serde_json::from_str(t.as_str()).ok(),
            Ok(_) => {}
            Err(_) => return None,
        }
    }
}

/// A socket past its first snapshot and subscribed; the first `calls` frame is the ack, and it
/// carries the totals so far.
async fn subscribed(s: &Server) -> (Ws, Value) {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/ws", s.addr))
        .await
        .expect("ws connect");
    let first = frame(&mut ws, Duration::from_secs(30))
        .await
        .expect("snapshot");
    assert_eq!(first["type"], "snapshot");
    ws.send(Message::text(r#"{"calls":{"subscribe":true}}"#))
        .await
        .expect("subscribe");
    loop {
        let f = frame(&mut ws, Duration::from_secs(30)).await.expect("ack");
        if f["type"] == "calls" {
            return (ws, f);
        }
    }
}

fn graph_row(kind: &'static str) -> Measurement {
    Measurement {
        plugin: "graph",
        kind,
        before_bytes: 4000,
        after_bytes: 900,
        est_before: 1000,
        est_after: 225,
        ref_id: None,
        call_id: None,
    }
}

/// One finished call as a second process writes it.
fn one_call(rt: &Runtime, symbol: &str, answer: &str) {
    let mut call = Call::start(rt, "callers", &json!({ "name": symbol }));
    call.progress(1);
    rt.record(&graph_row("cap")).unwrap();
    call.end(&Ok(answer.to_string()));
}

#[tokio::test]
async fn a_call_from_another_process_reaches_the_socket_and_equals_its_stats_rows() {
    let s = serve("one").await;
    let (mut ws, _) = subscribed(&s).await;
    let other = Runtime::open(s.cfg.clone(), "mcp-other").unwrap();

    let t0 = Instant::now();
    one_call(&other, "main", "(lsp)\na.rs:1 main");
    let view = loop {
        let left = Duration::from_secs(1)
            .checked_sub(t0.elapsed())
            .expect("the end of the call within one second");
        let f = frame(&mut ws, left)
            .await
            .expect("a calls frame within one second");
        if f["type"] == "calls" && !f["calls"]["feed"].as_array().unwrap().is_empty() {
            break f["calls"].clone();
        }
    };
    let row = &view["feed"][0];
    assert_eq!(row["tool"], "callers");
    assert_eq!(row["backend"], "lsp");
    assert_eq!(row["session"], "mcp-other");
    assert_eq!(row["ok"], true);

    // The totals are the very rows `rtok stats --plugin graph` lists.
    let stats = rtok::model::plugin_stats(&s.cfg, "graph").unwrap();
    let rows = stats["rows"].as_array().unwrap();
    let want = |key: &str| -> i64 { rows.iter().map(|r| r[key].as_i64().unwrap()).sum() };
    let total = &view["windows"][3];
    assert_eq!(total["calls"], 1);
    assert_eq!(total["before"], want("est_before"));
    assert_eq!(total["after"], want("est_after"));
    assert_eq!(row["before"], want("est_before"));
    assert_eq!(total["before"], 1000);
}

#[tokio::test]
async fn a_burst_of_500_calls_arrives_in_bounded_frames_and_the_socket_stays_responsive() {
    let s = serve("burst").await;
    let (mut ws, _) = subscribed(&s).await;
    let other = Runtime::open(s.cfg.clone(), "mcp-burst").unwrap();

    let writer = tokio::task::spawn_blocking(move || {
        for n in 0..500 {
            one_call(&other, &format!("sym{n}"), "a.rs:1 f");
        }
    });
    // The page can still talk to the server while the burst lands.
    let t = Instant::now();
    ws.send(Message::text(r#"{"expand":"no-such-id"}"#))
        .await
        .unwrap();

    let (mut frames, mut ends, mut est_before) = (0u32, 0u64, 0i64);
    let mut answered = None;
    let deadline = Instant::now() + Duration::from_secs(30);
    while ends < 500 {
        let f = frame(&mut ws, deadline.saturating_duration_since(Instant::now()))
            .await
            .expect("all 500 calls counted before the deadline");
        match f["type"].as_str().unwrap() {
            "message" if answered.is_none() => answered = Some(t.elapsed()),
            "calls" => {
                frames += 1;
                let v = &f["calls"];
                assert!(v["feed"].as_array().unwrap().len() <= FEED_ROWS);
                // Every frame is the whole state: the last window is the total so far.
                ends = v["windows"][3]["calls"].as_u64().unwrap();
                est_before = v["windows"][3]["before"].as_i64().unwrap();
            }
            _ => {}
        }
    }
    writer.await.unwrap();
    assert_eq!(ends, 500, "the total counts every call");
    assert_eq!(est_before, 500 * 1000);
    assert!(
        frames <= 60,
        "{frames} frames for 1500 events: not coalesced"
    );
    assert!(
        answered.expect("the expand reply") < Duration::from_secs(2),
        "the socket stalled behind the burst"
    );
}

#[tokio::test]
async fn nothing_is_replayed_to_a_new_socket_or_a_restarted_server() {
    let s = serve("replay").await;
    let other = Runtime::open(s.cfg.clone(), "mcp-old").unwrap();
    one_call(&other, "before_any_socket", "x");

    // Not subscribed: no calls frame, only snapshots.
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/ws", s.addr))
        .await
        .unwrap();
    one_call(&other, "unsubscribed", "x");
    while let Some(f) = frame(&mut ws, Duration::from_millis(800)).await {
        assert_ne!(f["type"], "calls", "an unsubscribed socket got call events");
    }
    drop(ws);

    // Subscribed: the ack's totals start empty (the old calls are not counted), and only a new
    // call reaches them.
    let (mut ws, ack) = subscribed(&s).await;
    assert_eq!(ack["calls"]["windows"][3]["calls"], 0, "{ack}");
    one_call(&other, "after", "x");
    let seen = loop {
        let f = frame(&mut ws, Duration::from_secs(1))
            .await
            .expect("the new call");
        if f["type"] == "calls" && f["calls"]["windows"][3]["calls"] == 1 {
            break f["calls"]["feed"].as_array().unwrap().clone();
        }
    };
    assert!(seen.iter().all(|r| r["target"] == "after"), "{seen:?}");

    // A reconnect after a gap starts from zero again: the call written while no socket
    // listened is not counted.
    drop(ws);
    tokio::time::sleep(Duration::from_millis(700)).await;
    one_call(&other, "during_the_gap", "x");
    let (mut ws, _) = subscribed(&s).await;
    while let Some(f) = frame(&mut ws, Duration::from_millis(800)).await {
        if f["type"] == "calls" {
            assert_eq!(f["calls"]["feed"], json!([]), "replayed: {f}");
        }
    }
}

#[tokio::test]
async fn a_malformed_subscription_is_refused_with_a_message() {
    let s = serve("bad").await;
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/ws", s.addr))
        .await
        .unwrap();
    ws.send(Message::text(r#"{"calls":{"subscribe":"yes"}}"#))
        .await
        .unwrap();
    loop {
        let f = frame(&mut ws, Duration::from_secs(30))
            .await
            .expect("a reply");
        if f["type"] == "message" {
            assert!(f["text"].as_str().unwrap().contains("calls"));
            return;
        }
    }
}

#[tokio::test]
async fn a_second_page_starts_from_the_totals_so_far_and_an_idle_stream_sends_nothing() {
    let s = serve("second").await;
    let (mut first, _) = subscribed(&s).await;
    // Nothing changed since the ack, so nothing is sent.
    while let Some(f) = frame(&mut first, Duration::from_millis(800)).await {
        assert_ne!(f["type"], "calls", "an idle stream sent {f}");
    }
    let other = Runtime::open(s.cfg.clone(), "mcp-other").unwrap();
    one_call(&other, "x", "y");
    loop {
        let f = frame(&mut first, Duration::from_secs(5))
            .await
            .expect("the call reaches the first page");
        if f["type"] == "calls" && f["calls"]["windows"][3]["calls"] == 1 {
            break;
        }
    }
    let (_second, ack) = subscribed(&s).await;
    assert_eq!(ack["calls"]["windows"][3]["calls"], 1, "{ack}");
    assert_eq!(ack["calls"]["feed"][0]["target"], "x");
}

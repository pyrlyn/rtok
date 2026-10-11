//! T620 / T621: the LSP transport against deterministic synthetic peers. Each peer is a tiny
//! `sh` script (never a real language server) so the silence, delay, partial frame, flood and
//! recording cases are exact and need no network or toolchain.

use super::*;
use std::sync::mpsc;

/// A session wired to `sh -c script "$@"`. `limit` is the request budget.
fn peer(script: &str, args: &[&Path], limit: Duration) -> Session {
    let mut cmd = Command::new("sh");
    cmd.arg("-c").arg(script).arg("peer");
    cmd.args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    let mut child = cmd.spawn().unwrap();
    let mut s = Session::attach(
        child.stdin.take().unwrap(),
        child.stdout.take().unwrap(),
        Some(child),
        PathBuf::new(),
        PathBuf::new(),
    );
    s.limit = Some(limit);
    s
}

/// Emits one framed response for request id 1 after `delay` seconds.
const REPLY_1: &str = "body='{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"ok\"}'; \
    printf 'Content-Length: %s\\r\\n\\r\\n%s' \"${#body}\" \"$body\"";

/// Runs `f` on a thread and fails the test when it does not finish in `secs`: the pre-fix
/// code blocks forever on these peers, and a hung test would hide the failure.
fn within<T: Send + 'static>(secs: u64, f: impl FnOnce() -> T + Send + 'static) -> T {
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let _ = tx.send(f());
    });
    rx.recv_timeout(Duration::from_secs(secs))
        .unwrap_or_else(|_| panic!("blocked for more than {secs}s past the request budget"))
}

fn timed(s: &mut Session, method: &str, params: Value) -> (Result<Value>, Duration) {
    let t0 = Instant::now();
    let r = s.request(method, params);
    (r, t0.elapsed())
}

fn is_timeout(e: &anyhow::Error) -> bool {
    format!("{e:#}").contains("timeout") && server_broke(e)
}

#[test]
fn silent_peer_hits_the_budget() {
    let (r, took) = within(10, || {
        let mut s = peer("exec sleep 30", &[], Duration::from_millis(200));
        timed(&mut s, "textDocument/hover", json!({}))
    });
    let e = r.unwrap_err();
    assert!(is_timeout(&e), "{e:#}");
    assert!(took < Duration::from_secs(3), "{took:?}");
}

#[test]
fn delayed_reply_past_the_budget_is_a_timeout() {
    let script = format!("sleep 2; {REPLY_1}; exec sleep 30");
    let (r, took) = within(10, move || {
        let mut s = peer(&script, &[], Duration::from_millis(150));
        timed(&mut s, "textDocument/hover", json!({}))
    });
    let e = r.unwrap_err();
    assert!(is_timeout(&e), "{e:#}");
    assert!(took < Duration::from_millis(1500), "{took:?}");
}

#[test]
fn timely_reply_still_succeeds() {
    let script = format!("sleep 0.05; {REPLY_1}; exec sleep 30");
    let (r, _) = within(10, move || {
        let mut s = peer(&script, &[], Duration::from_secs(5));
        timed(&mut s, "textDocument/hover", json!({}))
    });
    assert_eq!(r.unwrap(), json!("ok"));
}

#[test]
fn partial_header_and_partial_body_hit_the_budget() {
    for head in [
        "printf 'Content-Len'",
        "printf 'Content-Length: 100\\r\\n\\r\\n{\"jsonrpc\"'",
    ] {
        let script = format!("{head}; exec sleep 30");
        let (r, took) = within(10, move || {
            let mut s = peer(&script, &[], Duration::from_millis(200));
            timed(&mut s, "textDocument/hover", json!({}))
        });
        assert!(is_timeout(&r.unwrap_err()), "{head}");
        assert!(took < Duration::from_secs(3), "{head}: {took:?}");
    }
}

#[test]
fn a_notification_flood_does_not_extend_the_budget() {
    let script = "n='{\"jsonrpc\":\"2.0\",\"method\":\"$/progress\",\"params\":{}}'; \
        while :; do printf 'Content-Length: %s\\r\\n\\r\\n%s' \"${#n}\" \"$n\" || exit 0; sleep 0.01; done";
    let (r, took) = within(10, move || {
        let mut s = peer(script, &[], Duration::from_millis(300));
        timed(&mut s, "textDocument/hover", json!({}))
    });
    assert!(is_timeout(&r.unwrap_err()));
    assert!(took < Duration::from_secs(3), "{took:?}");
}

#[test]
fn a_peer_that_stops_reading_cannot_stall_the_write() {
    let (r, took) = within(10, || {
        let mut s = peer("exec sleep 30", &[], Duration::from_millis(300));
        let big = "x".repeat(4 << 20);
        timed(&mut s, "textDocument/didSave", json!({"text": big}))
    });
    let e = r.unwrap_err();
    assert!(is_timeout(&e), "{e:#}");
    assert!(took < Duration::from_secs(3), "{took:?}");
}

#[test]
fn an_expired_session_is_not_reused_and_never_reads_the_late_reply() {
    let script = format!("sleep 0.6; {REPLY_1}; exec sleep 30");
    let (first, second, took, usable) = within(10, move || {
        let mut s = peer(&script, &[], Duration::from_millis(100));
        let (first, _) = timed(&mut s, "textDocument/hover", json!({}));
        let (second, took) = timed(&mut s, "textDocument/hover", json!({}));
        (first, second, took, s.usable())
    });
    assert!(is_timeout(&first.unwrap_err()));
    assert!(second.is_err(), "the late reply to request 1 was consumed");
    assert!(took < Duration::from_millis(200), "{took:?}");
    assert!(!usable, "an expired session must be replaced");
}

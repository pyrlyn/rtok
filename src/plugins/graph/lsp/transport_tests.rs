//! T620 / T621: the LSP transport against deterministic synthetic peers. Each peer is a tiny
//! `sh` script (never a real language server) so the silence, delay, partial frame, flood and
//! recording cases are exact and need no network or toolchain.

use super::*;
use std::process::Command;
use std::sync::atomic::AtomicU32;
use std::sync::mpsc;

static SEQ: AtomicU32 = AtomicU32::new(0);

fn scratch(tag: &str) -> PathBuf {
    let n = SEQ.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!("rtok-lsp-{tag}-{}-{n}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

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

/// Everything the peer received on stdin, as JSON messages.
fn frames(path: &Path) -> Vec<Value> {
    let raw = std::fs::read(path).unwrap_or_default();
    let mut out = Vec::new();
    let mut rest = raw.as_slice();
    while let Some(i) = rest.windows(4).position(|w| w == b"\r\n\r\n") {
        let head = String::from_utf8_lossy(&rest[..i]).to_string();
        let n: usize = head
            .split_once(':')
            .and_then(|(_, v)| v.trim().parse().ok())
            .unwrap();
        if rest.len() < i + 4 + n {
            break;
        }
        out.push(serde_json::from_slice(&rest[i + 4..i + 4 + n]).unwrap());
        rest = &rest[i + 4 + n..];
    }
    out
}

/// Sends a sentinel after the calls under test and waits for it, so a frame that must NOT
/// have been sent is ruled out by order rather than by sleeping.
fn recorded(s: &mut Session, log: &Path) -> Vec<Value> {
    s.notify("$/sentinel", json!({})).unwrap();
    for _ in 0..500 {
        let f = frames(log);
        if f.last().is_some_and(|m| m["method"] == "$/sentinel") {
            return f[..f.len() - 1].to_vec();
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    panic!("sentinel never arrived: {:?}", frames(log));
}

fn recorder(dir: &Path, change: u64) -> (Session, PathBuf) {
    let log = dir.join("peer.log");
    let mut s = peer("exec cat > \"$1\"", &[&log], Duration::from_secs(5));
    s.sync_change = change;
    (s, log)
}

fn methods(f: &[Value]) -> Vec<String> {
    f.iter()
        .map(|m| m["method"].as_str().unwrap_or("").to_string())
        .collect()
}

#[test]
fn an_edit_between_queries_reaches_the_peer_as_did_change() {
    let dir = scratch("edit");
    let file = dir.join("a.rs");
    std::fs::write(&file, "fn old_symbol() {}\n").unwrap();
    let uri = file_uri(&file);
    let (mut s, log) = recorder(&dir, 1);
    s.sync_doc(&uri).unwrap();
    std::fs::write(&file, "fn new_symbol() {}\n").unwrap();
    s.sync_doc(&uri).unwrap();
    let f = recorded(&mut s, &log);
    assert_eq!(
        methods(&f),
        ["textDocument/didOpen", "textDocument/didChange"]
    );
    assert_eq!(f[0]["params"]["textDocument"]["version"], 1);
    assert_eq!(f[1]["params"]["textDocument"]["version"], 2);
    assert_eq!(
        f[1]["params"]["contentChanges"][0]["text"],
        "fn new_symbol() {}\n"
    );
    assert!(f[1]["params"]["contentChanges"][0].get("range").is_none());
}

#[test]
fn an_unchanged_file_sends_nothing_more() {
    let dir = scratch("same");
    let file = dir.join("a.rs");
    std::fs::write(&file, "fn same() {}\n").unwrap();
    let uri = file_uri(&file);
    let (mut s, log) = recorder(&dir, 1);
    for _ in 0..3 {
        s.sync_doc(&uri).unwrap();
    }
    let f = recorded(&mut s, &log);
    assert_eq!(methods(&f), ["textDocument/didOpen"]);
}

#[test]
fn consecutive_unicode_edits_bump_the_version_and_keep_exact_text() {
    let dir = scratch("uni");
    let file = dir.join("u.rs");
    let uri = file_uri(&file);
    let (mut s, log) = recorder(&dir, 2);
    let texts = [
        "fn größe() { /* 日本語 */ }\n",
        "fn 😀() {}\n",
        "fn größe2() { /* é */ }\n",
    ];
    for t in texts {
        std::fs::write(&file, t).unwrap();
        s.sync_doc(&uri).unwrap();
    }
    let f = recorded(&mut s, &log);
    assert_eq!(
        methods(&f),
        [
            "textDocument/didOpen",
            "textDocument/didChange",
            "textDocument/didChange"
        ]
    );
    assert_eq!(f[0]["params"]["textDocument"]["text"], texts[0]);
    for (i, t) in texts.iter().enumerate().skip(1) {
        assert_eq!(f[i]["params"]["textDocument"]["version"], i as u64 + 1);
        assert_eq!(f[i]["params"]["contentChanges"][0]["text"], *t);
    }
}

#[test]
fn a_server_without_change_sync_gets_close_and_reopen() {
    let dir = scratch("none");
    let file = dir.join("a.rs");
    std::fs::write(&file, "fn old() {}\n").unwrap();
    let uri = file_uri(&file);
    let (mut s, log) = recorder(&dir, 0);
    s.sync_doc(&uri).unwrap();
    std::fs::write(&file, "fn new() {}\n").unwrap();
    s.sync_doc(&uri).unwrap();
    let f = recorded(&mut s, &log);
    assert_eq!(
        methods(&f),
        [
            "textDocument/didOpen",
            "textDocument/didClose",
            "textDocument/didOpen"
        ]
    );
    assert_eq!(f[2]["params"]["textDocument"]["version"], 2);
    assert_eq!(f[2]["params"]["textDocument"]["text"], "fn new() {}\n");
}

#[test]
fn a_deleted_file_is_closed_and_a_recreated_one_opens_fresh() {
    let dir = scratch("del");
    let file = dir.join("a.rs");
    std::fs::write(&file, "fn gone() {}\n").unwrap();
    let uri = file_uri(&file);
    let (mut s, log) = recorder(&dir, 1);
    s.sync_doc(&uri).unwrap();
    std::fs::remove_file(&file).unwrap();
    s.sync_doc(&uri).unwrap();
    s.sync_doc(&uri).unwrap();
    std::fs::write(&file, "fn back() {}\n").unwrap();
    s.sync_doc(&uri).unwrap();
    let f = recorded(&mut s, &log);
    assert_eq!(
        methods(&f),
        [
            "textDocument/didOpen",
            "textDocument/didClose",
            "textDocument/didOpen"
        ]
    );
    assert_eq!(f[2]["params"]["textDocument"]["version"], 1);
    assert_eq!(f[2]["params"]["textDocument"]["text"], "fn back() {}\n");
}

#[test]
fn a_failed_notification_leaves_the_document_unsynced() {
    let dir = scratch("fail");
    let file = dir.join("a.rs");
    std::fs::write(&file, "fn a() {}\n").unwrap();
    let uri = file_uri(&file);
    // `true` exits at once, so every write hits a closed pipe.
    let mut s = peer("exit 0", &[], Duration::from_secs(5));
    s.sync_change = 1;
    while s
        .child
        .as_mut()
        .is_some_and(|c| c.try_wait().ok().flatten().is_none())
    {
        std::thread::sleep(Duration::from_millis(5));
    }
    assert!(s.sync_doc(&uri).is_err());
    assert!(
        !s.opened.contains_key(&uri),
        "marked open before the notification landed"
    );
}

#[test]
fn a_replacement_session_opens_the_current_text() {
    let dir = scratch("repl");
    let file = dir.join("a.rs");
    std::fs::write(&file, "fn v1() {}\n").unwrap();
    let uri = file_uri(&file);
    let (mut old, _) = recorder(&dir, 1);
    old.sync_doc(&uri).unwrap();
    drop(old);
    std::fs::write(&file, "fn v2() {}\n").unwrap();
    let (mut fresh, log) = recorder(&dir, 1);
    fresh.sync_doc(&uri).unwrap();
    let f = recorded(&mut fresh, &log);
    assert_eq!(methods(&f), ["textDocument/didOpen"]);
    assert_eq!(f[0]["params"]["textDocument"]["text"], "fn v2() {}\n");
}

#[test]
fn change_kind_reads_both_capability_shapes() {
    let n = |v: Value| change_kind(&json!({"capabilities": v}));
    assert_eq!(n(json!({"textDocumentSync": 1})), 1);
    assert_eq!(
        n(json!({"textDocumentSync": {"openClose": true, "change": 2}})),
        2
    );
    assert_eq!(n(json!({"textDocumentSync": {"openClose": true}})), 0);
    assert_eq!(n(json!({})), 0);
}

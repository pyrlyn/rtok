// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T385.8 (P28 Phase 1): the must-keep fixture an LLM compressor has to pass.
//!
//! `fixtures/p28_must_keep.toml` is hand-written (nothing from a real session): seven tool
//! results with the spans (locations, identifiers, error lines, numbers, commands, ids) that
//! a summary must not drop. These tests pin the fixture itself and record what the shipped
//! lossless `archive` pointer keeps of it, which is the bar Gate P28 compares a compressor
//! against. They run no model.

use std::collections::BTreeSet;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// The shipped `plugins.archive` pointer shows this many leading and trailing lines.
const HEAD: usize = 8;
const TAIL: usize = 4;
const KINDS: [&str; 6] = ["path", "identifier", "error", "number", "command", "id"];

struct Span {
    kind: String,
    text: String,
}

struct Body {
    id: String,
    text: String,
    keep: Vec<Span>,
}

fn bodies() -> Vec<Body> {
    let raw = include_str!("fixtures/p28_must_keep.toml");
    let doc: toml_edit::DocumentMut = raw.parse().expect("fixture parses");
    doc["body"]
        .as_array_of_tables()
        .expect("[[body]] entries")
        .iter()
        .map(|t| Body {
            id: t["id"].as_str().expect("id").to_string(),
            text: t["text"].as_str().expect("text").to_string(),
            keep: t["keep"]
                .as_array()
                .expect("keep array")
                .iter()
                .map(|v| {
                    let s = v.as_inline_table().expect("{ kind, text }");
                    Span {
                        kind: s["kind"].as_str().expect("kind").to_string(),
                        text: s["text"].as_str().expect("text").to_string(),
                    }
                })
                .collect(),
        })
        .collect()
}

/// Whether the span starts on one of the lines the pointer view shows.
fn at_edge(body: &Body, span: &Span) -> bool {
    let lines: Vec<&str> = body.text.lines().collect();
    let n = lines.len();
    lines
        .iter()
        .enumerate()
        .any(|(i, l)| l.contains(&span.text) && (i < HEAD || i >= n.saturating_sub(TAIL)))
}

#[test]
fn fixture_is_well_formed_and_not_trivially_passed_by_the_pointer() {
    let all = bodies();
    assert!(all.len() >= 6, "{} bodies", all.len());
    let mut ids = BTreeSet::new();
    let mut kinds = BTreeSet::new();
    for b in &all {
        assert!(ids.insert(b.id.clone()), "duplicate body id {}", b.id);
        let n = b.text.lines().count();
        assert!(
            n > HEAD + TAIL + 8,
            "{}: {n} lines leave no middle for a pointer to drop",
            b.id
        );
        let mut seen = BTreeSet::new();
        let mut middle = 0;
        for s in &b.keep {
            assert!(
                KINDS.contains(&s.kind.as_str()),
                "{}: kind {}",
                b.id,
                s.kind
            );
            assert!(!s.text.trim().is_empty(), "{}: empty span", b.id);
            assert!(!s.text.contains('\n'), "{}: multi-line span", b.id);
            assert!(
                b.text.lines().any(|l| l.contains(&s.text)),
                "{}: span not in the body: {}",
                b.id,
                s.text
            );
            assert!(seen.insert(&s.text), "{}: duplicate span {}", b.id, s.text);
            kinds.insert(s.kind.clone());
            middle += usize::from(!at_edge(b, s));
        }
        assert!(middle >= 2, "{}: only {middle} spans a pointer drops", b.id);
    }
    assert_eq!(
        kinds.len(),
        KINDS.len(),
        "every kind is exercised: {kinds:?}"
    );
}

fn rtok(home: &Path, args: &[&str], stdin: &[u8]) -> Vec<u8> {
    let mut child = Command::new(env!("CARGO_BIN_EXE_rtok"))
        .args(args)
        .env("RTOK_HOME", home)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn rtok");
    child.stdin.take().unwrap().write_all(stdin).unwrap();
    let out = child.wait_with_output().unwrap();
    assert!(
        out.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    out.stdout
}

fn tmp(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rtok-p28-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// Every body as one old tool result in a pi session array, then one live turn.
fn pi_array(all: &[Body]) -> serde_json::Value {
    let mut messages = Vec::new();
    for b in all {
        messages
            .push(serde_json::json!({"role": "user", "content": [{"type": "text", "text": "go"}]}));
        messages.push(
            serde_json::json!({"role": "assistant", "content": [{"type": "text", "text": "…"}]}),
        );
        messages.push(serde_json::json!({
            "role": "toolResult", "toolCallId": format!("call_{}", b.id), "toolName": "bash",
            "content": [{"type": "text", "text": b.text}], "isError": false
        }));
    }
    messages
        .push(serde_json::json!({"role": "user", "content": [{"type": "text", "text": "now"}]}));
    serde_json::Value::Array(messages)
}

/// The baseline Gate P28 starts from: the lossless pointer drops the middle spans, and
/// `expand` recovers every one of them. A compressor has to keep more than the pointer in
/// no more space; this test prints the pointer's score and gates only the two facts.
#[test]
fn pointer_view_keeps_only_edge_spans_and_expand_recovers_all() {
    let all = bodies();
    let home = tmp("pointer");
    std::fs::write(
        home.join("config.toml"),
        "[plugins.archive]\nkeep_turns = 1\nmin_tokens = 1\n",
    )
    .unwrap();
    let out = rtok(
        &home,
        &["archive", "rewrite", "--stdin"],
        &serde_json::to_vec(&pi_array(&all)).unwrap(),
    );
    let view: serde_json::Value = serde_json::from_slice(&out).unwrap();
    let (mut kept, mut total) = (0, 0);
    for b in &all {
        let call = format!("call_{}", b.id);
        let m = view
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["toolCallId"] == call.as_str())
            .unwrap();
        let shown = m["content"][0]["text"].as_str().unwrap();
        assert!(shown.starts_with("[archived "), "{}: not archived", b.id);
        let id = shown
            .split("expand(")
            .nth(1)
            .and_then(|s| s.split(')').next())
            .unwrap();
        let back = String::from_utf8(rtok(&home, &["expand", id], b"")).unwrap();
        for s in &b.keep {
            total += 1;
            assert!(back.contains(&s.text), "{}: expand lost {}", b.id, s.text);
            let survives = shown.contains(&s.text);
            kept += usize::from(survives);
            if at_edge(b, s) {
                assert!(survives, "{}: pointer lost an edge span {}", b.id, s.text);
            }
        }
    }
    eprintln!("p28 baseline: the archive pointer keeps {kept} of {total} must-keep spans");
    assert!(
        kept < total,
        "the pointer already keeps every span: fixture too easy"
    );
}

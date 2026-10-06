// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.4.1: the `project` argument of the MCP tools `symbol` and `callers`, through
//! `rtok mcp` over four fixture projects: `a` links `b`, `b` links `c`, `d` stands alone.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

fn home(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rtok-t3294-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dunce::canonicalize(&dir).unwrap()
}

fn rtok(home: &Path, args: &[&str]) {
    let out = Command::new(env!("CARGO_BIN_EXE_rtok"))
        .args(args)
        .current_dir(home)
        .env("RTOK_HOME", home)
        .env("HOME", home)
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{args:?}: {}",
        String::from_utf8_lossy(&out.stderr)
    );
}

/// One `tools/call` against a fresh `rtok mcp` rooted at `cwd`; the text of the result.
fn call(home: &Path, cwd: &Path, tool: &str, args: serde_json::Value) -> String {
    let mut child = Command::new(env!("CARGO_BIN_EXE_rtok"))
        .arg("mcp")
        .env("RTOK_HOME", home)
        .env("HOME", home)
        .current_dir(cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let req = serde_json::json!({
        "jsonrpc": "2.0", "id": 1, "method": "tools/call",
        "params": {"name": tool, "arguments": args}
    });
    child
        .stdin
        .take()
        .unwrap()
        .write_all(req.to_string().as_bytes())
        .unwrap();
    let out = child.wait_with_output().unwrap();
    let line = String::from_utf8_lossy(&out.stdout);
    let v: serde_json::Value = serde_json::from_str(line.trim()).expect("one JSON-RPC line");
    v["result"]["content"][0]["text"]
        .as_str()
        .unwrap_or_else(|| panic!("no text in {line}"))
        .to_string()
}

fn world(name: &str) -> PathBuf {
    let home = home(name);
    let src = [
        ("a", "fn a_caller() { shared(); }\n"),
        ("b", "fn b_caller() { shared(); }\n"),
        ("c", "fn shared() {}\n"),
        ("d", "fn d_caller() { shared(); }\n"),
    ];
    for (project, code) in src {
        let dir = home.join(project);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("lib.rs"), code).unwrap();
        rtok(&home, &["graph", "projects", "add", dir.to_str().unwrap()]);
    }
    let id = |n: &str| ["a", "b", "c", "d"].iter().position(|p| *p == n).unwrap() + 1;
    for (from, to) in [("a", "b"), ("b", "c")] {
        let (from, to) = (id(from).to_string(), id(to).to_string());
        rtok(&home, &["graph", "projects", "link", &to, "--from", &from]);
    }
    home
}

#[test]
fn callers_without_project_cross_the_links_and_label_each_project() {
    let home = world("cross");
    let out = call(
        &home,
        &home.join("a"),
        "callers",
        serde_json::json!({"name": "shared"}),
    );
    assert_eq!(
        out,
        "[a] lib.rs  a_caller \u{d7}1 (L1)\n[b] lib.rs  b_caller \u{d7}1 (L1)\n"
    );
}

#[test]
fn project_d_does_not_cross_and_a_bad_project_is_an_error() {
    let home = world("d");
    let a = home.join("a");
    let d = home.join("d");
    let by_path = call(
        &home,
        &a,
        "callers",
        serde_json::json!({"name": "shared", "project": d}),
    );
    assert_eq!(by_path, "lib.rs  d_caller \u{d7}1 (L1)\n");
    let by_id = call(
        &home,
        &a,
        "callers",
        serde_json::json!({"name": "shared", "project": "4"}),
    );
    assert_eq!(by_id, by_path);
    let bad = call(
        &home,
        &a,
        "symbol",
        serde_json::json!({"name": "shared", "project": "9999"}),
    );
    assert!(bad.contains("no project"), "{bad}");
}

#[test]
fn symbol_finds_the_definition_in_a_linked_project() {
    let home = world("symbol");
    let out = call(
        &home,
        &home.join("a"),
        "symbol",
        serde_json::json!({"name": "shared"}),
    );
    assert_eq!(out, "[c] lib.rs:1 function\nfn shared() {}\n");
}

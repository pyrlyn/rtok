// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T182: `rtok agents junk clear` through the binary — the CLI/JSON surface over a fixture
//! home with a database and a stray `rtok.log.<N>` past `[log] files`. The archive-retention
//! half of `scan`/`run` (referenced vs. orphan archive) is a `src/agents/junk.rs` unit test:
//! backdating a call's `ts` needs `Store::set_call_ts`, `#[cfg(test)]`-only and so reachable
//! only from inside the crate, never from this external test binary.

mod common;

use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn bin() -> &'static str {
    env!("CARGO_BIN_EXE_rtok")
}

fn home(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rtok-t182-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

fn rtok(args: &[&str], home: &Path) -> String {
    let out = Command::new(bin())
        .args(args)
        .env("RTOK_HOME", home)
        .env("HOME", home)
        // Whatever hosts the test faked with `fake_hosts` (T426); none when it faked none.
        .env("PATH", home.join(".fake-hosts"))
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "rtok {args:?}: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).into_owned()
}

#[test]
fn dry_run_lists_the_stale_log_and_yes_removes_it_without_touching_the_db() {
    let home = home("clear");
    let cfg = rtok::config::Config::load_from(&home).expect("config");
    let _store = rtok::store::Store::open(&cfg.core.db_path).expect("store");
    assert!(cfg.core.db_path.is_file());

    let stale_log = cfg.log.path.with_file_name(format!(
        "{}.{}",
        cfg.log.path.file_name().unwrap().to_str().unwrap(),
        cfg.log.files + 1
    ));
    fs::create_dir_all(stale_log.parent().unwrap()).unwrap();
    fs::write(&stale_log, b"stale").unwrap();

    let preview = rtok(&["agents", "junk", "clear", "--json"], &home);
    let rows: serde_json::Value = serde_json::from_str(&preview).unwrap();
    let paths: Vec<String> = rows
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["path"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(paths, vec![stale_log.display().to_string()]);
    assert!(stale_log.is_file(), "dry run must not delete anything");

    rtok(&["agents", "junk", "clear", "--yes", "--json"], &home);
    assert!(!stale_log.exists());
    assert!(
        cfg.core.db_path.is_file(),
        "the database itself is never touched"
    );
}

/// T330.1: `list` is read-only and prints exactly what the `clear` dry run plans.
#[test]
fn list_reports_the_stale_log_with_exact_bytes_and_removes_nothing() {
    let home = home("list");
    let cfg = rtok::config::Config::load_from(&home).expect("config");
    let stale_log = cfg.log.path.with_file_name(format!(
        "{}.{}",
        cfg.log.path.file_name().unwrap().to_str().unwrap(),
        cfg.log.files + 1
    ));
    fs::create_dir_all(stale_log.parent().unwrap()).unwrap();
    fs::write(&stale_log, b"stale").unwrap();

    let json = rtok(&["agents", "junk", "list", "--json"], &home);
    let report: serde_json::Value = serde_json::from_str(&json).unwrap();
    let rtok_row = &report["agents"][0];
    assert_eq!(rtok_row["name"], "rtok");
    assert_eq!(rtok_row["kinds"][0]["kind"], "log");
    assert_eq!(rtok_row["kinds"][0]["size_bytes"], 5);
    assert_eq!(report["freed_default_bytes"], 5);

    let text = rtok(&["agents", "junk", "list", "--bytes"], &home);
    assert!(text.contains("Freed by `clear`: 5\n"), "{text}");
    assert!(stale_log.is_file(), "list must not delete anything");
}

fn write(path: &Path, bytes: usize) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, vec![b'x'; bytes]).unwrap();
}

/// T330.2: `list` over a fixture HOME. Claude Code and Codex live where `CLAUDE_CONFIG_DIR` and
/// `CODEX_HOME` point, Cursor under its usual folders; every size equals `disk_usage`, Cursor's
/// folders are "not documented", and nothing is read outside the fixture.
#[test]
fn list_shows_each_installed_host_with_its_folders_and_exact_sizes() {
    let home = home("hosts");
    // Every host installed but Gemini CLI, so `--all` still has one not installed to show.
    let hosts = common::agents::fake_hosts(&home);
    for stub in ["gemini", "gemini.cmd"] {
        let _ = fs::remove_file(hosts.join(stub));
    }
    let claude = home.join("cc");
    let codex = home.join("cx");
    write(&claude.join("settings.json"), 10);
    write(&claude.join("debug/a.log"), 100);
    write(&codex.join("config.toml"), 10);
    write(&codex.join("log/codex.log"), 10);
    write(&home.join(".cursor/hooks.json"), 10);
    let cursor_data = home.join("Library/Application Support/Cursor");
    write(&cursor_data.join("state"), 10);
    // The hosts' own config paths still default to `~/.claude` and `~/.codex`.
    fs::create_dir_all(home.join(".claude")).unwrap();
    fs::create_dir_all(home.join(".codex")).unwrap();

    let out = Command::new(bin())
        .args(["agents", "junk", "list", "--json"])
        // Only an installed host lists its folders (T426).
        .env("PATH", &hosts)
        .env("RTOK_HOME", &home)
        .env("HOME", &home)
        .env("CLAUDE_CONFIG_DIR", &claude)
        .env("CODEX_HOME", &codex)
        .env("XDG_CACHE_HOME", home.join("xc"))
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    let report: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    let folder = |agent: &str, path: &Path| -> serde_json::Value {
        let row = report["agents"]
            .as_array()
            .unwrap()
            .iter()
            .find(|a| a["name"] == agent)
            .unwrap_or_else(|| panic!("no `{agent}` row: {report}"));
        row["folders"]
            .as_array()
            .unwrap()
            .iter()
            .find(|f| f["path"] == path.display().to_string())
            .unwrap_or_else(|| panic!("`{agent}` has no {}: {row}", path.display()))
            .clone()
    };

    let c = folder("claude", &claude);
    assert_eq!(c["size_bytes"], rtok::agents::junk::disk_usage(&claude));
    assert_eq!(
        (&c["role"], &c["documented"]),
        (&"data".into(), &true.into())
    );
    assert_eq!(folder("claude", &claude.join("debug"))["role"], "logs");
    assert_eq!(folder("codex", &codex.join("log"))["role"], "logs");
    let app = folder("cursor", &cursor_data);
    assert_eq!(
        app["size_bytes"],
        rtok::agents::junk::disk_usage(&cursor_data)
    );
    assert_eq!(app["documented"], false);
    assert_eq!(folder("cursor", &home.join(".cursor"))["documented"], false);

    let text = rtok(&["agents", "junk", "list", "--bytes"], &home);
    assert!(text.contains("not documented: not cleared"), "{text}");
    assert!(
        !text.contains('\x1b'),
        "no escape codes into a pipe: {text}"
    );

    let all = rtok(&["agents", "junk", "list", "--all", "--json"], &home);
    let all: serde_json::Value = serde_json::from_str(&all).unwrap();
    assert!(
        all["agents"]
            .as_array()
            .unwrap()
            .iter()
            .any(|a| a["host"] == true && a["installed"] == false),
        "--all lists a host that is not installed"
    );
}

/// T330.4: `clear` with filters through the binary, in a fixture home with no host installed
/// (no process is ever probed) and rtok's own cache under it.
struct Clear {
    home: PathBuf,
    cache: PathBuf,
}

impl Clear {
    fn new(name: &str) -> Self {
        let home = home(name);
        let env = |k: &str| match k {
            "XDG_CACHE_HOME" => Some(home.join(".cache").into_os_string()),
            "LOCALAPPDATA" => Some(home.join("AppData/Local").into_os_string()),
            _ => None,
        };
        let roots = rtok::agents::junk_map::Roots::new(home.clone(), env);
        let cache = roots.resolve("{rtok_cache}");
        Self { home, cache }
    }

    fn run(&self, args: &[&str]) -> std::process::Output {
        let base = ["agents", "junk", "clear"];
        Command::new(bin())
            .args(base.iter().chain(args))
            .current_dir(&self.home)
            .env("RTOK_HOME", &self.home)
            .env("HOME", &self.home)
            .env("USERPROFILE", &self.home)
            .env("XDG_CACHE_HOME", self.home.join(".cache"))
            .env("XDG_DATA_HOME", self.home.join(".local/share"))
            .env("LOCALAPPDATA", self.home.join("AppData/Local"))
            .env("RTOK_HOST_SANDBOX", "1")
            .env("PATH", self.home.join(".fake-hosts"))
            .output()
            .unwrap()
    }

    fn json(&self, args: &[&str]) -> (i32, serde_json::Value) {
        let out = self.run(args);
        let json = serde_json::from_slice(&out.stdout).unwrap_or_else(|e| {
            panic!("{e}: {}", String::from_utf8_lossy(&out.stderr));
        });
        (out.status.code().unwrap(), json)
    }
}

/// Every file under `dir` with its length and mtime, for "nothing changed".
fn tree(dir: &Path) -> Vec<(PathBuf, u64, std::time::SystemTime)> {
    let mut out = Vec::new();
    for e in fs::read_dir(dir).unwrap().flatten() {
        let meta = e.metadata().unwrap();
        if meta.is_dir() {
            out.extend(tree(&e.path()));
        } else {
            out.push((e.path(), meta.len(), meta.modified().unwrap()));
        }
    }
    out.sort();
    out
}

fn age(path: &Path, secs: u64) {
    let when = std::time::SystemTime::now() - std::time::Duration::from_secs(secs);
    let f = fs::File::options().write(true).open(path).unwrap();
    f.set_modified(when).unwrap();
}

#[test]
fn clear_with_a_kind_plans_exactly_and_yes_empties_the_cache_keeping_the_store() {
    let c = Clear::new("clear-kind");
    let cfg = rtok::config::Config::load_from(&c.home).expect("config");
    drop(rtok::store::Store::open(&cfg.core.db_path).expect("store"));
    write(&c.cache.join("a/blob"), 300);
    age(&c.cache.join("a/blob"), 2 * 86_400);
    write(&c.home.join("project/Cargo.lock"), 10);

    let before = tree(&c.home);
    let (code, plan) = c.json(&["--agent", "rtok", "--kind", "cache", "--json"]);
    assert_eq!(code, 0);
    assert_eq!(tree(&c.home), before, "a dry run changes no file");
    let items = plan["items"].as_array().unwrap();
    assert_eq!(items.len(), 1, "{plan}");
    assert_eq!(items[0]["kind"], "cache");
    assert_eq!(items[0]["path"], c.cache.display().to_string());
    assert_eq!(items[0]["action"], "clear");
    assert_eq!(plan["freed_bytes"], 0);

    let (code, done) = c.json(&["--agent", "rtok", "--kind", "cache", "--yes", "--json"]);
    assert_eq!(code, 0, "{done}");
    assert_eq!(done["freed_bytes"], plan["planned_bytes"]);
    assert!(c.cache.is_dir() && !c.cache.join("a").exists());
    assert!(cfg.core.db_path.is_file() && c.home.join("project/Cargo.lock").is_file());

    let (code, again) = c.json(&["--kind", "cache", "--yes", "--json"]);
    assert_eq!((code, again["planned_bytes"].as_u64()), (0, Some(0)));
}

/// A file written after the scan's idea of "idle" is refused at removal time and the run
/// exits 1; nothing is removed.
#[test]
fn clear_yes_skips_an_item_modified_in_the_last_minute_and_exits_1() {
    let c = Clear::new("clear-fresh");
    write(&c.cache.join("blob"), 10);
    let out = c.run(&["--kind", "cache", "--yes", "--json"]);
    assert_eq!(out.status.code(), Some(1));
    assert!(String::from_utf8_lossy(&out.stderr).contains("some junk could not be removed"));
    let done: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(done["items"][0]["note"], "modified in the last minute");
    assert!(c.cache.join("blob").is_file());
}

#[test]
fn a_bad_clear_flag_is_a_usage_error() {
    let c = Clear::new("clear-usage");
    for args in [
        ["--kind", "nope"],
        ["--agent", "nope"],
        ["--older-than", "soon"],
        ["--include", "everything"],
    ] {
        let out = c.run(&args);
        assert_eq!(out.status.code(), Some(2), "{args:?}");
    }
}

/// `--trash` moves to the freedesktop trash of the fixture home. Only on Linux: the macOS and
/// Windows trash cans belong to the real user, never a test's.
#[cfg(target_os = "linux")]
#[test]
fn clear_trash_moves_the_cache_to_the_home_trash() {
    let c = Clear::new("clear-trash");
    write(&c.cache.join("blob"), 10);
    age(&c.cache.join("blob"), 2 * 86_400);
    let (code, done) = c.json(&["--kind", "cache", "--yes", "--trash", "--json"]);
    assert_eq!(code, 0, "{done}");
    assert_eq!(done["items"][0]["note"], "moved to trash");
    assert!(!c.cache.join("blob").exists());
    assert!(c.home.join(".local/share/Trash/files/blob").is_file());
}

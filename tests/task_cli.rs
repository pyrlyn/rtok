// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T441.5: `rtok task …` end to end on the disk adapter, in a throwaway checkout with its own
//! home, so the store and `.rtok.toml` are the test's own.

use std::path::{Path, PathBuf};
use std::process::Command;

struct Sandbox {
    home: PathBuf,
    repo: PathBuf,
}

impl Sandbox {
    fn new(name: &str) -> Self {
        let base =
            std::env::temp_dir().join(format!("rtok-task-cli-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        let home = base.join("home");
        let repo = base.join("repo");
        std::fs::create_dir_all(&home).unwrap();
        // A checkout as rtok reads it, lexically: no git process needed.
        std::fs::create_dir_all(repo.join(".git")).unwrap();
        std::fs::write(
            repo.join(".git/config"),
            "[remote \"origin\"]\n\turl = https://github.com/me/tasks-demo.git\n",
        )
        .unwrap();
        Self { home, repo }
    }

    /// `rtok task <args>` in the checkout: (exited 0, stdout, stderr).
    fn task(&self, args: &[&str]) -> (bool, String, String) {
        self.rtok(None, &[&["task"], args].concat())
    }

    fn task_as(&self, agent: &str, args: &[&str]) -> (bool, String, String) {
        self.rtok(Some(agent), &[&["task"], args].concat())
    }

    /// The MCP tool `name` through `rtok mcp --call`: (exited 0, the tool's text).
    fn mcp(&self, name: &str, args: &str) -> (bool, String) {
        let (ok, out, _) = self.rtok(None, &["mcp", "--call", name, "--json", args]);
        (ok, out)
    }

    fn rtok(&self, agent: Option<&str>, args: &[&str]) -> (bool, String, String) {
        let mut cmd = Command::new(env!("CARGO_BIN_EXE_rtok"));
        cmd.args(args)
            .current_dir(&self.repo)
            .env("HOME", &self.home)
            .env("USERPROFILE", &self.home)
            .env("RTOK_HOME", self.home.join(".rtok"))
            .env_remove("RTOK_CONFIG")
            .env_remove("RTOK_AGENT_ID");
        if let Some(agent) = agent {
            cmd.env("RTOK_AGENT_ID", agent);
        }
        let out = cmd.output().unwrap();
        (
            out.status.success(),
            String::from_utf8_lossy(&out.stdout).into_owned(),
            String::from_utf8_lossy(&out.stderr).into_owned(),
        )
    }

    fn ok(&self, args: &[&str]) -> String {
        let (ok, out, err) = self.task(args);
        assert!(ok, "rtok task {args:?} failed: {err}");
        out
    }

    fn file(&self, rel: &str) -> PathBuf {
        self.repo.join(rel)
    }
}

impl Drop for Sandbox {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(self.home.parent().unwrap_or(Path::new("/nonexistent")));
    }
}

#[test]
fn a_plan_from_init_to_done() {
    let sb = Sandbox::new("plan");
    let init = sb.ok(&["init", "--prefix", "td"]);
    assert!(init.contains("adapter disk, next id TD1"), "{init}");
    assert!(
        std::fs::read_to_string(sb.file(".rtok.toml"))
            .unwrap()
            .contains("prefix = \"TD\"")
    );

    assert_eq!(
        sb.ok(&["create", "Parent", "-d", "Why it exists."]),
        "TD1\n"
    );
    assert_eq!(sb.ok(&["create", "Child", "--parent", "td1"]), "TD1.1\n");
    assert_eq!(sb.ok(&["create", "Other"]), "TD2\n");
    assert!(sb.file("tasks/TD1 - parent.md").is_file());

    assert_eq!(
        sb.ok(&["list"]),
        "TD1      open         Parent\n  TD1.1  open         Child\nTD2      open         Other\n"
    );
    assert_eq!(sb.ok(&["next"]), "TD1.1  Child\n");
    assert!(
        sb.ok(&["show", "TD1"])
            .contains("subtasks: TD1.1\n\nWhy it exists.\n")
    );

    let (ok, _, err) = sb.task(&["status", "TD1", "done"]);
    assert!(!ok && err.contains("TD1.1"), "{err}");
    assert_eq!(sb.ok(&["status", "TD1.1", "done"]), "TD1.1: done\n");
    assert_eq!(
        sb.ok(&["status", "TD1", "in_progress"]),
        "TD1: in-progress\n"
    );
    assert_eq!(sb.ok(&["status", "TD1", "done"]), "TD1: done\n");
    assert_eq!(sb.ok(&["status", "TD1"]), "TD1: done\n");
    assert!(sb.file("tasks/done/TD1 - parent.md").is_file());

    assert_eq!(sb.ok(&["list"]), "TD2  open         Other\n");
    let all: serde_json::Value =
        serde_json::from_str(&sb.ok(&["list", "--all", "--json"])).unwrap();
    assert_eq!(all.as_array().unwrap().len(), 3);
    let done: serde_json::Value =
        serde_json::from_str(&sb.ok(&["list", "--status", "done", "--json"])).unwrap();
    assert_eq!(done[0]["id"], "TD1");
    assert_eq!(done[1]["parent"], "TD1");

    // The counter lives in the store, not in the files: a task removed by hand keeps its id.
    std::fs::remove_file(sb.file("tasks/TD2 - other.md")).unwrap();
    assert_eq!(sb.ok(&["create", "Third"]), "TD3\n");
    let shown: serde_json::Value =
        serde_json::from_str(&sb.ok(&["show", "TD3", "--json"])).unwrap();
    assert_eq!(shown["status"], "open");
    assert_eq!(shown["subtasks"], serde_json::json!([]));
}

#[test]
fn refusals_name_the_problem() {
    let sb = Sandbox::new("refuse");
    let (ok, _, err) = sb.task(&["show", "X9"]);
    assert!(!ok && err.contains("no task X9"), "{err}");
    let (ok, _, err) = sb.task(&["create", "Sub", "--parent", "T7"]);
    assert!(!ok && err.contains("no parent task T7"), "{err}");
    let (ok, _, err) = sb.task(&["status", "T1", "blocked"]);
    assert!(!ok && err.contains("unknown task status"), "{err}");
    let (ok, _, err) = sb.task(&["init", "--adapter", "jira"]);
    assert!(!ok && err.contains("--adapter must be one of"), "{err}");
    assert_eq!(sb.ok(&["next"]), "no ready task\n");
    assert_eq!(sb.ok(&["next", "--json"]), "null\n");
}

/// T441.6: the MCP tools answer with the JSON `--json` prints, and both see the same plan.
#[test]
fn mcp_tools_answer_like_the_cli() {
    let sb = Sandbox::new("mcp");
    sb.ok(&["init", "--prefix", "M"]);
    let json = |s: &str| serde_json::from_str::<serde_json::Value>(s).unwrap();
    let mcp = |name: &str, args: &str| {
        let (ok, out) = sb.mcp(name, args);
        assert!(ok, "{name} {args}: {out}");
        json(&out)
    };

    let made = mcp("task_create", r#"{"title":"Parent","description":"Why."}"#);
    assert_eq!(made["id"], "M1");
    let sub = mcp("task_create", r#"{"title":"Child","parent":"M1"}"#);
    assert_eq!(sub["id"], "M1.1");
    assert_eq!(json(&sb.ok(&["create", "Other", "--json"]))["id"], "M2");

    assert_eq!(mcp("task_list", "{}"), json(&sb.ok(&["list", "--json"])));
    assert_eq!(
        mcp("task_get", r#"{"id":"M1"}"#),
        json(&sb.ok(&["show", "M1", "--json"]))
    );
    assert_eq!(mcp("task_next", "{}"), json(&sb.ok(&["next", "--json"])));

    let (ok, out) = sb.mcp("task_status", r#"{"id":"M1","status":"done"}"#);
    assert!(!ok && out.contains("M1.1"), "{out}");
    assert_eq!(
        mcp("task_status", r#"{"id":"M1.1","status":"done"}"#)["status"],
        "done"
    );
    assert_eq!(
        mcp("task_status", r#"{"id":"M1"}"#),
        json(&sb.ok(&["status", "M1", "--json"]))
    );
    assert_eq!(
        mcp("task_list", r#"{"status":["done"]}"#),
        json(&sb.ok(&["list", "--status", "done", "--json"]))
    );

    let (ok, out) = sb.mcp("task_get", "{}");
    assert!(!ok && out.contains("missing `id`"), "{out}");
    let (ok, out) = sb.mcp("task_get", r#"{"id":"M9"}"#);
    assert!(!ok && out.contains("no task M9"), "{out}");
}

/// T441.12: a store that lost its counters (a new machine, a wiped home) is brought back up to
/// the files, and a second sync has nothing to say.
#[test]
fn sync_raises_a_lost_counter_and_reports_the_drift() {
    let sb = Sandbox::new("sync");
    sb.ok(&["init", "--prefix", "S"]);
    sb.ok(&["create", "One"]);
    sb.ok(&["create", "Two"]);
    sb.ok(&["create", "Leaf", "--parent", "S1"]);
    std::fs::remove_dir_all(sb.home.join(".rtok")).unwrap();

    assert_eq!(
        sb.ok(&["sync"]),
        "disk: next id S3\nraised top level: 0 -> 2\nraised subtasks of S1: 0 -> 1\nabove the counter: S1, S2, S1.1\n"
    );
    let json: serde_json::Value = serde_json::from_str(&sb.ok(&["sync", "--json"])).unwrap();
    assert_eq!(json["counter"], 2);
    assert_eq!(json["raised"], serde_json::json!([]));
    assert_eq!(sb.ok(&["sync"]), "disk: next id S3, counters in step\n");
    assert_eq!(sb.ok(&["create", "Three"]), "S3\n");
}

/// T442: claim, a blocker and a stale-free second agent, through the CLI.
#[test]
fn claim_ready_and_a_blocker() {
    let sb = Sandbox::new("claim");
    sb.ok(&["init", "--prefix", "C"]);
    let db = sb.home.join(".rtok").join("rtok.db");
    let store = rtok::store::Store::open(&db).unwrap();
    let host = store.host_id("claude").unwrap().unwrap();
    let agent = store
        .register_agent(host, "cli-claim", None, None, None)
        .unwrap();
    let other = store
        .register_agent(host, "cli-other", None, None, None)
        .unwrap();

    assert_eq!(sb.ok(&["create", "First"]), "C1\n");
    assert_eq!(sb.ok(&["create", "Second"]), "C2\n");
    let (ok, _, err) = sb.task(&["claim", "C1"]);
    assert!(!ok && err.contains("no agent to bind"), "{err}");

    let claimed = sb.task_as(&agent, &["claim"]);
    assert!(claimed.0, "{}", claimed.2);
    assert_eq!(claimed.1, "C1  First\n");
    let again = sb.task_as(&agent, &["claim", "C1"]);
    assert!(again.0, "{}", again.2);
    assert_eq!(again.1, "C1  First  (already yours)\n");

    let (ok, out, err) = sb.task_as(&agent, &["dep", "C2", "C1"]);
    assert!(ok, "{err}");
    assert_eq!(out, "C2: blocked by C1\n");
    let ready = sb.ok(&["ready"]);
    assert!(!ready.contains("C2"), "{ready}");
    let (ok, _, err) = sb.task_as(&other, &["claim", "C1"]);
    assert!(!ok && err.contains("claimed by"), "{err}");
    let (ok, out, err) = sb.task_as(&agent, &["release", "C1"]);
    assert!(ok, "{err}");
    assert_eq!(out, "C1: open\n");
    assert!(sb.ok(&["ready"]).contains("C1"));
}

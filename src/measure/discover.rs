// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok discover` (T688): the shell commands agents ran that no `cmd` rule or formatter
//! covers, ranked by the output tokens the agent read. Input is the `PostToolUse` bodies the
//! hook already stores; nothing is re-run and nothing is claimed saved: a `Measurement` row
//! is the only saving rtok reports, and an unfiltered command has none.

use super::stats::{bash_family, est_tokens};
use crate::plugins::cmd::{formatters::filter_kind, rules::Settings};
use crate::render::{Col, table};
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeMap;

/// One command family with no rule and no formatter.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Gap {
    pub family: String,
    pub calls: u64,
    /// Output bytes the agent read across all calls (after any default cut, if `rtok run` ran).
    pub bytes: u64,
    pub est_tokens: u64,
    /// The command behind the largest single output, first line, capped.
    pub example: String,
}

#[derive(Debug, Default, Serialize, PartialEq, Eq)]
pub struct Discovery {
    /// Bash calls read.
    pub scanned: u64,
    /// Bash calls whose family has a rule or a formatter.
    pub covered: u64,
    /// Hook rows with no readable body (over `core.call_io_inline_bytes`, or not JSON).
    pub unreadable: u64,
    pub gaps: Vec<Gap>,
}

/// Longest `example` kept, in chars.
const EXAMPLE_CHARS: usize = 80;

/// Bytes the agent was shown: a plain string, `stdout` + `stderr`, or the serialized value.
fn output_bytes(response: &Value) -> u64 {
    if let Some(s) = response.as_str() {
        return s.len() as u64;
    }
    let part = |k: &str| response.get(k).and_then(Value::as_str).map(str::len);
    match (part("stdout"), part("stderr")) {
        (None, None) => response.to_string().len() as u64,
        (out, err) => (out.unwrap_or(0) + err.unwrap_or(0)) as u64,
    }
}

/// Rank the uncovered families in `bodies` (`PostToolUse` hook JSON). `rtok`'s own commands
/// are skipped: their output is already shaped and no rule could improve it.
pub fn rank(bodies: &[String], settings: &Settings, limit: usize) -> Discovery {
    let mut out = Discovery::default();
    // family -> (calls, bytes, largest output, its command)
    let mut by_family: BTreeMap<String, (u64, u64, u64, String)> = BTreeMap::new();
    for body in bodies {
        let Ok(v) = serde_json::from_str::<Value>(body) else {
            out.unreadable += 1;
            continue;
        };
        let tool = v.get("tool_name").and_then(Value::as_str).unwrap_or("");
        let command = v.pointer("/tool_input/command").and_then(Value::as_str);
        let Some(command) = command.filter(|_| crate::names::canonical_tool_name(tool) == "Bash")
        else {
            continue;
        };
        let command = crate::names::strip_wrap(command);
        let family = bash_family(&command);
        if family == "rtok" {
            continue;
        }
        out.scanned += 1;
        if filter_kind(settings, &family) != "default" {
            out.covered += 1;
            continue;
        }
        let bytes = v.get("tool_response").map_or(0, output_bytes);
        let row = by_family.entry(family).or_default();
        row.0 += 1;
        row.1 += bytes;
        if bytes > row.2 {
            row.2 = bytes;
            row.3 = command;
        }
    }
    out.gaps = by_family
        .into_iter()
        .map(|(family, (calls, bytes, _, command))| Gap {
            family,
            calls,
            bytes,
            est_tokens: est_tokens(bytes),
            example: command
                .lines()
                .next()
                .unwrap_or("")
                .chars()
                .take(EXAMPLE_CHARS)
                .collect(),
        })
        .collect();
    out.gaps.sort_by(|a, b| {
        (b.est_tokens, b.calls)
            .cmp(&(a.est_tokens, a.calls))
            .then_with(|| a.family.cmp(&b.family))
    });
    out.gaps.truncate(limit);
    out
}

impl Discovery {
    pub fn to_table(&self) -> String {
        let mut text = format!(
            "bash calls {}  covered {}  unreadable rows {}\n",
            self.scanned, self.covered, self.unreadable
        );
        if self.gaps.is_empty() {
            text.push_str("no uncovered command family\n");
            return text;
        }
        let cols = [
            Col::left(16),
            Col::right(7),
            Col::right(12),
            Col::right(12),
            Col::left(8),
        ];
        let mut rows = vec![vec![
            "family".to_string(),
            "calls".into(),
            "bytes".into(),
            "est_tokens".into(),
            "largest output".into(),
        ]];
        rows.extend(self.gaps.iter().map(|g| {
            vec![
                g.family.clone(),
                g.calls.to_string(),
                g.bytes.to_string(),
                g.est_tokens.to_string(),
                g.example.clone(),
            ]
        }));
        text.push_str(&table(&cols, &rows));
        text
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn post(command: &str, out: &str) -> String {
        json!({"hook_event_name": "PostToolUse", "tool_name": "Bash",
            "tool_input": {"command": command}, "tool_response": {"stdout": out}})
        .to_string()
    }

    fn settings() -> Settings {
        Settings::builtin()
    }

    #[test]
    fn ranks_uncovered_families_by_output_tokens() {
        let bodies = [
            post("terraform plan", &"x".repeat(4000)),
            post("terraform plan -out p", &"x".repeat(2000)),
            post("ping -c 3 host", &"y".repeat(8000)),
            post("du -sh .", "z"),
        ];
        let d = rank(&bodies, &settings(), 10);
        let names: Vec<_> = d.gaps.iter().map(|g| g.family.as_str()).collect();
        // terraform (6000 B in 2 calls) and ping (8000 B in 1 call): ping reads more.
        assert_eq!(names, ["ping", "terraform", "du"]);
        assert_eq!((d.gaps[1].calls, d.gaps[1].bytes), (2, 6000));
        assert_eq!(d.gaps[1].est_tokens, 1500);
        assert_eq!(d.gaps[1].example, "terraform plan");
    }

    #[test]
    fn a_rule_or_a_formatter_means_covered() {
        let bodies = [
            post("git status", &"a".repeat(500)),
            post("cargo build", &"b".repeat(500)),
            post("cd /r && grep -rn x .", &"c".repeat(500)),
            post("mise exec -- cargo test", &"d".repeat(500)),
        ];
        let d = rank(&bodies, &settings(), 10);
        assert_eq!((d.scanned, d.covered), (4, 4));
        assert!(d.gaps.is_empty());
        assert!(d.to_table().contains("no uncovered command family"));
    }

    #[test]
    fn a_user_rule_covers_its_family() {
        let rules = "[ping]\nmax_lines = 5\n";
        let mut vfs = crate::testutil::Vfs::new();
        vfs.write("rules.toml", rules);
        let with = Settings::from_vfs(&vfs, "rules.toml", None, 80);
        let bodies = [post("ping -c 3 host", &"y".repeat(800))];
        assert_eq!(rank(&bodies, &settings(), 10).gaps.len(), 1);
        assert!(rank(&bodies, &with, 10).gaps.is_empty());
    }

    #[test]
    fn rtok_run_wrapped_and_foreign_rows_are_read_correctly() {
        let bodies = [
            post("rtok run -- 'ping -c 1 h'", &"y".repeat(400)),
            post("rtok expand abc123", &"e".repeat(400)),
            // Not Bash, no command, not JSON, no kept body.
            json!({"tool_name": "Read", "tool_input": {"file_path": "/x"}}).to_string(),
            json!({"tool_name": "Bash", "tool_input": {}}).to_string(),
            "not json".to_string(),
            String::new(),
        ];
        let d = rank(&bodies, &settings(), 10);
        assert_eq!((d.scanned, d.unreadable), (1, 2));
        assert_eq!(d.gaps[0].family, "ping");
    }

    #[test]
    fn output_is_read_from_string_stdout_stderr_or_whole_value() {
        assert_eq!(output_bytes(&json!("abc")), 3);
        assert_eq!(output_bytes(&json!({"stdout": "ab", "stderr": "c"})), 3);
        assert_eq!(output_bytes(&json!({"stderr": "cc"})), 2);
        assert_eq!(
            output_bytes(&json!({"n": 1})),
            json!({"n": 1}).to_string().len() as u64
        );
    }

    #[test]
    fn limit_keeps_the_top_rows_and_ties_break_by_name() {
        let bodies = [
            post("b-tool x", "1234"),
            post("a-tool x", "1234"),
            post("c-tool", "1"),
        ];
        let d = rank(&bodies, &settings(), 2);
        let names: Vec<_> = d.gaps.iter().map(|g| g.family.as_str()).collect();
        assert_eq!(names, ["a-tool", "b-tool"]);
    }

    /// The whole path on a fixture store: hook rows of two sessions, one past the window, one
    /// `PreToolUse`, one body over the inline cap, and a split session block (T433).
    #[test]
    fn fixture_store_feeds_the_ranking() {
        let store = crate::store::Store::open_in_memory().unwrap();
        for s in ["s1", "s2"] {
            store
                .upsert_session(s, None, None, None, Some("hook"))
                .unwrap();
        }
        let hook = |session: &str, event: &str, stdin: &str, cap: usize| {
            let id = store
                .insert_call(session, "hook", "hook", None, None, None, None, Some(event))
                .unwrap();
            store
                .insert_hook_call_io(id, stdin.as_bytes(), None, cap)
                .unwrap();
            id
        };
        let with_session = |s: &str, cmd: &str, out: &str| {
            let mut v: Value = serde_json::from_str(&post(cmd, out)).unwrap();
            v["session_id"] = json!(s);
            v["cwd"] = json!("/r");
            v.to_string()
        };
        hook(
            "s1",
            "PostToolUse",
            &with_session("s1", "ping -c 1 h", &"p".repeat(900)),
            65536,
        );
        hook(
            "s2",
            "PostToolUse",
            &with_session("s2", "ping -c 1 h", &"p".repeat(900)),
            65536,
        );
        hook(
            "s2",
            "PostToolUse",
            &with_session("s2", "git log", &"g".repeat(900)),
            65536,
        );
        hook(
            "s2",
            "PreToolUse",
            &with_session("s2", "du -sh .", "-"),
            65536,
        );
        let old = hook(
            "s2",
            "PostToolUse",
            &with_session("s2", "ps aux", &"o".repeat(900)),
            65536,
        );
        store.set_call_ts(old, 10).unwrap();
        let huge = with_session("s2", "ls", &"h".repeat(300));
        hook("s2", "PostToolUse", &huge, 100);

        let bodies = store.hook_inputs_since("PostToolUse", 1000, 100).unwrap();
        assert_eq!(bodies.len(), 4, "old row and PreToolUse row are not read");
        let d = rank(&bodies, &settings(), 10);
        assert_eq!((d.scanned, d.covered, d.unreadable), (3, 1, 1));
        assert_eq!(d.gaps.len(), 1);
        assert_eq!((d.gaps[0].family.as_str(), d.gaps[0].calls), ("ping", 2));
        assert!(d.to_table().contains("ping"), "{}", d.to_table());
    }
}

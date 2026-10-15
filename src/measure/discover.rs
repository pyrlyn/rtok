// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok discover` (plan T661): the Bash commands an agent ran that no filter names, ranked
//! by the output tokens that reached the model, so the next rule family is picked from data.
//!
//! The source is rtok's own store: every `PostToolUse` hook body keeps the command and the
//! tool response. A body over `core.call_io_inline_bytes` lives in the archive instead and is
//! not read, so the biggest outputs can be missing; the numbers are a floor. No saving rate is
//! applied — a family with no `Measurement` row has no saving claim (`AGENTS.md`).

use crate::plugins::cmd::{bounded, formatters, rules};
use crate::render::{self, Col};
use crate::store::Store;
use anyhow::Result;
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeMap;

/// Newest hook rows read per run; bounds memory on a store that holds months of calls.
pub const MAX_ROWS: i64 = 50_000;

/// One command family with no filter of its own.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Gap {
    pub family: String,
    pub calls: u64,
    pub bytes: u64,
    /// `bytes` at the 4 chars/token heuristic `rtok stats` uses.
    pub tokens: u64,
    /// The newest command of the family, first line only.
    pub example: String,
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize)]
pub struct Report {
    /// Bash calls read.
    pub scanned: u64,
    /// Families without a formatter or a named rule, biggest first.
    pub gaps: Vec<Gap>,
}

/// Read the store's `PostToolUse` Bash bodies at or after `since` (unix seconds).
pub fn from_store(store: &Store, settings: &rules::Settings, since: i64) -> Result<Report> {
    let bodies = store.hook_inputs_for_tool_since("PostToolUse", "Bash", since, MAX_ROWS)?;
    Ok(collect(&bodies, settings))
}

/// Rank the unfiltered families in `bodies` (newest first, as the store returns them).
pub fn collect(bodies: &[String], settings: &rules::Settings) -> Report {
    struct Acc {
        calls: u64,
        bytes: u64,
        example: String,
    }
    let mut by: BTreeMap<String, Acc> = BTreeMap::new();
    let mut scanned = 0;
    for body in bodies {
        let Ok(v) = serde_json::from_str::<Value>(body) else {
            continue;
        };
        if v.get("tool_name").and_then(Value::as_str) != Some("Bash") {
            continue;
        }
        let Some(cmd) = v.pointer("/tool_input/command").and_then(Value::as_str) else {
            continue;
        };
        scanned += 1;
        // The hook rewrites most commands to `rtok run -- '<cmd>'`; the family is the inner one.
        let inner = unwrap_run(cmd);
        let cmd = inner.as_deref().unwrap_or(cmd);
        let Some(family) = program_of(cmd) else {
            continue;
        };
        if formatters::filter_kind(settings, &family) != "default" {
            continue;
        }
        let acc = by.entry(family).or_insert_with(|| Acc {
            calls: 0,
            bytes: 0,
            example: first_line(cmd),
        });
        acc.calls += 1;
        acc.bytes += response_bytes(v.get("tool_response"));
    }
    let mut gaps: Vec<Gap> = by
        .into_iter()
        .map(|(family, a)| Gap {
            family,
            calls: a.calls,
            bytes: a.bytes,
            tokens: crate::measure::stats::est_tokens(a.bytes),
            example: a.example,
        })
        .collect();
    gaps.sort_by(|a, b| {
        b.tokens
            .cmp(&a.tokens)
            .then(b.calls.cmp(&a.calls))
            .then_with(|| a.family.cmp(&b.family))
    });
    Report { scanned, gaps }
}

/// The table `rtok discover` prints, at most `top` families.
pub fn table(report: &Report, top: usize) -> String {
    let mut s = format!(
        "{} Bash calls read, {} command families with no filter\n",
        report.scanned,
        report.gaps.len()
    );
    if report.gaps.is_empty() {
        s.push_str("nothing to add: every Bash family read has a formatter or a named rule\n");
        return s;
    }
    let cols = [
        Col::left(8),
        Col::right(5),
        Col::right(8),
        Col::right(6),
        Col::left(0),
    ];
    let mut rows = vec![vec![
        "family".into(),
        "calls".into(),
        "tokens".into(),
        "mean".into(),
        "example".into(),
    ]];
    for g in report.gaps.iter().take(top) {
        rows.push(vec![
            g.family.clone(),
            g.calls.to_string(),
            g.tokens.to_string(),
            (g.tokens / g.calls.max(1)).to_string(),
            g.example.clone(),
        ]);
    }
    s.push_str(&render::table(&cols, &rows));
    s
}

/// The program a filter would be written for: the first stage of `cmd` that runs one, after
/// `FOO=1`, `mise exec --`, `uv run` and the like are peeled. `None` for a chain of two or more
/// distinct programs, which `rtok run` already routes to the `[script]` rule.
fn program_of(cmd: &str) -> Option<String> {
    if bounded::mixed_chain(cmd) {
        return None;
    }
    bounded::stages(cmd).iter().find_map(|stage| {
        let visible = formatters::visible_argv(stage);
        let stem = formatters::cmd_stem(visible.first()?);
        is_program(stem).then(|| stem.to_string())
    })
}

/// A name a filter could be written for: not rtok itself, not a shell keyword or builtin that
/// only glues commands together, and shaped like a binary (`-d`, `&&`, `$(x)` are lexer scraps).
fn is_program(family: &str) -> bool {
    const SYNTAX: &[&str] = &[
        "rtok", "other", "for", "while", "until", "if", "case", "do", "then", "function", "select",
        "export", "cd", "set", "unset", "source", "eval", "exec", "wait", "true", "false",
        "command", "time", "echo", "pwd", "printf", "test",
    ];
    family.starts_with(|c: char| c.is_ascii_alphanumeric())
        && family
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | '+'))
        && !SYNTAX.contains(&family)
}

/// stdout + stderr of a Bash `tool_response`, or the string itself on hosts that send one.
fn response_bytes(resp: Option<&Value>) -> u64 {
    let len = |v: Option<&Value>| v.and_then(Value::as_str).map_or(0, str::len) as u64;
    match resp {
        Some(Value::String(s)) => s.len() as u64,
        Some(v) => len(v.get("stdout")) + len(v.get("stderr")),
        None => 0,
    }
}

fn first_line(cmd: &str) -> String {
    const MAX: usize = 60;
    let line = cmd.lines().next().unwrap_or("").trim();
    match line.char_indices().nth(MAX) {
        Some((at, _)) => format!("{}…", &line[..at]),
        None => line.to_string(),
    }
}

/// The command inside `rtok run [--agent ID] -- '<cmd>'`, however many `cd x &&` precede it.
fn unwrap_run(cmd: &str) -> Option<String> {
    let rest = &cmd[cmd.find("rtok run ")? + "rtok run ".len()..];
    let (_, quoted) = rest.split_once("-- ")?;
    Some(dequote(quoted.trim_start()))
}

/// One POSIX shell word, quotes and escapes removed (the form `wrap_quote` writes).
fn dequote(word: &str) -> String {
    let mut out = String::new();
    let mut chars = word.chars();
    while let Some(c) = chars.next() {
        match c {
            '\'' => out.extend(chars.by_ref().take_while(|&q| q != '\'')),
            '"' => {
                while let Some(q) = chars.next() {
                    match q {
                        '"' => break,
                        '\\' => out.extend(chars.next()),
                        _ => out.push(q),
                    }
                }
            }
            '\\' => out.extend(chars.next()),
            c if c.is_whitespace() => break,
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn bash(cmd: &str, stdout: &str) -> String {
        json!({"tool_name": "Bash", "tool_input": {"command": cmd},
               "tool_response": {"stdout": stdout, "stderr": ""}})
        .to_string()
    }

    #[test]
    fn dequote_reads_what_wrap_quote_writes() {
        assert_eq!(dequote("'git status'"), "git status");
        assert_eq!(dequote(r#"'echo it'\''s'"#), "echo it's");
        assert_eq!(dequote(r#"'echo it'"'"'s' tail"#), "echo it's");
        assert_eq!(dequote(r#""a \"b\"""#), r#"a "b""#);
    }

    #[test]
    fn program_of_reads_past_shell_syntax_and_wrappers() {
        assert_eq!(
            program_of("cd /x; terraform plan").as_deref(),
            Some("terraform")
        );
        assert_eq!(
            program_of("export A=1\nhelm lint .").as_deref(),
            Some("helm")
        );
        assert_eq!(program_of("FOO=1 helm lint .").as_deref(), Some("helm"));
        assert_eq!(
            program_of("mise exec -- helm lint").as_deref(),
            Some("helm")
        );
        assert_eq!(program_of("ls -d x").as_deref(), Some("ls"));
        // Syntax alone names no program; a two-program chain belongs to the `[script]` rule.
        assert_eq!(program_of("export A=1"), None);
        assert_eq!(program_of("helm lint . && terraform plan"), None);
    }

    #[test]
    fn unwrap_run_skips_cd_and_flags() {
        assert_eq!(
            unwrap_run("cd x && rtok run --agent a1 -- 'terraform plan'").as_deref(),
            Some("terraform plan")
        );
        assert_eq!(unwrap_run("git status"), None);
        assert_eq!(unwrap_run("rtok run"), None);
    }

    #[test]
    fn ranks_unfiltered_families_by_tokens_and_skips_covered_ones() {
        let settings = rules::Settings::builtin();
        let big = "x".repeat(4000);
        let bodies = vec![
            bash("terraform plan", &big),
            bash("rtok run -- 'terraform apply'", &big),
            bash("cd infra && FOO=1 helm template .", &"y".repeat(8000)),
            bash("git status", &big),
            bash("cargo test", &big),
            bash("rtok search foo", &big),
            // Not Bash, and not JSON: neither counts as a scanned call.
            json!({"tool_name": "Read", "tool_input": {"command": "helm x"}}).to_string(),
            "not json".into(),
        ];
        let r = collect(&bodies, &settings);
        assert_eq!(r.scanned, 6);
        let seen: Vec<(&str, u64, u64)> = r
            .gaps
            .iter()
            .map(|g| (g.family.as_str(), g.calls, g.tokens))
            .collect();
        // Equal tokens: the family with more calls ranks first.
        assert_eq!(seen, [("terraform", 2, 2000), ("helm", 1, 2000)]);
    }

    #[test]
    fn example_is_the_newest_first_line_cut_to_a_width() {
        let settings = rules::Settings::builtin();
        let long = format!("terraform plan {}", "a".repeat(100));
        let r = collect(&[bash(&long, "x"), bash("terraform apply", "x")], &settings);
        let g = &r.gaps[0];
        assert!(g.example.starts_with("terraform plan aaa"), "{}", g.example);
        assert_eq!(g.example.chars().count(), 61);
        assert_eq!(first_line("one\ntwo"), "one");
    }

    #[test]
    fn empty_store_says_so() {
        let r = collect(&[], &rules::Settings::builtin());
        assert!(
            table(&r, 10).contains("nothing to add"),
            "{}",
            table(&r, 10)
        );
    }

    #[test]
    fn table_lists_at_most_top_rows() {
        let settings = rules::Settings::builtin();
        let bodies: Vec<String> = ["aaa", "bbb", "ccc"]
            .iter()
            .map(|c| bash(c, &"z".repeat(400)))
            .collect();
        let t = table(&collect(&bodies, &settings), 2);
        assert!(
            t.starts_with("3 Bash calls read, 3 command families"),
            "{t}"
        );
        assert_eq!(t.lines().count(), 1 + 1 + 2, "{t}");
    }

    /// The store path: only `PostToolUse` Bash bodies at or after `since` are read.
    #[test]
    fn from_store_reads_post_tool_use_bash_bodies_since_the_cutoff() {
        let store = Store::open_in_memory().unwrap();
        store
            .upsert_session("s", None, None, None, Some("hook"))
            .unwrap();
        let add = |event: &str, body: &str| {
            let id = store
                .insert_call("s", "hook", "hook", None, None, None, None, Some(event))
                .unwrap();
            store
                .insert_call_io(id, Some(body.as_bytes()), None, 1 << 20, None)
                .unwrap();
        };
        let out = "o".repeat(400);
        add("PostToolUse", &bash("terraform plan", &out));
        add("PreToolUse", &bash("kustomize build", &out));
        add("PostToolUse", &json!({"tool_name": "Read"}).to_string());
        let settings = rules::Settings::builtin();
        let r = from_store(&store, &settings, 0).unwrap();
        assert_eq!(r.scanned, 1);
        assert_eq!(r.gaps.len(), 1);
        assert_eq!(
            (r.gaps[0].family.as_str(), r.gaps[0].tokens),
            ("terraform", 100)
        );
        let later = from_store(&store, &settings, i64::MAX).unwrap();
        assert_eq!(later.scanned, 0);
    }
}

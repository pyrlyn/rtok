// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Opt-in automatic memory extraction (T666.1). `rtok memory extract <transcript.jsonl>` turns a
//! session transcript into ordinary notes through an [`Extractor`]. rtok ships no model client:
//! the one built-in extractor runs the program the user named in `[plugins.memory.extract]
//! command`, so no key or provider is ever stored here, and nothing runs while `enabled` is
//! false. No hook calls it.

use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

use anyhow::{Context, Result, bail};
use serde::Deserialize;

use crate::plugin::Runtime;

/// A note the extractor proposes. Only `title` and `body` matter: the kind is always `note`,
/// because model output must not pick `user`/`checkpoint`, which carry lifecycle rules.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub title: String,
    pub body: String,
}

/// Turns scrubbed transcript text into candidate notes (at most `max_notes` are kept).
pub trait Extractor {
    fn extract(&self, transcript: &str, max_notes: usize) -> Result<Vec<Candidate>>;
}

const MAX_TITLE: usize = 120;
const MAX_BODY: usize = 2000;

/// Runs `argv`: the transcript goes to stdin, one `{"title","body"}` JSON object per stdout line
/// comes back. A non-zero exit is an error; lines that do not parse are skipped.
pub struct CommandExtractor {
    argv: Vec<String>,
}

impl CommandExtractor {
    pub fn new(argv: &[String]) -> Result<Self> {
        if argv.first().is_none_or(|p| p.trim().is_empty()) {
            bail!(
                "no extractor: set [plugins.memory.extract] command to a program that reads the transcript on stdin"
            );
        }
        Ok(Self {
            argv: argv.to_vec(),
        })
    }
}

impl Extractor for CommandExtractor {
    fn extract(&self, transcript: &str, max_notes: usize) -> Result<Vec<Candidate>> {
        let mut child = Command::new(&self.argv[0])
            .args(&self.argv[1..])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .with_context(|| format!("starting the extractor `{}`", self.argv[0]))?;
        let mut stdin = child.stdin.take().context("extractor stdin")?;
        let text = transcript.to_string();
        // A writer thread keeps a large transcript from deadlocking against a full stdout pipe.
        let writer = std::thread::spawn(move || stdin.write_all(text.as_bytes()));
        let out = child
            .wait_with_output()
            .context("waiting for the extractor")?;
        // A program that exits without reading its stdin breaks the pipe; its exit code says why.
        let _ = writer.join();
        if !out.status.success() {
            bail!(
                "the extractor `{}` exited with {}",
                self.argv[0],
                out.status
            );
        }
        Ok(parse_candidates(
            &String::from_utf8_lossy(&out.stdout),
            max_notes,
        ))
    }
}

#[derive(Deserialize)]
struct Line {
    #[serde(default)]
    title: String,
    #[serde(default)]
    body: String,
}

/// Reads candidates from JSONL, dropping blank fields and bad lines, capping field sizes and count.
pub fn parse_candidates(out: &str, max_notes: usize) -> Vec<Candidate> {
    out.lines()
        .filter_map(|l| serde_json::from_str::<Line>(l.trim()).ok())
        .map(|l| Candidate {
            title: cap_chars(l.title.trim(), MAX_TITLE),
            body: cap_chars(l.body.trim(), MAX_BODY),
        })
        .filter(|c| !c.title.is_empty() && !c.body.is_empty())
        .take(max_notes)
        .collect()
}

fn cap_chars(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

/// The `user:`/`assistant:` text of a Claude Code transcript; tool calls and results are left out.
pub fn transcript_text(jsonl: &str) -> String {
    let mut out = String::new();
    for line in jsonl.lines() {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(line) else {
            continue;
        };
        let role = v["type"].as_str().unwrap_or_default();
        if role != "user" && role != "assistant" {
            continue;
        }
        let content = &v["message"]["content"];
        let text = match content {
            serde_json::Value::String(s) => s.clone(),
            serde_json::Value::Array(blocks) => blocks
                .iter()
                .filter(|b| b["type"] == "text")
                .filter_map(|b| b["text"].as_str())
                .collect::<Vec<_>>()
                .join("\n"),
            _ => String::new(),
        };
        if !text.trim().is_empty() {
            out.push_str(role);
            out.push_str(": ");
            out.push_str(text.trim());
            out.push('\n');
        }
    }
    out
}

/// The last `max` bytes of `s`, cut on a char boundary: the end of a session holds what stuck.
pub fn tail_bytes(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut start = s.len() - max;
    while !s.is_char_boundary(start) {
        start += 1;
    }
    &s[start..]
}

const OFF: &str =
    "memory extraction is off: set [plugins.memory.extract] enabled = true (it can cost money)";

/// `rtok memory extract` with the configured `command`; the off switch is checked first so a
/// disabled run never even looks at the command.
pub fn run_configured(rt: &Runtime, transcript: &Path) -> Result<String> {
    let cfg = &rt.config.plugins.memory.extract;
    if !cfg.enabled {
        bail!(OFF);
    }
    run(rt, &CommandExtractor::new(&cfg.command)?, transcript)
}

/// Saves what `extractor` proposes from `transcript`; refuses while disabled.
pub fn run(rt: &Runtime, extractor: &dyn Extractor, transcript: &Path) -> Result<String> {
    let cfg = &rt.config.plugins.memory.extract;
    if !cfg.enabled {
        bail!(OFF);
    }
    let raw = std::fs::read_to_string(transcript)
        .with_context(|| format!("reading {}", transcript.display()))?;
    let text = super::strip_private(&transcript_text(&raw));
    let input = tail_bytes(&text, cfg.max_input_bytes as usize);
    if input.trim().is_empty() {
        return Ok("nothing to extract: the transcript has no user or assistant text".into());
    }
    let found = extractor.extract(input, cfg.max_notes as usize)?;
    let (mut saved, mut skipped) = (0, 0);
    for c in found.iter().take(cfg.max_notes as usize) {
        match super::mem_save(rt, "note", &c.title, &c.body, None) {
            Ok(_) => saved += 1,
            Err(_) => skipped += 1,
        }
    }
    Ok(format!(
        "extracted {} candidates: saved {saved}, skipped {skipped}",
        found.len()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fake(Vec<Candidate>);

    impl Extractor for Fake {
        fn extract(&self, _: &str, max: usize) -> Result<Vec<Candidate>> {
            Ok(self.0.iter().take(max).cloned().collect())
        }
    }

    struct Spy(std::cell::RefCell<String>);

    impl Extractor for Spy {
        fn extract(&self, transcript: &str, _: usize) -> Result<Vec<Candidate>> {
            *self.0.borrow_mut() = transcript.to_string();
            Ok(Vec::new())
        }
    }

    fn cand(title: &str, body: &str) -> Candidate {
        Candidate {
            title: title.into(),
            body: body.into(),
        }
    }

    fn rt(session: &str, enabled: bool) -> Runtime {
        let mut rt = Runtime::in_memory(session).unwrap();
        rt.config.plugins.memory.extract.enabled = enabled;
        rt
    }

    fn transcript(rt: &Runtime, body: &str) -> std::path::PathBuf {
        let p = rt.config.log.path.with_file_name("t.jsonl");
        std::fs::write(&p, body).unwrap();
        p
    }

    const SESSION: &str = concat!(
        r#"{"type":"user","message":{"content":"we picked sqlite for the cache"}}"#,
        "\n",
        r#"{"type":"assistant","message":{"content":[{"type":"text","text":"noted <private>hunter2</private> done"},{"type":"tool_use","name":"Bash"}]}}"#,
        "\n",
        r#"{"type":"summary"}"#,
        "\nnot json\n",
    );

    #[test]
    fn it_refuses_while_disabled_and_saves_nothing() {
        let rt = rt("t666-off", false);
        let file = transcript(&rt, SESSION);
        let err = run(&rt, &Fake(vec![cand("a", "b")]), &file).unwrap_err();
        assert!(err.to_string().contains("off"), "{err}");
        assert!(rt.store.search_notes("b", 5).unwrap().is_empty());
    }

    #[test]
    fn the_configured_run_reports_off_before_it_looks_at_the_command() {
        let rt = rt("t666-cfg", false);
        let file = transcript(&rt, SESSION);
        let err = run_configured(&rt, &file).unwrap_err();
        assert!(err.to_string().contains("is off"), "{err}");
    }

    #[test]
    fn it_saves_candidates_as_plain_notes_and_a_repeat_updates_not_duplicates() {
        let rt = rt("t666-save", true);
        let file = transcript(&rt, SESSION);
        let fake = Fake(vec![cand("cache store", "the cache lives in sqlite")]);
        let out = run(&rt, &fake, &file).unwrap();
        assert!(out.contains("saved 1"), "{out}");
        run(&rt, &fake, &file).unwrap();
        let hits = rt.store.search_notes("sqlite", 5).unwrap();
        assert_eq!(hits.len(), 1, "{hits:?}");
    }

    #[test]
    fn the_model_sees_only_chat_text_with_private_spans_redacted() {
        let rt = rt("t666-scrub", true);
        let file = transcript(&rt, SESSION);
        let spy = Spy(Default::default());
        run(&rt, &spy, &file).unwrap();
        let seen = spy.0.borrow();
        assert!(seen.contains("user: we picked sqlite"), "{seen}");
        assert!(!seen.contains("hunter2"), "{seen}");
        assert!(!seen.contains("Bash"), "{seen}");
    }

    #[test]
    fn the_input_is_capped_to_the_end_of_the_session() {
        let mut rt = rt("t666-cap", true);
        rt.config.plugins.memory.extract.max_input_bytes = 40;
        let file = transcript(&rt, SESSION);
        let spy = Spy(Default::default());
        run(&rt, &spy, &file).unwrap();
        let seen = spy.0.borrow();
        assert!(seen.len() <= 40, "{}", seen.len());
        assert!(seen.trim_end().ends_with("done"), "{seen}");
    }

    #[test]
    fn the_note_count_is_capped_and_a_kind_is_never_taken_from_the_model() {
        let mut rt = rt("t666-max", true);
        rt.config.plugins.memory.extract.max_notes = 2;
        let file = transcript(&rt, SESSION);
        let fake = Fake(vec![
            cand("one", "alpha topic"),
            cand("two", "beta topic"),
            cand("three", "gamma topic"),
        ]);
        let out = run(&rt, &fake, &file).unwrap();
        assert!(out.contains("saved 2"), "{out}");
        assert!(rt.store.search_notes("gamma", 5).unwrap().is_empty());
    }

    #[test]
    fn parse_skips_bad_lines_blank_fields_and_caps_sizes() {
        let long = "x".repeat(5000);
        let out = format!(
            "{{\"title\":\"a\",\"body\":\"b\"}}\nnot json\n{{\"title\":\" \",\"body\":\"b\"}}\n{{\"title\":\"{long}\",\"body\":\"{long}\"}}\n"
        );
        let got = parse_candidates(&out, 5);
        assert_eq!(got.len(), 2);
        assert_eq!(got[1].title.chars().count(), MAX_TITLE);
        assert_eq!(got[1].body.chars().count(), MAX_BODY);
    }

    #[test]
    fn tail_bytes_never_splits_a_char() {
        assert_eq!(tail_bytes("aéb", 2), "b");
        assert_eq!(tail_bytes("abc", 10), "abc");
    }

    #[test]
    fn an_unset_command_is_an_error_not_a_silent_no_op() {
        assert!(CommandExtractor::new(&[]).is_err());
        assert!(CommandExtractor::new(&[" ".into()]).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn a_command_extractor_pipes_the_transcript_and_reads_json_lines() {
        let argv = [
            "sh".to_string(),
            "-c".to_string(),
            r#"cat >/dev/null; echo '{"title":"t","body":"b"}'"#.to_string(),
        ];
        let got = CommandExtractor::new(&argv)
            .unwrap()
            .extract("hello", 3)
            .unwrap();
        assert_eq!(got, vec![cand("t", "b")]);
        let failing = ["sh".to_string(), "-c".to_string(), "exit 3".to_string()];
        assert!(
            CommandExtractor::new(&failing)
                .unwrap()
                .extract("x", 3)
                .is_err()
        );
    }
}

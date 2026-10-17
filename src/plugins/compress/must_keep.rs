// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T385.9.1: the must-keep spans of an archived tool result and the guard that rejects a
//! summary which drops one. The kinds are the ones `tests/fixtures/p28_must_keep.toml` annotates
//! (`tests/p28_must_keep.rs` measures this extractor against that fixture); only the extraction
//! is code, so a span is found the same way in the prompt, the guard and the test.

use std::collections::HashSet;
use std::sync::LazyLock;

use regex::Regex;

/// The span kinds of the P28 fixture.
pub const KINDS: [&str; 6] = ["path", "identifier", "error", "number", "command", "id"];

/// Longest span kept whole; a longer line is cut on a char boundary, so the guard asks the
/// compressor for its first 200 chars only.
const SPAN_CHARS: usize = 200;

/// One verbatim substring a summary has to carry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Span {
    pub kind: &'static str,
    pub text: String,
}

/// `(kind, regex)` for the token-shaped spans; error lines and `$ ` commands are line rules.
static TOKENS: LazyLock<Vec<(&'static str, Regex)>> = LazyLock::new(|| {
    [
        // file:line[:col] locations, bare relative file paths and diff hunk headers.
        (
            "path",
            r"[\w./\\-]*[\w-]\.[A-Za-z][A-Za-z0-9]{0,5}:\d+(?::\d+)?",
        ),
        ("path", r"\b[\w.-]+(?:/[\w.-]+)+\.[A-Za-z]{1,6}\b"),
        ("path", r"^@@ .*? @@"),
        // Backticked names, `a::b` paths, CONST_CASE and declarations.
        ("identifier", r"`[^`\n]{1,80}`"),
        ("identifier", r"\b[A-Za-z_]\w*(?:::\w+)+"),
        ("identifier", r"\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b"),
        (
            "identifier",
            r"\b(?:fn|def|class|struct|enum|trait|impl|func) [A-Za-z_]\w*",
        ),
        // Compiler, exit and HTTP status codes.
        ("error", r"\bE\d{4}\b|\bos error \d+"),
        (
            "error",
            r"\b(?:status|returned|exited with status|code) \d{3}\b",
        ),
        // Quantities: units, decimals, long integers, `key=7` and `"key": 7` pairs and counted
        // outcomes.
        ("number", r"\b\d+(?:\.\d+)?\s?(?:ms|s|MB|KB|GB|%)\b"),
        ("number", r"\b\d+\.\d{2,}\b|\b\d[\d_]{4,}\b"),
        ("number", r#"\b\w+=\d+\b|"\w+": \d+"#),
        (
            "number",
            r"\b\d+ (?:passed|failed|ignored|errors?|warnings?|attempts?|retries)\b",
        ),
        // Hex digests, prefixed ids (`req_7f3a`, `bat_01HZ…`, `leg_0003`) and ip:port.
        (
            "id",
            r"\b[0-9a-f]{12,}\b|\b[a-z]{2,6}_[0-9A-Za-z]*\d[0-9A-Za-z]{3,}\b",
        ),
        ("id", r"\b\d{1,3}(?:\.\d{1,3}){3}:\d+\b"),
    ]
    .into_iter()
    .map(|(kind, re)| (kind, Regex::new(re).expect("static regex")))
    .collect()
});

static ERROR_LINE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?:error|Error|ERROR|fatal|FATAL|FAILED|panicked|thread '[^']*' panicked)\b")
        .expect("static regex")
});

fn cut(s: &str) -> &str {
    match s.char_indices().nth(SPAN_CHARS) {
        Some((at, _)) => &s[..at],
        None => s,
    }
}

/// Every must-keep span of `text`, each once, in order of appearance.
pub fn spans(text: &str) -> Vec<Span> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    let mut push = |kind: &'static str, s: &str| {
        let s = cut(s.trim());
        if !s.is_empty() && seen.insert(s.to_string()) {
            out.push(Span {
                kind,
                text: s.to_string(),
            });
        }
    };
    for line in text.lines() {
        let line = line.trim();
        if ERROR_LINE.is_match(line) {
            push("error", line);
        }
        if let Some(cmd) = line.strip_prefix("$ ") {
            push("command", cmd);
        }
        for (kind, re) in TOKENS.iter() {
            for m in re.find_iter(line) {
                let kind = if *kind == "identifier" && is_command(m.as_str()) {
                    "command"
                } else {
                    *kind
                };
                push(kind, m.as_str());
            }
        }
    }
    out
}

/// A backticked invocation with a flag in it (`acme settle --retry x`) is a command.
fn is_command(token: &str) -> bool {
    token.starts_with('`') && token.contains(" -")
}

/// The spans of `original` that `summary` does not contain verbatim. Empty = the summary
/// keeps them all.
pub fn dropped<'a>(spans: &'a [Span], summary: &str) -> Vec<&'a Span> {
    spans
        .iter()
        .filter(|s| !summary.contains(s.text.as_str()))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const LOG: &str = "Compiling a\nerror[E0308]: mismatched types\n  --> crates/x/src/lib.rs:42:7\n\
        connect failed: Connection refused (os error 61) addr=10.0.0.1:5432\n\
        ran `RetryBudget` in 248 ms; run `acme settle --retry bat_01HZX4M8Q2`\n\
        id 9c1e5ab07f2d44e8 settled=2 MAX_BACKOFF_MS\n$ cargo test --lib\n41 passed; 2 failed";

    fn of(kind: &str) -> Vec<String> {
        spans(LOG)
            .into_iter()
            .filter(|s| s.kind == kind)
            .map(|s| s.text)
            .collect()
    }

    #[test]
    fn every_kind_is_found_once_in_order() {
        assert_eq!(of("error")[0], "error[E0308]: mismatched types");
        assert!(of("error").contains(&"E0308".to_string()));
        assert!(of("path").contains(&"crates/x/src/lib.rs:42:7".to_string()));
        assert!(of("identifier").contains(&"`RetryBudget`".to_string()));
        assert!(of("identifier").contains(&"MAX_BACKOFF_MS".to_string()));
        assert!(of("number").contains(&"248 ms".to_string()));
        assert!(of("number").contains(&"settled=2".to_string()));
        assert!(of("number").contains(&"2 failed".to_string()));
        assert!(of("command").contains(&"`acme settle --retry bat_01HZX4M8Q2`".to_string()));
        assert!(of("command").contains(&"cargo test --lib".to_string()));
        assert!(of("id").contains(&"9c1e5ab07f2d44e8".to_string()));
        assert!(of("id").contains(&"10.0.0.1:5432".to_string()));
        let all = spans(LOG);
        let unique: HashSet<_> = all.iter().map(|s| &s.text).collect();
        assert_eq!(unique.len(), all.len(), "a span is listed once");
        assert!(all.iter().all(|s| KINDS.contains(&s.kind)));
    }

    #[test]
    fn a_summary_that_drops_a_span_is_named() {
        let all = spans(LOG);
        assert!(dropped(&all, LOG).is_empty());
        let lossy = LOG.replace("crates/x/src/lib.rs:42:7", "somewhere");
        let gone = dropped(&all, &lossy);
        // The location and the bare file name inside it are both spans.
        assert_eq!(gone.len(), 2);
        assert!(gone.iter().all(|s| s.text.contains("crates/x/src/lib.rs")));
    }

    #[test]
    fn a_long_line_is_cut_on_a_char_boundary() {
        let line = format!("error: {}", "ж".repeat(400));
        let s = spans(&line);
        assert_eq!(s[0].text.chars().count(), SPAN_CHARS);
    }
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T403: how many bytes repeated long paths and identifiers take in the message array a
//! request carries, and what a legend (`~12` for `src/proxy/mod.rs`) could save net.
//! The creator's proxy rows hold no request bodies, so the transcripts stand in: they are the
//! history the agent re-sends on every request.

use super::jsonl::Parsed;
use serde::{Deserialize, Serialize};
use std::borrow::Cow;
use std::cmp::Reverse;
use std::collections::HashMap;

/// Shorter paths cost less than their code plus legend line.
const MIN_PATH: usize = 16;
const MIN_IDENT: usize = 12;
/// Requests sampled per session. Every request re-sends the history, so a handful of evenly
/// spaced ones show how the share moves as the context grows without re-scanning it per turn.
const CHECKPOINTS: usize = 6;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct DictKind {
    /// Bytes of every occurrence, anywhere in the request, of a token that appears at least
    /// twice. The ceiling; not all of it can be rewritten.
    pub repeated: u64,
    /// Bytes the cache-safe legend removes from tool results, net of the definitions.
    pub saved: u64,
}

/// Summed over the sampled requests; see the module docs.
#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DictionaryRow {
    pub requests: u64,
    /// Bytes of the tool inputs and results in the sampled requests.
    pub input_bytes: u64,
    /// What the provider billed as input (uncached, cache writes, cache reads) for the same
    /// requests: the message content above leaves out the system prompt, tool schemas,
    /// prompts and thinking, so it is only a part of this.
    pub request_tokens: u64,
    pub paths: DictKind,
    pub idents: DictKind,
}

impl DictionaryRow {
    pub(crate) fn is_empty(&self) -> bool {
        self.requests == 0
    }

    fn merge(&mut self, o: &Self) {
        self.requests += o.requests;
        self.input_bytes += o.input_bytes;
        self.request_tokens += o.request_tokens;
        for (a, b) in [(&mut self.paths, o.paths), (&mut self.idents, o.idents)] {
            a.repeated += b.repeated;
            a.saved += b.saved;
        }
    }
}

/// Occurrences per token.
#[derive(Default)]
struct Tally {
    paths: HashMap<String, u64>,
    idents: HashMap<String, u64>,
}

impl Tally {
    fn add(&mut self, text: &str) {
        scan(text, |is_path, tok| {
            let map = if is_path {
                &mut self.paths
            } else {
                &mut self.idents
            };
            match map.get_mut(tok) {
                Some(n) => *n += 1,
                None => {
                    map.insert(tok.to_string(), 1);
                }
            }
        });
    }

    /// Tokens seen at least `min` times as `(is_path, len, count)`.
    fn seen(&self, min: u64) -> Vec<(bool, u64, u64)> {
        let mut all = Vec::new();
        for (is_path, map) in [(true, &self.paths), (false, &self.idents)] {
            all.extend(
                map.iter()
                    .filter(|&(_, &n)| n >= min)
                    .map(|(t, &n)| (is_path, t.len() as u64, n)),
            );
        }
        all.sort_unstable_by_key(|&(_, len, n)| Reverse(len * n));
        all
    }
}

/// One simulated request. `all` is everything the request carries; `results` only the tool
/// results, the one place a proxy may rewrite: the model's own turns are signed or quoted
/// back into edits.
#[derive(Default)]
struct Counts {
    all: Tally,
    results: Tally,
    bytes: u64,
}

impl Counts {
    fn clear(&mut self) {
        *self = Self::default();
    }

    fn add(&mut self, text: &str, rewritable: bool) {
        self.bytes += text.len() as u64;
        self.all.add(text);
        if rewritable {
            self.results.add(text);
        }
    }

    /// `repeated`: every occurrence of a token seen twice. `saved`: what a cache-safe legend
    /// removes. The history before a message must not change when a later one arrives, so a
    /// code is defined inline at the token's second use in a result (`path{~7}`, `c + 2` extra
    /// bytes) and only the third and later uses shrink to `~7`. Codes go to the biggest tokens
    /// first.
    fn saving(&self) -> DictionaryRow {
        let mut row = DictionaryRow {
            requests: 1,
            input_bytes: self.bytes,
            ..Default::default()
        };
        for (is_path, len, n) in self.all.seen(2) {
            let kind = if is_path {
                &mut row.paths
            } else {
                &mut row.idents
            };
            kind.repeated += len * n;
        }
        for (rank, (is_path, len, n)) in self.results.seen(3).into_iter().enumerate() {
            let kind = if is_path {
                &mut row.paths
            } else {
                &mut row.idents
            };
            // `~` plus the decimal rank.
            let code = 1 + (rank as u64 + 1).to_string().len() as u64;
            let gain = (n - 2) * len.saturating_sub(code);
            kind.saved += gain.saturating_sub(code + 2);
        }
        row
    }
}

fn is_run(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b'/' | b'~' | b'-')
}

/// Calls `f(true, path)` or `f(false, identifier)` for each candidate. A `/` inside a run
/// makes it a path, identifiers inside it are not counted again; `//host/x` is the tail of
/// a URL. An identifier is snake_case or camelCase, so prose words and hex hashes stay out.
fn scan(text: &str, mut f: impl FnMut(bool, &str)) {
    let b = text.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if !is_run(b[i]) {
            i += 1;
            continue;
        }
        let start = i;
        while i < b.len() && is_run(b[i]) {
            i += 1;
        }
        // Run bytes are ASCII, so both ends sit on char boundaries.
        let run = text[start..i].trim_end_matches(['.', '-']);
        if run.contains('/') {
            if run.len() >= MIN_PATH
                && !run.starts_with("//")
                && run.bytes().any(|c| c.is_ascii_alphabetic())
            {
                f(true, run);
            }
            continue;
        }
        for part in run.split(['.', '-', '~']) {
            if is_ident(part) {
                f(false, part);
            }
        }
    }
}

fn is_ident(s: &str) -> bool {
    let b = s.as_bytes();
    s.len() >= MIN_IDENT
        && (b[0].is_ascii_alphabetic() || b[0] == b'_')
        && !b.iter().all(u8::is_ascii_hexdigit)
        && (b.contains(&b'_')
            || (b.iter().any(u8::is_ascii_lowercase) && b.iter().any(u8::is_ascii_uppercase)))
}

/// Adds the session's sampled requests to `row`. A request is the tool inputs and results up
/// to its turn, counted afresh after a compaction.
pub fn fold(parsed: &Parsed, row: &mut DictionaryRow) {
    let mut items: Vec<(u32, Cow<str>, bool)> = parsed
        .tool_results
        .iter()
        .map(|r| (r.turn, Cow::Borrowed(r.content.as_str()), true))
        .chain(
            parsed
                .tool_uses
                .iter()
                .map(|u| (u.turn, Cow::Owned(u.input.to_string()), false)),
        )
        .collect();
    items.sort_by_key(|(turn, ..)| *turn);
    let sizes: Vec<(u32, u64)> = parsed
        .usages
        .iter()
        .map(|u| {
            let tokens = u.input_tokens + u.cache_creation_input_tokens + u.cache_read_input_tokens;
            (u.turn, u64::from(tokens))
        })
        .collect();
    let mut at: Vec<(u32, u64)> = (1..=CHECKPOINTS)
        .filter_map(|k| sizes.get((sizes.len() * k / CHECKPOINTS).checked_sub(1)?))
        .copied()
        .collect();
    at.dedup_by_key(|&mut (turn, _)| turn);
    let mut counts = Counts::default();
    let mut compactions = parsed.compactions.iter().peekable();
    let mut next = 0;
    for (t, tokens) in at {
        while next < items.len() && items[next].0 <= t {
            while compactions.next_if(|&&c| c <= items[next].0).is_some() {
                counts.clear();
            }
            counts.add(&items[next].1, items[next].2);
            next += 1;
        }
        while compactions.next_if(|&&c| c <= t).is_some() {
            counts.clear();
        }
        if counts.bytes > 0 {
            let mut one = counts.saving();
            one.request_tokens = tokens;
            row.merge(&one);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn found(text: &str) -> Vec<(bool, String)> {
        let mut v = Vec::new();
        scan(text, |p, t| v.push((p, t.to_string())));
        v
    }

    #[test]
    fn paths_and_identifiers_are_told_apart_from_prose_urls_and_hashes() {
        let got = found(
            "see /Users/x/apps/rtok/src/proxy/mod.rs, fn request_body_len() and \
             https://example.com/a/very/long/path plus deadbeefcafe1234 and implementation",
        );
        assert_eq!(
            got,
            [
                (true, "/Users/x/apps/rtok/src/proxy/mod.rs".to_string()),
                (false, "request_body_len".to_string()),
            ]
        );
        assert_eq!(
            found("camelCaseIdentifier"),
            [(false, "camelCaseIdentifier".to_string())]
        );
    }

    /// Four uses in results: the second defines `~1` (cost 4), the third and fourth shrink
    /// by `len - 2` each. A path used once stays out.
    #[test]
    fn saving_is_net_of_the_definition_and_ignores_singletons() {
        let path = "/Users/x/apps/rtok/src/proxy/mod.rs";
        let mut c = Counts::default();
        c.add(
            &format!("{path} {path} {path} {path} src/only/once/in/the/request.rs"),
            true,
        );
        let r = c.saving();
        let len = path.len() as u64;
        assert_eq!(r.paths.repeated, 4 * len);
        assert_eq!(r.paths.saved, 2 * (len - 2) - 4);
        assert_eq!(r.idents, DictKind::default());
    }

    /// The model's own tool inputs are quoted back into edits, so they count as repeats but
    /// are never shortened; two uses in results are one use short of paying.
    #[test]
    fn only_tool_results_are_rewritable() {
        let path = "/Users/x/apps/rtok/src/proxy/mod.rs";
        let mut c = Counts::default();
        c.add(&format!("{path} {path} {path}"), false);
        c.add(&format!("{path} {path}"), true);
        let r = c.saving();
        assert_eq!(
            (r.paths.repeated, r.paths.saved),
            (5 * path.len() as u64, 0)
        );
    }

    fn session(compact_after: Option<u32>) -> Parsed {
        let p = "/Users/x/apps/rtok/src/proxy/mod.rs";
        let mut lines = Vec::new();
        for i in 0..4 {
            lines.push(format!(
                "{{\"type\":\"assistant\",\"message\":{{\"id\":\"m{i}\",\"usage\":{{\"input_tokens\":1}},\
                 \"content\":[{{\"type\":\"tool_use\",\"id\":\"t{i}\",\"name\":\"Read\",\"input\":{{\"file_path\":\"{p}\"}}}}]}}}}"
            ));
            if compact_after == Some(i) {
                lines.push(r#"{"type":"system","subtype":"compact_boundary"}"#.into());
            }
        }
        crate::measure::jsonl::parse_jsonl(&lines.join("\n"))
    }

    /// The history is re-sent, so request `n` carries the path `n` times.
    #[test]
    fn fold_samples_each_request_of_a_short_session() {
        let mut row = DictionaryRow::default();
        fold(&session(None), &mut row);
        let len = "/Users/x/apps/rtok/src/proxy/mod.rs".len() as u64;
        assert_eq!((row.requests, row.paths.repeated), (4, (2 + 3 + 4) * len));
        assert_eq!(row.paths.saved, 0);
    }

    /// A compaction drops the old history from the request, so the count starts over.
    #[test]
    fn fold_starts_over_after_a_compaction() {
        let mut row = DictionaryRow::default();
        fold(&session(Some(1)), &mut row);
        let len = "/Users/x/apps/rtok/src/proxy/mod.rs".len() as u64;
        assert_eq!(row.paths.repeated, (2 + 2) * len);
    }
}

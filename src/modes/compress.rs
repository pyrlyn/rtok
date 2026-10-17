// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Deterministic English fluff stripper that never mutates fenced code bodies.
//!
//! # Why deterministic, not LLM / not caveman's Go proxy
//!
//! Caveman's shrink path is a separate process (and its issue #112 has corrupted inline
//! code). We need something that:
//! - runs inside the rtok binary (D6 — no wrap),
//! - is byte-stable and testable without a network,
//! - **never** touches ``` fences or `` `backtick` `` spans (lossless for code / errors),
//! - never drops negation tokens (`not` / `never` / `no` / `only` / `except`) so “do not
//!   delete” cannot become “delete”.
//!
//! Intensities mirror caveman's lite/full/ultra naming so the inject prompt and this helper
//! stay aligned; `Lite` matches the weak baseline in `tests/mode_bench.rs`, `Full` is what
//! we claim against that baseline.

/// Caveman-style compression intensity.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CaveIntensity {
    /// Drop only the weakest pleasantries (`Sure!`, `I'd be happy to help`).
    Lite,
    /// Drop filler/hedging and articles (`a`/`an`/`the`) outside fences.
    Full,
    /// Full plus strip common conjunction padding when unambiguous.
    Ultra,
}

/// Phrases stripped at every intensity (weak / "caveman-lite" baseline shares the first two).
const PLEASANTRIES: &[&str] = &[
    "I'd be happy to help you with that.",
    "I'd be happy to help you with that",
    "I'd be happy to help!",
    "I'd be happy to help.",
    "I'd be happy to help",
    "I would be happy to help.",
    "I would be happy to help",
    "Happy to help!",
    "Happy to help.",
    "Of course!",
    "Of course.",
    "Certainly!",
    "Certainly.",
    "Sure thing!",
    "Sure thing.",
    "Sure!",
    "Sure.",
];

const FILLER_WORDS: &[&str] = &[
    "just",
    "really",
    "basically",
    "actually",
    "simply",
    "literally",
];

const ARTICLES: &[&str] = &["a", "an", "the"];

const CONJUNCTION_PAD: &[&str] = &["and then", "and also", "as well as"];

/// Negation / critical words that must never be dropped as whole tokens.
const KEEP_WORDS: &[&str] = &["not", "never", "no", "only", "except"];

/// Strip English fluff from `text` at `intensity`.
///
/// Algorithm (why this shape):
/// 1. Find Markdown code fences by line (see [`find_fence`]) and copy each one **verbatim**
///    (opening line through closing fence). Unclosed fence → preserve the remainder
///    untouched (fail closed on code, never half-edit it).
/// 2. Outside fences only: drop pleasantries (all intensities), then filler + articles at
///    Full/Ultra, then conjunction padding at Ultra.
/// 3. Word drops are whole-token only; configured negation tokens are never stripped.
/// 4. Whole-word drops preserve text through the next backtick, but phrase replacements
///    are substring-based and can affect inline backtick spans.
///
/// Deterministic and allocation-light; not a semantic summarizer.
pub fn compress_prose(text: &str, intensity: CaveIntensity) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some((start, end)) = find_fence(rest) {
        out.push_str(&compress_outside(&rest[..start], intensity));
        out.push_str(&rest[start..end]);
        rest = &rest[end..];
    }
    out.push_str(&compress_outside(rest, intensity));
    out
}

/// Byte range of the first fenced code block in `text`, from the opening line start to the
/// end of the closing run (or to the end of `text` when it is never closed).
///
/// Fences follow CommonMark: an opening line holds at most three spaces of indent and a run
/// of three or more backticks or tildes (a backtick info string may not contain a backtick,
/// otherwise the run is an inline span); only a line with the same character, a run at least
/// as long and nothing else closes it. Matching raw "```" substrings instead let a shorter
/// run inside a longer fence end the block early and expose its code to word stripping.
fn find_fence(text: &str) -> Option<(usize, usize)> {
    let mut lines = text.split_inclusive('\n').scan(0, |pos, line| {
        let start = *pos;
        *pos += line.len();
        Some((start, line))
    });
    let (start, ch, len) = lines.find_map(|(start, line)| {
        let (ch, len, info) = fence_run(line)?;
        (ch != b'`' || !info.contains('`')).then_some((start, ch, len))
    })?;
    let close = lines.find_map(|(at, line)| {
        let (c, n, tail) = fence_run(line)?;
        (c == ch && n >= len && tail.trim().is_empty()).then(|| at + line.len() - tail.len())
    });
    Some((start, close.unwrap_or(text.len())))
}

/// Fence character, run length and the rest of the line, when `line` starts a fence run.
fn fence_run(line: &str) -> Option<(u8, usize, &str)> {
    let body = line.trim_start_matches(' ');
    if line.len() - body.len() > 3 {
        return None;
    }
    let ch = *body
        .as_bytes()
        .first()
        .filter(|c| matches!(c, b'`' | b'~'))?;
    let run = body.bytes().take_while(|b| *b == ch).count();
    (run >= 3).then(|| (ch, run, &body[run..]))
}

/// Compress an unfenced prose segment and normalize whitespace within each line.
fn compress_outside(prose: &str, intensity: CaveIntensity) -> String {
    let segments = inline_segments(prose);
    let last = segments.len().saturating_sub(1);
    let mut out = String::with_capacity(prose.len());

    for (index, segment) in segments.into_iter().enumerate() {
        match segment {
            InlineSegment::Protected(span) => out.push_str(span),
            InlineSegment::Prose(segment) => {
                let mut s = if index == 0 {
                    strip_leading_pleasantries(segment)
                } else {
                    segment.to_string()
                };
                match intensity {
                    CaveIntensity::Lite => {}
                    CaveIntensity::Full | CaveIntensity::Ultra => {
                        s = strip_words(&s, FILLER_WORDS);
                        s = strip_words(&s, ARTICLES);
                        if matches!(intensity, CaveIntensity::Ultra) {
                            for p in CONJUNCTION_PAD {
                                s = replace_ci(&s, p, " ");
                            }
                        }
                    }
                }
                out.push_str(&cleanup_ws_segment(&s, index > 0, index < last));
            }
        }
    }
    out
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum InlineSegment<'a> {
    Prose(&'a str),
    Protected(&'a str),
}

/// Split prose into editable text and matching backtick-delimited spans.
/// An unmatched delimiter protects the remainder rather than risking code corruption.
fn inline_segments(text: &str) -> Vec<InlineSegment<'_>> {
    let bytes = text.as_bytes();
    let mut segments = Vec::new();
    let mut prose_start = 0;
    let mut cursor = 0;

    while cursor < bytes.len() {
        if bytes[cursor] != b'`' {
            cursor += 1;
            continue;
        }

        let opener = cursor;
        while cursor < bytes.len() && bytes[cursor] == b'`' {
            cursor += 1;
        }
        let delimiter_len = cursor - opener;
        let mut closing_end = None;

        while cursor < bytes.len() {
            if bytes[cursor] != b'`' {
                cursor += 1;
                continue;
            }
            let closing = cursor;
            while cursor < bytes.len() && bytes[cursor] == b'`' {
                cursor += 1;
            }
            if cursor - closing == delimiter_len {
                closing_end = Some(cursor);
                break;
            }
        }

        if prose_start < opener {
            segments.push(InlineSegment::Prose(&text[prose_start..opener]));
        }
        if let Some(end) = closing_end {
            segments.push(InlineSegment::Protected(&text[opener..end]));
            prose_start = end;
        } else {
            segments.push(InlineSegment::Protected(&text[opener..]));
            prose_start = bytes.len();
        }
    }

    if prose_start < bytes.len() {
        segments.push(InlineSegment::Prose(&text[prose_start..]));
    }
    segments
}

fn strip_leading_pleasantries(text: &str) -> String {
    let mut rest = text;
    loop {
        let trimmed = rest.trim_start_matches(char::is_whitespace);
        let Some(phrase) = PLEASANTRIES.iter().find(|phrase| {
            trimmed
                .get(..phrase.len())
                .is_some_and(|prefix| prefix.eq_ignore_ascii_case(phrase))
                && trimmed[phrase.len()..]
                    .chars()
                    .next()
                    .is_none_or(|c| !c.is_ascii_alphanumeric() && c != '\'')
        }) else {
            break;
        };
        rest = &trimmed[phrase.len()..];
    }
    rest.to_string()
}

/// Replace standalone occurrences of the ASCII phrase `needle` (case-insensitively) with
/// `with`. A match that runs into a longer word is left alone: `and then` must not turn
/// `command then` into `comm`. An empty `needle` is returned unchanged — `find("")` always
/// succeeds, so an unguarded loop would never terminate.
fn replace_ci(hay: &str, needle: &str, with: &str) -> String {
    if needle.is_empty() {
        return hay.to_string();
    }
    let lower = hay.to_ascii_lowercase();
    let n = needle.to_ascii_lowercase();
    let bytes = hay.as_bytes();
    let mut out = String::with_capacity(hay.len());
    let mut i = 0;
    while let Some(rel) = lower[i..].find(&n) {
        let at = i + rel;
        let end = at + needle.len();
        // A word byte is ASCII alphanumeric or any non-ASCII byte: the latter may be the
        // lead of a letter we cannot classify at byte level, so it counts as part of a word.
        let joined_left = bytes[..at].last().is_some_and(|b| is_word_byte(*b));
        let joined_right = bytes[end..].first().is_some_and(|b| is_word_byte(*b));
        if joined_left || joined_right {
            // Inside a longer word: keep the byte and keep scanning past this match.
            out.push_str(&hay[i..at + 1]);
            i = at + 1;
            continue;
        }
        out.push_str(&hay[i..at]);
        out.push_str(with);
        i = end;
    }
    out.push_str(&hay[i..]);
    out
}

/// True when `b` continues a word rather than bounding one.
fn is_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b >= 0x80
}

/// Drop whole words from `words` when they appear as standalone tokens (ASCII word chars).
/// Never drops [`KEEP_WORDS`]. Inline code has already been removed from this prose segment.
fn strip_words(text: &str, words: &[&str]) -> String {
    let drop: std::collections::HashSet<&str> = words.iter().copied().collect();
    let keep: std::collections::HashSet<&str> = KEEP_WORDS.iter().copied().collect();
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    // The character before the current one in the *input*, so the left boundary is judged
    // like the right one and never from text this pass already edited.
    let mut prev: Option<char> = None;
    while let Some(c) = chars.next() {
        if c.is_ascii_alphabetic() {
            let mut word = String::new();
            word.push(c);
            while let Some(d) = chars.peek().copied() {
                if d.is_ascii_alphabetic() || d == '\'' {
                    word.push(chars.next().unwrap());
                } else {
                    break;
                }
            }
            let lower = word.to_ascii_lowercase();
            // A word glued to a dot, dash, slash, digit or non-ASCII letter on either side is
            // part of an identifier or a path (`a.txt`, `a-b`, `a/b`, `item1`, `foo_a`,
            // `/tmp/a`, `item1a`), not an article: dropping it turned `Edit a.txt` into
            // `Edit .txt` and `foo_a` into `foo_`.
            let glued =
                prev.is_some_and(is_name_glue) || chars.peek().copied().is_some_and(is_name_glue);
            if glued || keep.contains(lower.as_str()) || !drop.contains(lower.as_str()) {
                out.push_str(&word);
            }
            // else: drop the word
            prev = word.chars().last();
        } else {
            out.push(c);
            prev = Some(c);
        }
    }
    out
}

/// True for a character that joins a word to a neighbouring name or path.
fn is_name_glue(c: char) -> bool {
    matches!(c, '.' | '-' | '_' | '/' | '\\')
        || c.is_ascii_digit()
        || !c.is_ascii() && c.is_alphanumeric()
}

fn cleanup_ws_segment(s: &str, preserve_start: bool, preserve_end: bool) -> String {
    const EDGE: char = '\u{e000}';
    let mut padded = String::with_capacity(s.len() + 2 * EDGE.len_utf8());
    if preserve_start {
        padded.push(EDGE);
    }
    padded.push_str(s);
    if preserve_end {
        padded.push(EDGE);
    }

    let mut cleaned = cleanup_ws(&padded);
    if preserve_start {
        cleaned.remove(0);
    }
    if preserve_end {
        cleaned.pop();
    }
    cleaned
}

fn cleanup_ws(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut start = true;
    for line in s.lines() {
        if !start {
            out.push('\n');
        }
        start = false;
        let mut prev_space = true; // trim line start
        for c in line.chars() {
            if c.is_whitespace() {
                if !prev_space {
                    out.push(' ');
                    prev_space = true;
                }
            } else {
                out.push(c);
                prev_space = false;
            }
        }
        while out.ends_with(' ') {
            out.pop();
        }
    }
    if s.ends_with('\n') {
        out.push('\n');
    }
    out.trim_start().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_drops_filler_keeps_fence() {
        let in_ = "Sure! I'd be happy to help. The bug is just really in auth.\n```rs\nfn f() { let the = 1; }\n```\nThen fix it.";
        let out = compress_prose(in_, CaveIntensity::Full);
        assert!(out.contains("```rs\nfn f() { let the = 1; }\n```"), "{out}");
        assert!(!out.to_ascii_lowercase().starts_with("sure"), "{out}");
        assert!(!out.contains("really"), "{out}");
        assert!(!out.contains("just"), "{out}");
    }

    #[test]
    fn never_drops_negation() {
        let in_ = "Do not delete the table. Never run drop. This is no joke.";
        let out = compress_prose(in_, CaveIntensity::Full);
        assert!(out.contains("not"), "{out}");
        assert!(out.contains("Never") || out.contains("never"), "{out}");
        assert!(out.contains("no"), "{out}");
    }

    #[test]
    fn lite_only_pleasantries() {
        let in_ = "Sure! The function really works.";
        let out = compress_prose(in_, CaveIntensity::Lite);
        assert!(!out.starts_with("Sure"), "{out}");
        assert!(out.contains("really"), "{out}");
        assert!(out.contains("The"), "{out}");
    }

    #[test]
    fn protects_inline_backtick_spans() {
        let in_ = "Sure! The answer is just `Sure! The really exact value` and the result.";
        assert_eq!(
            compress_prose(in_, CaveIntensity::Full),
            "answer is `Sure! The really exact value` and result."
        );
    }

    #[test]
    fn protects_double_backtick_spans() {
        let in_ = "Of course! Use ``the really `simple` value`` and the helper.";
        assert_eq!(
            compress_prose(in_, CaveIntensity::Full),
            "Use ``the really `simple` value`` and helper."
        );
    }

    /// Ultra strips `and then` as a phrase; the same letters inside a word must survive —
    /// `command then` used to come back as `comm check`.
    #[test]
    fn ultra_phrase_strip_never_cuts_a_word() {
        let out = compress_prose(
            "Run the command then check the output.",
            CaveIntensity::Ultra,
        );
        assert!(out.contains("command then"), "{out}");
    }

    #[test]
    fn ultra_phrase_strip_still_drops_the_standalone_phrase() {
        let out = compress_prose("Build it and then run it.", CaveIntensity::Ultra);
        assert!(!out.contains("and then"), "{out}");
        assert!(out.contains("Build it") && out.contains("run it"), "{out}");
    }

    /// An article glued to a dot, dash, slash or digit belongs to a name, not to the prose:
    /// `Edit a.txt` came back as `Edit .txt`.
    #[test]
    fn an_article_inside_a_name_is_kept() {
        for (input, want) in [
            ("Edit a.txt now", "a.txt"),
            ("Open a-b next", "a-b"),
            ("Check a/b here", "a/b"),
            ("Keep a1 there", "a1"),
        ] {
            let out = compress_prose(input, CaveIntensity::Full);
            assert!(out.contains(want), "{input} → {out}");
        }
        assert_eq!(
            compress_prose("Edit the file", CaveIntensity::Full),
            "Edit file"
        );
    }

    /// A word glued to a name on its *left* (`foo_a`, `/tmp/a`, `item1a`) is part of that name:
    /// only the right-hand glue used to be checked, so `foo_a` came back as `foo_`.
    #[test]
    fn a_suffix_inside_a_name_is_kept() {
        for intensity in [
            CaveIntensity::Lite,
            CaveIntensity::Full,
            CaveIntensity::Ultra,
        ] {
            for name in [
                "foo_a",
                "foo_the",
                "/tmp/a",
                "C:\\tmp\\the",
                "item1a",
                "v2an",
                "x-a",
                "x.the",
                "_a_",
                "naïvea",
                "a_b",
                "1_a_2",
            ] {
                let input = format!("Edit {name} now");
                let out = compress_prose(&input, intensity);
                assert!(out.contains(name), "{intensity:?}: {input} → {out}");
            }
        }
        assert_eq!(
            compress_prose("Edit the file and a test", CaveIntensity::Ultra),
            "Edit file and test"
        );
        assert_eq!(
            compress_prose("foo_a is really the one", CaveIntensity::Full),
            "foo_a is one"
        );
    }

    #[test]
    fn a_suffix_name_next_to_code_is_kept() {
        assert_eq!(
            compress_prose("Use `x` then foo_a, and the `y`.", CaveIntensity::Full),
            "Use `x` then foo_a, and `y`."
        );
    }

    /// The outer four-backtick fence holds a triple-backtick example: the shorter run must not
    /// close it, or the filler words inside the outer block get stripped.
    #[test]
    fn a_longer_fence_survives_a_shorter_inner_run() {
        let fence =
            "````md\nSure! Example:\n```rs\nlet the = 1;\n```\nThe really exact content\n````";
        for intensity in [
            CaveIntensity::Lite,
            CaveIntensity::Full,
            CaveIntensity::Ultra,
        ] {
            let input = format!("Sure! The bug is really here.\n{fence}\nFix the thing.");
            let out = compress_prose(&input, intensity);
            assert!(out.contains(fence), "{intensity:?}: {out}");
        }
        let out = compress_prose(
            &format!("The bug is really here.\n{fence}\nFix the thing."),
            CaveIntensity::Full,
        );
        assert!(out.starts_with("bug is here.\n"), "{out}");
        assert!(out.ends_with("Fix thing."), "{out}");
    }

    #[test]
    fn fences_follow_markdown_opening_and_closing_rules() {
        // A longer closer, a tilde fence and an indented fence are all verbatim blocks.
        for fence in [
            "```\nthe really\n`````",
            "~~~\nthe really\n~~~",
            "   ```\nthe really\n   ```",
            "```\nthe really\n```   ",
        ] {
            let out = compress_prose(
                &format!("The intro\n{fence}\nThe outro"),
                CaveIntensity::Full,
            );
            assert!(out.contains(fence.trim_end()), "{fence:?} → {out}");
            assert!(out.ends_with("outro"), "{out}");
        }
        // Backticks inside a code line, or a different delimiter, never close the block.
        for body in ["``` the really", "~~~", "`` the really ``", "````` the"] {
            let fence = format!("````\nx\n{body}\nthe really exact\n````");
            let out = compress_prose(&fence, CaveIntensity::Full);
            assert_eq!(out, fence);
        }
        // Unclosed block: everything after the opener is kept.
        let unclosed = "The intro\n```\nthe really\n```x\nthe end";
        assert_eq!(
            compress_prose(unclosed, CaveIntensity::Full),
            "intro\n```\nthe really\n```x\nthe end"
        );
    }

    #[test]
    fn inline_triple_backtick_spans_stay_inline() {
        assert_eq!(
            compress_prose(
                "The ```the really``` span and the rest.",
                CaveIntensity::Full
            ),
            "```the really``` span and rest."
        );
    }

    #[test]
    fn empty_needle_is_identity() {
        assert_eq!(replace_ci("command then", "", " "), "command then");
    }

    #[test]
    fn preserves_whitespace_inside_backtick_spans() {
        let in_ = "The result is `left   middle\n  right` for the test.";
        assert_eq!(
            compress_prose(in_, CaveIntensity::Full),
            "result is `left   middle\n  right` for test."
        );
    }

    #[test]
    fn removes_only_complete_leading_pleasantries() {
        assert_eq!(
            compress_prose("Assure! Sure! The result.", CaveIntensity::Lite),
            "Assure! Sure! The result."
        );
        assert_eq!(
            compress_prose("Sure! Sure thing! Done.", CaveIntensity::Lite),
            "Done."
        );
    }
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T431: noise a saved request body carries without saying anything — terminal escapes,
//! control and zero-width characters, harness wrapper blocks, trailing whitespace. Pure;
//! [`body`] is what the store applies unless `[core] store_raw` is set.

use std::borrow::Cow;
use std::sync::LazyLock;

use regex::Regex;
use serde_json::Value;

/// Blocks a host wraps around a prompt or a tool result for the model, not for the record:
/// each one the 2026-10-05 scan of saved hook bodies found (T431).
const WRAPPERS: [&str; 4] = [
    "system-reminder",
    "task-notification",
    "ci-monitor-event",
    "local-command-caveat",
];

/// One alternative per tag: the regex crate has no backreferences, and a shared alternation
/// would let `<a>` close on `</b>`. The newlines after a block go with it.
static WRAPPER: LazyLock<Regex> = LazyLock::new(|| {
    let alts: Vec<String> = WRAPPERS.iter().map(|t| format!("<{t}>.*?</{t}>")).collect();
    Regex::new(&format!("(?s)(?:{})\n*", alts.join("|"))).expect("static wrapper regex")
});

/// A request body cleaned for saving. JSON has every string value cleaned (keys, numbers and
/// structure kept); other UTF-8 is cleaned as text; anything else is kept as is. A body with
/// nothing to clean comes back borrowed, byte for byte.
pub fn body(bytes: &[u8]) -> Cow<'_, [u8]> {
    if let Ok(mut value) = serde_json::from_slice::<Value>(bytes) {
        if !strings(&mut value, text) {
            return Cow::Borrowed(bytes);
        }
        return serde_json::to_vec(&value).map_or(Cow::Borrowed(bytes), Cow::Owned);
    }
    match std::str::from_utf8(bytes).map(text) {
        Ok(Cow::Owned(clean)) => Cow::Owned(clean.into_bytes()),
        _ => Cow::Borrowed(bytes),
    }
}

/// Run `clean` over every string under `value` in place (keys, numbers and structure stay);
/// true when any changed.
pub fn strings(value: &mut Value, clean: fn(&str) -> Cow<'_, str>) -> bool {
    match value {
        Value::String(s) => match clean(s) {
            Cow::Owned(cleaned) => {
                *s = cleaned;
                true
            }
            Cow::Borrowed(_) => false,
        },
        Value::Array(items) => items
            .iter_mut()
            .fold(false, |hit, v| strings(v, clean) | hit),
        Value::Object(map) => map
            .values_mut()
            .fold(false, |hit, v| strings(v, clean) | hit),
        _ => false,
    }
}

/// `s` without wrapper blocks, escapes, control and zero-width characters, with LF line
/// ends, no trailing spaces or tabs, and at most one blank line in a row.
pub fn text(s: &str) -> Cow<'_, str> {
    let unwrapped = WRAPPER.replace_all(s, "");
    let clean = whitespace(&characters(&unwrapped, true));
    match clean == s {
        true => Cow::Borrowed(s),
        false => Cow::Owned(clean),
    }
}

/// `s` without escapes, control and zero-width characters and nothing else (T432): wrappers,
/// CR, trailing blanks and blank runs stay, because the model reads this text and its
/// exact-match edits fail when a result's whitespace no longer matches the file.
pub fn terminal_noise(s: &str) -> Cow<'_, str> {
    let clean = characters(s, false);
    match clean == s {
        true => Cow::Borrowed(s),
        false => Cow::Owned(clean),
    }
}

/// Drops ANSI escapes, zero-width spaces and BOMs, and control characters other than `\n`
/// and `\t`. With `line_ends`, CRLF and a lone CR become LF; without, `\r` is kept.
fn characters(s: &str, line_ends: bool) -> String {
    let bytes = s.as_bytes();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while let Some(c) = s[i..].chars().next() {
        match c {
            '\u{1b}' => {
                i = skip_escape(bytes, i);
                continue;
            }
            '\r' if line_ends && bytes.get(i + 1) == Some(&b'\n') => {}
            '\r' if line_ends => out.push('\n'),
            '\n' | '\t' | '\r' => out.push(c),
            // U+200D stays: it joins emoji sequences, so dropping it changes what is shown.
            '\u{200b}' | '\u{2060}' | '\u{feff}' => {}
            c if c.is_control() => {}
            c => out.push(c),
        }
        i += c.len_utf8();
    }
    out
}

/// Trailing spaces and tabs cut from every line; three or more newlines become two.
fn whitespace(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut newlines = 0;
    for (n, line) in s.split('\n').enumerate() {
        if n > 0 {
            newlines += 1;
            if newlines <= 2 {
                out.push('\n');
            }
        }
        let line = line.trim_end_matches([' ', '\t']);
        if !line.is_empty() {
            newlines = 0;
            out.push_str(line);
        }
    }
    out
}

/// Skip one ANSI escape at `i` (CSI `ESC [ … final`, OSC `ESC ] … BEL|ESC \`, else `ESC`
/// plus one ASCII byte) and return the index after it. Every end it returns follows an
/// ASCII byte, so cutting `[i, end)` out of UTF-8 leaves UTF-8.
pub fn skip_escape(body: &[u8], i: usize) -> usize {
    let mut j = i + 1;
    match body.get(j) {
        Some(b'[') => {
            j += 1;
            while matches!(body.get(j), Some(c) if !(0x40..=0x7e).contains(c)) {
                j += 1;
            }
            (j + 1).min(body.len())
        }
        Some(b']') => {
            j += 1;
            while j < body.len() {
                if body[j] == 0x07 {
                    return j + 1;
                }
                if body[j] == 0x1b && body.get(j + 1) == Some(&b'\\') {
                    return j + 2;
                }
                j += 1;
            }
            j
        }
        Some(c) if c.is_ascii() => j + 1,
        _ => j,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rstest::rstest;

    #[rstest]
    #[case::csi_colour("\u{1b}[31mred\u{1b}[0m", "red")]
    #[case::csi_cursor("a\u{1b}[2K\u{1b}[1Gb", "ab")]
    #[case::osc_link_bel("\u{1b}]8;;https://x\u{7}link\u{1b}]8;;\u{7}", "link")]
    #[case::osc_link_st("\u{1b}]8;;https://x\u{1b}\\link\u{1b}]8;;\u{1b}\\", "link")]
    #[case::two_byte_escape("a\u{1b}=b", "ab")]
    #[case::escape_before_utf8("\u{1b}é", "é")]
    #[case::unterminated_csi("a\u{1b}[", "a")]
    #[case::unterminated_csi_over_utf8("a\u{1b}[ёж", "a")]
    #[case::control("a\u{7}b\u{0}c\u{8}d\u{7f}e", "abcde")]
    #[case::tab_kept("a\tb", "a\tb")]
    #[case::crlf("a\r\nb\r\n", "a\nb\n")]
    #[case::lone_cr("a\rb", "a\nb")]
    #[case::zero_width("a\u{200b}b\u{2060}c\u{feff}", "abc")]
    #[case::emoji_joiner_kept("👩\u{200d}💻", "👩\u{200d}💻")]
    #[case::trailing_blanks("a  \nb\t\n", "a\nb\n")]
    #[case::indent_kept("    fn x() {\n\tlet y;\n}", "    fn x() {\n\tlet y;\n}")]
    #[case::blank_runs("a\n\n\n\n\nb", "a\n\nb")]
    #[case::blank_lines_of_spaces("a\n  \n \t\n\nb", "a\n\nb")]
    #[case::one_blank_kept("a\n\nb", "a\n\nb")]
    #[case::reminder_before_prompt(
        "<system-reminder>\nYou are in a worktree.\n</system-reminder>\n\n\nfix the test",
        "fix the test"
    )]
    #[case::notification_only(
        "<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n</task-notification>",
        ""
    )]
    #[case::ci_event("<ci-monitor-event>pr 7 green</ci-monitor-event>\nok", "ok")]
    #[case::caveat("<local-command-caveat>Caveat: x</local-command-caveat>\nls", "ls")]
    #[case::inline_reminder("a <system-reminder>x</system-reminder> b", "a  b")]
    #[case::two_blocks(
        "<system-reminder>1</system-reminder>\nmid\n<system-reminder>2</system-reminder>\nend",
        "mid\nend"
    )]
    #[case::mismatched_tags_kept(
        "<system-reminder>x</task-notification>",
        "<system-reminder>x</task-notification>"
    )]
    #[case::unclosed_kept("<system-reminder> x", "<system-reminder> x")]
    #[case::other_tags_kept("<task-id>b1</task-id>", "<task-id>b1</task-id>")]
    fn text_drops_each_kind_of_noise(#[case] input: &str, #[case] want: &str) {
        let got = text(input);
        assert_eq!(got, want, "{input:?}");
        assert_eq!(text(&got), got, "cleaning twice changes nothing: {input:?}");
    }

    #[rstest]
    #[case::escapes("\u{1b}[31mred\u{1b}[0m \u{1b}]8;;u\u{7}l\u{1b}]8;;\u{7}", "red l")]
    #[case::control_and_zero_width("a\u{7}b\u{0}c\u{200b}d\u{feff}e\u{2060}", "abcde")]
    #[case::whitespace_kept("a  \r\n\r\n\n\n\tb \t\n", "a  \r\n\r\n\n\n\tb \t\n")]
    #[case::wrapper_kept(
        "<system-reminder>\n\u{1b}[1mx\u{1b}[0m\n</system-reminder>\n\n\n",
        "<system-reminder>\nx\n</system-reminder>\n\n\n"
    )]
    #[case::emoji_joiner_kept("👩\u{200d}💻", "👩\u{200d}💻")]
    fn terminal_noise_touches_nothing_else(#[case] input: &str, #[case] want: &str) {
        let got = terminal_noise(input);
        assert_eq!(got, want, "{input:?}");
        assert_eq!(terminal_noise(&got), got, "idempotent: {input:?}");
    }

    #[test]
    fn terminal_noise_borrows_clean_text() {
        assert!(matches!(terminal_noise("a  \r\nb\t"), Cow::Borrowed(_)));
    }

    #[test]
    fn clean_text_is_borrowed() {
        let s = "plain text\n\nwith one blank line and\ttabs";
        assert!(matches!(text(s), Cow::Borrowed(_)));
    }

    #[test]
    fn json_strings_are_cleaned_and_everything_else_kept() {
        let raw = br#"{"tool_input":{"command":"ls  \r\n"},"n":1.5,"ok":true,"none":null,"list":["\u001b[1mA\u001b[0m",2],"key  \u0007":"v"}"#;
        let got: Value = serde_json::from_slice(&body(raw)).unwrap();
        let want = serde_json::json!({
            "tool_input": {"command": "ls\n"},
            "n": 1.5,
            "ok": true,
            "none": null,
            "list": ["A", 2],
            "key  \u{7}": "v",
        });
        assert_eq!(got, want);
    }

    #[test]
    fn a_clean_json_body_keeps_its_bytes() {
        // Key order and spacing that a re-serialisation would change.
        let raw = br#"{ "z": "a", "a": [1, 2] }"#;
        let got = body(raw);
        assert!(matches!(got, Cow::Borrowed(_)));
        assert_eq!(&*got, raw);
    }

    #[test]
    fn plain_text_bodies_are_cleaned_as_text() {
        assert_eq!(&*body(b"\x1b[32mok\x1b[0m  \r\n"), b"ok\n");
    }

    #[test]
    fn invalid_utf8_is_kept_as_is() {
        let raw = b"\xff\x1b[31m\xfe";
        assert_eq!(&*body(raw), raw);
    }

    /// A `UserPromptSubmit` body as Claude Code sends it (the 2026-10-05 scan): the wrapper
    /// goes, the session fields stay.
    #[test]
    fn a_real_hook_prompt_loses_its_wrapper() {
        let raw = br#"{"session_id":"s1","hook_event_name":"UserPromptSubmit","prompt":"<system-reminder>\nYou are operating in a git worktree.\n</system-reminder>\n\n\nIn rtok, run the tests  "}"#;
        let got: Value = serde_json::from_slice(&body(raw)).unwrap();
        assert_eq!(got["prompt"], "In rtok, run the tests");
        assert_eq!(got["session_id"], "s1");
        assert_eq!(got["hook_event_name"], "UserPromptSubmit");
    }

    proptest::proptest! {
        /// Cleaning is idempotent and never panics on arbitrary text, escapes and wrappers
        /// included, and its output never holds what it removes.
        #[test]
        fn cleaning_is_idempotent(s in "(\\PC|[\\x00-\\x1f\\x7f\\u{200b}\\u{feff}]|\\x1b\\[[0-9;]*m|<system-reminder>|</system-reminder>|\r\n| {1,3}\n)*") {
            let once = text(&s).into_owned();
            proptest::prop_assert_eq!(text(&once), once.as_str());
            let left = ["\u{1b}", "\r", "\n\n\n", " \n"].into_iter().find(|l| once.contains(l));
            proptest::prop_assert!(left.is_none(), "left {:?} in {:?}", left, once);
        }
    }
}

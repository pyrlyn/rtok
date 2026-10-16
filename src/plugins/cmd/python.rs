// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Python output (T675): `pytest` / `unittest` sessions and tracebacks.
//!
//! A passing run shrinks to its summary; a failing run keeps every failure block, the
//! short summary and the totals; progress lines survive only when they show a failure.
//! A traceback keeps all its frames, but a run of identical frames (deep recursion) folds
//! into one `[… repeated N more times]` line. Written for rtok from the output formats
//! alone; no code copied.
//!
//! Every function returns `None` when the text is not the shape it knows, so the caller
//! keeps the previous behaviour.

use regex::Regex;
use std::sync::OnceLock;

fn re(cell: &'static OnceLock<Regex>, pat: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(pat).expect("static pattern"))
}

/// `=== name ===`, as pytest prints its sections.
fn banner(line: &str) -> Option<&str> {
    let t = line.trim();
    let inner = t.strip_prefix("==")?.strip_suffix("==")?;
    Some(inner.trim_matches('=').trim())
}

/// `.F.. [ 40%]` / `tests/a.py ..F` — a progress line.
fn progress(line: &str) -> bool {
    static R: OnceLock<Regex> = OnceLock::new();
    re(&R, r"^\S*\s*[.FEsxXpP]+\s*(\[\s*\d+%\])?$").is_match(line.trim_end())
}

/// `3 passed, 1 failed in 0.10s` without the `===` frame (`-q`).
fn totals(line: &str) -> bool {
    static R: OnceLock<Regex> = OnceLock::new();
    re(
        &R,
        r"^\d+ (passed|failed|error|errors|skipped|xfailed|xpassed|warnings?|deselected)\b.* in [\d.]+s",
    )
    .is_match(line.trim())
}

#[derive(PartialEq, Clone, Copy)]
enum Section {
    Header,
    Kept,
}

/// A pytest session: header and passing progress dropped; failures, errors, warnings,
/// the short summary and the totals kept whole.
pub fn pytest(output: &str) -> Option<String> {
    let lines: Vec<&str> = output.lines().collect();
    let has_banner = lines.iter().any(|l| banner(l).is_some());
    let has_totals = lines
        .iter()
        .any(|l| totals(l) || banner(l).is_some_and(totals));
    if !has_totals || !(has_banner || lines.iter().any(|l| progress(l))) {
        return None;
    }
    // A `FAILED id` line already names every failing test; the dots line would repeat it.
    let named = lines
        .iter()
        .any(|l| l.starts_with("FAILED ") || l.starts_with("ERROR "));
    let mut section = Section::Header;
    let mut out: Vec<&str> = Vec::new();
    for line in lines {
        if let Some(name) = banner(line) {
            section = if name.contains("test session starts") {
                Section::Header
            } else {
                Section::Kept
            };
            // The `FAILURES` / `short test summary info` frames only label what follows; the
            // totals frame is the one banner worth its tokens.
            if totals(name) {
                out.push(line);
            }
            continue;
        }
        if totals(line) {
            out.push(line);
            continue;
        }
        if section == Section::Kept {
            out.push(line);
        } else if progress(line) {
            if !named && line.contains(['F', 'E']) {
                out.push(line);
            }
        } else if ["ERROR", "WARN", "Error", "error"]
            .iter()
            .any(|w| line.contains(w))
        {
            out.push(line);
        }
    }
    (!out.is_empty()).then(|| out.join("\n"))
}

/// `unittest`: the dots line goes when it shows no failure; everything else stays.
pub fn unittest(output: &str) -> Option<String> {
    static R: OnceLock<Regex> = OnceLock::new();
    if !re(&R, r"(?m)^Ran \d+ tests? in [\d.]+s$").is_match(output) {
        return None;
    }
    let kept: Vec<&str> = output
        .lines()
        .filter(|l| !(l.chars().all(|c| matches!(c, '.' | 's' | 'x')) && !l.is_empty()))
        .collect();
    Some(kept.join("\n"))
}

/// `python <script>` / `python -m unittest`: a unittest report is shortened, a traceback has
/// its repeated frames folded. `None` when neither applies, so the `python` rule runs as before.
pub fn script(output: &str) -> Option<String> {
    if let Some(got) = unittest(output) {
        return Some(collapse_frames(&got));
    }
    let folded = collapse_frames(output);
    (folded != output).then_some(folded)
}

/// Identical consecutive traceback frames (a `File …` line and its code line) folded:
/// `    [previous frame repeated N more times]`. Text outside tracebacks is untouched.
pub fn collapse_frames(output: &str) -> String {
    let lines: Vec<&str> = output.lines().collect();
    let mut out: Vec<String> = Vec::with_capacity(lines.len());
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i];
        out.push(line.to_string());
        i += 1;
        if !line.starts_with("Traceback (most recent call last):") {
            continue;
        }
        // The frames: indented lines until the exception line.
        let start = i;
        while i < lines.len() && lines[i].starts_with(' ') {
            i += 1;
        }
        out.extend(fold(&lines[start..i]));
    }
    let mut text = out.join("\n");
    if output.ends_with('\n') {
        text.push('\n');
    }
    text
}

/// Frames are `  File …` plus the lines below it up to the next `  File`.
fn fold(frames: &[&str]) -> Vec<String> {
    let mut groups: Vec<Vec<&str>> = Vec::new();
    for l in frames {
        if l.starts_with("  File ") || groups.is_empty() {
            groups.push(vec![l]);
        } else if let Some(g) = groups.last_mut() {
            g.push(l);
        }
    }
    let mut out = Vec::new();
    let mut k = 0;
    while k < groups.len() {
        let mut run = 1;
        while k + run < groups.len() && groups[k + run] == groups[k] {
            run += 1;
        }
        out.extend(groups[k].iter().map(|l| (*l).to_string()));
        if run > 1 {
            out.push(format!(
                "  [previous frame repeated {} more times]",
                run - 1
            ));
        }
        k += run;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const FAILING: &str =
        "============================= test session starts ==============================
platform linux -- Python 3.12.1, pytest-8.1.1
rootdir: /w
collected 5 items

tests/test_a.py ..F                                                      [ 60%]
tests/test_b.py ..                                                       [100%]

=================================== FAILURES ===================================
________________________________ test_boom _____________________________________

    def test_boom():
>       assert 1 == 2
E       assert 1 == 2

tests/test_a.py:7: AssertionError
=========================== short test summary info ============================
FAILED tests/test_a.py::test_boom - assert 1 == 2
========================= 1 failed, 4 passed in 0.10s ==========================";

    #[test]
    fn failing_session_keeps_failures_and_drops_header_and_passing_progress() {
        let got = pytest(FAILING).unwrap();
        assert!(got.contains("assert 1 == 2") && got.contains("tests/test_a.py:7: AssertionError"));
        assert!(got.contains("FAILED tests/test_a.py::test_boom"));
        assert!(
            !got.contains("test_a.py ..F"),
            "named by the FAILED line: {got}"
        );
        assert!(
            !got.contains("platform linux") && !got.contains("test_b.py .."),
            "{got}"
        );
        assert!(got.contains("1 failed, 4 passed in 0.10s"));
    }

    #[test]
    fn passing_session_is_its_totals_line() {
        let raw = "============================= test session starts ==============================
collected 3 items

test_a.py ...                                                            [100%]

============================== 3 passed in 0.02s ===============================";
        assert_eq!(
            pytest(raw).unwrap(),
            "============================== 3 passed in 0.02s ==============================="
        );
    }

    #[test]
    fn quiet_mode_without_banners_still_parses() {
        let raw = "..F.. [100%]\n=================================== FAILURES ===================================\nboom\n1 failed, 4 passed in 0.10s";
        let got = pytest(raw).unwrap();
        assert!(got.contains("..F.."), "{got}");
        assert!(got.ends_with("1 failed, 4 passed in 0.10s"));
    }

    #[test]
    fn non_pytest_text_is_left_alone() {
        assert!(pytest("hello\nworld\n").is_none());
        assert!(unittest("hello\n").is_none());
    }

    #[test]
    fn unittest_drops_a_clean_dots_line_only() {
        let ok = "....\n----------------------------------------------------------------------\nRan 4 tests in 0.001s\n\nOK";
        assert!(!unittest(ok).unwrap().starts_with("...."));
        let bad = ".F..\n======\nFAIL: test_x\nRan 4 tests in 0.001s\n\nFAILED (failures=1)";
        assert!(unittest(bad).unwrap().contains(".F.."));
    }

    #[test]
    fn recursion_frames_fold_and_every_other_frame_stays() {
        let mut raw = String::from(
            "start\nTraceback (most recent call last):\n  File \"m.py\", line 9, in <module>\n    f(3)\n",
        );
        for _ in 0..40 {
            raw.push_str("  File \"m.py\", line 5, in f\n    return f(n - 1)\n");
        }
        raw.push_str("RecursionError: maximum recursion depth exceeded\nend\n");
        let got = collapse_frames(&raw);
        assert!(
            got.contains("[previous frame repeated 39 more times]"),
            "{got}"
        );
        assert!(got.contains("File \"m.py\", line 9, in <module>") && got.contains("f(3)"));
        assert!(got.contains("RecursionError: maximum recursion depth exceeded"));
        assert!(got.starts_with("start\n") && got.ends_with("end\n"));
        assert!(got.lines().count() < 12, "{got}");
    }

    #[test]
    fn distinct_frames_are_not_folded() {
        let raw = "Traceback (most recent call last):\n  File \"a.py\", line 1, in a\n    b()\n  File \"b.py\", line 2, in b\n    c()\nValueError: x\n";
        assert_eq!(collapse_frames(raw), raw);
    }
}

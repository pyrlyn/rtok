// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `for` / `while` / `until` output (T678): a polling loop prints the same line again and again.
//!
//! Numbers and ids are what differ between those lines, so each line is cut at them and runs
//! of three or more lines with the same cut collapse to `shape ×N (vals: a..b)`. The range of
//! every changing field stays (smallest..largest for numbers, first..last for ids); the values
//! between are in the archive (`rtok expand <id>`). A line with an error word is never
//! collapsed and ends a run, so the one failing poll among fifty healthy ones is still on the
//! page verbatim. Written for rtok from the output shape alone; no code copied.

use regex::Regex;
use std::sync::OnceLock;

/// Fewer lines than this are not worth a `×N` line.
const MIN_RUN: usize = 3;

fn field() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\b[0-9a-f]{12,}\b|\d+(?:\.\d+)?",
        )
        .expect("static pattern")
    })
}

fn problem(line: &str) -> bool {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(
            r"(?i)error|fail|warn|panic|exception|denied|refused|timed? ?out|fatal|unreachable",
        )
        .expect("static pattern")
    })
    .is_match(line)
}

/// A line cut at its number/id fields: the text between them and the fields themselves.
struct Cut<'a> {
    parts: Vec<&'a str>,
    vals: Vec<&'a str>,
}

impl<'a> Cut<'a> {
    fn of(line: &'a str) -> Self {
        let (mut parts, mut vals, mut at) = (Vec::new(), Vec::new(), 0);
        for m in field().find_iter(line) {
            parts.push(&line[at..m.start()]);
            vals.push(m.as_str());
            at = m.end();
        }
        parts.push(&line[at..]);
        Cut { parts, vals }
    }

    fn same_shape(&self, other: &Cut) -> bool {
        self.parts == other.parts
    }
}

/// `output` with runs of same-shape lines collapsed, or `None` when nothing collapsed.
pub fn collapse(output: &str) -> Option<String> {
    let lines: Vec<&str> = output.lines().collect();
    let cuts: Vec<Cut> = lines.iter().map(|l| Cut::of(l)).collect();
    let mut out: Vec<String> = Vec::new();
    let mut changed = false;
    let mut i = 0;
    while i < lines.len() {
        if lines[i].trim().is_empty() || problem(lines[i]) {
            out.push(lines[i].to_string());
            i += 1;
            continue;
        }
        // Blank lines (`echo` between polls) do not end a run, but only the ones inside
        // it are dropped.
        let mut members = vec![i];
        let mut j = i + 1;
        while j < lines.len() {
            if lines[j].trim().is_empty() {
                j += 1;
            } else if !problem(lines[j]) && cuts[j].same_shape(&cuts[i]) {
                members.push(j);
                j += 1;
            } else {
                break;
            }
        }
        if members.len() < MIN_RUN {
            out.push(lines[i].to_string());
            i += 1;
            continue;
        }
        out.push(run_line(&cuts, &members));
        changed = true;
        i = members[members.len() - 1] + 1;
    }
    if !changed {
        return None;
    }
    let got = out.join("\n");
    (got.len() < output.len()).then_some(got)
}

/// `a..b` for a field that changes: the smallest and largest when every value is a number, else the
/// first and last (ids carry no order).
fn span<'a>(vals: impl Iterator<Item = &'a str>) -> String {
    let vals: Vec<&str> = vals.collect();
    let nums: Option<Vec<f64>> = vals.iter().map(|v| v.parse().ok()).collect();
    if let Some(nums) = nums {
        let at = |pick: fn(f64, f64) -> f64| {
            let best = nums.iter().copied().reduce(pick).unwrap_or_default();
            vals[nums.iter().position(|&n| n == best).unwrap_or(0)]
        };
        return format!("{}..{}", at(f64::min), at(f64::max));
    }
    format!("{}..{}", vals[0], vals[vals.len() - 1])
}

/// One line for the run: constant fields written back in, changing ones as `#` with their
/// the value range after `×N`.
fn run_line(cuts: &[Cut], members: &[usize]) -> String {
    let first = &cuts[members[0]];
    let mut line = String::new();
    let mut vals: Vec<String> = Vec::new();
    for (k, part) in first.parts.iter().enumerate() {
        line.push_str(part);
        if k >= first.vals.len() {
            continue;
        }
        if members.iter().all(|&m| cuts[m].vals[k] == first.vals[k]) {
            line.push_str(first.vals[k]);
        } else {
            line.push('#');
            vals.push(span(members.iter().map(|&m| cuts[m].vals[k])));
        }
    }
    if vals.is_empty() {
        format!("{line} ×{}", members.len())
    } else {
        format!("{line} ×{} (vals: {})", members.len(), vals.join(", "))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn polling_lines_collapse_with_first_and_last_values() {
        let raw: String = (1..=20)
            .map(|i| format!("attempt {i}: status=pending\n"))
            .collect();
        let got = collapse(&raw).unwrap();
        assert_eq!(got, "attempt #: status=pending ×20 (vals: 1..20)");
    }

    #[test]
    fn a_changed_line_ends_the_run_and_stays_verbatim() {
        let mut raw: String = (1..=9)
            .map(|i| format!("attempt {i}: status=pending\n"))
            .collect();
        raw.push_str("attempt 10: status=ready\nattempt 11: status=ready\n");
        let got = collapse(&raw).unwrap();
        assert_eq!(
            got,
            "attempt #: status=pending ×9 (vals: 1..9)\nattempt 10: status=ready\nattempt 11: status=ready"
        );
    }

    #[test]
    fn error_lines_are_never_collapsed_and_split_the_run() {
        let raw = "poll 1 ok\npoll 2 ok\npoll 3 ok\npoll 4 failed: connection refused\npoll 5 failed: connection refused\npoll 6 ok\npoll 7 ok\npoll 8 ok\n";
        let got = collapse(raw).unwrap();
        assert_eq!(
            got,
            "poll # ok ×3 (vals: 1..3)\npoll 4 failed: connection refused\npoll 5 failed: connection refused\npoll # ok ×3 (vals: 6..8)"
        );
    }

    #[test]
    fn identical_lines_and_blank_separators_collapse_to_a_count() {
        let raw = "{\"status\":\"starting\"}\n\n{\"status\":\"starting\"}\n\n{\"status\":\"starting\"}\n\n{\"status\":\"up\"}\n";
        let got = collapse(raw).unwrap();
        assert_eq!(got, "{\"status\":\"starting\"} ×3\n\n{\"status\":\"up\"}");
    }

    #[test]
    fn a_number_that_goes_up_and_down_shows_its_smallest_and_largest() {
        let got = collapse("took 5 ms\ntook 9 ms\ntook 3 ms\ntook 7 ms\n").unwrap();
        assert_eq!(got, "took # ms ×4 (vals: 3..9)");
    }

    #[test]
    fn two_lines_are_left_alone() {
        assert!(collapse("poll 1\npoll 2\n").is_none());
        assert!(collapse("").is_none());
    }

    #[test]
    fn ids_and_timestamps_are_fields_too() {
        let raw = "12:00:01 job 9f8e7d6c5b4a ready\n12:00:02 job 1a2b3c4d5e6f ready\n12:00:03 job aabbccddeeff ready\n";
        let got = collapse(raw).unwrap();
        assert_eq!(
            got,
            "12:00:# job # ready ×3 (vals: 01..03, 9f8e7d6c5b4a..aabbccddeeff)"
        );
    }
}

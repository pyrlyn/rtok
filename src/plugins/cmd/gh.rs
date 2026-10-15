// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `gh` output (T676): `pr|issue view`, `pr checks`, `run view --log`.
//!
//! The formats are `gh`'s non-tty ones (tab separated), which is what an agent's Bash tool
//! sees. Written for rtok from the output shape alone; no code copied. Every function returns
//! `None` for any other shape or when it would not be shorter, so the `gh` rule applies as
//! before; the raw text stays one `rtok expand <id>` away.

use regex::Regex;
use std::sync::OnceLock;

/// Lines of context kept around an error line inside a failing step.
const BEFORE: usize = 2;
const AFTER: usize = 4;

/// The dispatch for `gh <area> <verb> …`.
pub fn format(argv: &[String], output: &str) -> Option<String> {
    let got = match (argv.get(1)?.as_str(), argv.get(2)?.as_str()) {
        ("pr" | "issue", "view") => view(output),
        ("pr", "checks") => checks(output),
        ("run", "view") => run_log(output),
        _ => None,
    }?;
    (got.len() < output.len()).then_some(got)
}

/// `title:\tX` … `--` body: empty fields go, the body loses its blank-line runs and
/// trailing blanks. `--json` and `--comments` shapes that do not start with a field stand down.
fn view(output: &str) -> Option<String> {
    static FIELD: OnceLock<Regex> = OnceLock::new();
    let field = FIELD.get_or_init(|| Regex::new(r"^[a-z][a-z-]*:\t").expect("static pattern"));
    if !output.lines().next().is_some_and(|l| field.is_match(l)) {
        return None;
    }
    let mut out: Vec<&str> = Vec::new();
    let mut in_body = false;
    let mut blank = false;
    for raw in output.lines() {
        let line = raw.trim_end();
        if in_body {
            if line.is_empty() {
                blank = true;
                continue;
            }
            if blank && !out.is_empty() {
                out.push("");
            }
            blank = false;
            out.push(line);
        } else if line == "--" {
            in_body = true;
            out.push(line);
        } else if field.is_match(raw)
            && raw
                .split_once('\t')
                .is_some_and(|(_, v)| v.trim().is_empty())
        {
            // An unset field (`labels:\t`) says nothing; `raw` still has the tab that
            // `trim_end` took off `line`.
        } else {
            out.push(line);
        }
    }
    Some(out.join("\n"))
}

const STATES: &[&str] = &["pass", "fail", "pending", "skipping", "cancel"];

/// `name\tstate\telapsed\turl` rows: a count line, then every row that is not `pass`/`skipping`
/// whole (URL included — it is where the failure log lives).
fn checks(output: &str) -> Option<String> {
    let rows: Vec<(&str, &str)> = output
        .lines()
        .map(|l| {
            let mut c = l.split('\t');
            (c.nth(1).unwrap_or(""), l)
        })
        .collect();
    if rows.is_empty() || !rows.iter().all(|(s, _)| STATES.contains(s)) {
        return None;
    }
    let count = |s: &str| rows.iter().filter(|(st, _)| *st == s).count();
    let mut parts: Vec<String> = Vec::new();
    for s in STATES {
        let n = count(s);
        if n > 0 {
            parts.push(format!("{n} {s}"));
        }
    }
    let mut out = vec![format!("{} checks: {}", rows.len(), parts.join(", "))];
    out.extend(
        rows.iter()
            .filter(|(s, _)| !matches!(*s, "pass" | "skipping"))
            .map(|(_, l)| l.to_string()),
    );
    Some(out.join("\n"))
}

fn is_problem(text: &str) -> bool {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"(?i)##\[(error|warning)\]|\berror\b|\bfailed\b|\bfailure\b|panicked|\bFAIL\b|warning:")
            .expect("static pattern")
    })
    .is_match(text)
}

/// `job\tstep\t<timestamp> text` log lines grouped by step. A step without a problem line
/// collapses to one count; a step with one keeps those lines plus a little context, each
/// under a `job / step` header, timestamps off.
fn run_log(output: &str) -> Option<String> {
    static TS: OnceLock<Regex> = OnceLock::new();
    let ts = TS.get_or_init(|| Regex::new(r"^\d{4}-\d\d-\d\dT[\d:.]+Z ?").expect("static pattern"));
    let mut steps: Vec<(String, Vec<String>)> = Vec::new();
    for line in output.lines() {
        let mut cols = line.splitn(3, '\t');
        let (Some(job), Some(step), Some(text)) = (cols.next(), cols.next(), cols.next()) else {
            return None;
        };
        let head = format!("{job} / {step}");
        let text = ts.replace(text, "").into_owned();
        match steps.last_mut() {
            Some((h, lines)) if *h == head => lines.push(text),
            _ => steps.push((head, vec![text])),
        }
    }
    if steps.is_empty() {
        return None;
    }
    let mut out: Vec<String> = Vec::new();
    let (mut quiet, mut quiet_lines) = (0usize, 0usize);
    for (head, lines) in &steps {
        let hits: Vec<usize> = (0..lines.len())
            .filter(|&i| is_problem(&lines[i]))
            .collect();
        if hits.is_empty() {
            quiet += 1;
            quiet_lines += lines.len();
            continue;
        }
        let mut keep = vec![false; lines.len()];
        for &i in &hits {
            let from = i.saturating_sub(BEFORE);
            let to = (i + AFTER + 1).min(lines.len());
            keep[from..to].iter_mut().for_each(|k| *k = true);
        }
        out.push(format!("## {head}"));
        let mut gap = false;
        for (i, line) in lines.iter().enumerate() {
            if keep[i] {
                if gap {
                    out.push("  …".to_string());
                    gap = false;
                }
                out.push(line.clone());
            } else {
                gap = true;
            }
        }
    }
    if quiet > 0 {
        out.push(format!(
            "{quiet} steps without errors ({quiet_lines} lines)"
        ));
    }
    Some(out.join("\n"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(s: &str) -> Vec<String> {
        s.split_whitespace().map(str::to_string).collect()
    }

    const VIEW: &str = "title:\tFix the thing\nstate:\tOPEN\nauthor:\tada\nlabels:\t\nassignees:\t\nreviewers:\t\nprojects:\t\nmilestone:\t\nnumber:\t12\nurl:\thttps://github.com/o/r/pull/12\nadditions:\t10\ndeletions:\t2\n--\nFirst paragraph.   \n\n\n\nSecond paragraph.\n\n\n";

    #[test]
    fn view_drops_empty_fields_and_blank_runs_but_keeps_the_rest() {
        let got = format(&argv("gh pr view 12"), VIEW).unwrap();
        for kept in [
            "title:\tFix the thing",
            "number:\t12",
            "url:\thttps://github.com/o/r/pull/12",
            "additions:\t10",
        ] {
            assert!(got.contains(kept), "{got}");
        }
        assert!(
            !got.contains("labels:") && !got.contains("milestone:"),
            "{got}"
        );
        assert!(
            got.ends_with("First paragraph.\n\nSecond paragraph."),
            "{got}"
        );
    }

    #[test]
    fn view_of_json_or_other_shapes_stands_down() {
        assert!(view("{\"title\":\"x\"}").is_none());
        assert!(view("").is_none());
    }

    const CHECKS: &str = "build\tpass\t1m2s\thttps://ci/1\nlint\tpass\t10s\thttps://ci/2\ntest (ubuntu)\tfail\t3m\thttps://ci/3\ntest (mac)\tpending\t0\thttps://ci/4\ndocs\tskipping\t0\thttps://ci/5\ncodeql\tpass\t5m\thttps://ci/6\n";

    #[test]
    fn checks_keep_failing_and_pending_rows_with_urls_and_count_the_rest() {
        let got = format(&argv("gh pr checks"), CHECKS).unwrap();
        assert!(
            got.starts_with("6 checks: 3 pass, 1 fail, 1 pending, 1 skipping"),
            "{got}"
        );
        assert!(
            got.contains("test (ubuntu)\tfail\t3m\thttps://ci/3"),
            "{got}"
        );
        assert!(got.contains("test (mac)\tpending"), "{got}");
        assert!(!got.contains("lint") && !got.contains("codeql"), "{got}");
    }

    #[test]
    fn checks_with_an_unknown_state_stand_down() {
        assert!(checks("a\tweird\t1s\thttps://x\n").is_none());
    }

    fn log() -> String {
        let mut s = String::new();
        for i in 0..30 {
            s.push_str(&format!(
                "build\tSet up job\t2026-10-15T10:00:{i:02}.1234567Z line {i}\n"
            ));
        }
        for i in 0..12 {
            s.push_str(&format!(
                "build\tRun tests\t2026-10-15T10:01:{i:02}.1234567Z compiling crate {i}\n"
            ));
        }
        s.push_str("build\tRun tests\t2026-10-15T10:02:00.0000000Z test a ... ok\n");
        s.push_str(
            "build\tRun tests\t2026-10-15T10:02:01.0000000Z ##[error]test b failed: expected 1\n",
        );
        s.push_str("build\tRun tests\t2026-10-15T10:02:02.0000000Z summary line\n");
        for i in 0..12 {
            s.push_str(&format!(
                "build\tRun tests\t2026-10-15T10:03:{i:02}.1234567Z tail noise {i}\n"
            ));
        }
        s
    }

    #[test]
    fn run_log_keeps_the_error_with_context_and_counts_quiet_steps() {
        let raw = log();
        let got = format(&argv("gh run view 7 --log"), &raw).unwrap();
        assert!(got.contains("## build / Run tests"), "{got}");
        assert!(got.contains("##[error]test b failed: expected 1"), "{got}");
        assert!(
            got.contains("test a ... ok") && got.contains("summary line"),
            "context kept: {got}"
        );
        assert!(!got.contains("2026-10-15T"), "timestamps dropped: {got}");
        assert!(got.contains("1 steps without errors (30 lines)"), "{got}");
        assert!(got.lines().count() < raw.lines().count() / 2, "{got}");
    }

    #[test]
    fn warnings_in_a_quiet_looking_step_are_kept_not_counted_away() {
        let raw = "b\tbuild\t2026-10-15T10:00:00.0Z warning: unused import\nb\tbuild\t2026-10-15T10:00:01.0Z ok\n";
        let got = run_log(raw).unwrap();
        assert!(got.contains("warning: unused import"), "{got}");
    }

    #[test]
    fn output_that_is_not_shorter_is_left_to_the_rule() {
        assert!(format(&argv("gh pr checks"), "a\tfail\t1s\thttps://x\n").is_none());
        assert!(format(&argv("gh repo view"), VIEW).is_none());
    }
}

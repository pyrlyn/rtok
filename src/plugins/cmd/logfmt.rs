// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Field summary of an omitted run of logfmt lines (T683).
//!
//! The head/tail cut drops the middle of a log. When that middle is `key=value` lines, the
//! marker says what the dropped lines shared and what changed: constant pairs once, then
//! each varying key with its first and last value. The agent learns the service, level and
//! route of the hidden run without an `expand`. Idea studied in caveman (field summary of
//! a collapsed run); no code copied.

/// Shorter runs are cheaper to read than to summarise.
const MIN_RUN: usize = 8;
/// Keys listed in one summary; a wider line is not a log worth describing.
const MAX_KEYS: usize = 12;
/// A varying value longer than this prints as its key alone.
const MAX_VALUE: usize = 24;

/// A `key=value` pair and where it sits in its line.
struct Pair<'a> {
    key: &'a str,
    value: &'a str,
    start: usize,
    end: usize,
}

/// The `key=value` pairs of `line`, values as written (a `"…"` value keeps its quotes).
fn pairs(line: &str) -> Vec<Pair<'_>> {
    let b = line.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        while i < b.len() && b[i].is_ascii_whitespace() {
            i += 1;
        }
        let start = i;
        while i < b.len() && !b[i].is_ascii_whitespace() && b[i] != b'=' {
            i += 1;
        }
        let key = &line[start..i];
        if i < b.len() && b[i] == b'=' && is_key(key) {
            let v0 = i + 1;
            i = v0;
            if b.get(i) == Some(&b'"') {
                i += 1;
                while i < b.len() && b[i] != b'"' {
                    i += if b[i] == b'\\' { 2 } else { 1 };
                }
                i += 1;
            } else {
                while i < b.len() && !b[i].is_ascii_whitespace() {
                    i += 1;
                }
            }
            let end = i.min(b.len());
            out.push(Pair {
                key,
                value: &line[v0..end],
                start,
                end,
            });
        } else {
            while i < b.len() && !b[i].is_ascii_whitespace() {
                i += 1;
            }
        }
    }
    out
}

fn fields(line: &str) -> Vec<(&str, &str)> {
    pairs(line).into_iter().map(|p| (p.key, p.value)).collect()
}

fn is_key(k: &str) -> bool {
    k.starts_with(|c: char| c.is_ascii_alphabetic() || c == '_')
        && k.chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-'))
}

/// `fields: svc=api level=info; varying: ts=T1..T2 dur_ms=10..16` for a run of logfmt lines,
/// or `None` when the run is short or its lines share no key.
pub fn summary(run: &[String]) -> Option<String> {
    if run.len() < MIN_RUN {
        return None;
    }
    // (key, first, last, constant) for keys present in every line so far.
    let mut cols: Vec<(&str, &str, &str, bool)> = fields(&run[0])
        .into_iter()
        .map(|(k, v)| (k, v, v, true))
        .collect();
    for line in &run[1..] {
        let f = fields(line);
        cols.retain_mut(|c| match f.iter().find(|(k, _)| *k == c.0) {
            Some((_, v)) => {
                c.3 &= *v == c.1;
                c.2 = v;
                true
            }
            None => false,
        });
        if cols.is_empty() {
            return None;
        }
    }
    cols.truncate(MAX_KEYS);
    let constant: Vec<String> = cols
        .iter()
        .filter(|c| c.3)
        .map(|c| format!("{}={}", c.0, c.1))
        .collect();
    let varying: Vec<String> = cols
        .iter()
        .filter(|c| !c.3)
        .map(|c| {
            if c.1 == c.2 || c.1.len().max(c.2.len()) > MAX_VALUE {
                c.0.to_string()
            } else {
                format!("{}={}..{}", c.0, c.1, c.2)
            }
        })
        .collect();
    let mut parts = Vec::new();
    if !constant.is_empty() {
        parts.push(format!("fields: {}", constant.join(" ")));
    }
    if !varying.is_empty() {
        parts.push(format!("varying: {}", varying.join(" ")));
    }
    Some(parts.join("; "))
}

/// A line needs this many pairs to count as a logfmt record.
const MIN_PAIRS: usize = 3;

/// Strip from every record the pairs all records share, and return them as one header.
/// `anchor` lines (errors, warnings) stay verbatim and do not vote. Nothing changes unless
/// at least [`MIN_RUN`] records, three quarters of the non-anchor lines, share a pair.
/// Lossless as a view: the archive keeps the raw lines and the header restates the pairs.
pub fn factor(lines: &mut [String], anchor: impl Fn(&str) -> bool) -> Option<String> {
    let rec: Vec<usize> = (0..lines.len())
        .filter(|&i| !anchor(&lines[i]) && pairs(&lines[i]).len() >= MIN_PAIRS)
        .collect();
    let plain = lines.iter().filter(|l| !anchor(l)).count();
    if rec.len() < MIN_RUN || rec.len() * 4 < plain * 3 {
        return None;
    }
    let mut common: Vec<(String, String)> = fields(&lines[rec[0]])
        .into_iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();
    for &i in &rec[1..] {
        let f = fields(&lines[i]);
        common.retain(|(k, v)| f.iter().any(|(k2, v2)| k2 == k && v2 == v));
        if common.is_empty() {
            return None;
        }
    }
    common.truncate(MAX_KEYS);
    for &i in &rec {
        let line = &lines[i];
        let mut out = line.clone();
        for p in pairs(line).iter().rev() {
            if common.iter().any(|(k, v)| k == p.key && v == p.value) {
                let end = (p.end..out.len())
                    .find(|&j| !out.as_bytes()[j].is_ascii_whitespace())
                    .unwrap_or(out.len());
                out.replace_range(p.start..end, "");
            }
        }
        let out = out.trim_end().to_string();
        if !out.is_empty() {
            lines[i] = out;
        }
    }
    let list: Vec<String> = common.iter().map(|(k, v)| format!("{k}={v}")).collect();
    Some(format!("[every record: {}]", list.join(" ")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(n: usize) -> Vec<String> {
        (0..n)
            .map(|i| format!("ts={i} level=info msg=\"a b\" id=r{i} tail"))
            .collect()
    }

    #[test]
    fn fields_reads_bare_and_quoted_values() {
        assert_eq!(
            fields(r#"2026 INFO a=1 msg="x \" y=z" b= =c d"#),
            vec![("a", "1"), ("msg", r#""x \" y=z""#), ("b", "")]
        );
    }

    #[test]
    fn constants_print_once_and_varying_keys_show_a_range() {
        let s = summary(&run(10)).unwrap();
        assert_eq!(
            s,
            r#"fields: level=info msg="a b"; varying: ts=0..9 id=r0..r9"#
        );
    }

    #[test]
    fn a_key_missing_from_one_line_is_left_out() {
        let mut r = run(10);
        r[4] = "ts=4 level=info".into();
        let s = summary(&r).unwrap();
        assert!(!s.contains("id=") && !s.contains("msg="), "{s}");
    }

    #[test]
    fn short_runs_and_plain_text_have_no_summary() {
        assert!(summary(&run(MIN_RUN - 1)).is_none());
        let plain: Vec<String> = (0..20).map(|i| format!("line {i}")).collect();
        assert!(summary(&plain).is_none());
    }

    #[test]
    fn long_varying_values_print_the_key_only() {
        let r: Vec<String> = (0..10)
            .map(|i| format!("k={}{i}", "x".repeat(30)))
            .collect();
        assert_eq!(summary(&r).unwrap(), "varying: k");
    }

    fn records(n: usize) -> Vec<String> {
        (0..n)
            .map(|i| format!("ts={i} level=info svc=api msg=\"a b\" id=r{i}"))
            .collect()
    }

    #[test]
    fn factor_strips_shared_pairs_and_keeps_anchors_verbatim() {
        let mut lines = records(10);
        lines[5] = "ts=5 level=error svc=api msg=\"boom\" id=r5".into();
        let raw = lines.clone();
        let header = factor(&mut lines, |l| l.contains("level=error")).unwrap();
        assert_eq!(header, r#"[every record: level=info svc=api msg="a b"]"#);
        assert_eq!(lines[0], "ts=0 id=r0");
        assert_eq!(lines[5], raw[5], "anchor stays whole");
    }

    #[test]
    fn factor_leaves_short_or_mixed_output_alone() {
        let mut few = records(MIN_RUN - 1);
        assert!(factor(&mut few, |_| false).is_none());
        let mut mixed: Vec<String> = (0..20).map(|i| format!("plain {i}")).collect();
        mixed.extend(records(8));
        assert!(factor(&mut mixed, |_| false).is_none());
        let mut distinct: Vec<String> = (0..10).map(|i| format!("a={i} b={i} c={i}")).collect();
        assert!(factor(&mut distinct, |_| false).is_none());
    }

    #[test]
    fn a_varying_key_with_equal_ends_prints_no_misleading_range() {
        let r: Vec<String> = (0..10)
            .map(|i| {
                format!(
                    "k={} j=a",
                    [4, 5, 6][i % 3] * (i < 9) as usize + 4 * (i == 9) as usize
                )
            })
            .collect();
        let s = summary(&r).unwrap();
        assert!(s.contains("varying: k") && !s.contains("k=4..4"), "{s}");
    }
}

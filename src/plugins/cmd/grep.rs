// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `grep` / `rg` / `git grep` hit lists (T673): each path printed once with its matches as
//! `line:text` beneath it, the indentation the matches share dropped, a long line cut to a
//! window around the hit, and a per-file budget that ends in `… +N more`. Idea studied in
//! sqz (path once, `line:text`) and lean-ctx (per-file cap); no code copied.
//!
//! Every function returns `None` when the output is not the `path:line:text` shape this
//! knows, so the caller falls back to the grep rule instead of guessing. The raw bytes
//! stay behind `rtok expand <id>`.

use regex::Regex;
use std::collections::BTreeMap;

/// Matches kept per file with their text.
const PER_FILE: usize = 12;
/// Output lines with match text; files after that are listed as bare `path:line,line` anchors, so
/// every path and line number survives a wide search.
const BUDGET: usize = 16;
/// Line numbers listed for one anchor-only file.
const ANCHORS: usize = 12;
/// Chars kept either side of the hit in a long line.
const WINDOW: usize = 60;

/// Flags that change the output shape (counts, file lists, context blocks): not ours.
const SHAPE_FLAGS: &[&str] = &[
    "-c",
    "-l",
    "-L",
    "-q",
    "-o",
    "-A",
    "-B",
    "-C",
    "-f",
    "-z",
    "-Z",
    "--count",
    "--files-with-matches",
    "--files-without-match",
    "--only-matching",
    "--context",
    "--after-context",
    "--before-context",
    "--null",
    "--heading",
    "--json",
    "--files",
    "--no-filename",
    "-h",
    "--file",
];

/// Short flags that take a value in the next argv slot.
const VALUE_SHORT: &str = "mgtTdDM";

/// Long flags whose value is the next argv slot (`--type rust`), so it is not the pattern.
const VALUE_LONG: &[&str] = &[
    "type",
    "type-not",
    "glob",
    "iglob",
    "max-count",
    "include",
    "exclude",
    "exclude-dir",
    "max-depth",
    "threads",
    "ignore-file",
    "replace",
    "sort",
    "sortr",
];

/// Regroup a `path:line:text` hit list. `None` for any other shape.
pub fn group(argv: &[String], output: &str) -> Option<String> {
    let (flags, pattern) = parse_argv(argv)?;
    let numbered = flags.contains('n');
    let re = pattern.as_deref().and_then(|p| build_regex(&flags, p));

    let mut files: BTreeMap<&str, Vec<(Option<&str>, &str)>> = BTreeMap::new();
    let mut other: Vec<&str> = Vec::new();
    for line in output.lines() {
        if line.is_empty() {
            continue;
        }
        if line.starts_with("Binary file ")
            || line.starts_with("grep: ")
            || line.starts_with("rg: ")
        {
            other.push(line);
            continue;
        }
        let (path, rest) = line.split_once(':')?;
        if path.is_empty() || path.contains(char::is_whitespace) {
            return None;
        }
        let (num, text) = if numbered {
            let (n, t) = rest.split_once(':')?;
            if n.is_empty() || !n.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            (Some(n), t)
        } else {
            (None, rest)
        };
        files.entry(path).or_default().push((num, text));
    }
    if files.is_empty() {
        return None;
    }

    let mut out: Vec<String> = Vec::new();
    let mut used = 0usize;
    for (path, hits) in &files {
        if used >= BUDGET {
            out.push(anchors(path, hits));
            continue;
        }
        let before = out.len();
        let shown = &hits[..hits.len().min(PER_FILE)];
        let indent = common_indent(shown);
        let body: Vec<String> = shown
            .iter()
            .map(|(num, text)| {
                let text = window(&text[indent.min(text.len())..], re.as_ref());
                match num {
                    Some(n) => format!("{n}:{text}"),
                    None => text,
                }
            })
            .collect();
        let more = hits.len() - shown.len();
        if hits.len() == 1 {
            out.push(format!("{path}:{}", body[0]));
        } else {
            out.push((*path).to_string());
            out.extend(body);
            if more > 0 {
                out.push(format!("… +{more} more"));
            }
        }
        used += out.len() - before;
    }
    out.extend(other.iter().map(|l| (*l).to_string()));
    let joined = out.join("\n");
    (joined.len() < output.trim_end().len()).then_some(joined)
}

/// `path:12,40,41` — where the hits are, without their text.
fn anchors(path: &str, hits: &[(Option<&str>, &str)]) -> String {
    let nums: Vec<&str> = hits.iter().filter_map(|(n, _)| *n).collect();
    if nums.is_empty() {
        return format!("{path} ×{}", hits.len());
    }
    let mut list = nums[..nums.len().min(ANCHORS)].join(",");
    if nums.len() > ANCHORS {
        list.push_str(&format!(",…+{}", nums.len() - ANCHORS));
    }
    format!("{path}:{list}")
}

/// The flag letters (all short flags seen, `i`, `F`, `E`, `n` …) and the pattern.
/// `None` when a flag changes the output shape or no pattern can be found.
fn parse_argv(argv: &[String]) -> Option<(String, Option<String>)> {
    let mut args = argv.iter().map(String::as_str).peekable();
    let bin = args.next()?;
    let git = super::formatters::cmd_stem(bin) == "git";
    if git {
        args.next()?; // `grep`
    }
    let mut flags = String::new();
    let mut patterns: Vec<String> = Vec::new();
    let mut positional: Option<String> = None;
    while let Some(a) = args.next() {
        if SHAPE_FLAGS.contains(&a) {
            return None;
        }
        if let Some(long) = a.strip_prefix("--") {
            match long {
                "line-number" => flags.push('n'),
                "ignore-case" => flags.push('i'),
                "fixed-strings" => flags.push('F'),
                "extended-regexp" => flags.push('E'),
                "perl-regexp" => flags.push('P'),
                l if VALUE_LONG.contains(&l) => {
                    args.next();
                }
                "regexp" => patterns.push(args.next()?.to_string()),
                l if l.starts_with("regexp=") => patterns.push(l["regexp=".len()..].to_string()),
                _ => {}
            }
            continue;
        }
        if let Some(short) = a.strip_prefix('-').filter(|s| !s.is_empty()) {
            let mut chars = short.chars();
            while let Some(c) = chars.next() {
                if SHAPE_FLAGS.contains(&format!("-{c}").as_str()) {
                    return None;
                }
                if c == 'e' {
                    let rest: String = chars.by_ref().collect();
                    patterns.push(if rest.is_empty() {
                        args.next()?.to_string()
                    } else {
                        rest
                    });
                    break;
                }
                if VALUE_SHORT.contains(c) {
                    if chars.as_str().is_empty() {
                        args.next();
                    }
                    break;
                }
                flags.push(c);
            }
            continue;
        }
        if positional.is_none() {
            positional = Some(a.to_string());
        }
    }
    // `rg` numbers lines only on request when stdout is not a terminal, like grep.
    let pattern = if patterns.is_empty() {
        positional
    } else {
        Some(patterns.join("|"))
    };
    Some((flags, pattern))
}

/// The pattern as a `Regex`, or `None` when its dialect is not one the `regex` crate
/// reads the same way (a BRE with `\|`, a `-P` lookaround): no window is cut then.
fn build_regex(flags: &str, pattern: &str) -> Option<Regex> {
    let mut p = if flags.contains('F') {
        regex::escape(pattern)
    } else {
        if pattern.contains('\\') && !flags.contains('E') && !flags.contains('P') {
            return None;
        }
        pattern.to_string()
    };
    if flags.contains('i') {
        p = format!("(?i){p}");
    }
    Regex::new(&p).ok()
}

/// Chars of leading whitespace every shown hit shares; relative indentation stays.
fn common_indent(hits: &[(Option<&str>, &str)]) -> usize {
    hits.iter()
        .filter(|(_, t)| !t.trim().is_empty())
        .map(|(_, t)| t.bytes().take_while(|b| matches!(b, b' ' | b'\t')).count())
        .min()
        .unwrap_or(0)
}

/// `text` cut to `WINDOW` chars either side of the first hit; whole when it is short or
/// the pattern is unknown or not found in it.
fn window(text: &str, re: Option<&Regex>) -> String {
    let Some(m) = re.and_then(|re| re.find(text)) else {
        return text.to_string();
    };
    let start = text[..m.start()]
        .char_indices()
        .rev()
        .nth(WINDOW - 1)
        .map_or(0, |(i, _)| i);
    let end = text[m.end()..]
        .char_indices()
        .nth(WINDOW)
        .map_or(text.len(), |(i, _)| m.end() + i);
    if start == 0 && end == text.len() {
        return text.to_string();
    }
    format!(
        "{}{}{}",
        if start > 0 { "…" } else { "" },
        &text[start..end],
        if end < text.len() { "…" } else { "" }
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(s: &str) -> Vec<String> {
        s.split_whitespace().map(str::to_string).collect()
    }

    #[test]
    fn prints_each_path_once_and_keeps_every_path() {
        let raw =
            "src/a.rs:10:    let x = hit;\nsrc/a.rs:20:    let y = hit;\nsrc/b.rs:3:fn hit() {}\n";
        let got = group(&argv("grep -rn hit src"), raw).unwrap();
        assert_eq!(
            got,
            "src/a.rs\n10:let x = hit;\n20:let y = hit;\nsrc/b.rs:3:fn hit() {}"
        );
    }

    #[test]
    fn files_are_path_sorted() {
        let raw = "z.rs:1:a\nz.rs:2:a\na.rs:1:a\na.rs:2:a\n";
        let got = group(&argv("rg -n a"), raw).unwrap();
        assert!(
            got.find("a.rs").unwrap() < got.find("z.rs").unwrap(),
            "{got}"
        );
    }

    #[test]
    fn per_file_budget_ends_in_a_count() {
        let raw: String = (1..=30).map(|n| format!("big.rs:{n}:hit {n}\n")).collect();
        let got = group(&argv("grep -rn hit ."), &raw).unwrap();
        assert!(got.contains("… +18 more"), "{got}");
        assert!(
            got.contains("12:hit 12") && !got.contains("13:hit 13"),
            "{got}"
        );
    }

    #[test]
    fn files_past_the_budget_keep_path_and_line_numbers() {
        let raw: String = (1..=40)
            .map(|n| {
                format!(
                    "f{n:02}.rs:{n}:fn main() {{}}\nf{n:02}.rs:{}:fn main() {{}}\n",
                    n + 100
                )
            })
            .collect();
        let got = group(&argv("grep -rn main ."), &raw).unwrap();
        for n in 1..=40 {
            assert!(
                got.contains(&format!("f{n:02}.rs")),
                "f{n:02}.rs lost:\n{got}"
            );
        }
        assert!(got.contains("f40.rs:40,140"), "{got}");
        assert!(got.lines().count() < 80, "{got}");
    }

    #[test]
    fn long_line_is_cut_around_the_match() {
        let pad = "x".repeat(300);
        let raw = format!("a.rs:1:{pad}NEEDLE{pad}\na.rs:2:NEEDLE\n");
        let got = group(&argv("grep -rn NEEDLE ."), &raw).unwrap();
        let first = got.lines().nth(1).unwrap();
        assert!(first.contains("NEEDLE") && first.len() < 160, "{first}");
        assert!(first.starts_with("1:…") && first.ends_with('…'), "{first}");
    }

    #[test]
    fn case_insensitive_pattern_still_finds_the_window() {
        let pad = "x".repeat(200);
        let raw = format!("a.rs:1:{pad}Needle{pad}\na.rs:2:needle\n");
        let got = group(&argv("grep -rni needle ."), &raw).unwrap();
        assert!(got.lines().nth(1).unwrap().contains("Needle"), "{got}");
    }

    #[test]
    fn other_shapes_fall_back() {
        assert!(group(&argv("grep -rc hit src"), "a.rs:3\nb.rs:4\n").is_none());
        assert!(group(&argv("rg -l hit"), "a.rs\nb.rs\n").is_none());
        assert!(group(&argv("grep -rn -A2 hit ."), "a.rs:1:hit\na.rs-2-x\n").is_none());
        assert!(group(&argv("grep -n hit f"), "3:hit\n").is_none());
    }

    #[test]
    fn stderr_and_binary_lines_survive() {
        let raw = "a.rs:1:hit\na.rs:2:hit\nBinary file x.bin matches\ngrep: gone: No such file or directory\n";
        let got = group(&argv("grep -rn hit ."), raw).unwrap();
        assert!(got.contains("Binary file x.bin matches"), "{got}");
        assert!(
            got.contains("grep: gone: No such file or directory"),
            "{got}"
        );
    }

    #[test]
    fn never_longer_than_raw() {
        assert!(group(&argv("grep -rn hit ."), "a.rs:1:hit\n").is_none());
    }

    #[test]
    fn git_grep_and_unnumbered_output() {
        let raw = "src/a.rs:let hit = 1;\nsrc/a.rs:let hit = 2;\n";
        let got = group(&argv("git grep hit"), raw).unwrap();
        assert_eq!(got, "src/a.rs\nlet hit = 1;\nlet hit = 2;");
    }
}

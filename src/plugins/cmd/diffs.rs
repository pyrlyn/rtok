// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `git diff` and `git show` in two tiers (T681).
//!
//! A diff that fits the budget keeps every changed line and one line of context around it;
//! longer unchanged runs fold to `⋯ N unchanged`. A bigger one becomes a `path +A -D` line per
//! file with its hunk headers (git puts the enclosing function there), and only the smallest
//! files keep their hunks. Every file path is in the output either way. Hunks are read by the
//! line counts in their `@@` header, so a deleted line that starts with `--` cannot be taken for
//! a file header. Anything that does not parse as a plain `diff --git` stream (a combined merge
//! diff, stray text between hunks) returns `None` and the caller's older filter applies.
//! Written for rtok from the output shape alone; no code copied.

use regex::Regex;
use std::sync::OnceLock;

/// Raw diffs up to this many lines stay whole (tier one).
const SMALL: usize = 120;
/// Tier two shows the hunks of at most this many files...
const DETAIL_FILES: usize = 3;
/// ...and of those only while their folded bodies fit this many bytes together (docs have lines
/// of a thousand characters, so lines alone say nothing).
const DETAIL_BYTES: usize = 2500;
/// The function text git appends to a hunk header is cut at this many characters.
const HEAD_CONTEXT: usize = 60;

struct Hunk<'a> {
    head: &'a str,
    body: Vec<&'a str>,
}

struct File<'a> {
    header: Vec<&'a str>,
    hunks: Vec<Hunk<'a>>,
}

struct Parsed<'a> {
    preamble: Vec<&'a str>,
    files: Vec<File<'a>>,
}

fn counts(head: &str) -> Option<(usize, usize)> {
    static R: OnceLock<Regex> = OnceLock::new();
    let r = R.get_or_init(|| {
        Regex::new(r"^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@").expect("static pattern")
    });
    let c = r.captures(head)?;
    let n = |i| c.get(i).map_or(Some(1), |m| m.as_str().parse().ok());
    Some((n(1)?, n(2)?))
}

fn parse(output: &str) -> Option<Parsed<'_>> {
    let lines: Vec<&str> = output.lines().collect();
    let mut at = lines.iter().position(|l| l.starts_with("diff --git "))?;
    let preamble = lines[..at].to_vec();
    let mut files: Vec<File> = Vec::new();
    while at < lines.len() {
        if !lines[at].starts_with("diff --git ") {
            return None;
        }
        let mut file = File {
            header: vec![lines[at]],
            hunks: Vec::new(),
        };
        at += 1;
        while at < lines.len()
            && !lines[at].starts_with("@@ ")
            && !lines[at].starts_with("diff --git ")
        {
            file.header.push(lines[at]);
            at += 1;
        }
        while at < lines.len() && lines[at].starts_with("@@ ") {
            let head = lines[at];
            let (mut old, mut new) = counts(head)?;
            at += 1;
            let mut body = Vec::new();
            while old > 0 || new > 0 {
                let line = *lines.get(at)?;
                match line.as_bytes().first() {
                    Some(b' ') => (old, new) = (old.checked_sub(1)?, new.checked_sub(1)?),
                    Some(b'-') => old = old.checked_sub(1)?,
                    Some(b'+') => new = new.checked_sub(1)?,
                    _ if line.is_empty() => (old, new) = (old.checked_sub(1)?, new.checked_sub(1)?),
                    _ => return None,
                }
                body.push(line);
                at += 1;
            }
            while lines.get(at).is_some_and(|l| l.starts_with('\\')) {
                body.push(lines[at]);
                at += 1;
            }
            file.hunks.push(Hunk { head, body });
        }
        files.push(file);
    }
    Some(Parsed { preamble, files })
}

fn changed(h: &Hunk, sign: u8) -> usize {
    h.body
        .iter()
        .filter(|l| l.as_bytes().first() == Some(&sign))
        .count()
}

/// The path a file's header names: `b/` side of the `diff --git` line, `old -> new` for a rename.
fn path_of(file: &File) -> String {
    let first = file.header[0].trim_start_matches("diff --git ");
    let Some(split) = first.rfind(" b/") else {
        return first.to_string();
    };
    let old = first[..split].trim_start_matches("a/");
    let new = &first[split + 3..];
    if old == new {
        new.to_string()
    } else {
        format!("{old} -> {new}")
    }
}

/// What the header says besides the path: new, deleted, renamed, binary, mode change.
fn note_of(file: &File) -> String {
    let has = |p: &str| file.header.iter().any(|l| l.starts_with(p));
    let mut notes = Vec::new();
    if has("new file mode") {
        notes.push("new");
    }
    if has("deleted file mode") {
        notes.push("deleted");
    }
    if has("Binary files") || has("GIT binary patch") {
        notes.push("binary");
    }
    if has("old mode") {
        notes.push("mode");
    }
    if notes.is_empty() {
        String::new()
    } else {
        format!(" ({})", notes.join(", "))
    }
}

/// Header lines worth keeping: `index` and the `diff --git` line (when `+++` names the file)
/// and a `--- a/x` that matches `+++ b/x` are bookkeeping.
fn header_lines(file: &File) -> Vec<String> {
    let named = file.header.iter().any(|l| l.starts_with("+++ "));
    let mut out = Vec::new();
    for (i, l) in file.header.iter().enumerate() {
        if l.starts_with("index ") || (i == 0 && named) {
            continue;
        }
        if let Some(a) = l.strip_prefix("--- a/")
            && file
                .header
                .get(i + 1)
                .and_then(|n| n.strip_prefix("+++ b/"))
                == Some(a)
        {
            continue;
        }
        out.push(l.to_string());
    }
    out
}

/// A hunk body with one line of context around each change; longer runs fold.
fn fold(body: &[&str]) -> Vec<String> {
    let is_ctx = |l: &str| l.starts_with(' ') || l.is_empty();
    let first = body.iter().position(|l| !is_ctx(l));
    let last = body.iter().rposition(|l| !is_ctx(l));
    let (Some(first), Some(last)) = (first, last) else {
        return body.iter().map(|l| l.to_string()).collect();
    };
    let mut out: Vec<String> = Vec::new();
    let mut i = 0;
    while i < body.len() {
        if !is_ctx(body[i]) {
            out.push(body[i].to_string());
            i += 1;
            continue;
        }
        let mut j = i;
        while j < body.len() && is_ctx(body[j]) {
            j += 1;
        }
        let run = &body[i..j];
        let (head, tail) = match (i < first, j > last) {
            (true, _) => (0, 1),
            (_, true) => (1, 0),
            _ => (1, 1),
        };
        let hidden = run.len().saturating_sub(head + tail);
        if hidden >= 2 {
            out.extend(run[..head].iter().map(|l| l.to_string()));
            out.push(format!(" ⋯ {hidden} unchanged"));
            out.extend(run[run.len() - tail..].iter().map(|l| l.to_string()));
        } else {
            out.extend(run.iter().map(|l| l.to_string()));
        }
        i = j;
    }
    out
}

/// The hunk header with git's trailing function text cut short.
fn head(h: &Hunk) -> String {
    let Some(end) = h.head[2..].find("@@").map(|i| i + 4) else {
        return h.head.to_string();
    };
    let (range, context) = h.head.split_at(end);
    match context.char_indices().nth(HEAD_CONTEXT) {
        Some((cut, _)) => format!("{range}{}…", &context[..cut]),
        None => h.head.to_string(),
    }
}

fn whole(file: &File) -> Vec<String> {
    let mut out = header_lines(file);
    for h in &file.hunks {
        out.push(head(h));
        out.extend(fold(&h.body));
    }
    out
}

fn tier_two(parsed: &Parsed) -> Vec<String> {
    let sizes: Vec<usize> = parsed
        .files
        .iter()
        .map(|f| whole(f).iter().map(|l| l.len() + 1).sum())
        .collect();
    let mut order: Vec<usize> = (0..parsed.files.len()).collect();
    order.sort_by_key(|&i| sizes[i]);
    let mut detail = vec![false; parsed.files.len()];
    let mut used = 0;
    for &i in order.iter().take(DETAIL_FILES) {
        if parsed.files[i].hunks.is_empty() {
            continue;
        }
        if used + sizes[i] > DETAIL_BYTES {
            break;
        }
        used += sizes[i];
        detail[i] = true;
    }
    let (add, del): (usize, usize) = parsed.files.iter().fold((0, 0), |(a, d), f| {
        (
            a + f.hunks.iter().map(|h| changed(h, b'+')).sum::<usize>(),
            d + f.hunks.iter().map(|h| changed(h, b'-')).sum::<usize>(),
        )
    });
    let mut out = vec![format!("{} files, +{add} -{del}", parsed.files.len())];
    for (i, f) in parsed.files.iter().enumerate() {
        let (a, d): (usize, usize) = (
            f.hunks.iter().map(|h| changed(h, b'+')).sum(),
            f.hunks.iter().map(|h| changed(h, b'-')).sum(),
        );
        out.push(format!("{} +{a} -{d}{}", path_of(f), note_of(f)));
        if detail[i] {
            out.extend(
                whole(f)
                    .into_iter()
                    .filter(|l| !l.starts_with("+++ ") && !l.starts_with("--- ")),
            );
        } else {
            out.extend(f.hunks.iter().map(|h| format!("  {}", head(h))));
        }
    }
    out
}

/// `output` as a tiered diff, or `None` when it is not a plain `diff --git` stream or the
/// result would not be shorter.
pub fn tiered(output: &str) -> Option<String> {
    let parsed = parse(output)?;
    let mut out: Vec<String> = parsed.preamble.iter().map(|l| l.to_string()).collect();
    if output.lines().count() <= SMALL {
        for f in &parsed.files {
            out.extend(whole(f));
        }
    } else {
        out.extend(tier_two(&parsed));
    }
    let got = out.join("\n");
    (got.len() < output.len()).then_some(got)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(name: &str, body: &str) -> String {
        format!(
            "diff --git a/{name} b/{name}\nindex 1111111..2222222 100644\n--- a/{name}\n+++ b/{name}\n{body}"
        )
    }

    fn ctx(n: usize, tag: &str) -> String {
        (0..n).map(|i| format!(" {tag}{i}\n")).collect()
    }

    #[test]
    fn small_diff_keeps_changes_with_one_line_of_context() {
        let body = format!(
            "@@ -1,12 +1,12 @@ fn main\n{}-old\n+new\n{}",
            ctx(5, "a"),
            ctx(6, "b")
        );
        let raw = file("src/a.rs", &body);
        let got = tiered(&raw).unwrap();
        assert_eq!(
            got,
            "+++ b/src/a.rs\n@@ -1,12 +1,12 @@ fn main\n ⋯ 4 unchanged\n a4\n-old\n+new\n b0\n ⋯ 5 unchanged"
        );
    }

    #[test]
    fn interior_context_between_two_changes_folds_the_middle() {
        let body = format!("@@ -1,7 +1,7 @@\n-x\n+y\n{}-p\n+q\n", ctx(5, "m"));
        let got = tiered(&file("f.rs", &body)).unwrap();
        assert!(got.contains(" m0\n ⋯ 3 unchanged\n m4\n-p\n+q"), "{got}");
    }

    #[test]
    fn a_deleted_line_that_looks_like_a_file_header_stays_a_hunk_line() {
        // ` a`, `--- a/x` (a deleted line) and `+++ b/x` (an added one) are all hunk body.
        let raw = format!(
            "{}{}",
            file("f.txt", "@@ -1,2 +1,2 @@\n a\n--- a/x\n+++ b/x\n"),
            file("g.txt", "@@ -1 +1 @@\n-p\n+q\n")
        );
        let parsed = parse(&raw).unwrap();
        assert_eq!(parsed.files.len(), 2);
        assert_eq!(parsed.files[0].hunks[0].body, [" a", "--- a/x", "+++ b/x"]);
        assert_eq!(parsed.files[1].hunks[0].body, ["-p", "+q"]);
    }

    fn big() -> String {
        let mut raw = String::new();
        for i in 0..8 {
            let mut body = format!("@@ -10,40 +10,40 @@ fn func_{i}\n");
            body.push_str(&ctx(3, "c"));
            for k in 0..(i * 2 + 1) {
                body.push_str(&format!("-old{k}\n+new{k}\n"));
            }
            body.push_str(&ctx(40 - 3 - i * 2 - 1, "t"));
            raw.push_str(&file(&format!("crates/m{i}/src/lib.rs"), &body));
        }
        raw
    }

    #[test]
    fn big_diff_lists_every_path_with_counts_and_hunk_headers() {
        let raw = big();
        assert!(raw.lines().count() > SMALL);
        let got = tiered(&raw).unwrap();
        assert!(got.starts_with("8 files, +"), "{got}");
        for i in 0..8 {
            assert!(
                got.contains(&format!("crates/m{i}/src/lib.rs +")),
                "m{i} lost: {got}"
            );
            assert!(
                got.contains(&format!("@@ -10,40 +10,40 @@ fn func_{i}")),
                "{got}"
            );
        }
        // Only the smallest files keep their changed lines.
        assert!(got.contains("-old0\n+new0"), "{got}");
        assert!(!got.contains("old13"), "{got}");
        assert!(got.len() * 3 < raw.len(), "{} vs {}", got.len(), raw.len());
    }

    #[test]
    fn new_deleted_renamed_and_binary_files_say_so() {
        let mut raw = String::from("commit abc\nAuthor: a\n\n    msg\n\n");
        raw.push_str("diff --git a/n.rs b/n.rs\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/n.rs\n@@ -0,0 +1,2 @@\n+a\n+b\n");
        raw.push_str(
            "diff --git a/o.rs b/p.rs\nsimilarity index 90%\nrename from o.rs\nrename to p.rs\n",
        );
        raw.push_str("diff --git a/i.png b/i.png\nindex 1..2 100644\nBinary files a/i.png and b/i.png differ\n");
        raw.push_str(&big().replace("crates/m", "z/m"));
        let got = tiered(&raw).unwrap();
        assert!(
            got.starts_with("commit abc\nAuthor: a\n\n    msg\n\n"),
            "{got}"
        );
        assert!(got.contains("n.rs +2 -0 (new)"), "{got}");
        assert!(got.contains("o.rs -> p.rs +0 -0"), "{got}");
        assert!(got.contains("i.png +0 -0 (binary)"), "{got}");
    }

    #[test]
    fn combined_or_malformed_diffs_stand_down() {
        assert!(tiered("diff --cc f\n@@@ -1 -1 +1 @@@\n").is_none());
        assert!(tiered("nothing here\n").is_none());
        assert!(tiered(&file("f", "@@ -1,2 +1,2 @@\n a\ngarbage\n")).is_none());
    }
}

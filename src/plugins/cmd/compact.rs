// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Structured formatters for `cargo test`, `cargo build`, `git status`, `git diff` and
//! `ls -l` (T661). Ideas studied in rtk (github.com/rtk-ai/rtk, Apache-2.0) and written
//! again in rtok's style: aggregate per-suite `test result:` lines into one, keep each
//! failure's panic body, drop cargo progress lines, print `git status` as porcelain
//! codes, and list `ls -l` as names with sizes. No rtk code is copied.
//!
//! Every function returns `None` when the input is not the shape it knows, so the caller
//! falls back to the previous behaviour instead of guessing.

/// Lines cargo prints while it works; none carries a diagnostic.
const CARGO_PROGRESS: &[&str] = &[
    "Compiling ",
    "Checking ",
    "Downloading ",
    "Downloaded ",
    "Fresh ",
    "Blocking ",
    "Locking ",
    "Updating ",
    "Adding ",
    "Running ",
    "Doc-tests ",
];

fn is_progress(line: &str) -> bool {
    let t = line.trim_start();
    CARGO_PROGRESS.iter().any(|p| t.starts_with(p))
}

/// `Finished `dev` profile [unoptimized + debuginfo] target(s) in 3.2s` → `Finished dev`.
/// The wall-clock time and the profile flags say nothing about the result.
fn short_finished(line: &str) -> Option<String> {
    let t = line.trim_start().strip_prefix("Finished ")?;
    let profile = t.split('`').nth(1).unwrap_or("").trim();
    Some(if profile.is_empty() {
        "Finished".to_string()
    } else {
        format!("Finished {profile}")
    })
}

/// `warning: `foo` (lib) generated 2 warnings` repeats the count of the blocks above it.
fn is_generated_count(line: &str) -> bool {
    line.starts_with("warning: `") && line.contains(" generated ") && line.contains("warning")
}

/// One `warning:` block: its headline, every `-->` location and the lint name when the
/// block (or an earlier one with the same headline shape) says it.
struct Warn {
    head: String,
    locs: Vec<String>,
    code: Option<String>,
}

enum Piece {
    Line(String),
    Warn(usize),
}

/// `#[warn(unused_imports)]` or a clippy `…/index.html#redundant_clone` link → the lint name.
fn lint_code(line: &str) -> Option<String> {
    if let Some(r) = line.split("#[warn(").nth(1) {
        return Some(r.split(')').next()?.to_string());
    }
    let r = line
        .split("rust-clippy/")
        .nth(1)?
        .split("index.html#")
        .nth(1)?;
    Some(format!("clippy::{}", r.split_whitespace().next()?))
}

/// The headline with its backticked names removed: `unused import: `a`` and `…: `b``
/// share one shape, so a warning without a lint note still joins its group.
fn head_shape(head: &str) -> String {
    head.split('`').step_by(2).collect()
}

/// `cargo build` / `check` / `clippy`: the diagnostic headline and its `-->` location, the
/// `could not compile` verdict, and a one-word `Finished <profile>` on success. Two or more
/// warnings of one lint fold into `lint ×N: first headline (loc, loc, …)` (T684): every
/// location stays, the repeated headlines are in the archive.
pub fn cargo_build(output: &str) -> Option<String> {
    let mut pieces: Vec<Piece> = Vec::new();
    let mut warns: Vec<Warn> = Vec::new();
    let mut open = false;
    for line in output.lines() {
        if is_progress(line) || is_generated_count(line) {
            continue;
        }
        if let Some(f) = short_finished(line) {
            open = false;
            pieces.push(Piece::Line(f));
        } else if line.starts_with("warning:") {
            open = true;
            warns.push(Warn {
                head: line.to_string(),
                locs: Vec::new(),
                code: None,
            });
            pieces.push(Piece::Warn(warns.len() - 1));
        } else if open && line.trim_start().starts_with("-->") {
            warns.last_mut()?.locs.push(line.to_string());
        } else if open && let Some(c) = lint_code(line) {
            warns.last_mut()?.code.get_or_insert(c);
        } else if ["error[", "error:", "-->", "warning:"]
            .iter()
            .any(|n| line.contains(n))
        {
            open = false;
            pieces.push(Piece::Line(line.to_string()));
        }
    }
    if pieces.is_empty() {
        return None;
    }
    let mut by_shape: std::collections::HashMap<String, String> = Default::default();
    for w in &warns {
        if let Some(c) = &w.code {
            by_shape
                .entry(head_shape(&w.head))
                .or_insert_with(|| c.clone());
        }
    }
    let lint = |w: &Warn| {
        w.code
            .clone()
            .or_else(|| by_shape.get(&head_shape(&w.head)).cloned())
    };
    let key = |w: &Warn| lint(w).unwrap_or_else(|| head_shape(&w.head));
    let mut groups: std::collections::HashMap<String, Vec<usize>> = Default::default();
    for (i, w) in warns.iter().enumerate() {
        groups.entry(key(w)).or_default().push(i);
    }
    let mut kept: Vec<String> = Vec::new();
    for piece in &pieces {
        let Piece::Warn(i) = piece else {
            if let Piece::Line(l) = piece {
                kept.push(l.clone());
            }
            continue;
        };
        let k = key(&warns[*i]);
        let members = &groups[&k];
        if members.len() < 2 {
            kept.push(warns[*i].head.clone());
            kept.extend(warns[*i].locs.iter().cloned());
        } else if members[0] == *i {
            let locs: Vec<&str> = members
                .iter()
                .flat_map(|&m| warns[m].locs.iter())
                .filter_map(|l| l.trim_start().strip_prefix("-->"))
                .map(str::trim)
                .collect();
            let head = warns[*i].head.trim_start_matches("warning: ");
            let label = match lint(&warns[*i]) {
                Some(c) => format!("{c} ×{}: {head}", members.len()),
                None => format!("{head} ×{}", members.len()),
            };
            kept.push(format!("{label} ({})", locs.join(", ")));
        }
    }
    Some(kept.join("\n"))
}

#[derive(Default)]
struct Totals {
    suites: u32,
    passed: u64,
    failed: u64,
    ignored: u64,
    filtered: u64,
}

impl Totals {
    /// `test result: ok. 3 passed; 0 failed; 1 ignored; 0 measured; 2 filtered out; …`
    fn add(&mut self, line: &str) -> bool {
        let Some(rest) = line.strip_prefix("test result: ") else {
            return false;
        };
        let mut seen = false;
        for part in rest.split(';') {
            let words: Vec<&str> = part.split_whitespace().collect();
            let (n, what) = match words.as_slice() {
                [.., n, w, "out"] | [.., n, w] => (n.parse::<u64>().ok(), *w),
                _ => continue,
            };
            let Some(n) = n else { continue };
            seen = true;
            match what {
                "passed" => self.passed += n,
                "failed" => self.failed += n,
                "ignored" => self.ignored += n,
                "filtered" => self.filtered += n,
                _ => {}
            }
        }
        if seen {
            self.suites += 1;
        }
        seen
    }

    fn line(&self) -> String {
        let mut parts = vec![format!("{} passed", self.passed)];
        if self.failed > 0 {
            parts.push(format!("{} failed", self.failed));
        }
        if self.ignored > 0 {
            parts.push(format!("{} ignored", self.ignored));
        }
        if self.filtered > 0 {
            parts.push(format!("{} filtered out", self.filtered));
        }
        let s = if self.suites == 1 { "" } else { "s" };
        format!(
            "cargo test: {} ({} suite{s})",
            parts.join(", "),
            self.suites
        )
    }
}

/// `cargo test`: one totals line for every suite, each failure's panic body (location,
/// message, `left`/`right`) and the names that failed. A build that never reached the
/// tests is a build log, so it goes to [`cargo_build`].
pub fn cargo_test(output: &str) -> Option<String> {
    let mut totals = Totals::default();
    let mut failed: Vec<&str> = Vec::new();
    let mut bodies: Vec<String> = Vec::new();
    let mut in_failures = false;
    let mut in_body = false;
    for line in output.lines() {
        if totals.add(line) {
            in_failures = false;
            in_body = false;
            continue;
        }
        if let Some(name) = line
            .strip_prefix("test ")
            .and_then(|l| l.strip_suffix(" ... FAILED"))
        {
            failed.push(name);
            continue;
        }
        if line == "failures:" {
            // The first `failures:` opens the bodies, the second lists names we have.
            in_body = !in_failures;
            in_failures = true;
            continue;
        }
        if in_failures && in_body {
            if line.starts_with("---- ") || line.trim().is_empty() {
                continue;
            }
            if line.starts_with("note: run with `RUST_BACKTRACE") {
                continue;
            }
            bodies.push(line.to_string());
        }
    }
    if totals.suites == 0 {
        let compile = output
            .lines()
            .any(|l| l.starts_with("error[") || l.starts_with("error:"));
        return if compile { cargo_build(output) } else { None };
    }
    let mut out = bodies;
    if !failed.is_empty() {
        out.push(format!("FAILED: {}", failed.join(", ")));
    }
    out.push(totals.line());
    Some(out.join("\n"))
}

/// `git status` long format → porcelain `XY path`: staged changes in the first column,
/// worktree changes in the second, `??` for untracked. Hints and section prose go; the
/// branch lines and `nothing to commit` stay.
pub fn git_status(output: &str) -> Option<String> {
    #[derive(PartialEq)]
    enum Section {
        None,
        Staged,
        Unstaged,
        Untracked,
        Unmerged,
    }
    const VERBS: &[(&str, char)] = &[
        ("modified:", 'M'),
        ("new file:", 'A'),
        ("deleted:", 'D'),
        ("renamed:", 'R'),
        ("copied:", 'C'),
        ("typechange:", 'T'),
    ];
    const UNMERGED: &[(&str, &str)] = &[
        ("both modified:", "UU"),
        ("both added:", "AA"),
        ("both deleted:", "DD"),
        ("added by us:", "AU"),
        ("added by them:", "UA"),
        ("deleted by us:", "DU"),
        ("deleted by them:", "UD"),
    ];
    const STATE_LINES: &[&str] = &[
        "On branch ",
        "Your branch ",
        "HEAD detached ",
        "nothing to commit",
        "You have unmerged paths",
        "All conflicts fixed",
        "You are currently ",
        "interactive rebase in progress",
        "Last command",
        "Next command",
        "No commits yet",
        "and have ",
    ];
    let mut out: Vec<String> = Vec::new();
    let mut section = Section::None;
    let mut entries = 0usize;
    for line in output.lines() {
        let head = line.trim();
        if head.is_empty() || (head.starts_with('(') && head.ends_with(')')) {
            continue;
        }
        if head.starts_with("Changes to be committed:") {
            section = Section::Staged;
        } else if head.starts_with("Changes not staged for commit:") {
            section = Section::Unstaged;
        } else if head.starts_with("Untracked files:") {
            section = Section::Untracked;
        } else if head.starts_with("Unmerged paths:") {
            section = Section::Unmerged;
        } else if head.starts_with("no changes added to commit")
            || head.starts_with("nothing added to commit but untracked")
        {
            // Restates the sections above.
        } else if line.starts_with(['\t', ' ']) && section != Section::None {
            let code = if section == Section::Untracked {
                Some("??".to_string())
            } else if section == Section::Unmerged {
                UNMERGED
                    .iter()
                    .find(|(v, _)| head.starts_with(v))
                    .map(|(_, c)| (*c).to_string())
            } else {
                VERBS
                    .iter()
                    .find(|(v, _)| head.starts_with(v))
                    .map(|(_, c)| {
                        if section == Section::Staged {
                            format!("{c} ")
                        } else {
                            format!(" {c}")
                        }
                    })
            };
            let code = code?;
            let path = match head.split_once(':') {
                Some((_, p)) if section != Section::Untracked => p.trim(),
                _ => head,
            };
            out.push(format!("{code} {path}"));
            entries += 1;
        } else if line.starts_with(['\t', ' ']) {
            return None;
        } else if STATE_LINES.iter().any(|p| head.starts_with(p)) {
            out.push(head.to_string());
        }
        // Any other top-level line (an echoed command, a wrapper banner) is dropped, as
        // the long-format filter this replaces did.
    }
    if out.is_empty() || (entries == 0 && section != Section::None) {
        return None;
    }
    Some(out.join("\n"))
}

/// `git diff`: hunks verbatim; per file only the `+++ b/path` header. `index` lines,
/// the `diff --git` line and a `--- a/path` that names the same file are bookkeeping.
/// Mode, rename, binary and `/dev/null` lines stay, so new, deleted, renamed and binary
/// files still say so.
pub fn git_diff(output: &str) -> String {
    let lines: Vec<&str> = output.lines().collect();
    let mut out: Vec<&str> = Vec::with_capacity(lines.len());
    let mut i = 0;
    while i < lines.len() {
        let l = lines[i];
        if l.starts_with("index ") {
            i += 1;
            continue;
        }
        if l.starts_with("diff --git ") {
            // Keep it only when no `+++` header follows before the next file (binary or
            // mode-only change): then it is the only line naming the file.
            let has_header = lines[i + 1..]
                .iter()
                .take_while(|n| !n.starts_with("diff --git ") && !n.starts_with("@@"))
                .any(|n| n.starts_with("+++ "));
            if has_header {
                i += 1;
                continue;
            }
        }
        if let Some(a) = l.strip_prefix("--- a/")
            && let Some(b) = lines.get(i + 1).and_then(|n| n.strip_prefix("+++ b/"))
            && a == b
        {
            i += 1;
            continue;
        }
        out.push(l);
        i += 1;
    }
    out.join("\n")
}

/// `1234` → `1.2K`; bytes under 1 KiB print as `NB`.
fn human(bytes: u64) -> String {
    const UNITS: &[&str] = &["K", "M", "G", "T"];
    if bytes < 1024 {
        return format!("{bytes}B");
    }
    let mut v = bytes as f64 / 1024.0;
    let mut u = 0;
    while v >= 1024.0 && u + 1 < UNITS.len() {
        v /= 1024.0;
        u += 1;
    }
    format!("{v:.1}{}", UNITS[u])
}

/// True when an `ls` argv asks for the long format (`-l`, `-la`, `-al`, `-lh` …).
pub fn ls_long(argv: &[String]) -> bool {
    argv.iter()
        .skip(1)
        .any(|a| a.starts_with('-') && !a.starts_with("--") && a[1..].contains('l'))
}

/// `ls -l`: directories on one line, then one `name size` per file; `.`/`..`, `total`,
/// owner, group, link count and dates go. Symlinks keep `-> target`, executables get `*`.
/// Any line that is not the GNU/BSD long shape returns `None`.
pub fn ls_l(output: &str) -> Option<String> {
    let mut dirs: Vec<String> = Vec::new();
    let mut files: Vec<Entry> = Vec::new();
    let mut groups: Vec<String> = Vec::new();
    let flush = |dirs: &mut Vec<String>, files: &mut Vec<Entry>, groups: &mut Vec<String>| {
        let mut g = Vec::new();
        if !dirs.is_empty() {
            g.push(format!("dirs: {}", dirs.join(" ")));
        }
        g.extend(render_files(std::mem::take(files)));
        dirs.clear();
        groups.push(g.join("\n"));
    };
    for line in output.lines() {
        if line.is_empty() {
            continue;
        }
        if line.starts_with("total ") {
            continue;
        }
        if line.ends_with(':') && !line.contains(' ') {
            // `ls -l a b` prints `a:` headers.
            if !dirs.is_empty() || !files.is_empty() {
                flush(&mut dirs, &mut files, &mut groups);
            }
            groups.push(line.to_string());
            continue;
        }
        let mut it = line.split_whitespace();
        let perms = it.next()?;
        let kind = perms.chars().next()?;
        if perms.len() < 10 || !"-dlcbps".contains(kind) {
            return None;
        }
        let _links = it.next()?;
        let _owner = it.next()?;
        let _group = it.next()?;
        let size_tok = it.next()?;
        // Device files print `major, minor`; keep the shape simple and stand down.
        let size: u64 = size_tok.parse().ok()?;
        let (_m, _d, _t) = (it.next()?, it.next()?, it.next()?);
        // Name is everything after the 8th field, spaces included.
        let name = nth_field_rest(line, 8)?;
        if name == "." || name == ".." {
            continue;
        }
        match kind {
            'd' => dirs.push(format!("{name}/")),
            'l' => files.push(Entry::Link(name.to_string())),
            _ => files.push(Entry::File {
                name: name.to_string(),
                exec: perms[1..].contains('x'),
                size,
            }),
        }
    }
    if !dirs.is_empty() || !files.is_empty() {
        flush(&mut dirs, &mut files, &mut groups);
    }
    if groups.is_empty() {
        return None;
    }
    Some(groups.join("\n"))
}

/// One listing line of `ls -l`, before it is written out.
enum Entry {
    Link(String),
    File { name: String, exec: bool, size: u64 },
}

/// More regular files than this in one listing are grouped by extension (T679).
const GROUP_ABOVE: usize = 30;

/// A listing's files as lines: `name size` each; above [`GROUP_ABOVE`] files, one line per extension
/// (`*.rs (3): a 1.2K, b 300B, c 9K`; the name is stem + the header's extension, a lone file of
/// its extension stays a plain line); three or more dotfiles share a `dotfiles:` line. Symlinks
/// keep their own `a -> b` line.
fn render_files(entries: Vec<Entry>) -> Vec<String> {
    let text = |name: &str, exec: bool, size: u64| {
        format!("{name}{} {}", if exec { "*" } else { "" }, human(size))
    };
    let count = entries
        .iter()
        .filter(|e| matches!(e, Entry::File { .. }))
        .count();
    let dots = entries
        .iter()
        .filter(|e| matches!(e, Entry::File { name, .. } if name.starts_with('.')))
        .count();
    let (mut loose, mut dotted): (Vec<String>, Vec<String>) = (Vec::new(), Vec::new());
    let mut by_ext: Vec<(&str, Vec<String>)> = Vec::new();
    let mut pending: Vec<(usize, String)> = Vec::new();
    for e in &entries {
        match e {
            Entry::Link(name) => loose.push(name.clone()),
            Entry::File { name, exec, size } if dots >= 3 && name.starts_with('.') => {
                dotted.push(text(name, *exec, *size));
            }
            Entry::File { name, exec, size } => {
                let split = name.rfind('.').filter(|&i| i > 0 && i + 1 < name.len());
                match split {
                    Some(i) if count > GROUP_ABOVE => {
                        let (stem, ext) = name.split_at(i);
                        let at = match by_ext.iter().position(|(x, _)| *x == ext) {
                            Some(at) => at,
                            None => {
                                by_ext.push((ext, Vec::new()));
                                by_ext.len() - 1
                            }
                        };
                        by_ext[at].1.push(text(stem, *exec, *size));
                        pending.push((at, text(name, *exec, *size)));
                    }
                    _ => loose.push(text(name, *exec, *size)),
                }
            }
        }
    }
    let mut lines = loose;
    if !dotted.is_empty() {
        lines.push(format!("dotfiles: {}", dotted.join(", ")));
    }
    for (at, (ext, members)) in by_ext.iter().enumerate() {
        if members.len() < 2 {
            lines.extend(
                pending
                    .iter()
                    .filter(|(i, _)| *i == at)
                    .map(|(_, t)| t.clone()),
            );
        } else {
            lines.push(format!(
                "*{ext} ({}): {}",
                members.len(),
                members.join(", ")
            ));
        }
    }
    lines
}

/// The rest of `line` after `n` whitespace-separated fields.
fn nth_field_rest(line: &str, n: usize) -> Option<&str> {
    let mut rest = line.trim_start();
    for _ in 0..n {
        let end = rest.find(char::is_whitespace)?;
        rest = rest[end..].trim_start();
    }
    (!rest.is_empty()).then_some(rest)
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_FAIL: &str = "   Compiling c v0.1.0\n    Finished `test` profile [unoptimized + debuginfo] target(s) in 0.00s\n     Running unittests src/lib.rs (target/debug/deps/c-1)\n\nrunning 3 tests\ntest a::ok ... ok\ntest a::bad ... FAILED\ntest a::ok2 ... ok\n\nfailures:\n\n---- a::bad stdout ----\n\nthread 'a::bad' panicked at src/lib.rs:9:9:\nassertion `left == right` failed: nope\n  left: 2\n right: 3\nnote: run with `RUST_BACKTRACE=1` environment variable to display a backtrace\n\n\nfailures:\n    a::bad\n\ntest result: FAILED. 2 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s\n";

    #[test]
    fn cargo_test_keeps_the_panic_body_and_one_totals_line() {
        let got = cargo_test(TEST_FAIL).unwrap();
        assert_eq!(
            got,
            "thread 'a::bad' panicked at src/lib.rs:9:9:\nassertion `left == right` failed: nope\n  left: 2\n right: 3\nFAILED: a::bad\ncargo test: 2 passed, 1 failed (1 suite)"
        );
    }

    #[test]
    fn cargo_test_sums_every_suite() {
        let out = "running 2 tests\ntest x ... ok\ntest y ... ok\n\ntest result: ok. 2 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.00s\n\n   Doc-tests c\n\nrunning 0 tests\n\ntest result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s\n";
        assert_eq!(
            cargo_test(out).unwrap(),
            "cargo test: 2 passed, 1 ignored (2 suites)"
        );
    }

    #[test]
    fn cargo_test_compile_error_is_a_build_log() {
        let out = "   Compiling c v0.1.0\nerror[E0308]: mismatched types\n --> src/lib.rs:3:5\n  |\nerror: could not compile `c` (lib test) due to 1 previous error\n";
        assert_eq!(
            cargo_test(out).unwrap(),
            "error[E0308]: mismatched types\n --> src/lib.rs:3:5\nerror: could not compile `c` (lib test) due to 1 previous error"
        );
    }

    #[test]
    fn cargo_build_success_is_one_word_and_warning_counts_go() {
        let out = "   Compiling c v0.1.0\nwarning: unused variable: `x`\n --> src/lib.rs:1:5\n  |\nwarning: `c` (lib) generated 1 warning\n    Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.20s\n";
        assert_eq!(
            cargo_build(out).unwrap(),
            "warning: unused variable: `x`\n --> src/lib.rs:1:5\nFinished dev"
        );
    }

    #[test]
    fn git_status_long_format_reads_as_porcelain() {
        let out = "On branch main\nChanges to be committed:\n  (use \"git restore --staged <file>...\" to unstage)\n\tnew file:   a.rs\n\nChanges not staged for commit:\n  (use \"git add <file>...\" to update what will be committed)\n\tmodified:   b.rs\n\trenamed:    c.rs -> d.rs\n\nUntracked files:\n  (use \"git add <file>...\" to include in what will be committed)\n\te.txt\n\tdir/\n\nno changes added to commit (use \"git add\" and/or \"git commit -a\")\n";
        assert_eq!(
            git_status(out).unwrap(),
            "On branch main\nA  a.rs\n M b.rs\n R c.rs -> d.rs\n?? e.txt\n?? dir/"
        );
    }

    #[test]
    fn git_status_clean_stays_as_is() {
        let out = "On branch main\nnothing to commit, working tree clean\n";
        assert_eq!(git_status(out).unwrap(), out.trim_end());
    }

    #[test]
    fn git_status_unknown_entry_stands_down() {
        let out = "On branch main\nChanges not staged for commit:\n\tweird:   a\n";
        assert!(git_status(out).is_none());
    }

    #[test]
    fn git_diff_keeps_hunks_and_one_header_per_file() {
        let out = "diff --git a/x b/x\nindex 1..2 100644\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\ndiff --git a/n b/n\nnew file mode 100644\nindex 0..3\n--- /dev/null\n+++ b/n\n@@ -0,0 +1 @@\n+c\ndiff --git a/bin b/bin\nindex 4..5 100644\nBinary files a/bin and b/bin differ\n";
        assert_eq!(
            git_diff(out),
            "+++ b/x\n@@ -1 +1 @@\n-a\n+b\nnew file mode 100644\n--- /dev/null\n+++ b/n\n@@ -0,0 +1 @@\n+c\ndiff --git a/bin b/bin\nBinary files a/bin and b/bin differ"
        );
    }

    #[test]
    fn ls_l_lists_dirs_then_files_with_sizes() {
        let out = "total 24\ndrwxr-xr-x  5 u g  160 Oct 14 23:50 .\ndrwxr-xr-x  9 u g  288 Oct 13 19:44 ..\ndrwxr-xr-x  2 u g   64 Oct 14 23:50 src\n-rw-r--r--  1 u g 3364 Oct 14 23:49 Cargo.toml\n-rwxr-xr-x  1 u g  120 Oct 14 23:49 run me.sh\nlrwxrwxrwx  1 u g    8 Oct 14 23:49 link -> src\n";
        assert_eq!(
            ls_l(out).unwrap(),
            "dirs: src/\nCargo.toml 3.3K\nrun me.sh* 120B\nlink -> src"
        );
    }

    #[test]
    fn ls_l_stands_down_on_other_shapes() {
        assert!(ls_l("a.txt\nb.txt\n").is_none());
        assert!(ls_long(&["ls".into(), "-la".into()]));
        assert!(!ls_long(&["ls".into(), "-a".into()]));
        assert!(!ls_long(&["ls".into(), "--color".into()]));
    }

    fn long_line(name: &str, size: u64, perms: &str) -> String {
        format!("{perms}  1 u g {size} Oct 14 23:50 {name}\n")
    }

    #[test]
    fn ls_l_groups_by_extension_above_thirty_files_and_keeps_every_name() {
        let rw = "-rw-r--r--";
        let mut out = String::from("total 99\n");
        for i in 0..20 {
            out.push_str(&long_line(&format!("m{i}.rs"), 1200 + i, rw));
        }
        for i in 0..8 {
            out.push_str(&long_line(&format!("t{i}.toml"), 300, rw));
        }
        out.push_str(&long_line("README.md", 2048, rw));
        out.push_str(&long_line("Makefile", 900, rw));
        out.push_str(&long_line("run.sh", 120, "-rwxr-xr-x"));
        out.push_str(&long_line("x.sh", 10, "-rwxr-xr-x"));
        let got = ls_l(&out).unwrap();
        let lines: Vec<&str> = got.lines().collect();
        assert_eq!(lines.len(), 5, "{got}");
        assert_eq!(lines[0], "Makefile 900B");
        assert!(lines[1].starts_with("*.rs (20): m0 1.2K, m1 1.2K"), "{got}");
        assert!(lines[1].ends_with("m19 1.2K"), "{got}");
        assert!(
            lines[2].starts_with("*.toml (8): t0 300B, t1 300B"),
            "{got}"
        );
        assert_eq!(lines[3], "README.md 2.0K");
        assert_eq!(lines[4], "*.sh (2): run* 120B, x* 10B");
    }

    #[test]
    fn ls_l_leaves_thirty_files_or_fewer_ungrouped() {
        let mut out = String::new();
        for i in 0..30 {
            out.push_str(&long_line(&format!("f{i}.rs"), 10, "-rw-r--r--"));
        }
        let got = ls_l(&out).unwrap();
        assert_eq!(got.lines().count(), 30, "{got}");
        assert!(got.starts_with("f0.rs 10B\nf1.rs 10B"), "{got}");
    }

    #[test]
    fn ls_la_dotfiles_share_one_line() {
        let rw = "-rw-r--r--";
        let out = format!(
            "{}{}{}{}{}",
            long_line(".gitignore", 40, rw),
            long_line(".env", 12, rw),
            long_line(".editorconfig", 300, rw),
            long_line("main.rs", 5000, rw),
            long_line(".npmrc", 7, rw),
        );
        assert_eq!(
            ls_l(&out).unwrap(),
            "main.rs 4.9K\ndotfiles: .gitignore 40B, .env 12B, .editorconfig 300B, .npmrc 7B"
        );
        let two = format!(
            "{}{}",
            long_line(".env", 12, rw),
            long_line(".npmrc", 7, rw)
        );
        assert_eq!(ls_l(&two).unwrap(), ".env 12B\n.npmrc 7B");
    }

    /// T684: rustc prints the `#[warn(lint)]` note only on the first block of a lint, so the
    /// later ones join it through their headline shape; every location survives.
    #[test]
    fn cargo_build_groups_warnings_by_lint_and_keeps_every_location() {
        let out = "   Compiling c v0.1.0\n\
warning: unused import: `a`\n --> src/a.rs:1:5\n  |\n  = note: `#[warn(unused_imports)]` on by default\n\n\
warning: unused import: `b`\n --> src/b.rs:2:5\n  |\n\n\
warning: unused import: `c`\n --> src/c.rs:3:5\n  |\n\n\
warning: redundant clone\n --> src/d.rs:4:1\n  |\n  = help: for further information visit https://rust-lang.github.io/rust-clippy/master/index.html#redundant_clone\n\n\
warning: `c` (lib) generated 4 warnings\n    Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.20s\n";
        assert_eq!(
            cargo_build(out).unwrap(),
            "unused_imports ×3: unused import: `a` (src/a.rs:1:5, src/b.rs:2:5, src/c.rs:3:5)\nwarning: redundant clone\n --> src/d.rs:4:1\nFinished dev"
        );
    }

    #[test]
    fn cargo_build_groups_shape_twins_without_any_lint_note() {
        let out = "warning: unused variable: `x`\n --> src/a.rs:1:1\nwarning: unused variable: `y`\n --> src/b.rs:2:2\nerror[E0308]: mismatched types\n --> src/c.rs:3:3\n";
        assert_eq!(
            cargo_build(out).unwrap(),
            "unused variable: `x` ×2 (src/a.rs:1:1, src/b.rs:2:2)\nerror[E0308]: mismatched types\n --> src/c.rs:3:3"
        );
    }
}

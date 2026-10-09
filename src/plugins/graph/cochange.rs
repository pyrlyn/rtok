// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T371: files that change together in git history, for `impact` and the repo map.
//!
//! The symbol graph cannot see a migration and its model, a test and its fixture, or docs and
//! the code they describe: they share no name. The last commits do. Pairs are counted from
//! `git log`, kept as one document in the `kv` table keyed by the root, and recounted only when
//! HEAD moves, so a repeat `impact` costs one `git rev-parse`.
//!
//! Clean-room: Empryo's `repo-map.ts` was read for the idea (BSL 1.1), no code is taken.

use std::collections::HashMap;
use std::path::Path;

use rtok_plugin_sdk::Ctx;
use serde::{Deserialize, Serialize};

use super::{git_stdout, index};

/// Commits read. A longer window mostly finds pairs from files that no longer exist.
const COMMITS: &str = "300";
/// A commit touching more files than this is a rename, a format run or a vendor drop; its pairs
/// say nothing about files that belong together.
const MAX_FILES: usize = 20;
/// One shared commit is chance.
const MIN_COUNT: u32 = 2;
/// Partners listed after an `impact` answer.
const TOP_PARTNERS: usize = 5;
/// Marks the start of a commit in `git log` output; no file name contains it.
const COMMIT_MARK: char = '\u{1}';

/// Two files (`a < b`) and the number of commits that touched both.
pub type Pair = (String, String, u32);

#[derive(Deserialize, Serialize)]
struct Doc {
    head: String,
    pairs: Vec<Pair>,
}

/// The co-change pairs of the repo at `root`, most shared commits first. Empty when `root` is
/// not a git repo or git fails: the callers behave as they did before this existed.
pub fn pairs(cx: &Ctx, root: &Path) -> Vec<Pair> {
    let Some(head) = git_stdout(root, &["rev-parse", "HEAD"]) else {
        return Vec::new();
    };
    let head = String::from_utf8_lossy(&head).trim().to_string();
    let key = format!("cochange:{}", index::canon(root));
    let stored = cx
        .plugin_state_get("graph", &key)
        .ok()
        .flatten()
        .and_then(|s| serde_json::from_str::<Doc>(&s).ok());
    if let Some(doc) = stored.filter(|d| d.head == head) {
        return doc.pairs;
    }
    // quotepath off keeps non-ASCII names literal; `--relative` makes paths match the index's.
    let Some(log) = git_stdout(
        root,
        &[
            "-c",
            "core.quotepath=false",
            "log",
            "--name-only",
            "--relative",
            "--no-renames",
            "--format=%x01%H",
            "-n",
            COMMITS,
        ],
    ) else {
        return Vec::new();
    };
    let doc = Doc {
        head,
        pairs: count(&String::from_utf8_lossy(&log)),
    };
    if let Ok(json) = serde_json::to_string(&doc) {
        // A failed write only means the next call recounts.
        let _ = cx.plugin_state_set("graph", &key, &json);
    }
    doc.pairs
}

/// Pair counts from `git log --name-only --format=%x01%H` output.
fn count(log: &str) -> Vec<Pair> {
    count_pairs(&commits(log))
}

/// The file list of each commit, newest first. A commit with one file, none (a merge) or more
/// than [`MAX_FILES`] has no pairs worth counting and is dropped.
fn commits(log: &str) -> Vec<Vec<&str>> {
    log.split(COMMIT_MARK)
        .map(|commit| {
            // Git quotes names it cannot print plainly; they match no indexed path.
            let mut files: Vec<&str> = commit
                .lines()
                .skip(1)
                .filter(|l| !l.is_empty() && !l.starts_with('"'))
                .collect();
            files.sort_unstable();
            files.dedup();
            files
        })
        .filter(|files| (2..=MAX_FILES).contains(&files.len()))
        .collect()
}

/// Pairs shared by at least [`MIN_COUNT`] of `commits`, sorted by count, then path, so the
/// document is the same for the same history.
fn count_pairs(commits: &[Vec<&str>]) -> Vec<Pair> {
    let mut counts: HashMap<(&str, &str), u32> = HashMap::new();
    for files in commits {
        for (i, a) in files.iter().enumerate() {
            for b in &files[i + 1..] {
                *counts.entry((a, b)).or_default() += 1;
            }
        }
    }
    let mut pairs: Vec<Pair> = counts
        .into_iter()
        .filter(|&(_, n)| n >= MIN_COUNT)
        .map(|((a, b), n)| (a.to_string(), b.to_string(), n))
        .collect();
    pairs.sort_by(|x, y| y.2.cmp(&x.2).then_with(|| (&x.0, &x.1).cmp(&(&y.0, &y.1))));
    pairs
}

/// `changes with: a.rs (7), b.rs (4)` for the files that most often change with `file`, or
/// `None` when it has no history. A partner that no longer exists is left out.
pub fn changes_with(cx: &Ctx, root: &Path, file: &str) -> Option<String> {
    let partners: Vec<String> = partners(&pairs(cx, root), file)
        .filter(|(other, _)| root.join(other).is_file())
        .take(TOP_PARTNERS)
        .map(|(other, n)| format!("{other} ({n})"))
        .collect();
    (!partners.is_empty()).then(|| format!("changes with: {}", partners.join(", ")))
}

/// Who changes with `file`, most often first (`pairs` is already in that order).
fn partners<'a>(pairs: &'a [Pair], file: &'a str) -> impl Iterator<Item = (&'a str, u32)> {
    pairs.iter().filter_map(move |(a, b, n)| match file {
        f if f == a => Some((b.as_str(), *n)),
        f if f == b => Some((a.as_str(), *n)),
        _ => None,
    })
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::super::index::tests::cx;
    use super::*;

    fn git(dir: &Path, args: &[&str]) {
        assert!(
            std::process::Command::new("git")
                .current_dir(dir)
                .env("GIT_AUTHOR_NAME", "t")
                .env("GIT_AUTHOR_EMAIL", "t@t")
                .env("GIT_COMMITTER_NAME", "t")
                .env("GIT_COMMITTER_EMAIL", "t@t")
                .args(args)
                .status()
                .unwrap()
                .success(),
            "{args:?}"
        );
    }

    /// Stages only `files`: the runtime keeps its database in the same directory.
    fn commit(dir: &Path, files: &[&str], msg: &str) {
        for f in files {
            let path = dir.join(f);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(&path, format!("{msg}\n")).unwrap();
        }
        git(dir, &[&["add", "--"], files].concat());
        git(dir, &["commit", "-q", "-m", msg]);
    }

    /// Three commits: `a`+`b`, `a`+`b`+`c`, and 21 files over the cap. Only the first two count.
    fn scripted_repo(name: &str) -> (crate::plugin::Runtime, std::path::PathBuf) {
        let (rt, dir) = cx(name);
        git(&dir, &["init", "-q", "-b", "main"]);
        commit(&dir, &["a.rs", "b.rs"], "one");
        commit(&dir, &["a.rs", "b.rs", "c.rs"], "two");
        let wide: Vec<String> = (0..21).map(|i| format!("w{i}.rs")).collect();
        let mut files: Vec<&str> = wide.iter().map(String::as_str).collect();
        files.extend(["a.rs", "b.rs"]);
        commit(&dir, &files, "three");
        (rt, dir)
    }

    #[test]
    fn counts_pairs_over_the_cap_and_below_the_minimum() {
        let (rt, dir) = scripted_repo("cochange-count");
        let cx = Ctx::new(&rt);
        let got = pairs(&cx, &dir);
        // `a`/`b` share two commits; `c` shares one with each, so it is below the minimum; the
        // 21-file commit is over the cap and adds nothing.
        assert_eq!(got, vec![("a.rs".to_string(), "b.rs".to_string(), 2)]);
        assert_eq!(
            changes_with(&cx, &dir, "a.rs").as_deref(),
            Some("changes with: b.rs (2)")
        );
        assert_eq!(changes_with(&cx, &dir, "c.rs"), None);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn recounts_only_when_head_moves_and_skips_deleted_partners() {
        let (rt, dir) = scripted_repo("cochange-head");
        let cx = Ctx::new(&rt);
        let key = format!("cochange:{}", index::canon(&dir));
        let first = cx.plugin_state_get("graph", &key).unwrap();
        assert!(first.is_none(), "nothing stored before the first call");
        assert_eq!(pairs(&cx, &dir).len(), 1);
        let stored = cx.plugin_state_get("graph", &key).unwrap().unwrap();
        // A document for the current HEAD is trusted as is: no recount.
        let head = serde_json::from_str::<Doc>(&stored).unwrap().head;
        let fake = Doc {
            head,
            pairs: vec![("x.rs".into(), "y.rs".into(), 9)],
        };
        cx.plugin_state_set("graph", &key, &serde_json::to_string(&fake).unwrap())
            .unwrap();
        assert_eq!(pairs(&cx, &dir), fake.pairs);
        commit(&dir, &["a.rs", "b.rs"], "four");
        assert_eq!(
            pairs(&cx, &dir),
            vec![("a.rs".to_string(), "b.rs".to_string(), 3)]
        );
        fs::remove_file(dir.join("b.rs")).unwrap();
        assert_eq!(changes_with(&cx, &dir, "a.rs"), None);
        let _ = fs::remove_dir_all(dir);
    }

    /// `impact` names the files that change with the one defining the symbol, after the walk.
    #[test]
    fn impact_lists_the_partners_of_the_defining_file() {
        let (rt, dir) = cx("cochange-impact");
        git(&dir, &["init", "-q", "-b", "main"]);
        for msg in ["one", "two"] {
            commit(&dir, &["core.rs", "schema.sql", "docs/core.md"], msg);
        }
        commit(&dir, &["core.rs"], "three");
        fs::write(
            dir.join("core.rs"),
            "pub fn core_fn() {}
",
        )
        .unwrap();
        fs::write(
            dir.join("caller.rs"),
            "fn go() {
    core_fn();
}
",
        )
        .unwrap();
        let cx = Ctx::new(&rt);
        let out = super::super::impact(&cx, &dir, "core_fn", 2, None).unwrap();
        assert!(out.contains("caller.rs"), "{out}");
        assert!(
            out.trim_end()
                .ends_with("changes with: docs/core.md (2), schema.sql (2)"),
            "{out}"
        );
        let silent = super::super::impact(&cx, &dir, "go", 2, None).unwrap();
        assert!(!silent.contains("changes with"), "{silent}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_root_that_is_not_a_repo_has_no_pairs() {
        let (rt, dir) = cx("cochange-norepo");
        let cx = Ctx::new(&rt);
        assert!(pairs(&cx, &dir).is_empty());
        assert_eq!(changes_with(&cx, &dir, "a.rs"), None);
        let _ = fs::remove_dir_all(dir);
    }

    /// T371 backtest, run by hand: `cargo test --lib cochange_backtest -- --ignored --nocapture`.
    /// For each of the last 100 commits that touch 2 to 20 files, the partners of its first file
    /// are counted from the 300 commits before it (no look-ahead); a hit is any other file of the
    /// commit among the top 5. Also times one cold build on this repo.
    #[test]
    #[ignore = "reads this repository's history; run by hand"]
    fn cochange_backtest_hit_at_5() {
        let repo = Path::new(env!("CARGO_MANIFEST_DIR"));
        let log = git_stdout(
            repo,
            &[
                "-c",
                "core.quotepath=false",
                "log",
                "--name-only",
                "--relative",
                "--no-renames",
                "--format=%x01%H",
                "-n",
                "500",
            ],
        )
        .expect("git log");
        let log = String::from_utf8_lossy(&log);
        let all = commits(&log);
        let (mut hits, mut covered, mut total) = (0, 0, 0);
        for (i, files) in all.iter().take(100).enumerate() {
            let before = &all[i + 1..all.len().min(i + 1 + 300)];
            let pairs = count_pairs(before);
            let top: Vec<&str> = partners(&pairs, files[0])
                .take(TOP_PARTNERS)
                .map(|p| p.0)
                .collect();
            total += 1;
            covered += usize::from(!top.is_empty());
            hits += usize::from(files[1..].iter().any(|f| top.contains(f)));
        }
        let (rt, _dir) = cx("cochange-time");
        let cx = Ctx::new(&rt);
        let start = std::time::Instant::now();
        let n = pairs(&cx, repo).len();
        let took = start.elapsed();
        use std::io::Write;
        let _ = writeln!(
            std::io::stderr(),
            "cochange backtest: {total} commits, hit@5 {:.3} ({hits}), first file had any partner in {covered}; cold build {took:?} for {n} pairs",
            hits as f64 / total as f64
        );
        assert!(hits as f64 / total as f64 >= 0.30);
    }
}

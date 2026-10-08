// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The T330.5.1 kinds on the `cache` item model: `logs` (the §22 log folders, review),
//! `backups` (rtok's own `_backup` generations past `setup.backup_files`, review),
//! `crash-dumps` (listed from the platform crash folder, cleared only from an `extra` folder),
//! `snapshots` (Gemini CLI's restore points, never), the `[agents.junk] extra` paths and the
//! `exclude` globs. Nothing here deletes: `junk_clear` plans, re-checks and removes (T330.4).

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

use globset::{Glob, GlobSet, GlobSetBuilder};

use super::junk_cache::{Ctx, Item, Owned, RTOK_OWN, SECTION_22, is_symlink, make_item};
use super::junk_kinds::{NO_EVIDENCE, NOT_DOCUMENTED, aged_items};
use super::junk_map::Roots;
use crate::config::AgentsJunk;

/// The evidence of a path the user named in `[agents.junk] extra` (D36).
pub const EXTRA: &str = "[agents.junk] extra";

pub const EXCLUDED: &str = "excluded by [agents.junk] exclude";

const SNAPSHOT: &str = "snapshot: never cleared; Gemini CLI restores it with /restore";

const DUMP_EXTS: [&str; 4] = [".ips", ".crash", ".dmp", ".diag"];

fn days(n: u32) -> Duration {
    Duration::from_secs(u64::from(n) * 86_400)
}

/// `[agents.junk] temp_min_age_hours` as a window.
pub fn hours(n: u32) -> Duration {
    Duration::from_secs(u64::from(n) * 3600)
}

fn classed(items: Vec<Item>, class: &'static str) -> Vec<Item> {
    items.into_iter().map(|i| Item { class, ..i }).collect()
}

/// Entries of the §22 log folders (or a single §22 log file) older than `keep_logs_days`.
pub fn log_items(dirs: &[PathBuf], junk: &AgentsJunk, cx: &Ctx, limit: Duration) -> Vec<Item> {
    let aged = aged_items(
        "logs",
        dirs,
        SECTION_22,
        days(junk.keep_logs_days),
        cx,
        limit,
    );
    classed(aged, "review")
}

/// rtok's own copies in the `_backup` folder of each of `dirs` that `setup.backup_files` has no
/// room for: a cap lowered after the copies were taken. The newest copy of a file always stays.
pub fn backup_items(dirs: &[PathBuf], keep: u32, limit: Duration) -> Vec<Item> {
    let keep = usize::try_from(keep).unwrap_or(usize::MAX);
    let stale = dirs
        .iter()
        .flat_map(|d| rtok_agent_sdk::stale_backups(&d.join(rtok_agent_sdk::BACKUP_DIR), keep));
    let items = stale.map(|p| make_item("backups", &p, RTOK_OWN, None, limit));
    classed(items.collect(), "review")
}

fn is_dump(name: &str) -> bool {
    DUMP_EXTS.iter().any(|e| name.ends_with(e))
}

/// Crash reports macOS keeps for `names` (a host's binaries and apps) in
/// `~/Library/Logs/DiagnosticReports`. §22 has no row for that folder, so each is listed only
/// (D36); an `extra` entry with kind `crash-dumps` is the way to clear them.
pub fn crash_items(roots: &Roots, names: &[String], limit: Duration) -> Vec<Item> {
    let dir = roots.resolve("{home}/Library/Logs/DiagnosticReports");
    let ours = |f: &str| {
        names.iter().any(|n| {
            f.strip_prefix(n.as_str())
                .is_some_and(|rest| rest.starts_with(['-', '_', ' ', '.']))
        })
    };
    let entries = std::fs::read_dir(dir).into_iter().flatten().flatten();
    entries
        .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
        .filter(|e| {
            e.file_name()
                .to_str()
                .is_some_and(|f| is_dump(f) && ours(f))
        })
        .map(|e| {
            let item = make_item(
                "crash-dumps",
                &e.path(),
                NO_EVIDENCE,
                Some(NOT_DOCUMENTED.into()),
                limit,
            );
            Item {
                class: "review",
                ..item
            }
        })
        .collect()
}

/// The entries of a crash folder the user named: `safe` past `crash_dump_min_age_days`,
/// `review` before it (cleared only when `--kind crash-dumps` names them).
fn dump_items(dirs: &[PathBuf], junk: &AgentsJunk, cx: &Ctx, limit: Duration) -> Vec<Item> {
    let floor = days(junk.crash_dump_min_age_days);
    let mut items = aged_items("crash-dumps", dirs, EXTRA, Duration::ZERO, cx, limit);
    for i in &mut items {
        let modified = std::fs::symlink_metadata(&i.path).and_then(|m| m.modified());
        let age = |t: SystemTime| cx.policy.now.duration_since(t).unwrap_or_default();
        if !modified.is_ok_and(|t| age(t) >= floor) {
            i.class = "review";
        }
    }
    items
}

/// Gemini CLI's restore points (`research.md` §22.1): the shadow git repo of each project
/// under `history/` and each project's `tmp/<hash>/checkpoints`. Class `never`: listed for
/// their size with the host's own way back, never cleared.
pub fn snapshot_items(roots: &Roots, limit: Duration) -> Vec<Item> {
    let gemini = roots.resolve("{home}/.gemini");
    let children = |d: PathBuf| {
        let found = std::fs::read_dir(d).into_iter().flatten().flatten();
        found.map(|e| e.path()).collect::<Vec<_>>()
    };
    let mut dirs = children(gemini.join("history"));
    dirs.extend(
        children(gemini.join("tmp"))
            .into_iter()
            .map(|p| p.join("checkpoints")),
    );
    dirs.into_iter()
        .filter(|d| d.is_dir() && !is_symlink(d))
        .map(|d| Item {
            class: "never",
            ..make_item("snapshots", &d, SECTION_22, Some(SNAPSHOT.into()), limit)
        })
        .collect()
}

/// The `[agents.junk] extra` entries of `agent` (a host id or `rtok`): a `cache` folder joins
/// the owned caches (emptied, its top folder kept); a `temp`, `logs` or `crash-dumps` folder
/// gives its entries by that kind's age rule. A relative path or an unknown kind, which
/// `config validate` refuses, is ignored here rather than guessed at.
pub fn extra_items(
    junk: &AgentsJunk,
    agent: &str,
    rtok_home: &Path,
    home: &Path,
    cx: &Ctx,
    limit: Duration,
) -> (Vec<Owned>, Vec<Item>) {
    let (mut owned, mut items) = (Vec::new(), Vec::new());
    for e in junk.extra.iter().filter(|e| e.host == agent) {
        let path = rtok_hook::expand_with(Path::new(&e.path), rtok_home, Some(home));
        if !path.is_absolute() {
            continue;
        }
        let one = std::slice::from_ref(&path);
        match e.kind.as_str() {
            "cache" => owned.push(Owned {
                path,
                evidence: EXTRA,
                idle_rule: false,
            }),
            "temp" => {
                let floor = hours(junk.temp_min_age_hours);
                items.extend(aged_items("temp", one, EXTRA, floor, cx, limit));
            }
            "logs" => {
                let aged = aged_items("logs", one, EXTRA, days(junk.keep_logs_days), cx, limit);
                items.extend(classed(aged, "review"));
            }
            "crash-dumps" => items.extend(dump_items(one, junk, cx, limit)),
            _ => {}
        }
    }
    (owned, items)
}

/// Whether anything under the directory `dir` matches `set`. A walk cut by `deadline` answers
/// yes: what it did not see may be the excluded file.
fn holds_match(dir: &Path, set: &GlobSet, deadline: Instant) -> bool {
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        if Instant::now() >= deadline {
            return true;
        }
        for e in std::fs::read_dir(&d).into_iter().flatten().flatten() {
            let p = e.path();
            if set.is_match(&p) {
                return true;
            }
            if e.file_type().is_ok_and(|t| t.is_dir()) {
                stack.push(p);
            }
        }
    }
    false
}

/// Keeps every item `[agents.junk] exclude` matches, and every folder that holds a match: a
/// cache emptied as a whole would take the excluded file with it. A glob that does not compile
/// keeps every item, so protection that cannot be checked is never dropped.
pub fn exclude(
    items: &mut [Item],
    globs: &[String],
    rtok_home: &Path,
    home: &Path,
    limit: Duration,
) {
    if globs.is_empty() {
        return;
    }
    let built = globs.iter().try_fold(GlobSetBuilder::new(), |mut b, g| {
        let g = rtok_hook::expand_with(Path::new(g), rtok_home, Some(home));
        b.add(Glob::new(&g.to_string_lossy())?);
        Ok::<_, globset::Error>(b)
    });
    let set = built.and_then(|b| b.build());
    for i in items.iter_mut().filter(|i| i.counted()) {
        let p = Path::new(&i.path);
        let hit = match &set {
            Ok(set) => {
                let deadline = Instant::now() + limit;
                set.is_match(p) || (p.is_dir() && !is_symlink(p) && holds_match(p, set, deadline))
            }
            Err(_) => true,
        };
        if hit {
            i.kept = Some(match &set {
                Ok(_) => EXCLUDED.into(),
                Err(e) => format!("invalid [agents.junk] exclude, nothing cleared: {e}"),
            });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::junk_cache::DEFAULT_IDLE;
    use crate::config::JunkExtra;
    use crate::testutil::tmp_dir;

    const LIMIT: Duration = Duration::from_secs(10);
    const DAY: u64 = 86_400;

    fn put(path: &Path, bytes: &[u8]) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, bytes).unwrap();
    }

    fn aged(path: &Path, secs: u64) {
        let f = std::fs::File::options().write(true).open(path).unwrap();
        f.set_modified(SystemTime::now() - Duration::from_secs(secs))
            .unwrap();
    }

    fn cx(dir: &Path) -> Ctx {
        Ctx::new(dir, DEFAULT_IDLE, SystemTime::now())
    }

    fn named<'a>(items: &'a [Item], name: &str) -> &'a Item {
        let hit = items.iter().find(|i| Path::new(&i.path).ends_with(name));
        hit.unwrap_or_else(|| panic!("no item ending in {name}: {items:?}"))
    }

    fn roots(home: &Path) -> Roots {
        Roots::new(home.to_path_buf(), |_| None)
    }

    #[test]
    fn a_log_past_keep_logs_days_is_review_junk_and_a_fresher_one_stays() {
        let root = tmp_dir("review-logs");
        let debug = root.join("debug");
        put(&debug.join("old.log"), &[b'x'; 100]);
        put(&debug.join("new.log"), b"x");
        put(&root.join("single.log"), b"x");
        aged(&debug.join("old.log"), 31 * DAY);
        aged(&debug.join("new.log"), 29 * DAY);
        aged(&root.join("single.log"), 40 * DAY);
        let dirs = [debug, root.join("single.log")];

        let items = log_items(&dirs, &AgentsJunk::default(), &cx(&root), LIMIT);

        assert_eq!(items.len(), 3, "{items:?}");
        for gone in ["old.log", "single.log"] {
            let i = named(&items, gone);
            assert!(i.counted() && (i.kind, i.class, i.evidence) == ("logs", "review", SECTION_22));
        }
        assert_eq!(
            named(&items, "new.log").kept.as_deref(),
            Some("modified within the idle window")
        );
        let week = AgentsJunk {
            keep_logs_days: 7,
            ..AgentsJunk::default()
        };
        let items = log_items(&dirs, &week, &cx(&root), LIMIT);
        assert!(
            items.iter().all(Item::counted),
            "29 days is past 7: {items:?}"
        );
    }

    #[test]
    fn only_backup_generations_past_the_cap_are_listed_and_the_newest_always_stays() {
        let dir = tmp_dir("review-backups");
        let bak = dir.join(rtok_agent_sdk::BACKUP_DIR);
        for f in [
            "s.json.bak-1",
            "s.json.bak-2",
            "s.json.bak-3",
            "o.json.bak-9",
            "notes.txt",
        ] {
            put(&bak.join(f), b"x");
        }
        let one = std::slice::from_ref(&dir);

        let items = backup_items(one, 2, LIMIT);

        assert_eq!(items.len(), 1, "{items:?}");
        let i = &items[0];
        assert!(i.path.ends_with("s.json.bak-1") && i.counted());
        assert_eq!(
            (i.kind, i.class, i.evidence),
            ("backups", "review", RTOK_OWN)
        );
        assert_eq!(backup_items(one, 1, LIMIT).len(), 2);
        assert!(backup_items(one, 0, LIMIT).is_empty(), "0 keeps every copy");
    }

    #[test]
    fn crash_reports_named_for_the_host_are_listed_read_only() {
        let home = tmp_dir("review-crash");
        let reports = home.join("Library/Logs/DiagnosticReports");
        for f in [
            "claude-2026-09-01-101010.ips",
            "Cursor Helper (Renderer)-2026-09-01.ips",
            "claudette-2026-09-01.ips",
            "claude-notes.txt",
            "other-2026.crash",
        ] {
            put(&reports.join(f), b"x");
        }
        let names = ["claude".to_string(), "Cursor".to_string()];

        let items = crash_items(&roots(&home), &names, LIMIT);

        assert_eq!(items.len(), 2, "{items:?}");
        for i in &items {
            assert_eq!(
                (i.kind, i.kept.as_deref()),
                ("crash-dumps", Some(NOT_DOCUMENTED))
            );
        }
    }

    #[test]
    fn gemini_restore_points_are_sized_and_never_counted() {
        let home = tmp_dir("review-snapshots");
        put(&home.join(".gemini/history/abc/HEAD"), &[b'x'; 64]);
        put(&home.join(".gemini/tmp/h1/checkpoints/c.json"), b"{}");
        put(&home.join(".gemini/tmp/h2/chats/s.json"), b"{}");

        let items = snapshot_items(&roots(&home), LIMIT);

        assert_eq!(items.len(), 2, "{items:?}");
        for i in &items {
            assert!(!i.counted() && i.class == "never" && i.bytes > 0);
            assert!(i.kept.as_deref().unwrap().contains("/restore"));
        }
    }

    #[test]
    fn extra_paths_become_items_of_their_kind_for_their_host_only() {
        let home = tmp_dir("review-extra");
        let dumps = home.join("dumps");
        put(&dumps.join("old.dmp"), b"x");
        put(&dumps.join("new.dmp"), b"x");
        aged(&dumps.join("old.dmp"), 10 * DAY);
        aged(&dumps.join("new.dmp"), 2 * DAY);
        put(&home.join("applogs/a.log"), b"x");
        aged(&home.join("applogs/a.log"), 40 * DAY);
        let entry = |host: &str, kind: &str, path: &str| JunkExtra {
            host: host.into(),
            kind: kind.into(),
            path: path.into(),
        };
        let junk = AgentsJunk {
            extra: vec![
                entry("cursor", "cache", "~/Library/Caches/Cursor"),
                entry("cursor", "crash-dumps", "~/dumps"),
                entry("cursor", "logs", "~/applogs"),
                entry("codex", "cache", "~/elsewhere"),
                entry("cursor", "sessions", "~/sessions"),
                entry("cursor", "cache", "relative/dir"),
            ],
            ..AgentsJunk::default()
        };

        let (owned, items) = extra_items(
            &junk,
            "cursor",
            &home.join(".rtok"),
            &home,
            &cx(&home),
            LIMIT,
        );

        assert_eq!(owned.len(), 1);
        assert_eq!(owned[0].path, home.join("Library/Caches/Cursor"));
        assert_eq!(owned[0].evidence, EXTRA);
        assert_eq!(items.len(), 3, "{items:?}");
        assert!(items.iter().all(|i| i.evidence == EXTRA && i.counted()));
        assert_eq!(named(&items, "old.dmp").class, "safe");
        assert_eq!(named(&items, "new.dmp").class, "review");
        assert_eq!(
            (named(&items, "a.log").kind, named(&items, "a.log").class),
            ("logs", "review")
        );
    }

    #[test]
    fn exclude_keeps_a_match_and_a_folder_holding_one_and_a_bad_glob_keeps_all() {
        let home = tmp_dir("review-exclude");
        put(&home.join("c1/keep.me"), b"x");
        put(&home.join("c2/blob"), b"x");
        put(&home.join("t/old.tmp"), b"x");
        let item = |p: &str| make_item("cache", &home.join(p), SECTION_22, None, LIMIT);
        let fresh = || vec![item("c1"), item("c2"), item("t/old.tmp")];
        let rtok = home.join(".rtok");

        let mut items = fresh();
        let globs = ["**/keep.me".to_string(), "~/t/*.tmp".to_string()];
        exclude(&mut items, &globs, &rtok, &home, LIMIT);
        let kept: Vec<_> = items.iter().map(|i| i.kept.as_deref()).collect();
        assert_eq!(kept, [Some(EXCLUDED), None, Some(EXCLUDED)]);

        let mut items = fresh();
        exclude(&mut items, &["[".to_string()], &rtok, &home, LIMIT);
        assert!(
            items
                .iter()
                .all(|i| i.kept.as_deref().unwrap().starts_with("invalid"))
        );
    }
}

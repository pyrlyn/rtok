// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The `cache` junk kind (T330.3.1): which directories `agents junk` may clear as cache, and
//! which of those it would leave alone today. D36: a path is cache only with evidence, a
//! `research.md` §22 row, a valid `CACHEDIR.TAG`, or rtok's own say-so for what rtok writes;
//! a platform cache root with none of these is a listed folder, never an [`Item`].
//! A tagged directory is judged by T152's rules (T342), the code `rtok worktree clean` runs:
//! idle for the window, and never the cache of the worktree the command runs from.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

use serde::Serialize;

use super::junk::disk_usage_until;
use crate::worktree::clean::{Policy, kept_because};
use crate::worktree::list::{Cache, usage_until};

/// T152's default idle window for a tagged cache.
pub const DEFAULT_IDLE: Duration = Duration::from_secs(24 * 3600);

pub const SECTION_22: &str = "research.md §22";
pub const TAG: &str = "CACHEDIR.TAG";
pub const RTOK_OWN: &str = "rtok-owned";

/// A walk that hit its deadline. Its newest mtime is a lower bound, so the cache is not idle
/// just because every file visited so far is old.
const SCAN_STOPPED: &str = "scan stopped: not cleared";

/// One directory `clear` may empty as cache.
#[derive(Debug, Clone, Serialize)]
pub struct Item {
    pub kind: &'static str,
    pub class: &'static str,
    pub path: String,
    pub bytes: u64,
    /// What D36 accepts as the reason the path is junk.
    pub evidence: &'static str,
    /// Why `clear` leaves it for now (T152's idle and own-worktree rules). Such an item is
    /// listed but counted in no "Freed" total.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kept: Option<String>,
}

impl Item {
    pub fn counted(&self) -> bool {
        self.kept.is_none()
    }
}

/// A directory that is cache without a `CACHEDIR.TAG`: a §22 row or something rtok writes.
pub struct Owned {
    pub path: PathBuf,
    pub evidence: &'static str,
    /// Apply T152's idle rule (a tool may be using it right now), as for a tagged cache.
    pub idle_rule: bool,
}

/// What the rules need to know about this run.
pub struct Ctx {
    /// Canonical, so a symlinked checkout is still recognised as the current worktree.
    pub cwd: PathBuf,
    pub policy: Policy,
}

impl Ctx {
    pub fn new(cwd: &Path, idle: Duration, now: SystemTime) -> Self {
        Self {
            cwd: cwd.canonicalize().unwrap_or_else(|_| cwd.to_path_buf()),
            policy: Policy { idle, now },
        }
    }
}

fn real(p: &Path) -> PathBuf {
    p.canonicalize().unwrap_or_else(|_| p.to_path_buf())
}

fn is_symlink(p: &Path) -> bool {
    std::fs::symlink_metadata(p).is_ok_and(|m| m.file_type().is_symlink())
}

/// T152's idle answer for one directory, or [`SCAN_STOPPED`] when the walk did not finish.
/// An unfinished mtime must not read as idle: the file that is still in use may be the one
/// the deadline left unvisited.
fn idle_keep(path: &Path, current: bool, cx: &Ctx, limit: Duration) -> Option<String> {
    let (u, cut) = usage_until(path, Instant::now() + limit);
    if cut {
        return Some(SCAN_STOPPED.into());
    }
    let cache = Cache {
        path: path.to_path_buf(),
        bytes: 0,
        modified: u.modified,
    };
    kept_because(&cache, current, false, &cx.policy)
}

/// The cache items under one agent: its `owned` directories plus every tagged directory below
/// `roots`. Each size and each walk gets `limit`, so a huge folder is a lower bound, not a hang.
/// A directory inside a counted one is dropped, so a byte is never listed twice.
pub fn cache_items(owned: &[Owned], roots: &[PathBuf], cx: &Ctx, limit: Duration) -> Vec<Item> {
    let mut items: Vec<Item> = Vec::new();
    let item = |path: &Path, evidence: &'static str, kept: Option<String>| Item {
        kind: "cache",
        class: "safe",
        path: path.display().to_string(),
        bytes: disk_usage_until(path, Some(Instant::now() + limit)).bytes,
        evidence,
        kept,
    };
    for o in owned {
        // A link out of the folder is never followed (D36); `list` still shows its target.
        if is_symlink(&o.path) || !o.path.is_dir() {
            continue;
        }
        let kept = o.idle_rule.then(|| idle_keep(&o.path, false, cx, limit));
        items.push(item(&o.path, o.evidence, kept.flatten()));
    }
    for root in roots.iter().filter(|r| r.is_dir()) {
        let (found, cut) = usage_until(root, Instant::now() + limit);
        let current = cx.cwd.starts_with(real(root));
        for cache in found.caches {
            let kept = if cut {
                Some(SCAN_STOPPED.into())
            } else {
                kept_because(&cache, current, false, &cx.policy)
            };
            items.push(item(&cache.path, TAG, kept));
        }
    }
    drop_nested(items)
}

/// One row per path (the first evidence wins), and none inside a path that is itself counted:
/// clearing the outer one already frees it. Sorted by path.
fn drop_nested(mut items: Vec<Item>) -> Vec<Item> {
    items.sort_by(|a, b| a.path.cmp(&b.path));
    items.dedup_by(|b, a| a.path == b.path);
    let counted: Vec<PathBuf> = items
        .iter()
        .filter(|i| i.counted())
        .map(|i| PathBuf::from(&i.path))
        .collect();
    items.retain(|i| {
        let p = Path::new(&i.path);
        !counted.iter().any(|c| c != p && p.starts_with(c))
    });
    items
}

/// Test fixtures shared with `junk.rs`.
#[cfg(test)]
pub(super) fn tagged(dir: &Path) {
    std::fs::write(
        dir.join("CACHEDIR.TAG"),
        "Signature: 8a477f597d28d172789f06886806bc55\n# a cache\n",
    )
    .unwrap();
}

/// Every file under `dir` last modified `secs` ago.
#[cfg(test)]
pub(super) fn age_files(dir: &Path, secs: u64) {
    let when = SystemTime::now() - Duration::from_secs(secs);
    for e in std::fs::read_dir(dir).unwrap().flatten() {
        if e.file_type().unwrap().is_dir() {
            age_files(&e.path(), secs);
        } else {
            let f = std::fs::File::options().write(true).open(e.path()).unwrap();
            f.set_modified(when).unwrap();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::tmp_dir;

    fn item(path: &str, kept: Option<&str>) -> Item {
        Item {
            kind: "cache",
            class: "safe",
            path: path.into(),
            bytes: 1,
            evidence: TAG,
            kept: kept.map(String::from),
        }
    }

    fn put(path: &Path, bytes: usize) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, vec![b'x'; bytes]).unwrap();
    }

    #[test]
    fn an_item_inside_a_counted_one_is_dropped_but_not_inside_a_kept_one() {
        let items = vec![
            item("/a/cache", None),
            item("/a/cache/inner", None),
            item("/b/target", Some("modified within the idle window")),
            item("/b/target/inner", None),
            item("/a/cache", None),
        ];
        let paths: Vec<String> = drop_nested(items).into_iter().map(|i| i.path).collect();
        assert_eq!(paths, ["/a/cache", "/b/target", "/b/target/inner"]);
    }

    /// T152's rules on a tagged cache: idle goes, fresh stays, the current worktree's stays
    /// even when idle, and a tag with the wrong signature never makes cache.
    #[test]
    fn tagged_caches_follow_the_idle_and_current_worktree_rules() {
        let root = tmp_dir("junk-cache-tags");
        put(&root.join("old/o"), 10);
        put(&root.join("new/o"), 10);
        put(&root.join("bad/o"), 10);
        put(&root.join("owned/o"), 10);
        put(&root.join("owned/inner/o"), 10);
        for d in ["old", "new", "owned/inner"] {
            tagged(&root.join(d));
        }
        std::fs::write(root.join("bad/CACHEDIR.TAG"), "Signature: nope\n").unwrap();
        age_files(&root.join("old"), 2 * 86_400);
        age_files(&root.join("owned"), 2 * 86_400);
        let owned = [Owned {
            path: root.join("owned"),
            evidence: SECTION_22,
            idle_rule: false,
        }];
        let limit = Duration::from_secs(10);

        let elsewhere = Ctx::new(
            &tmp_dir("junk-cache-elsewhere"),
            DEFAULT_IDLE,
            SystemTime::now(),
        );
        let items = cache_items(&owned, std::slice::from_ref(&root), &elsewhere, limit);
        let row = |name: &str| items.iter().find(|i| i.path.ends_with(name)).unwrap();
        assert_eq!(
            items.len(),
            3,
            "bad signature and nested item are not listed: {items:?}"
        );
        assert_eq!(
            (row("/old").evidence, row("/old").kept.clone()),
            (TAG, None)
        );
        assert_eq!(row("/owned").evidence, SECTION_22);
        assert_eq!(
            row("/new").kept.as_deref(),
            Some("modified within the idle window")
        );
        assert_eq!(
            row("/old").bytes,
            disk_usage_until(&root.join("old"), None).bytes
        );

        let inside = Ctx::new(&root.join("old"), DEFAULT_IDLE, SystemTime::now());
        let items = cache_items(&[], std::slice::from_ref(&root), &inside, limit);
        let current = "the worktree this command runs from; name its path to clean it";
        assert!(
            items.iter().all(|i| i.kept.as_deref() == Some(current)),
            "{items:?}"
        );
    }

    #[test]
    fn an_owned_tool_cache_waits_for_the_idle_window_and_a_missing_one_is_skipped() {
        let root = tmp_dir("junk-cache-owned");
        put(&root.join("busy/o"), 10);
        put(&root.join("quiet/o"), 10);
        age_files(&root.join("quiet"), 2 * 86_400);
        let own = |name: &str| Owned {
            path: root.join(name),
            evidence: RTOK_OWN,
            idle_rule: true,
        };
        let cx = Ctx::new(&root, DEFAULT_IDLE, SystemTime::now());
        let items = cache_items(
            &[own("busy"), own("quiet"), own("gone")],
            &[],
            &cx,
            Duration::from_secs(10),
        );
        let kept: Vec<_> = items
            .iter()
            .map(|i| (i.path.ends_with("busy"), i.counted()))
            .collect();
        assert_eq!(kept, [(true, false), (false, true)]);
    }

    /// A deadline can fall between an old file and a newer one. The partial mtime would look
    /// idle, so a cut scan frees nothing.
    #[test]
    fn a_cut_short_scan_frees_nothing() {
        let root = tmp_dir("junk-cache-cut");
        put(&root.join("old/o"), 10);
        tagged(&root.join("old"));
        age_files(&root.join("old"), 2 * 86_400);
        let cx = Ctx::new(
            &tmp_dir("junk-cache-cut-cwd"),
            DEFAULT_IDLE,
            SystemTime::now(),
        );
        let owned = [Owned {
            path: root.join("old"),
            evidence: RTOK_OWN,
            idle_rule: true,
        }];
        let items = cache_items(&owned, &[root], &cx, Duration::ZERO);
        assert!(
            items.iter().all(|i| !i.counted()),
            "a stopped scan must not count as freed: {items:?}"
        );
    }
}

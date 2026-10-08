// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The `temp`, `build`, `locks` and `swap` junk kinds and rtok's plugin staging caches
//! (T330.3.2), on top of the `cache` item model of `junk_cache`. D36: only a path with
//! evidence may be cleared. `temp` has it (the §22 temp dirs) and so do the tagged caches of
//! an agent's worktree (`build`) and the plugin copies rtok itself put there; a lock or swap
//! file has none (§22 names no such path), so it is listed read-only, "not documented: not
//! cleared", with the reason a live owner would give first. Nothing here deletes.

use std::fs::FileType;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use super::HOSTS;
use super::junk_cache::{
    Ctx, Item, Owned, RTOK_OWN, SCAN_STOPPED, SECTION_22, TAG, is_symlink, make_item, real,
};
use super::plugin_install::PLUGIN_CACHE;
use crate::config::Config;
use crate::store::Store;
use crate::worktree::clean::{Policy, kept_because};
use crate::worktree::list::{self, Cache, usage_until};

/// Why a find with no D36 evidence is not cleared.
pub const NOT_DOCUMENTED: &str =
    "not documented: not cleared (add to [agents.junk] extra to clear)";

/// The evidence column of a find nothing documents.
const NO_EVIDENCE: &str = "none";

/// How deep a walk for lock, swap and build leftovers goes: an agent folder is shallow, and a
/// deep tree is a checkout, not state.
const MAX_DEPTH: usize = 12;

/// Never entered: history, and a dependency tree that is not the agent's state.
const SKIP_DIRS: [&str; 2] = [".git", "node_modules"];

/// Build output without a `CACHEDIR.TAG`: listed in an agent worktree, never cleared (D36).
const BUILD_DIRS: [&str; 3] = ["dist", ".next", "__pycache__"];

/// Reinstallable dependency folders and the files beside one that can reinstall it.
const DEPS: [(&str, &[&str]); 5] = [
    ("node_modules", &["package.json"]),
    (
        ".venv",
        &["pyproject.toml", "requirements.txt", "uv.lock", "Pipfile"],
    ),
    ("vendor", &["composer.json", "Gemfile", "go.mod"]),
    (
        ".gradle",
        &["build.gradle", "build.gradle.kts", "settings.gradle"],
    ),
    ("Pods", &["Podfile"]),
];

/// `deps` has no D36 evidence (no §22 row, no tag), so it is listed only; one nothing beside it
/// could reinstall says that first.
fn deps_reason(path: &Path) -> String {
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or_default();
    let manifests = DEPS
        .iter()
        .find(|(d, _)| *d == name)
        .map_or(&[][..], |d| d.1);
    let beside = path.parent().unwrap_or(path);
    if manifests.iter().any(|m| beside.join(m).is_file()) {
        NOT_DOCUMENTED.into()
    } else {
        "no lockfile or manifest beside it: cannot be reinstalled".into()
    }
}

/// Lock files that are package-manager state and never junk.
pub(super) const PACKAGE_LOCKS: [&str; 14] = [
    "Cargo.lock",
    "yarn.lock",
    "bun.lock",
    "poetry.lock",
    "uv.lock",
    "pdm.lock",
    "Pipfile.lock",
    "Gemfile.lock",
    "composer.lock",
    "flake.lock",
    "Podfile.lock",
    "pubspec.lock",
    "mix.lock",
    "deno.lock",
];

/// Depth-first search under `root` for what `pick` names. A picked entry is not entered and a
/// symlink never is; `skip` prunes a directory. The walk stops at `deadline` with what it has.
fn find(
    root: &Path,
    deadline: Instant,
    skip: &dyn Fn(&Path) -> bool,
    pick: &dyn Fn(&str, &FileType) -> Option<&'static str>,
) -> Vec<(PathBuf, &'static str)> {
    let mut found = Vec::new();
    let mut stack = vec![(root.to_path_buf(), 0)];
    while let Some((dir, depth)) = stack.pop() {
        if Instant::now() >= deadline {
            break;
        }
        for e in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
            let (Ok(kind), Ok(name)) = (e.file_type(), e.file_name().into_string()) else {
                continue;
            };
            let path = e.path();
            if let Some(k) = pick(&name, &kind) {
                found.push((path, k));
            } else if kind.is_dir()
                && depth < MAX_DEPTH
                && !SKIP_DIRS.contains(&name.as_str())
                && !skip(&path)
            {
                stack.push((path, depth + 1));
            }
        }
    }
    found
}

/// Vim, Chrome and Emacs leave these beside what they edit; Emacs' `.#name` is a symlink.
fn is_swap(name: &str, kind: &FileType) -> bool {
    let file = kind.is_file()
        && [".swp", ".swo", ".crswap"]
            .iter()
            .any(|e| name.ends_with(e));
    file || (kind.is_symlink() && name.starts_with(".#"))
}

fn is_lock(name: &str, kind: &FileType) -> bool {
    !kind.is_symlink()
        && (name == "LOCK" && kind.is_file()
            || name.ends_with(".lock") && !PACKAGE_LOCKS.contains(&name))
}

/// An editor's or a tool's copy of a file (`*.bak`, `*.bak-<ts>`, `*~`); rtok's own `_backup`
/// generations are `junk_review::backup_items`, never walked here.
fn is_backup(name: &str, kind: &FileType) -> bool {
    kind.is_file() && (name.ends_with(".bak") || name.contains(".bak-") || name.ends_with('~'))
}

fn lock_or_swap(name: &str, kind: &FileType) -> Option<&'static str> {
    if is_swap(name, kind) {
        Some("swap")
    } else if is_lock(name, kind) {
        Some("locks")
    } else if is_backup(name, kind) {
        Some("backups")
    } else {
        None
    }
}

/// Whether another process holds an advisory lock on `path`. Opening for write first keeps the
/// probe valid on Windows; a read-only file falls back to a read handle.
fn lock_held(path: &Path) -> std::io::Result<bool> {
    let file = std::fs::File::options()
        .read(true)
        .write(true)
        .open(path)
        .or_else(|_| std::fs::File::open(path))?;
    Ok(!rtok_sys::try_lock_exclusive(&file)?)
}

/// Why a file stays because a process may use it, or `None` when nothing holds it.
pub(super) fn held_reason(path: &Path) -> Option<String> {
    match lock_held(path) {
        Ok(false) => None,
        Ok(true) => Some("held by a running process".into()),
        Err(e) => Some(format!("cannot check whether a process holds it: {e}")),
    }
}

/// The pid a swap file names: Vim writes it little-endian at byte 24 of the `b0` block, Emacs
/// puts `user@host.PID[:boot]` in the link target. Chrome's `.crswap` names none.
fn swap_owner(path: &Path) -> Option<i32> {
    let name = path.file_name()?.to_str()?;
    if name.starts_with(".#") {
        let target = std::fs::read_link(path).ok()?;
        let tail = target.to_str()?.rsplit('.').next()?;
        return tail.split(':').next()?.parse().ok();
    }
    let mut head = [0u8; 28];
    std::fs::File::open(path).ok()?.read_exact(&mut head).ok()?;
    (&head[..2] == b"b0")
        .then(|| u32::from_le_bytes([head[24], head[25], head[26], head[27]]) as i32)
}

fn swap_reason(path: &Path) -> Option<String> {
    match swap_owner(path) {
        Some(pid) if rtok_sys::process_alive(pid) => {
            Some(format!("owner process {pid} is running"))
        }
        Some(_) => None,
        None => Some("owner unknown".into()),
    }
}

/// Lock, swap and backup files under an agent's folders. None has D36 evidence, so each is
/// kept: a live owner or a held lock says so, the rest read "not documented". Package-manager
/// lockfiles are not junk and are not listed.
pub fn found_items(roots: &[PathBuf], limit: Duration) -> Vec<Item> {
    let deadline = Instant::now() + limit;
    let ours = |p: &Path| {
        p.file_name()
            .is_some_and(|n| n == rtok_agent_sdk::BACKUP_DIR)
    };
    let finds = roots
        .iter()
        .flat_map(|r| find(r, deadline, &ours, &lock_or_swap));
    finds
        .map(|(path, kind)| {
            let reason = if kind == "swap" {
                swap_reason(&path)
            } else if kind == "locks" && std::fs::symlink_metadata(&path).is_ok_and(|m| m.is_file())
            {
                held_reason(&path)
            } else {
                None
            };
            let kept = reason.unwrap_or_else(|| NOT_DOCUMENTED.into());
            let class = if kind == "backups" { "review" } else { "safe" };
            Item {
                class,
                ..make_item(kind, &path, NO_EVIDENCE, Some(kept), limit)
            }
        })
        .collect()
}

/// The entries of the §22 temp dirs `dirs`, kept while touched within `min_age`
/// (`[agents.junk] temp_min_age_hours`). A running agent's temp is left by `junk_clear` (T330.4).
pub fn temp_items(dirs: &[PathBuf], min_age: Duration, cx: &Ctx, limit: Duration) -> Vec<Item> {
    aged_items("temp", dirs, SECTION_22, min_age, cx, limit)
}

/// One item per entry of each folder in `dirs`, or the path itself when it is a file (Zed's
/// single `Zed.log`): kept while touched within `min_age` or, for a file, while another process
/// holds a lock on it. A symlink is never an item.
pub fn aged_items(
    kind: &'static str,
    dirs: &[PathBuf],
    evidence: &'static str,
    min_age: Duration,
    cx: &Ctx,
    limit: Duration,
) -> Vec<Item> {
    let policy = Policy {
        idle: min_age,
        now: cx.policy.now,
    };
    let mut items = Vec::new();
    for dir in dirs.iter().filter(|d| !is_symlink(d)) {
        let entries: Vec<PathBuf> = if dir.is_dir() {
            let found = std::fs::read_dir(dir).into_iter().flatten().flatten();
            found.map(|e| e.path()).collect()
        } else {
            vec![dir.clone()]
        };
        for path in entries {
            let Ok(meta) = std::fs::symlink_metadata(&path) else {
                continue;
            };
            if meta.file_type().is_symlink() {
                continue;
            }
            let (modified, cut) = if meta.is_dir() {
                let (u, cut) = usage_until(&path, Instant::now() + limit);
                (u.modified, cut)
            } else {
                (meta.modified().ok(), false)
            };
            let entry = Cache {
                path: path.clone(),
                bytes: 0,
                modified,
            };
            let kept = if cut {
                Some(SCAN_STOPPED.into())
            } else {
                kept_because(&entry, false, false, &policy)
            };
            let kept = kept.or_else(|| meta.is_file().then(|| held_reason(&path)).flatten());
            items.push(make_item(kind, &path, evidence, kept, limit));
        }
    }
    items
}

/// Build output in agent worktrees: a tagged cache (`target/`) is judged by T152's rules, idle
/// and never the worktree the command runs from (T342); `dist/`, `.next/` and `__pycache__/`
/// carry no tag, so they are listed read-only (D36).
pub fn build_items(worktrees: &[PathBuf], cx: &Ctx, limit: Duration) -> Vec<Item> {
    let mut items = Vec::new();
    for wt in worktrees {
        let (usage, cut) = usage_until(wt, Instant::now() + limit);
        let current = cx.cwd.starts_with(real(wt));
        for cache in &usage.caches {
            let kept = if cut {
                Some(SCAN_STOPPED.into())
            } else {
                kept_because(cache, current, false, &cx.policy)
            };
            items.push(make_item("build", &cache.path, TAG, kept, limit));
        }
        let tagged = |p: &Path| usage.caches.iter().any(|c| c.path == p);
        let named = |name: &str, kind: &FileType| match kind.is_dir() {
            true if BUILD_DIRS.contains(&name) => Some("build"),
            true if DEPS.iter().any(|(d, _)| *d == name) => Some("deps"),
            _ => None,
        };
        let finds = find(wt, Instant::now() + limit, &tagged, &named);
        // A tagged `.venv` (uv writes the tag) is already `build` above.
        for (path, kind) in finds.into_iter().filter(|(p, _)| !tagged(p)) {
            let (class, kept) = match kind {
                "deps" => ("review", deps_reason(&path)),
                _ => ("safe", NOT_DOCUMENTED.into()),
            };
            items.push(Item {
                class,
                ..make_item(kind, &path, NO_EVIDENCE, Some(kept), limit)
            });
        }
    }
    items
}

/// The host and path of every agent worktree of the repository `cwd` is in: a linked worktree
/// bound to an agent (T285), seen by a session (T154) or made in a host's own pool (T289).
/// Without a repository or a store the list is short, never an error.
pub fn agent_worktrees(cfg: &Config, cwd: &Path) -> Vec<(&'static str, PathBuf)> {
    let Ok(mut rows) = list::rows(cwd) else {
        return Vec::new();
    };
    let store = Store::open(&cfg.core.db_path).ok();
    list::attribute_with_store(&mut rows, store.as_ref(), &cfg.agents.idle);
    rows.into_iter()
        .filter(|r| !matches!(r.state, "main" | "orphan" | "stale"))
        .filter_map(|r| {
            let named = r.agent.and_then(|a| a.host);
            let named = named.or(r.session.and_then(|s| s.host));
            let host = named.unwrap_or_else(|| r.origin.to_string());
            Some((*HOSTS.iter().find(|h| **h == host)?, r.path))
        })
        .collect()
}

/// Version directories of the rtok plugin that Claude Code unpacked and no longer uses: every
/// one under `<claude>/plugins/cache/rtok/rtok` that `installed_plugins.json` does not name as
/// an `installPath` (T279). A file that is missing, unreadable or names no rtok install says
/// nothing about what is in use, so then none is listed. T152's idle rule applies.
pub fn staging_caches(claude: &Path) -> Vec<Owned> {
    let text = std::fs::read_to_string(claude.join("plugins/installed_plugins.json"));
    let json: Option<serde_json::Value> = text.ok().and_then(|t| serde_json::from_str(&t).ok());
    let installs = json
        .as_ref()
        .and_then(|j| j.get("plugins")?.get(super::claude::PLUGIN_ID)?.as_array());
    let used: Vec<PathBuf> = installs
        .into_iter()
        .flatten()
        .filter_map(|i| Some(real(Path::new(i.get("installPath")?.as_str()?))))
        .collect();
    if used.is_empty() {
        return Vec::new();
    }
    let versions = std::fs::read_dir(claude.join(PLUGIN_CACHE));
    versions
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir() && !is_symlink(p) && !used.contains(&real(p)))
        .map(|path| Owned {
            path,
            evidence: RTOK_OWN,
            idle_rule: true,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::junk_cache::{DEFAULT_IDLE, age_files, tagged};
    use crate::testutil::tmp_dir;
    use std::time::SystemTime;

    const LIMIT: Duration = Duration::from_secs(10);

    fn put(path: &Path, bytes: &[u8]) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, bytes).unwrap();
    }

    fn age(path: &Path, secs: u64) {
        let f = std::fs::File::options().write(true).open(path).unwrap();
        f.set_modified(SystemTime::now() - Duration::from_secs(secs))
            .unwrap();
    }

    fn cx(cwd: &Path) -> Ctx {
        Ctx::new(cwd, DEFAULT_IDLE, SystemTime::now())
    }

    fn row<'a>(items: &'a [Item], name: &str) -> &'a Item {
        let hit = items.iter().find(|i| Path::new(&i.path).ends_with(name));
        hit.unwrap_or_else(|| panic!("no item ending in {name}: {items:?}"))
    }

    /// A vim swap file naming `pid`: the `b0` block, pid little-endian at byte 24.
    fn vim_swap(path: &Path, pid: u32) {
        let mut head = vec![0u8; 64];
        head[..2].copy_from_slice(b"b0");
        head[24..28].copy_from_slice(&pid.to_le_bytes());
        put(path, &head);
    }

    #[test]
    fn a_held_lock_is_kept_for_its_reason_and_package_lockfiles_are_never_listed() {
        let root = tmp_dir("kinds-locks");
        put(&root.join("Cargo.lock"), b"x");
        put(&root.join("yarn.lock"), b"x");
        put(&root.join("free.lock"), b"x");
        put(&root.join("held.lock"), b"x");
        put(&root.join("db/LOCK"), b"");
        put(&root.join("node_modules/dep.lock"), b"x");
        std::fs::create_dir_all(root.join("state/.lock")).unwrap();
        // Another open file description, so the probe conflicts as another process would.
        let holder = std::fs::File::open(root.join("held.lock")).unwrap();
        assert!(rtok_sys::try_lock_exclusive(&holder).unwrap());

        let items = found_items(std::slice::from_ref(&root), LIMIT);

        let mut names: Vec<_> = items
            .iter()
            .map(|i| Path::new(&i.path).strip_prefix(&root).unwrap().to_owned())
            .collect();
        names.sort();
        let want = ["db/LOCK", "free.lock", "held.lock", "state/.lock"];
        assert_eq!(names, want.map(PathBuf::from));
        assert_eq!(
            row(&items, "held.lock").kept.as_deref(),
            Some("held by a running process")
        );
        assert_eq!(
            row(&items, "free.lock").kept.as_deref(),
            Some(NOT_DOCUMENTED)
        );
        assert!(items.iter().all(|i| i.kind == "locks" && !i.counted()));
    }

    #[test]
    fn backup_copies_are_listed_read_only_and_rtoks_own_backup_folder_is_not_walked() {
        let root = tmp_dir("kinds-backups");
        put(&root.join("notes.txt~"), b"x");
        put(&root.join("a/settings.json.bak"), b"x");
        put(&root.join("_backup/settings.json.bak-1"), b"x");

        let items = found_items(std::slice::from_ref(&root), LIMIT);

        assert_eq!(items.len(), 2, "{items:?}");
        for i in &items {
            assert_eq!(
                (i.kind, i.class, i.kept.as_deref()),
                ("backups", "review", Some(NOT_DOCUMENTED))
            );
        }
    }

    #[test]
    fn a_swap_file_of_a_live_process_is_kept_for_that_and_a_dead_owner_is_only_not_documented() {
        let root = tmp_dir("kinds-swap");
        vim_swap(&root.join(".live.txt.swp"), std::process::id());
        // No process has this pid: the kernel's pid space ends far below it.
        vim_swap(&root.join(".dead.txt.swo"), 0x7fff_fff0);
        put(&root.join("page.crswap"), b"no header");
        put(&root.join("short.swp"), b"b0");
        #[cfg(unix)]
        {
            let me = format!("me@host.{}:1700000000", std::process::id());
            std::os::unix::fs::symlink(me, root.join(".#edit.rs")).unwrap();
        }

        let items = found_items(std::slice::from_ref(&root), LIMIT);

        let why = |n: &str| row(&items, n).kept.clone().unwrap();
        let live = format!("owner process {} is running", std::process::id());
        assert_eq!(why(".live.txt.swp"), live);
        assert_eq!(why(".dead.txt.swo"), NOT_DOCUMENTED);
        assert_eq!(why("page.crswap"), "owner unknown");
        assert_eq!(why("short.swp"), "owner unknown");
        #[cfg(unix)]
        assert_eq!(why(".#edit.rs"), live);
        assert!(items.iter().all(|i| i.kind == "swap" && !i.counted()));
    }

    #[test]
    fn temp_goes_when_idle_and_stays_when_fresh_held_or_a_link() {
        let root = tmp_dir("kinds-temp");
        let dir = root.join("shell-snapshots");
        put(&dir.join("old.sh"), &[b'x'; 100]);
        put(&dir.join("new.sh"), b"x");
        put(&dir.join("held.sh"), b"x");
        put(&dir.join("old-dir/f"), &[b'x'; 50]);
        for f in ["old.sh", "held.sh", "old-dir/f"] {
            age(&dir.join(f), 2 * 86_400);
        }
        let holder = std::fs::File::open(dir.join("held.sh")).unwrap();
        assert!(rtok_sys::try_lock_exclusive(&holder).unwrap());
        #[cfg(unix)]
        std::os::unix::fs::symlink(&root, dir.join("link")).unwrap();

        let items = temp_items(
            std::slice::from_ref(&dir),
            DEFAULT_IDLE,
            &cx(&tmp_dir("kinds-temp-cwd")),
            LIMIT,
        );

        assert_eq!(items.len(), 4, "a symlink is no item: {items:?}");
        for gone in ["old.sh", "old-dir"] {
            let i = row(&items, gone);
            assert!(i.counted() && i.evidence == SECTION_22 && i.kind == "temp");
        }
        assert_eq!(
            row(&items, "new.sh").kept.as_deref(),
            Some("modified within the idle window")
        );
        assert_eq!(
            row(&items, "held.sh").kept.as_deref(),
            Some("held by a running process")
        );
    }

    #[test]
    fn a_tagged_target_follows_t152_and_untagged_build_dirs_are_listed_only() {
        let wt = tmp_dir("kinds-build");
        put(&wt.join("src/main.rs"), b"fn main() {}");
        put(&wt.join("target/o"), &[b'x'; 200]);
        tagged(&wt.join("target"));
        put(&wt.join("target/pkg/__pycache__/c"), b"x");
        put(&wt.join("web/dist/app.js"), b"x");
        put(&wt.join("py/__pycache__/m.pyc"), b"x");
        put(&wt.join("node_modules/pkg/dist/i.js"), b"x");
        put(&wt.join("app/package.json"), b"{}");
        put(&wt.join("app/node_modules/m/i.js"), b"x");
        age_files(&wt, 3 * 86_400);

        let items = build_items(
            std::slice::from_ref(&wt),
            &cx(&tmp_dir("kinds-b-cwd")),
            LIMIT,
        );

        assert_eq!(items.len(), 5, "{items:?}");
        let deps = |n: &str| {
            let i = row(&items, n);
            assert_eq!((i.kind, i.class, i.counted()), ("deps", "review", false));
            i.kept.clone().unwrap()
        };
        assert_eq!(deps("app/node_modules"), NOT_DOCUMENTED);
        assert!(deps("node_modules").starts_with("no lockfile or manifest"));
        let target = row(&items, "target");
        assert!(target.counted() && target.evidence == TAG && target.kind == "build");
        for listed in ["web/dist", "py/__pycache__"] {
            let i = row(&items, listed);
            assert_eq!(
                (i.kept.as_deref(), i.evidence),
                (Some(NOT_DOCUMENTED), "none")
            );
        }

        let here = build_items(std::slice::from_ref(&wt), &cx(&wt.join("src")), LIMIT);
        assert_eq!(
            row(&here, "target").kept.as_deref(),
            Some("the worktree this command runs from; name its path to clean it")
        );
    }

    #[test]
    fn only_plugin_versions_the_host_record_no_longer_names_are_staging_junk() {
        let claude = tmp_dir("kinds-staging");
        let cache = claude.join(PLUGIN_CACHE);
        for v in ["0.1.0", "0.2.0", "0.3.0"] {
            put(&cache.join(v).join("plugin.json"), b"{}");
        }
        assert!(
            staging_caches(&claude).is_empty(),
            "no record, nothing is stale"
        );

        let record = |body: &str| {
            put(
                &claude.join("plugins/installed_plugins.json"),
                body.as_bytes(),
            )
        };
        record(r#"{"version":2,"plugins":{"other@x":[{"installPath":"/p"}]}}"#);
        assert!(staging_caches(&claude).is_empty(), "no rtok install named");
        record("not json");
        assert!(staging_caches(&claude).is_empty());

        let used = cache
            .join("0.2.0")
            .display()
            .to_string()
            .replace('\\', "\\\\");
        record(&format!(
            r#"{{"version":2,"plugins":{{"rtok@rtok":[{{"installPath":"{used}"}}]}}}}"#
        ));
        let stale = staging_caches(&claude);
        let names: Vec<_> = stale
            .iter()
            .map(|o| o.path.file_name().unwrap().to_str().unwrap().to_owned())
            .collect();
        assert_eq!(names.len(), 2);
        assert!(names.contains(&"0.1.0".into()) && names.contains(&"0.3.0".into()));
        assert!(stale.iter().all(|o| o.evidence == RTOK_OWN && o.idle_rule));
    }

    /// The worktree is made in Claude Code's own pool (`.claude/worktrees`), so its host is
    /// known without a store row (T289).
    #[test]
    fn a_worktree_in_a_hosts_pool_is_an_agent_worktree_and_the_main_checkout_is_not() {
        let (cfg, dir) = crate::testutil::config("kinds-agent-wt");
        let repo = dir.join("repo");
        std::fs::create_dir_all(&repo).unwrap();
        let git = |args: &[&str]| {
            let out = std::process::Command::new("git")
                .current_dir(&repo)
                .args([
                    "-c",
                    "user.name=t",
                    "-c",
                    "user.email=t@t",
                    "-c",
                    "commit.gpgsign=false",
                ])
                .args(args)
                .output()
                .unwrap();
            assert!(out.status.success(), "{args:?}: {out:?}");
        };
        git(&["init", "-q"]);
        git(&["commit", "-q", "--allow-empty", "-m", "m"]);
        let pool = repo.join(".claude/worktrees/w1");
        git(&["worktree", "add", "-q", "--detach", pool.to_str().unwrap()]);

        let found = agent_worktrees(&cfg, &repo);

        assert_eq!(found.len(), 1, "{found:?}");
        assert_eq!(found[0].0, "claude");
        assert!(Path::new(&found[0].1).ends_with("w1"));
        assert!(agent_worktrees(&cfg, &dir).is_empty(), "not a repository");
    }
}

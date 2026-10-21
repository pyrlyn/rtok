// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T953: a new worktree starts with the build caches of the sibling checkout that built them
//! last, cloned copy-on-write where the file system shares blocks, so it neither rebuilds every
//! dependency nor spends the disk twice. Best effort: the worktree exists before this runs.

use std::fs;
use std::path::{Component, Path, PathBuf};
use std::time::SystemTime;

use fs_kit::tree::{Cloned, clone_tree};

/// One cache filled: where in the checkout, from which sibling, and how the files went.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Seeded {
    pub dir: PathBuf,
    pub source: PathBuf,
    pub files: Cloned,
}

/// Seeds `checkout` from the other worktrees of its repository; a listing git refuses leaves
/// the caches empty, like any other failure here.
pub fn from_siblings(cwd: &Path, checkout: &Path, dirs: &[String]) {
    match super::git::list(cwd) {
        Ok(list) => {
            let siblings: Vec<PathBuf> = list
                .into_iter()
                .filter(|r| r.prunable.is_none())
                .map(|r| r.path)
                .collect();
            run(&siblings, checkout, dirs);
        }
        Err(e) => crate::log::stderr_ln(&format!("warning: caches not seeded: {e:#}")),
    }
}

/// Fills each `dirs` position of `checkout` that a sibling has and `checkout` lacks; prints one
/// line per cache, and a warning for a cache that failed, whose partial copy is removed.
pub fn run(siblings: &[PathBuf], checkout: &Path, dirs: &[String]) -> Vec<Seeded> {
    let mut seeded = Vec::new();
    for dir in positions(checkout, dirs) {
        let Some(source) = freshest(siblings, checkout, &dir) else {
            continue;
        };
        let dest = checkout.join(&dir);
        match clone_tree(&source.join(&dir), &dest) {
            Ok(files) => {
                crate::log::stderr_ln(&format!(
                    "seeded {} from {}: {} cloned, {} copied",
                    dir.display(),
                    source.display(),
                    files.cloned,
                    files.copied
                ));
                seeded.push(Seeded { dir, source, files });
            }
            Err(e) => {
                // A half-copied cache could hold outputs whose mtimes were not kept, which a
                // build would trust; an empty one only costs a rebuild.
                let _ = fs_kit::tree::remove_any(&dest);
                crate::log::stderr_ln(&format!("warning: {} not seeded: {e}", dir.display()));
            }
        }
    }
    seeded
}

/// The relative paths `dirs` names in `checkout`: each entry, and for a bare name the same name
/// inside every visible top-level directory too. Only free places count: the parent exists and
/// nothing (a tracked file, an earlier seed) is there yet. An entry that is absolute or climbs
/// with `..` is ignored, so a project config cannot point the copy outside the checkout.
fn positions(checkout: &Path, dirs: &[String]) -> Vec<PathBuf> {
    let tops: Vec<PathBuf> = fs::read_dir(checkout)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .filter(|e| !e.file_name().to_string_lossy().starts_with('.'))
        .map(|e| PathBuf::from(e.file_name()))
        .collect();
    let mut out = Vec::new();
    for dir in dirs.iter().map(PathBuf::from) {
        if dir.as_os_str().is_empty()
            || !dir.components().all(|c| matches!(c, Component::Normal(_)))
        {
            continue;
        }
        let bare = dir.components().count() == 1;
        let nested = tops.iter().filter(|_| bare).map(|top| top.join(&dir));
        for rel in std::iter::once(dir.clone()).chain(nested) {
            let at = checkout.join(&rel);
            let free = at.parent().is_some_and(Path::is_dir) && fs::symlink_metadata(&at).is_err();
            if free && !out.contains(&rel) {
                out.push(rel);
            }
        }
    }
    out
}

/// The sibling whose `dir` was built most recently. A link is never a source: it could pull a
/// tree from outside the sibling into the new checkout.
fn freshest(siblings: &[PathBuf], checkout: &Path, dir: &Path) -> Option<PathBuf> {
    let checkout = crate::fs::canon(checkout);
    siblings
        .iter()
        .filter(|s| crate::fs::canon(s) != checkout)
        .filter_map(|s| {
            let meta = fs::symlink_metadata(s.join(dir)).ok()?;
            meta.is_dir().then(|| (built(&s.join(dir)), s))
        })
        .max_by_key(|(when, _)| *when)
        .map(|(_, s)| s.clone())
}

/// When a build last wrote under `dir`: the newest mtime of it, its entries and theirs. Two
/// levels reach what every build touches (`target/debug/deps`, `node_modules/.package-lock.json`)
/// without walking the whole cache.
fn built(dir: &Path) -> SystemTime {
    let mtime = |p: &Path| fs::symlink_metadata(p).and_then(|m| m.modified()).ok();
    let entries = |p: &Path| -> Vec<PathBuf> {
        fs::read_dir(p)
            .into_iter()
            .flatten()
            .flatten()
            .map(|e| e.path())
            .collect()
    };
    let mut newest = mtime(dir);
    for child in entries(dir) {
        newest = newest.max(mtime(&child));
        if fs::symlink_metadata(&child).is_ok_and(|m| m.is_dir()) {
            newest = entries(&child)
                .iter()
                .map(|g| mtime(g))
                .fold(newest, Ord::max);
        }
    }
    newest.unwrap_or(SystemTime::UNIX_EPOCH)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::tmp_dir;
    use std::time::Duration;

    fn touch(path: &Path, secs: u64) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, path.to_string_lossy().as_bytes()).unwrap();
        let when = SystemTime::UNIX_EPOCH + Duration::from_secs(secs);
        fs::File::options()
            .write(true)
            .open(path)
            .unwrap()
            .set_modified(when)
            .unwrap();
    }

    fn dirs(names: &[&str]) -> Vec<String> {
        names.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn bare_names_match_one_level_down_and_only_free_places_count() {
        let tmp = tmp_dir("seed");
        let new = tmp.join("new");
        fs::create_dir_all(new.join("web/node_modules")).unwrap();
        fs::create_dir_all(new.join(".hidden")).unwrap();
        let got = positions(
            &new,
            &dirs(&["target", "node_modules", "a/b", "../up", "/abs", ""]),
        );
        let want = ["target", "web/target", "node_modules"].map(PathBuf::from);
        assert_eq!(
            got, want,
            "web/node_modules exists, .hidden is skipped, a/b has no parent, ../up and /abs are refused"
        );
    }

    #[test]
    fn the_sibling_that_built_last_is_the_source() {
        let tmp = tmp_dir("seed");
        let (old, recent, new) = (tmp.join("old"), tmp.join("recent"), tmp.join("new"));
        touch(&old.join("target/debug/deps/a"), 1_000);
        touch(&old.join("target/debug/x"), 1_000);
        touch(&recent.join("target/debug/x"), 2_000);
        fs::create_dir_all(&new).unwrap();
        let siblings = [old.clone(), new.clone(), recent.clone()];
        assert_eq!(freshest(&siblings, &new, Path::new("target")), Some(recent));
        assert_eq!(freshest(&siblings, &new, Path::new("node_modules")), None);
    }

    #[test]
    fn run_fills_free_caches_keeps_mtimes_and_counts_every_file() {
        let tmp = tmp_dir("seed");
        let (main, new) = (tmp.join("main"), tmp.join("new"));
        touch(&main.join("target/debug/deps/libx.rlib"), 1_000);
        touch(&main.join("web/node_modules/p/index.js"), 1_000);
        fs::create_dir_all(new.join("web")).unwrap();
        let seeded = run(
            &[main.clone(), new.clone()],
            &new,
            &dirs(&["target", "node_modules"]),
        );
        let got: Vec<_> = seeded
            .iter()
            .map(|s| (s.dir.clone(), s.files.cloned + s.files.copied))
            .collect();
        assert_eq!(
            got,
            [
                (PathBuf::from("target"), 1),
                (PathBuf::from("web/node_modules"), 1)
            ]
        );
        assert!(seeded.iter().all(|s| s.source == main));
        // APFS clones every file; elsewhere the same files arrive as copies.
        if cfg!(target_os = "macos") {
            assert!(seeded.iter().all(|s| s.files.copied == 0), "{seeded:?}");
        }
        let rlib = new.join("target/debug/deps/libx.rlib");
        assert_eq!(
            fs::read(&rlib).unwrap(),
            fs::read(main.join("target/debug/deps/libx.rlib")).unwrap()
        );
        let modified = fs::metadata(&rlib).unwrap().modified().unwrap();
        assert_eq!(
            modified,
            SystemTime::UNIX_EPOCH + Duration::from_secs(1_000)
        );
        // A second run finds nothing free and leaves the caches alone.
        assert!(run(&[main, new.clone()], &new, &dirs(&["target"])).is_empty());
    }

    #[cfg(unix)]
    #[test]
    fn a_failed_seed_leaves_no_partial_cache_and_no_link_is_a_source() {
        use std::os::unix::fs::PermissionsExt;
        let tmp = tmp_dir("seed");
        let (main, new) = (tmp.join("main"), tmp.join("new"));
        touch(&main.join("target/a"), 1_000);
        touch(&main.join("target/locked/b"), 1_000);
        fs::set_permissions(
            main.join("target/locked"),
            fs::Permissions::from_mode(0o000),
        )
        .unwrap();
        fs::create_dir_all(&new).unwrap();
        let elsewhere = tmp.join("elsewhere");
        touch(&elsewhere.join("secret"), 1_000);
        std::os::unix::fs::symlink(&elsewhere, main.join("node_modules")).unwrap();
        let seeded = run(
            &[main.clone(), new.clone()],
            &new,
            &dirs(&["target", "node_modules"]),
        );
        fs::set_permissions(
            main.join("target/locked"),
            fs::Permissions::from_mode(0o755),
        )
        .unwrap();
        assert!(seeded.is_empty(), "{seeded:?}");
        assert!(!new.join("target").exists() && !new.join("node_modules").exists());
    }
}

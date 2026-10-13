// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Where a project lives (T660.1): the one lexical walk from a directory to its git
//! checkout, and the one guard that keeps a path rtok builds inside it.
//!
//! Lexical only — `.git` files, `commondir` and `config` are read, git is never spawned, so
//! the hook budget (≤ 10 ms) holds. The walk runs over [`ReadFs`] so callers can test it on an
//! in-memory filesystem.

use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};

use fs_kit::path::{normalize, path_starts_with};

/// Read a file as text and canonicalize, the two lookups the walk needs.
pub trait ReadFs {
    fn read_to_string(&self, path: &Path) -> io::Result<String>;
    /// Canonical path when the entry exists; `None` if missing (caller keeps the lexical abs).
    fn canonicalize(&self, path: &Path) -> Option<PathBuf>;
}

/// Host disk backend.
#[derive(Clone, Copy, Debug, Default)]
pub struct HostFs;

impl ReadFs for HostFs {
    fn read_to_string(&self, path: &Path) -> io::Result<String> {
        std::fs::read_to_string(path)
    }

    fn canonicalize(&self, path: &Path) -> Option<PathBuf> {
        fs_kit::path::canonicalize(path).ok()
    }
}

/// The git checkout a directory belongs to. The two roots differ only inside a linked
/// worktree: per-checkout files (`.rtok.toml`) belong to `checkout`, per-project identity and
/// where worktrees are placed belong to `main`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProjectRoot {
    /// The directory holding the nearest `.git` entry: a linked worktree's own directory.
    pub checkout: PathBuf,
    /// The main checkout, the parent of the common `.git`. A submodule is its own project, so
    /// it and a plain checkout have `main == checkout`.
    pub main: PathBuf,
    /// The common `.git` directory (`<main>/.git`), where `config` and the worktree admin
    /// dirs live.
    pub git_dir: PathBuf,
}

impl ProjectRoot {
    /// The project `cwd` is in, or `None` outside a git checkout.
    pub fn find(cwd: &Path) -> Option<Self> {
        Self::find_with(&HostFs, cwd)
    }

    pub fn find_with(fs: &impl ReadFs, cwd: &Path) -> Option<Self> {
        let mut dir = cwd.to_path_buf();
        loop {
            let dot = dir.join(".git");
            let git_dir = match fs.read_to_string(&dot) {
                // A linked worktree: `.git` is a file naming its admin dir, whose `commondir`
                // names the main checkout's `.git`. Anything else with a `.git` file (a
                // submodule) stays its own project.
                Ok(text) => common_dir(fs, &dir, &text).unwrap_or(dot),
                // Missing on the host (Vfs also answers NotFound for a directory key, so the
                // existence check is `canonicalize`): keep climbing.
                Err(e) if e.kind() == ErrorKind::NotFound && fs.canonicalize(&dot).is_none() => {
                    if !dir.pop() {
                        return None;
                    }
                    continue;
                }
                // Exists but does not read as a file: the `.git` directory of a main checkout.
                Err(_) => dot,
            };
            let main = git_dir.parent()?.to_path_buf();
            return Some(Self {
                checkout: dir,
                main,
                git_dir,
            });
        }
    }
}

/// `<main>/.git` for a worktree whose `.git` file reads `gitdir: <admin dir>`; both that path
/// and the admin dir's `commondir` may be relative (`worktree.useRelativePaths`).
fn common_dir(fs: &impl ReadFs, root: &Path, dot_git: &str) -> Option<PathBuf> {
    let gitdir = dot_git
        .lines()
        .find_map(|l| l.strip_prefix("gitdir:"))?
        .trim();
    let gitdir = normalize(root, Path::new(gitdir));
    let common = fs.read_to_string(&gitdir.join("commondir")).ok()?;
    Some(normalize(&gitdir, Path::new(common.trim())))
}

/// Symlinks resolved through the deepest ancestor that exists, the rest appended: a path
/// that does not exist yet still has to be judged by where it would land.
pub fn resolved(path: &Path) -> PathBuf {
    // Not std canonicalize: its `\\?\` prefix makes `starts_with` miss the temp dir.
    let real = |a: &Path| {
        let canon = fs_kit::path::canonicalize(a).ok()?;
        let rel = path.strip_prefix(a).ok()?;
        // The tail does not exist, so it holds no symlink and its `..` is safe to apply lexically.
        Some(normalize(&canon, rel))
    };
    let landed = path.ancestors().find_map(real);
    landed.unwrap_or_else(|| fs_kit::path::canon(path))
}

/// `path` (relative ones start at `root`) as the place it lands, with symlinks followed before
/// any `..`; `PermissionDenied` when that is not `root` or below it. The one guard for a path
/// rtok builds from untrusted input — a lexical `..` check alone misses a symlink inside the
/// root that points out of it.
pub fn contain(root: &Path, path: &Path) -> io::Result<PathBuf> {
    // Joined, not normalized: `link/..` is the link target's parent, which only the OS knows.
    let landed = resolved(&root.join(path));
    if path_starts_with(&landed, &resolved(root)) {
        return Ok(landed);
    }
    Err(io::Error::new(
        ErrorKind::PermissionDenied,
        format!(
            "{} resolves to {}, outside {}",
            path.display(),
            landed.display(),
            root.display()
        ),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// A fresh directory, symlink-free so `/var` -> `/private/var` cannot colour a comparison.
    fn tmp(name: &str) -> PathBuf {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let n = NEXT.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!("rtok-root-{name}-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        fs_kit::path::canon(&dir)
    }

    fn write(path: &Path, body: &str) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, body).unwrap();
    }

    /// `<base>/main` with a `.git` dir and one linked worktree `<base>/wt`; `gitdir` and
    /// `commondir` are written as `relative` or absolute paths.
    fn linked(base: &Path, relative: bool) {
        let (main, wt) = (base.join("main"), base.join("wt"));
        let admin = main.join(".git/worktrees/wt");
        write(&main.join(".git/HEAD"), "ref: refs/heads/main\n");
        let (commondir, gitdir) = if relative {
            (
                "../..\n".to_string(),
                "../main/.git/worktrees/wt".to_string(),
            )
        } else {
            (
                format!("{}\n", main.join(".git").display()),
                admin.display().to_string(),
            )
        };
        write(&admin.join("commondir"), &commondir);
        write(&wt.join(".git"), &format!("gitdir: {gitdir}\n"));
        write(&wt.join("src/deep/a.rs"), "");
    }

    #[test]
    fn main_checkout_found_from_deep_inside() {
        let base = tmp("main");
        write(&base.join("repo/.git/HEAD"), "");
        write(&base.join("repo/a/b/c/x.rs"), "");
        let root = ProjectRoot::find(&base.join("repo/a/b/c")).unwrap();
        let repo = base.join("repo");
        assert_eq!(root.checkout, repo);
        assert_eq!(root.main, repo);
        assert_eq!(root.git_dir, repo.join(".git"));
    }

    #[test]
    fn linked_worktree_has_its_own_checkout_and_the_main_one() {
        for relative in [false, true] {
            let base = tmp("linked");
            linked(&base, relative);
            let root = ProjectRoot::find(&base.join("wt/src/deep")).unwrap();
            assert_eq!(root.checkout, base.join("wt"), "relative={relative}");
            assert_eq!(root.main, base.join("main"), "relative={relative}");
            assert_eq!(root.git_dir, base.join("main/.git"), "relative={relative}");
        }
    }

    #[test]
    fn submodule_is_its_own_project() {
        let base = tmp("sub");
        write(&base.join("repo/.git/modules/dep/HEAD"), "");
        write(
            &base.join("repo/vendor/dep/.git"),
            "gitdir: ../../.git/modules/dep\n",
        );
        write(&base.join("repo/vendor/dep/src/a.rs"), "");
        let root = ProjectRoot::find(&base.join("repo/vendor/dep/src")).unwrap();
        let dep = base.join("repo/vendor/dep");
        assert_eq!(root.checkout, dep);
        assert_eq!(root.main, dep);
    }

    #[test]
    fn outside_any_repo_is_none() {
        let base = tmp("none");
        write(&base.join("scratch/a.txt"), "");
        // The temp dir could itself sit in a repository on an odd machine; the walk must at
        // least not report one below `base`.
        let found = ProjectRoot::find(&base.join("scratch"));
        assert!(found.is_none_or(|r| !r.checkout.starts_with(&base)));
    }

    #[test]
    fn contain_accepts_what_stays_inside() {
        let root = tmp("in");
        write(&root.join("a/b.txt"), "");
        assert_eq!(
            contain(&root, Path::new("a/b.txt")).unwrap(),
            root.join("a/b.txt")
        );
        assert_eq!(
            contain(&root, Path::new("a/../a/b.txt")).unwrap(),
            root.join("a/b.txt")
        );
        assert_eq!(contain(&root, Path::new(".")).unwrap(), root);
        assert_eq!(contain(&root, &root).unwrap(), root);
        assert_eq!(
            contain(&root, &root.join("a/new/deeper.txt")).unwrap(),
            root.join("a/new/deeper.txt")
        );
    }

    #[test]
    fn contain_refuses_what_leaves() {
        let base = tmp("out");
        let root = base.join("root");
        write(&root.join("a/b.txt"), "");
        write(&base.join("other/secret"), "");
        for path in [
            PathBuf::from("../other/secret"),
            PathBuf::from("a/../../other/secret"),
            base.join("other/secret"),
            // A sibling that shares the root's name as a prefix is not inside it.
            base.join("root-sibling/x"),
        ] {
            let err = contain(&root, &path).unwrap_err();
            assert_eq!(err.kind(), ErrorKind::PermissionDenied, "{path:?}");
            assert!(
                err.to_string().contains(&root.display().to_string()),
                "{err}"
            );
        }
    }

    #[cfg(unix)]
    #[test]
    fn contain_follows_symlinks() {
        let base = tmp("link");
        let root = base.join("root");
        write(&root.join("real/file.txt"), "");
        write(&base.join("other/secret"), "");
        std::os::unix::fs::symlink(base.join("other"), root.join("escape")).unwrap();
        std::os::unix::fs::symlink(root.join("real"), root.join("alias")).unwrap();

        assert!(contain(&root, Path::new("escape/secret")).is_err());
        // Not yet existing below an escaping link is still an escape.
        assert!(contain(&root, Path::new("escape/new/file")).is_err());
        // The OS takes `..` after the link, not before it: lexically this is `root/secret`.
        std::fs::create_dir_all(base.join("other/inner")).unwrap();
        std::os::unix::fs::symlink(base.join("other/inner"), root.join("hop")).unwrap();
        assert!(contain(&root, Path::new("hop/../secret")).is_err());
        assert!(contain(&root, Path::new("hop/../made/up")).is_err());
        assert_eq!(
            contain(&root, Path::new("alias/file.txt")).unwrap(),
            root.join("real/file.txt")
        );
    }
}

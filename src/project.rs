// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Project identity (T133): the checkout `cwd` belongs to, resolved so that a linked git
//! worktree and its main checkout share one name — for notes (`memory`) and for the
//! `sessions.project` every hook call attributes (T154).
//!
//! Lexical only — `.git` files, `commondir` and `config` are read, git is never spawned, so
//! the hook budget (≤ 10 ms) holds. Runs over [`ReadFs`] so unit tests use `Vfs`.

use std::io::ErrorKind;
use std::path::{Path, PathBuf};

pub mod refs;

use crate::fs::{HostFs, ReadFs, normalize};

/// Name of the project `cwd` lies in, if it is inside a git checkout: the `origin` repo name
/// (`git@host:owner/repo.git`, `https://…/repo`, `file:///…/repo.git` → `repo`), else the
/// basename of the main checkout. A linked worktree resolves to its main checkout's name.
pub fn project_name(cwd: &Path) -> Option<String> {
    project_name_with(&HostFs, cwd)
}

pub(crate) fn project_name_with(fs: &impl ReadFs, cwd: &Path) -> Option<String> {
    let common = common_git_dir(fs, cwd)?;
    let main = common.parent()?;
    origin_url(fs, &common)
        .and_then(|url| url_name(&url))
        .or_else(|| main.file_name().map(|s| s.to_string_lossy().into_owned()))
}

/// The key rtok's per-project state is filed under (T441.3 task counters): the `origin`
/// remote as `host/owner/repo`, lower case, so two clones of one repository share it whatever
/// protocol they cloned with; without an `origin`, the main checkout's path. A linked worktree
/// resolves to its main checkout's key.
pub fn project_key(cwd: &Path) -> Option<String> {
    project_key_with(&HostFs, cwd)
}

pub(crate) fn project_key_with(fs: &impl ReadFs, cwd: &Path) -> Option<String> {
    let common = common_git_dir(fs, cwd)?;
    if let Some(url) = origin_url(fs, &common).and_then(|u| canonical_url(&u)) {
        return Some(url);
    }
    let main = common.parent()?;
    let main = fs.canonicalize(main).unwrap_or_else(|| main.to_path_buf());
    Some(main.to_string_lossy().into_owned())
}

/// `<main>/.git` of the checkout `cwd` lies in, through a linked worktree's `commondir`.
fn common_git_dir(fs: &impl ReadFs, cwd: &Path) -> Option<PathBuf> {
    let mut root = cwd.to_path_buf();
    let common = loop {
        let dot = root.join(".git");
        match fs.read_to_string(&dot) {
            // A linked worktree: `.git` is a file naming its admin dir, whose `commondir`
            // names the main checkout's `.git`. Anything else with a `.git` file (a
            // submodule) keeps the old answer: this directory is the project.
            Ok(text) => break common_dir(fs, &root, &text).unwrap_or(dot),
            // Missing on the host (Vfs also answers NotFound for a directory key, so the
            // existence check is `canonicalize`): keep climbing.
            Err(e) if e.kind() == ErrorKind::NotFound && fs.canonicalize(&dot).is_none() => {}
            // Exists but does not read as a file: the `.git` directory of a main checkout.
            Err(_) => break dot,
        }
        if !root.pop() {
            return None;
        }
    };
    Some(common)
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

/// `[remote "origin"] url` in `<common>/config`.
fn origin_url(fs: &impl ReadFs, common: &Path) -> Option<String> {
    let config = fs.read_to_string(&common.join("config")).ok()?;
    let mut in_origin = false;
    for line in config.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            in_origin = line == r#"[remote "origin"]"#;
            continue;
        }
        let Some((key, url)) = line.split_once('=') else {
            continue;
        };
        if in_origin && key.trim() == "url" {
            return Some(url.trim().to_string());
        }
    }
    None
}

/// Last path segment of a remote URL, without `.git`.
fn url_name(url: &str) -> Option<String> {
    let name = url
        .trim_end_matches('/')
        .rsplit(['/', ':'])
        .next()?
        .trim_end_matches(".git");
    (!name.is_empty()).then(|| name.to_string())
}

/// `git@github.com:Owner/Repo.git`, `ssh://git@github.com/owner/repo` and
/// `https://user@github.com/owner/repo/` → `github.com/owner/repo`. A `file://` or plain path
/// remote keeps its path. `None` for an empty URL.
fn canonical_url(url: &str) -> Option<String> {
    let url = url.trim().trim_end_matches('/');
    let url = url.strip_suffix(".git").unwrap_or(url);
    let (rest, scp) = match url.split_once("://") {
        Some((_, rest)) => (rest, false),
        None => (url, true),
    };
    // Userinfo sits before the host; an `@` later in the path is part of the path.
    let rest = match rest.find('@') {
        Some(i) if !rest[..i].contains('/') => &rest[i + 1..],
        _ => rest,
    };
    // scp form `host:owner/repo`; a local path (`/srv/repo`, `C:\repo`) has no host part.
    let rest = match rest.split_once(':') {
        Some((host, path)) if scp && !host.contains(['/', '\\']) && host.len() > 1 => {
            format!("{host}/{}", path.trim_start_matches('/'))
        }
        _ => rest.to_string(),
    };
    (!rest.is_empty()).then(|| rest.to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::Vfs;
    use rstest::rstest;

    /// A main checkout with an `origin` and one linked worktree whose paths are relative.
    fn fixture(url: &str) -> Vfs {
        let mut vfs = Vfs::new();
        vfs.write(
            "/home/me/rtok/.git/config",
            format!("[core]\n\tbare = false\n[remote \"origin\"]\n\turl = {url}\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n"),
        );
        vfs.write("/home/me/rtok/.git/HEAD", "ref: refs/heads/main\n");
        vfs.write(
            "/home/me/rtok/.git/worktrees/rtok-t133/commondir",
            "../..\n",
        );
        vfs.write(
            "/home/me/rtok/.git/worktrees/rtok-t133/gitdir",
            "/home/me/_worktrees/rtok-t133/.git\n",
        );
        vfs.write(
            "/home/me/_worktrees/rtok-t133/.git",
            "gitdir: ../../rtok/.git/worktrees/rtok-t133\n",
        );
        vfs.write("/home/me/_worktrees/rtok-t133/src/lib.rs", "");
        vfs
    }

    #[test]
    fn main_checkout_and_linked_worktree_resolve_to_one_project() {
        let vfs = fixture("git@github.com:listepo/rtok-notes.git");
        let main = project_name_with(&vfs, Path::new("/home/me/rtok/src"));
        let linked = project_name_with(&vfs, Path::new("/home/me/_worktrees/rtok-t133/src"));
        assert_eq!(main.as_deref(), Some("rtok-notes"));
        assert_eq!(linked, main);
    }

    #[test]
    fn plain_directory_is_no_project() {
        let mut vfs = Vfs::new();
        vfs.write("/home/me/scratch/notes.txt", "");
        assert_eq!(project_name_with(&vfs, Path::new("/home/me/scratch")), None);
    }

    #[test]
    fn without_origin_the_main_checkout_basename_names_the_project() {
        let mut vfs = Vfs::new();
        vfs.write("/home/me/local-only/.git/HEAD", "ref: refs/heads/main\n");
        vfs.write("/home/me/local-only/.git/worktrees/wt/commondir", "../..\n");
        vfs.write(
            "/home/me/wt/.git",
            "gitdir: /home/me/local-only/.git/worktrees/wt\n",
        );
        assert_eq!(
            project_name_with(&vfs, Path::new("/home/me/local-only")).as_deref(),
            Some("local-only")
        );
        assert_eq!(
            project_name_with(&vfs, Path::new("/home/me/wt")).as_deref(),
            Some("local-only")
        );
    }

    #[rstest]
    #[case("https://github.com/pyrlyn/rtok", "rtok")]
    #[case("https://github.com/pyrlyn/rtok.git/", "rtok")]
    #[case("ssh://git@github.com/pyrlyn/rtok.git", "rtok")]
    #[case("file:///srv/git/rtok.git", "rtok")]
    fn origin_url_shapes_give_the_repo_name(#[case] url: &str, #[case] name: &str) {
        let vfs = fixture(url);
        assert_eq!(
            project_name_with(&vfs, Path::new("/home/me/rtok")).as_deref(),
            Some(name)
        );
    }

    /// A submodule's `.git` file points at `modules/<name>` (no `commondir`): the submodule
    /// directory stays its own project, as before T133.
    #[test]
    fn submodule_keeps_its_own_name() {
        let mut vfs = fixture("https://github.com/pyrlyn/rtok");
        vfs.write(
            "/home/me/rtok/vendor/dep/.git",
            "gitdir: ../../.git/modules/dep\n",
        );
        vfs.write(
            "/home/me/rtok/.git/modules/dep/HEAD",
            "ref: refs/heads/main\n",
        );
        assert_eq!(
            project_name_with(&vfs, Path::new("/home/me/rtok/vendor/dep/src")).as_deref(),
            Some("dep")
        );
    }

    #[rstest]
    #[case("git@github.com:Pyrlyn/rtok.git", "github.com/pyrlyn/rtok")]
    #[case("ssh://git@github.com/pyrlyn/rtok", "github.com/pyrlyn/rtok")]
    #[case("https://me@github.com/pyrlyn/rtok/", "github.com/pyrlyn/rtok")]
    #[case("https://gitlab.example.com/g/sub/n.git", "gitlab.example.com/g/sub/n")]
    #[case("https://host/a/b@v2", "host/a/b@v2")]
    #[case("file:///srv/git/rtok.git", "/srv/git/rtok")]
    #[case("/srv/git/rtok", "/srv/git/rtok")]
    fn remotes_canonicalize_across_protocols(#[case] url: &str, #[case] key: &str) {
        assert_eq!(canonical_url(url).as_deref(), Some(key));
    }

    #[test]
    fn worktrees_share_the_remote_key_and_fall_back_to_the_main_path() {
        let vfs = fixture("git@github.com:listepo/rtok.git");
        let main = project_key_with(&vfs, Path::new("/home/me/rtok/src"));
        let linked = project_key_with(&vfs, Path::new("/home/me/_worktrees/rtok-t133/src"));
        assert_eq!(main.as_deref(), Some("github.com/listepo/rtok"));
        assert_eq!(linked, main);

        let mut vfs = Vfs::new();
        vfs.write("/home/me/local-only/.git/HEAD", "ref: refs/heads/main\n");
        vfs.write("/home/me/local-only/a.rs", "");
        let key = project_key_with(&vfs, Path::new("/home/me/local-only"));
        assert_eq!(key.as_deref(), Some("/home/me/local-only"));
        assert_eq!(project_key_with(&vfs, Path::new("/home/me")), None);
    }
}

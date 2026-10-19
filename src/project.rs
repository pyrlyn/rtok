// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Project identity (T133): the checkout `cwd` belongs to, resolved so that a linked git
//! worktree and its main checkout share one name — for notes (`memory`) and for the
//! `sessions.project` every hook call attributes (T154).
//!
//! Lexical only — the checkout walk is [`ProjectRoot`]; here `config` is read, git is never
//! spawned, so the hook budget (≤ 10 ms) holds. Runs over [`ReadFs`] so unit tests use `Vfs`.

use std::path::{Path, PathBuf};

pub mod refs;

use crate::fs::{HostFs, ReadFs, normalize};
use rtok_config::root::ProjectRoot;

/// Name of the project `cwd` lies in, if it is inside a git checkout: the `origin` repo name
/// (`git@host:owner/repo.git`, `https://…/repo`, `file:///…/repo.git` → `repo`), else the
/// basename of the main checkout. A linked worktree resolves to its main checkout's name.
pub fn project_name(cwd: &Path) -> Option<String> {
    project_name_with(&HostFs, cwd)
}

pub(crate) fn project_name_with(fs: &impl ReadFs, cwd: &Path) -> Option<String> {
    let root = ProjectRoot::find_with(fs, cwd)?;
    origin_url(fs, &root.git_dir)
        .and_then(|url| url_name(&url))
        .or_else(|| {
            root.main
                .file_name()
                .map(|s| s.to_string_lossy().into_owned())
        })
}

/// The key rtok's per-project state is filed under (T441.3 task counters): the `origin`
/// remote as `host/owner/repo`, lower case, so two clones of one repository share it whatever
/// protocol they cloned with; a filesystem `origin` as its absolute path (a relative one is
/// read from the main checkout); without an `origin`, the main checkout's path. A linked
/// worktree resolves to its main checkout's key. Counters filed under an older key are not
/// moved: `task create` raises a new key's counter past every id the adapter holds.
pub fn project_key(cwd: &Path) -> Option<String> {
    project_key_with(&HostFs, cwd)
}

/// The directory a tool should treat as the project when the launch cwd is not one
/// (`$HOME`, `/`). `cwd` when it lies in a git checkout; else the first of `extra` (the
/// client's `roots/list` directories) that does. [`Err`] otherwise — the message says to
/// pass a path or open a project, and it is never git's `fatal: not a git repository`.
pub fn project_dir(cwd: &Path, extra: &[PathBuf]) -> anyhow::Result<PathBuf> {
    if ProjectRoot::find(cwd).is_some() {
        return Ok(cwd.to_path_buf());
    }
    if let Some(root) = extra.iter().find(|r| ProjectRoot::find(r).is_some()) {
        return Ok(root.clone());
    }
    Err(no_project_root(cwd))
}

/// The one wording when neither the cwd nor any extra root lies in a repository.
pub fn no_project_root(cwd: &Path) -> anyhow::Error {
    anyhow::anyhow!(
        "no project root: rtok mcp runs in {}; pass a path or open a project",
        crate::fs::canon(cwd).display()
    )
}

pub(crate) fn project_key_with(fs: &impl ReadFs, cwd: &Path) -> Option<String> {
    let root = ProjectRoot::find_with(fs, cwd)?;
    let (main, origin) = (&root.main, origin_url(fs, &root.git_dir));
    // A relative path means a different repository from each checkout, so it is resolved
    // before it is a key (T586).
    if let Some(path) = origin.as_deref().and_then(local_remote) {
        return Some(local_key(fs, main, &path));
    }
    if let Some(url) = origin.and_then(|u| canonical_url(&u)) {
        return Some(url);
    }
    let main = fs.canonicalize(main).unwrap_or_else(|| main.clone());
    Some(main.to_string_lossy().into_owned())
}

/// The filesystem path an `origin` URL names, or `None` for a network remote (`scheme://`,
/// or scp's `host:path`). A path, relative ones included, is any URL that is neither.
fn local_remote(url: &str) -> Option<String> {
    let url = url.trim();
    if let Some(rest) = url.strip_prefix("file://") {
        // `file:///srv/x`, `file://localhost/srv/x`; `file:///C:/x` is a Windows drive.
        let path = rest.strip_prefix("localhost").unwrap_or(rest);
        let drive = path
            .as_bytes()
            .get(1..3)
            .is_some_and(|d| d[0].is_ascii_alphabetic() && d[1] == b':');
        return Some(if drive { &path[1..] } else { path }.to_string());
    }
    let scp = url
        .split_once(':')
        .is_some_and(|(host, _)| !host.contains(['/', '\\']) && host.len() > 1);
    (!url.is_empty() && !url.contains("://") && !scp).then(|| url.to_string())
}

/// `path` as the absolute, symlink-free (when it exists) location of the remote, without a
/// `.git` suffix or trailing separator, so every spelling of one remote gives one key. Relative
/// paths start at the main checkout, which a linked worktree shares. Case is kept: the
/// filesystem, not rtok, decides whether two spellings are one directory.
fn local_key(fs: &impl ReadFs, main: &Path, path: &str) -> String {
    // Normalising the whole join rebuilds `main` too: a main checkout reached through a
    // worktree's `commondir` and one reached from cwd can differ in separators on Windows,
    // and a missing remote never reaches `canonicalize` to even them out.
    let abs = normalize(Path::new(""), &main.join(path));
    let abs = fs.canonicalize(&abs).unwrap_or(abs);
    let abs = abs.to_string_lossy();
    let abs = abs.trim_end_matches(['/', '\\']);
    abs.strip_suffix(".git").unwrap_or(abs).to_string()
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

    /// T647.1: the walk over the in-memory filesystem gives the same two roots as on disk.
    #[test]
    fn project_root_separates_the_worktree_from_the_main_checkout() {
        let vfs = fixture("https://github.com/pyrlyn/rtok");
        let linked = ProjectRoot::find_with(&vfs, Path::new("/home/me/_worktrees/rtok-t133/src"));
        let linked = linked.unwrap();
        assert_eq!(linked.checkout, Path::new("/home/me/_worktrees/rtok-t133"));
        assert_eq!(linked.main, Path::new("/home/me/rtok"));
        assert_eq!(linked.git_dir, Path::new("/home/me/rtok/.git"));
        let main = ProjectRoot::find_with(&vfs, Path::new("/home/me/rtok")).unwrap();
        assert_eq!(
            (main.checkout, main.main),
            ("/home/me/rtok".into(), "/home/me/rtok".into())
        );
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

    /// The fixtures name Unix-style paths; on Windows a joined path keeps the `/` of its base
    /// and gets `\` for the rest, so only the separator differs from the expectation.
    fn slashed(key: Option<String>) -> Option<String> {
        key.map(|k| k.replace('\\', "/"))
    }

    /// A checkout `<dir>/checkout` whose `origin` is spelled `url`; `remote` also exists as a
    /// directory beside it when `remote_exists`.
    fn local_remote_fixture(vfs: &mut Vfs, dir: &str, url: &str, remote_exists: bool) {
        vfs.write(
            format!("{dir}/checkout/.git/config"),
            format!("[remote \"origin\"]\n\turl = {url}\n"),
        );
        if remote_exists {
            vfs.write(format!("{dir}/remote.git/HEAD"), "ref: refs/heads/main\n");
        }
    }

    /// T586: the same relative `origin` in two checkouts is two different repositories, so two
    /// different keys, whether or not the remote exists yet.
    #[rstest]
    fn one_relative_origin_in_two_checkouts_is_two_keys(#[values(true, false)] exists: bool) {
        let mut vfs = Vfs::new();
        local_remote_fixture(&mut vfs, "/a", "../remote.git", exists);
        local_remote_fixture(&mut vfs, "/b", "../remote.git", exists);
        let key = |dir: &str| slashed(project_key_with(&vfs, Path::new(dir)));
        assert_eq!(key("/a/checkout").as_deref(), Some("/a/remote"));
        assert_eq!(key("/b/checkout").as_deref(), Some("/b/remote"));
    }

    /// T586: relative, absolute and `file://` spellings of one local remote are one key, and a
    /// linked worktree files under its main checkout's.
    #[rstest]
    #[case("../remote.git")]
    #[case("../remote.git/")]
    #[case("../x/../remote")]
    #[case("/a/remote.git")]
    #[case("file:///a/remote.git")]
    #[case("file://localhost/a/remote.git")]
    fn spellings_of_one_local_remote_share_a_key(#[case] url: &str) {
        let mut vfs = Vfs::new();
        local_remote_fixture(&mut vfs, "/a", url, true);
        vfs.write("/a/checkout/.git/worktrees/wt/commondir", "../..\n");
        vfs.write("/wt/.git", "gitdir: /a/checkout/.git/worktrees/wt\n");
        let main = project_key_with(&vfs, Path::new("/a/checkout/src"));
        assert_eq!(slashed(main.clone()).as_deref(), Some("/a/remote"));
        assert_eq!(project_key_with(&vfs, Path::new("/wt")), main);
    }

    /// T586: only filesystem remotes are resolved; network forms and their lower-casing stay.
    #[rstest]
    #[case("git@github.com:Owner/Repo.git", "github.com/owner/repo")]
    #[case("github.com:Owner/Repo", "github.com/owner/repo")]
    #[case("https://Example.com/Owner/Repo", "example.com/owner/repo")]
    fn network_remotes_are_not_resolved_against_the_checkout(#[case] url: &str, #[case] key: &str) {
        let mut vfs = Vfs::new();
        local_remote_fixture(&mut vfs, "/a", url, false);
        assert_eq!(
            project_key_with(&vfs, Path::new("/a/checkout")).as_deref(),
            Some(key)
        );
    }

    /// T586: a local path keeps its case; the filesystem decides what is equal.
    #[test]
    fn a_missing_local_remote_keeps_its_spelled_case() {
        let mut vfs = Vfs::new();
        local_remote_fixture(&mut vfs, "/a", "../Remote With Space.git", false);
        assert_eq!(
            slashed(project_key_with(&vfs, Path::new("/a/checkout"))).as_deref(),
            Some("/a/Remote With Space")
        );
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

    /// cwd wins when it lies in a checkout; else a client root that does; else the wording
    /// that tells the caller to pass a path or open a project.
    #[test]
    fn project_dir_prefers_cwd_then_extra_and_never_git_fatal() {
        let base = std::env::temp_dir().join(format!("rtok-project-dir-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        let (home, repo, plain) = (base.join("home"), base.join("repo"), base.join("plain"));
        for d in [&home, &repo, &plain] {
            std::fs::create_dir_all(d).unwrap();
        }
        std::fs::create_dir_all(repo.join(".git")).unwrap();
        // cwd itself is in a checkout: cwd wins, even with other extras around.
        let got = project_dir(&repo, &[home.clone(), plain.clone()]).unwrap();
        assert_eq!(got, repo);
        // cwd is not a checkout: the extra that is, in order.
        let got = project_dir(&home, &[plain.clone(), repo.clone()]).unwrap();
        assert_eq!(got, repo);
        // A subdirectory of a checkout still counts as "cwd lies in one".
        let src = repo.join("src");
        std::fs::create_dir_all(&src).unwrap();
        assert_eq!(project_dir(&src, &[]).unwrap(), src);
        // Neither cwd nor any extra: the wording, never git's fatal.
        let err = project_dir(&home, std::slice::from_ref(&plain))
            .unwrap_err()
            .to_string();
        assert!(err.contains("no project root"), "{err}");
        assert!(err.contains("pass a path or open a project"), "{err}");
        assert!(!err.contains("fatal:"), "{err}");
        let _ = std::fs::remove_dir_all(base);
    }
}

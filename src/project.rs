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
    project_keys(cwd).map(|k| k.key)
}

/// A project's current key and, when state was filed under a different one before T586.1, the
/// legacy key it may still sit under.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectKeys {
    pub key: String,
    /// The pre-T586.1 key of a local-path `origin` (`../remote.git` → `../remote`); `None`
    /// when it equals `key`. Several repositories can share it, so it is only ever read.
    pub legacy: Option<String>,
}

/// Keys to read persisted rows under, current first.
pub fn read_keys<'a>(key: &'a str, legacy: Option<&'a str>) -> Vec<&'a str> {
    std::iter::once(key).chain(legacy).collect()
}

pub fn project_keys(cwd: &Path) -> Option<ProjectKeys> {
    project_keys_with(&HostFs, cwd)
}

pub(crate) fn project_keys_with(fs: &impl ReadFs, cwd: &Path) -> Option<ProjectKeys> {
    let common = common_git_dir(fs, cwd)?;
    let main = common.parent()?;
    let origin = origin_url(fs, &common);
    let key = origin
        .as_deref()
        .and_then(|u| canonical_url(fs, main, u))
        .unwrap_or_else(|| {
            let main = fs.canonicalize(main).unwrap_or_else(|| main.to_path_buf());
            main.to_string_lossy().into_owned()
        });
    let legacy = origin
        .as_deref()
        .and_then(remote_key)
        .filter(|legacy| *legacy != key);
    Some(ProjectKeys { key, legacy })
}

#[cfg(test)]
pub(crate) fn project_key_with(fs: &impl ReadFs, cwd: &Path) -> Option<String> {
    project_keys_with(fs, cwd).map(|k| k.key)
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
/// `https://user@github.com/owner/repo/` → `github.com/owner/repo`. A local remote (`file://`,
/// absolute or relative path) becomes its absolute path, so every spelling of one repository
/// shares a key and `../remote.git` in two checkouts does not (T586.1). `None` for an empty URL.
fn canonical_url(fs: &impl ReadFs, main: &Path, url: &str) -> Option<String> {
    if let Some(path) = local_path(url.trim()) {
        return Some(local_key(fs, main, &path));
    }
    remote_key(url)
}

/// `host/owner/repo` of a remote URL taken at face value, without looking at the filesystem:
/// the key of a network or scp remote, and what every local remote was keyed as before T586.1.
fn remote_key(url: &str) -> Option<String> {
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

/// The filesystem path a `file://` URL or plain-path remote names; `None` for a network URL,
/// an scp-like `host:path` (git: a colon before the first slash, a one-letter prefix being a
/// drive) and an empty string.
fn local_path(url: &str) -> Option<PathBuf> {
    if url.is_empty() {
        return None;
    }
    if url.starts_with("file://") {
        // A non-local host has no path here: the generic branch keeps it as a host/path key.
        return url::Url::parse(url).ok()?.to_file_path().ok();
    }
    if url.contains("://") {
        return None;
    }
    let colon = url.find(':');
    let slash = url.find(['/', '\\']);
    let scp = matches!(colon, Some(c) if c > 1 && slash.is_none_or(|s| s > c));
    (!scp).then(|| PathBuf::from(url))
}

/// A local remote's key: git resolves a relative path against the main checkout (where the
/// shared `config` lives, whichever worktree asks), through symlinks; a remote that is not on
/// disk (yet) keeps its lexical absolute path. Lower case and no `.git`, like network keys.
fn local_key(fs: &impl ReadFs, main: &Path, path: &Path) -> String {
    let joined = main.join(path);
    let abs = fs
        .canonicalize(&joined)
        .unwrap_or_else(|| normalize(main, path));
    let abs = abs.to_string_lossy();
    let abs = abs.trim_end_matches('/');
    abs.strip_suffix(".git").unwrap_or(abs).to_ascii_lowercase()
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
        let vfs = Vfs::new();
        assert_eq!(
            canonical_url(&vfs, Path::new("/home/me/rtok"), url).as_deref(),
            Some(key)
        );
    }

    /// Network and scp spellings never see the checkout; a relative path is resolved against it.
    #[test]
    fn local_remotes_resolve_against_the_main_checkout_without_touching_network_urls() {
        let vfs = Vfs::new();
        let main = Path::new("/home/me/rtok");
        let key = |url| canonical_url(&vfs, main, url);
        assert_eq!(key("../origin.git").as_deref(), Some("/home/me/origin"));
        assert_eq!(key("./sub/../../o/").as_deref(), Some("/home/me/o"));
        assert_eq!(
            key("file:///srv/My%20Repo.git").as_deref(),
            Some("/srv/my repo")
        );
        assert_eq!(key("git@host:../x.git").as_deref(), Some("host/../x"));
        assert_eq!(key("https://host/a/b.git").as_deref(), Some("host/a/b"));
        assert_eq!(key("  ").as_deref(), None);
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

    /// T586.2: only a local-path origin has a pre-T586.1 key to fall back to.
    #[rstest]
    #[case("../remote.git", Some("../remote"))]
    #[case("./sub/../o/", Some("./sub/../o"))]
    #[case("file:///srv/My%20Repo.git", Some("/srv/my%20repo"))]
    #[case("/srv/git/rtok.git", None)]
    #[case("git@github.com:Me/rtok.git", None)]
    #[case("https://host/a/b.git", None)]
    fn legacy_key_exists_only_where_the_key_changed(
        #[case] url: &str,
        #[case] legacy: Option<&str>,
    ) {
        let keys = project_keys_with(&fixture(url), Path::new("/home/me/rtok")).unwrap();
        assert_eq!(keys.legacy.as_deref(), legacy);
        assert_ne!(Some(keys.key.as_str()), legacy);
        let read = read_keys(&keys.key, keys.legacy.as_deref());
        assert_eq!(read.len(), 1 + usize::from(legacy.is_some()));
        assert_eq!(read[0], keys.key);
    }

    #[test]
    fn a_checkout_without_origin_has_no_legacy_key() {
        let mut vfs = Vfs::new();
        vfs.write("/home/me/local-only/.git/HEAD", "ref: refs/heads/main\n");
        let keys = project_keys_with(&vfs, Path::new("/home/me/local-only")).unwrap();
        assert_eq!(keys.legacy, None);
    }

    /// Real-git fixtures (T586.1): a seed repo, bare remotes cloned from it and checkouts
    /// whose `origin` is spelled in the test.
    mod local_remote {
        use super::*;
        use std::process::{Command, Output};

        fn git(dir: &Path, args: &[&str]) -> Output {
            let out = Command::new("git")
                .arg("-C")
                .arg(dir)
                .args(["-c", "user.name=t", "-c", "user.email=t@t"])
                .args(args)
                .output()
                .expect("git");
            assert!(out.status.success(), "git {args:?}: {out:?}");
            out
        }

        /// A canonical scratch dir (macOS `/var` is a symlink), lower case: keys are.
        fn scratch(tag: &str) -> PathBuf {
            let base = std::env::temp_dir().join(format!("rtok-pk-{tag}-{}", std::process::id()));
            std::fs::create_dir_all(&base).unwrap();
            dunce::canonicalize(base).unwrap()
        }

        fn seed(base: &Path) -> PathBuf {
            let seed = base.join("seed");
            std::fs::create_dir_all(&seed).unwrap();
            git(&seed, &["init", "-q"]);
            std::fs::write(seed.join("a.txt"), "a\n").unwrap();
            git(&seed, &["add", "."]);
            git(&seed, &["commit", "-q", "-m", "init"]);
            seed
        }

        /// `<dir>/remote.git` (a bare clone of `seed`) and `<dir>/checkout` with `origin`
        /// set to `url`.
        fn site(dir: &Path, seed: &Path, url: &str) -> PathBuf {
            std::fs::create_dir_all(dir).unwrap();
            git(
                dir,
                &[
                    "clone",
                    "-q",
                    "--bare",
                    seed.to_str().unwrap(),
                    "remote.git",
                ],
            );
            let checkout = dir.join("checkout");
            std::fs::create_dir_all(&checkout).unwrap();
            git(&checkout, &["init", "-q"]);
            git(&checkout, &["remote", "add", "origin", url]);
            checkout
        }

        fn lower(p: &Path) -> String {
            p.to_string_lossy().to_ascii_lowercase()
        }

        fn key(dir: &Path) -> String {
            project_key(dir).expect("key")
        }

        /// Two checkouts spelling `origin` as `../remote.git` reach different repositories
        /// (git resolves it against the checkout), so they must not share a key.
        #[test]
        fn identical_relative_remotes_of_different_repositories_get_distinct_keys() {
            let base = scratch("distinct");
            let seed = seed(&base);
            let a = site(&base.join("a"), &seed, "../remote.git");
            let b = site(&base.join("b"), &seed, "../remote.git");
            assert_ne!(key(&a), key(&b));
            assert_eq!(key(&a), lower(&base.join("a/remote")));
            assert_eq!(key(&b), lower(&base.join("b/remote")));
            let _ = std::fs::remove_dir_all(base);
        }

        /// Relative, dotted, absolute and `file://` spellings of one remote are one project,
        /// whichever checkout (main or linked worktree, nested cwd) asks.
        #[test]
        fn every_spelling_of_one_local_remote_gives_one_key() {
            let base = scratch("spellings");
            let seed = seed(&base);
            let site_dir = base.join("with space");
            let checkout = site(&site_dir, &seed, "../remote.git");
            let remote = site_dir.join("remote.git");
            let want = key(&checkout);
            assert_eq!(want, lower(&site_dir.join("remote")));
            let file_url = format!("file://{}", remote.to_string_lossy().replace(' ', "%20"));
            for url in [
                "./../remote.git",
                "../checkout/../remote.git",
                "../remote.git/",
                remote.to_str().unwrap(),
                file_url.as_str(),
            ] {
                git(&checkout, &["remote", "set-url", "origin", url]);
                assert_eq!(key(&checkout), want, "{url}");
                // The control: git itself reaches the same remote with that spelling.
                git(&checkout, &["ls-remote", "origin"]);
            }
            git(&checkout, &["remote", "set-url", "origin", "../remote.git"]);
            git(&checkout, &["commit", "-q", "--allow-empty", "-m", "x"]);
            let wt = base.join("wt");
            git(
                &checkout,
                &["worktree", "add", "-q", wt.to_str().unwrap(), "-b", "w"],
            );
            std::fs::create_dir_all(checkout.join("sub")).unwrap();
            assert_eq!(key(&wt), want);
            assert_eq!(key(&checkout.join("sub")), want);
            let _ = std::fs::remove_dir_all(base);
        }

        /// A remote that does not exist (yet) still gets a stable absolute key.
        #[test]
        fn missing_local_remote_falls_back_to_a_lexical_absolute_key() {
            let base = scratch("missing");
            let checkout = base.join("checkout");
            std::fs::create_dir_all(&checkout).unwrap();
            git(&checkout, &["init", "-q"]);
            git(&checkout, &["remote", "add", "origin", "../gone.git"]);
            assert_eq!(key(&checkout), lower(&base.join("gone")));
            let _ = std::fs::remove_dir_all(base);
        }
    }
}

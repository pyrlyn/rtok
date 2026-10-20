// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T351: roots the path guard accepts beyond cwd and `allow_paths`, for `rtok mcp` only.
//!
//! Agents work in a task worktree while the host launched the server in the main checkout,
//! so a path in a sibling worktree of the cwd's repository is allowed, and so is every
//! `file://` root of the client's `roots/list` answer (`mcp.rs`) with the worktrees of its
//! repository (T435). Scratchpads and every
//! other directory stay outside; `plugins.read.allow_paths` remains the manual escape hatch.
//! Nothing here runs unless `rtok mcp` called [`enable`], so hooks never spawn git.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{LazyLock, Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// A linked worktree can be added after the server started, so the listing is re-read
/// after this long, and only when a path failed the cheap checks first.
const WORKTREE_TTL: Duration = Duration::from_secs(30);

static ENABLED: AtomicBool = AtomicBool::new(false);
static DENY_SECRETS: AtomicBool = AtomicBool::new(false);
static CLIENT: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());
/// Per cwd: when the listing was read, and its worktree paths.
type Listing = (Instant, Vec<PathBuf>);

static WORKTREES: LazyLock<Mutex<HashMap<PathBuf, Listing>>> = LazyLock::new(Default::default);

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// Called once by `rtok mcp`; turns the extra roots on for this process.
pub fn enable() {
    ENABLED.store(true, Ordering::Relaxed);
}

/// Called by `rtok mcp --http`: refuse credential files (see [`is_secret`]) in this process.
pub fn deny_secrets() {
    DENY_SECRETS.store(true, Ordering::Relaxed);
}

/// Whether credential files are refused in this process.
pub(crate) fn secrets_denied() -> bool {
    DENY_SECRETS.load(Ordering::Relaxed)
}

/// A file that usually holds credentials: `.env` and its variants (templates excepted),
/// private keys and Java keystores, `.git/config` and `.git-credentials`, direnv's `.envrc`,
/// and the package-manager, netrc and pgpass credential files.
pub(crate) fn is_secret(path: &Path) -> bool {
    let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
        return false;
    };
    let name = name.to_ascii_lowercase();
    if name == ".env" {
        return true;
    }
    if let Some(rest) = name.strip_prefix(".env.") {
        return !matches!(rest, "example" | "sample" | "template" | "dist");
    }
    let ext = Path::new(&name).extension().and_then(|e| e.to_str());
    if matches!(
        ext,
        Some("pem" | "key" | "p12" | "pfx" | "jks" | "keystore")
    ) {
        return true;
    }
    if matches!(
        name.as_str(),
        "id_rsa"
            | "id_dsa"
            | "id_ecdsa"
            | "id_ed25519"
            | "id_ecdsa_sk"
            | "id_ed25519_sk"
            | ".netrc"
            | ".npmrc"
            | ".pypirc"
            | ".pgpass"
            | ".git-credentials"
            | ".envrc"
            | "credentials"
    ) {
        return true;
    }
    name == "config"
        && path
            .parent()
            .and_then(Path::file_name)
            .is_some_and(|d| d == ".git")
}

/// The directories of the client's latest `roots/list` answer (replaces the previous one).
pub fn set_client_roots(roots: Vec<PathBuf>) {
    *lock(&CLIENT) = roots;
}

/// The client's latest `roots/list` directories, for the project-dir fallback.
pub(crate) fn client_roots() -> Vec<PathBuf> {
    lock(&CLIENT).clone()
}

/// Client roots plus the worktrees of the repositories of `cwd` and of every client root;
/// empty unless [`enable`]d.
pub(crate) fn dynamic(cwd: &Path) -> Vec<PathBuf> {
    if !ENABLED.load(Ordering::Relaxed) {
        return Vec::new();
    }
    let client = lock(&CLIENT).clone();
    let mut roots = client.clone();
    // T435: Claude.app's Code tab can hand a session a server whose cwd is another project,
    // so the session's own repository is known only as a client root.
    for dir in std::iter::once(cwd).chain(client.iter().map(PathBuf::as_path)) {
        roots.extend(worktrees(dir));
    }
    roots
}

fn worktrees(cwd: &Path) -> Vec<PathBuf> {
    if let Some((at, list)) = lock(&WORKTREES).get(cwd)
        && at.elapsed() < WORKTREE_TTL
    {
        return list.clone();
    }
    // Fail soft: no git, not a repository, or a broken listing grants nothing.
    let list: Vec<PathBuf> = crate::worktree::git::list(cwd)
        .map(|records| records.into_iter().map(|r| r.path).collect())
        .unwrap_or_default();
    lock(&WORKTREES).insert(cwd.to_path_buf(), (Instant::now(), list.clone()));
    list
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_names_are_recognised() {
        for p in [
            ".env",
            "a/.env.local",
            "k/server.pem",
            "x.key",
            "h/.ssh/id_ed25519",
            ".git/config",
            "r/.npmrc",
            ".aws/credentials",
            "h/.git-credentials",
            "p/.envrc",
            "h/.pgpass",
            "h/.ssh/id_ed25519_sk",
            "android/release.jks",
            "app.keystore",
        ] {
            assert!(is_secret(Path::new(p)), "{p}");
        }
        for p in [
            "src/main.rs",
            ".env.example",
            "config",
            "app/config",
            "keys.rs",
            "README.md",
        ] {
            assert!(!is_secret(Path::new(p)), "{p}");
        }
    }
    use std::process::Command;

    fn git(dir: &Path, args: &[&str]) {
        let ok = Command::new("git")
            .arg("-C")
            .arg(dir)
            .args(["-c", "user.name=t", "-c", "user.email=t@t"])
            .args(args)
            .output()
            .expect("git")
            .status
            .success();
        assert!(ok, "git {args:?}");
    }

    /// Main checkout + linked worktree + an unrelated dir, all canonical (macOS `/var`).
    fn repo(tag: &str) -> (PathBuf, PathBuf, PathBuf, PathBuf) {
        let base = std::env::temp_dir().join(format!("rtok-roots-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        let (main, wt, other) = (base.join("main"), base.join("wt"), base.join("other"));
        for d in [&main, &other] {
            std::fs::create_dir_all(d).unwrap();
        }
        let base = fs_kit::path::canonicalize(&base).unwrap();
        let (main, other) = (base.join("main"), base.join("other"));
        git(&main, &["init", "-q"]);
        std::fs::write(main.join("a.txt"), "a\n").unwrap();
        git(&main, &["add", "."]);
        git(&main, &["commit", "-q", "-m", "init"]);
        git(
            &main,
            &["worktree", "add", "-q", wt.to_str().unwrap(), "-b", "w"],
        );
        (base.clone(), main, base.join("wt"), other)
    }

    #[test]
    fn worktrees_of_the_repo_are_listed_and_other_dirs_are_not() {
        let (base, main, wt, other) = repo("list");
        let listed: Vec<PathBuf> = worktrees(&main)
            .iter()
            .map(|p| fs_kit::path::canonicalize(p).unwrap())
            .collect();
        assert!(listed.contains(&wt) && listed.contains(&main), "{listed:?}");
        assert!(!listed.contains(&other));
        // Not a repository: nothing, no error.
        assert!(worktrees(&other).is_empty());
        let _ = std::fs::remove_dir_all(base);
    }

    #[test]
    fn guard_accepts_sibling_worktree_and_client_roots() {
        let (base, main, wt, other) = repo("guard");
        let scratch = base.join("scratchpad");
        std::fs::create_dir_all(&scratch).unwrap();
        let files = [wt.join("a.txt"), other.join("b.txt"), scratch.join("c.txt")];
        for f in &files[1..] {
            std::fs::write(f, "x\n").unwrap();
        }
        let ok = |p: &Path| super::super::resolve(&main, p, &[]).is_ok();
        enable();
        assert!(ok(&files[0]), "sibling worktree");
        assert!(
            !ok(&files[1]) && !ok(&files[2]),
            "unrelated dir, scratchpad"
        );
        set_client_roots(vec![other.clone(), base.join("gone")]);
        assert!(ok(&files[1]), "client root");
        assert!(!ok(&files[2]), "scratchpad stays outside");
        let err = super::super::resolve(&main, &files[2], &[]).unwrap_err();
        assert!(err.to_string().contains("path outside cwd"), "{err}");
        // T435: the process cwd is another project; this repository is only a client root.
        set_client_roots(vec![main.clone()]);
        assert!(
            super::super::resolve(&other, &files[0], &[]).is_ok(),
            "worktree of a client root's repository"
        );
        assert!(super::super::resolve(&other, &files[2], &[]).is_err());
        set_client_roots(Vec::new());
        let _ = std::fs::remove_dir_all(base);
    }
}

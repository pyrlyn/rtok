// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Narrow FS surface for `read()` resolve + content (T56.5 / post-T56.4) and for the
//! project resolver (T133): read a file as text, canonicalize, and a lexical path join.
//!
//! Production uses [`HostFs`]. Unit tests drive the same path over [`crate::testutil::Vfs`]
//! without deleting disk e2e twins. Search/tree keep `ignore::WalkBuilder` on the host.
//! Crate level, not inside the `read` plugin: `plugin.rs` attributes every session through
//! [`crate::project`] whatever features are compiled in (T154).

use std::path::{Component, Path, PathBuf};

/// Read bytes as UTF-8 text and canonicalize (follow symlinks when the backend supports them).
pub trait ReadFs {
    fn read_to_string(&self, path: &Path) -> std::io::Result<String>;
    /// Canonical path when the entry exists; `None` if missing (caller keeps the lexical abs).
    fn canonicalize(&self, path: &Path) -> Option<PathBuf>;
}

/// Host disk backend for production `read` / `resolve`.
#[derive(Clone, Copy, Debug, Default)]
pub struct HostFs;

impl ReadFs for HostFs {
    fn read_to_string(&self, path: &Path) -> std::io::Result<String> {
        std::fs::read_to_string(path)
    }

    fn canonicalize(&self, path: &Path) -> Option<PathBuf> {
        dunce::canonicalize(path).ok()
    }
}

/// Lexical join of `path` onto `root` (`..` pops, `.` drops) — no disk access, so a missing
/// path still normalises. `read` resolves with it; `project` follows `gitdir:` / `commondir`.
///
/// Deliberately NOT clamped: `project` legitimately climbs above `root` (a linked
/// worktree's `gitdir: ../../main/.git/...`). Confinement lives at the boundary —
/// `read::resolve_with` rejects anything outside the allow-roots via `under()`.
pub(crate) fn normalize(root: &Path, path: &Path) -> PathBuf {
    let mut out = if path.is_absolute() {
        PathBuf::new()
    } else {
        root.to_path_buf()
    };
    for c in path.components() {
        match c {
            // Push, do not replace: on Windows `C:\foo` is Prefix("C:") then
            // RootDir — replacing wiped the drive and confined to `\foo`.
            Component::RootDir => out.push(Component::RootDir.as_os_str()),
            Component::Prefix(p) => out = PathBuf::from(p.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            Component::Normal(s) => out.push(s),
        }
    }
    out
}

/// T263/T356: whether `root` is the filesystem root or `home`, which no index or walk may use as
/// a project root. Lives here, not in `read`, so the store can apply it without the `read`
/// feature; `home` is a parameter so tests need not touch `$HOME`.
pub(crate) fn is_unwalkable_root(root: &Path, home: Option<&Path>) -> bool {
    let root = canon(root);
    let is_home = home.is_some_and(|h| same_path(&canon(h), &root));
    root.parent().is_none() || is_home
}

/// Existing path, without the `\\?\` prefix `std::fs::canonicalize` adds on Windows.
/// A missing path is returned with that prefix stripped lexically (`dunce::simplified`).
pub(crate) fn canon(path: &Path) -> PathBuf {
    dunce::canonicalize(path).unwrap_or_else(|_| dunce::simplified(path).to_path_buf())
}

/// `path` is `root` or a file under it. On Windows the check is ASCII-case-insensitive
/// and a `\\?\` prefix does not count as a different directory.
pub(crate) fn path_starts_with(path: &Path, root: &Path) -> bool {
    if root.as_os_str().is_empty() {
        return false;
    }
    let path = dunce::simplified(path);
    let root = dunce::simplified(root);
    if path.starts_with(root) {
        return true;
    }
    cfg!(windows) && components_prefix_ci(path, root)
}

/// The same directory, including Windows case and a `\\?\` prefix.
pub(crate) fn same_path(a: &Path, b: &Path) -> bool {
    let a = dunce::simplified(a);
    let b = dunce::simplified(b);
    if a == b {
        return true;
    }
    cfg!(windows) && components_eq_ci(a, b)
}

/// `path` with `prefix` removed. On Windows, ASCII case and a `\\?\` prefix still match.
pub(crate) fn strip_prefix(path: &Path, prefix: &Path) -> Option<PathBuf> {
    let path = dunce::simplified(path);
    let prefix = dunce::simplified(prefix);
    if let Ok(rel) = path.strip_prefix(prefix) {
        return Some(rel.to_path_buf());
    }
    if !cfg!(windows) {
        return None;
    }
    let path_c: Vec<_> = path.components().collect();
    let pref_c: Vec<_> = prefix.components().collect();
    if pref_c.is_empty() || pref_c.len() > path_c.len() {
        return None;
    }
    if !path_c
        .iter()
        .zip(pref_c.iter())
        .all(|(p, r)| components_match(*p, *r))
    {
        return None;
    }
    let mut out = PathBuf::new();
    for c in path_c.into_iter().skip(pref_c.len()) {
        out.push(c.as_os_str());
    }
    Some(out)
}

fn components_match(a: Component, b: Component) -> bool {
    match (a, b) {
        (Component::Normal(x), Component::Normal(y)) => x.eq_ignore_ascii_case(y),
        (Component::Prefix(x), Component::Prefix(y)) => {
            x.as_os_str().eq_ignore_ascii_case(y.as_os_str())
        }
        (x, y) => x == y,
    }
}

fn components_prefix_ci(path: &Path, root: &Path) -> bool {
    let path_c: Vec<_> = path.components().collect();
    let root_c: Vec<_> = root.components().collect();
    if root_c.is_empty() || root_c.len() > path_c.len() {
        return false;
    }
    path_c
        .iter()
        .zip(root_c.iter())
        .all(|(p, r)| components_match(*p, *r))
}

fn components_eq_ci(a: &Path, b: &Path) -> bool {
    let a: Vec<_> = a.components().collect();
    let b: Vec<_> = b.components().collect();
    a.len() == b.len() && a.iter().zip(&b).all(|(x, y)| components_match(*x, *y))
}

/// Forward-slash Vfs key from a [`Path`] (Windows separators normalized).
#[cfg(test)]
pub(crate) fn path_key(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}

#[cfg(test)]
impl ReadFs for crate::testutil::Vfs {
    fn read_to_string(&self, path: &Path) -> std::io::Result<String> {
        let key = self.resolve_key(&path_key(path));
        match self.read_str(&key) {
            Some(s) => Ok(s.to_string()),
            None => Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                format!("vfs missing: {key}"),
            )),
        }
    }

    fn canonicalize(&self, path: &Path) -> Option<PathBuf> {
        let key = path_key(path);
        if let Some(target) = self.symlink_target(&key) {
            // One hop, then treat the target as the real path (host canonicalize follows).
            let target_key = self.resolve_key(target);
            return Some(PathBuf::from(target_key));
        }
        if self.meta(&key).is_some() {
            return Some(PathBuf::from(key));
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// T356: `/` and the given home are unwalkable (also through a trailing `.`); a project is not.
    #[test]
    fn unwalkable_root_is_the_filesystem_root_or_home_only() {
        let home = crate::testutil::tmp_dir("t356-fs-home");
        let project = crate::testutil::tmp_dir("t356-fs-project");
        assert!(is_unwalkable_root(Path::new("/"), None));
        assert!(is_unwalkable_root(&home, Some(&home)));
        assert!(is_unwalkable_root(&home.join("."), Some(&home)));
        assert!(!is_unwalkable_root(&project, Some(&home)));
        assert!(!is_unwalkable_root(&project, None));
    }

    /// T430: `\\?\C:\…` and `c:\…` are one directory, and a child still strips to a relative path.
    #[cfg(windows)]
    #[test]
    fn windows_path_identity_folds_verbatim_prefix_and_ascii_case() {
        let path = Path::new(r"\\?\C:\Users\Me\proj\file.txt");
        let root = Path::new(r"c:\users\me");
        assert!(path_starts_with(path, root));
        assert!(!path_starts_with(path, Path::new(r"c:\users\me\other")));
        assert!(same_path(
            Path::new(r"\\?\C:\Users\Me"),
            Path::new(r"c:\users\me")
        ));
        assert_eq!(
            strip_prefix(path, root).unwrap(),
            PathBuf::from(r"proj\file.txt")
        );
        let home = crate::testutil::tmp_dir("t430-fs-case");
        assert!(is_unwalkable_root(&home, Some(&home)));
        let flipped: PathBuf = home
            .to_string_lossy()
            .chars()
            .map(|c| match c {
                'A'..='Z' => c.to_ascii_lowercase(),
                'a'..='z' => c.to_ascii_uppercase(),
                _ => c,
            })
            .collect::<String>()
            .into();
        assert!(
            is_unwalkable_root(&flipped, Some(&home)),
            "flipped {flipped:?} vs {}",
            home.display()
        );
    }
}

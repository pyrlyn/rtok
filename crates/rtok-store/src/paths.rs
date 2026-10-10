// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Path identity shared by the store and the host: Windows case, and the `\\?\` prefix
//! `std::fs::canonicalize` adds. One copy, so a root the store drops is the same root the
//! host refuses to walk.

use std::path::{Component, Path, PathBuf};

/// Whether `root` is the filesystem root or `home`, which no index or walk may use as a
/// project root. `home` is a parameter so tests need not touch `$HOME`.
pub fn is_unwalkable_root(root: &Path, home: Option<&Path>) -> bool {
    let root = canon(root);
    let is_home = home.is_some_and(|h| same_path(&canon(h), &root));
    root.parent().is_none() || is_home
}

/// Existing path, without the `\\?\` prefix `std::fs::canonicalize` adds on Windows.
/// A missing path is returned with that prefix stripped lexically (`dunce::simplified`).
pub fn canon(path: &Path) -> PathBuf {
    dunce::canonicalize(path).unwrap_or_else(|_| dunce::simplified(path).to_path_buf())
}

/// `path` is `root` or a file under it. On Windows the check is ASCII-case-insensitive
/// and a `\\?\` prefix does not count as a different directory.
pub fn path_starts_with(path: &Path, root: &Path) -> bool {
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
pub fn same_path(a: &Path, b: &Path) -> bool {
    let a = dunce::simplified(a);
    let b = dunce::simplified(b);
    if a == b {
        return true;
    }
    cfg!(windows) && components_eq_ci(a, b)
}

/// `suffix` names the trailing components of `path` (`src/a.rs` in `/repo/src/a.rs`), whole
/// components only. On Windows the comparison is ASCII-case-insensitive.
pub fn path_ends_with(path: &Path, suffix: &Path) -> bool {
    ends_with(path, suffix, cfg!(windows))
}

fn ends_with(path: &Path, suffix: &Path, fold: bool) -> bool {
    // `Path::ends_with("")` is true; an empty suffix names nothing.
    if suffix.as_os_str().is_empty() {
        return false;
    }
    if path.ends_with(suffix) {
        return true;
    }
    if !fold {
        return false;
    }
    let path_c: Vec<_> = path.components().collect();
    let suf_c: Vec<_> = suffix.components().collect();
    suf_c.len() <= path_c.len()
        && path_c[path_c.len() - suf_c.len()..]
            .iter()
            .zip(&suf_c)
            .all(|(p, s)| components_match(*p, *s))
}

/// `path` with `prefix` removed. On Windows, ASCII case and a `\\?\` prefix still match.
pub fn strip_prefix(path: &Path, prefix: &Path) -> Option<PathBuf> {
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

#[cfg(test)]
mod tests {
    use super::*;

    /// `fold` is a parameter so the Windows branch is exercised on every CI platform.
    #[test]
    fn suffix_match_folds_ascii_case_only_when_asked() {
        let abs = Path::new("/Repo/Src/Main.rs");
        let rel = Path::new("src/main.rs");
        assert!(ends_with(abs, rel, true));
        assert!(!ends_with(abs, rel, false));
        assert!(ends_with(abs, Path::new("Src/Main.rs"), false));
        // Whole components: a partial file name never matches, folded or not.
        assert!(!ends_with(abs, Path::new("ain.rs"), true));
        assert!(!ends_with(abs, Path::new(""), true));
        assert!(!ends_with(rel, abs, true));
    }
}

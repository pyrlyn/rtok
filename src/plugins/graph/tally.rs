// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.36: what a `symbol`, `callers` or `impact` call found, counted by the backends (LSP,
//! tags, text) at the point where they hold structured rows, never read back from the answer
//! text, which mixes code bodies with result lines. The count is the backend's finding before
//! the token cap: a cut answer still reports everything the archive holds.
//!
//! The record is thread-local and armed only by [`arm`] (the MCP call in `events.rs`), so the CLI
//! and the hooks, which call the same backends, record nothing and keep nothing.

use std::cell::RefCell;
use std::collections::HashSet;
use std::path::{Path, PathBuf};

struct Hit {
    root: PathBuf,
    name: String,
    file: Option<String>,
}

thread_local! {
    static HITS: RefCell<Option<Vec<Hit>>> = const { RefCell::new(None) };
}

/// What one call returned.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Counts {
    /// Asked symbols that the answer lists at least once.
    pub symbols: u32,
    /// Distinct files of the listed rows.
    pub files: u32,
    /// Projects of the scope that contributed a row.
    pub projects: u32,
}

/// Starts a record for the call on this thread, dropping any earlier one.
pub fn arm() {
    HITS.with(|h| *h.borrow_mut() = Some(Vec::new()));
}

/// Stops recording and returns the counts; zeros when nothing was armed.
pub fn take() -> Counts {
    let hits = HITS.with(|h| h.borrow_mut().take()).unwrap_or_default();
    let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
    Counts {
        symbols: count(
            hits.iter()
                .filter(|h| !h.name.is_empty())
                .map(|h| h.name.as_str())
                .collect::<HashSet<_>>()
                .len(),
        ),
        files: count(
            hits.iter()
                .filter_map(|h| Some((&h.root, h.file.as_ref()?)))
                .collect::<HashSet<_>>()
                .len(),
        ),
        projects: count(hits.iter().map(|h| &h.root).collect::<HashSet<_>>().len()),
    }
}

/// The record's length now, for [`rewind`].
pub fn mark() -> usize {
    HITS.with(|h| h.borrow().as_ref().map_or(0, Vec::len))
}

/// Drops what was recorded since `mark`: an LSP answer that the tags index replaces must not
/// count twice, nor count at all when the server failed half way.
pub fn rewind(mark: usize) {
    HITS.with(|h| {
        if let Some(hits) = h.borrow_mut().as_mut() {
            hits.truncate(mark);
        }
    });
}

/// `name` has rows in `root`, in `files` (none for a call chain, which names no file).
pub fn hit<S: AsRef<str>>(root: &Path, name: &str, files: impl IntoIterator<Item = S>) {
    HITS.with(|h| {
        let mut slot = h.borrow_mut();
        let Some(hits) = slot.as_mut() else { return };
        let before = hits.len();
        hits.extend(files.into_iter().map(|f| Hit {
            root: root.to_path_buf(),
            name: name.to_string(),
            file: Some(f.as_ref().to_string()),
        }));
        if hits.len() == before {
            hits.push(Hit {
                root: root.to_path_buf(),
                name: name.to_string(),
                file: None,
            });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_distinct_names_files_and_projects() {
        arm();
        hit(Path::new("/a"), "f", ["x.rs", "y.rs"]);
        hit(Path::new("/a"), "f", ["x.rs"]);
        hit(Path::new("/b"), "g", ["x.rs"]);
        hit(Path::new("/b"), "chain", Vec::<&str>::new());
        assert_eq!(
            take(),
            Counts {
                symbols: 3,
                files: 3,
                projects: 2
            }
        );
    }

    #[test]
    fn rewind_drops_what_a_failed_backend_recorded() {
        arm();
        hit(Path::new("/a"), "f", ["x.rs"]);
        let m = mark();
        hit(Path::new("/b"), "g", ["y.rs"]);
        rewind(m);
        assert_eq!(
            take(),
            Counts {
                symbols: 1,
                files: 1,
                projects: 1
            }
        );
    }

    #[test]
    fn nothing_is_kept_unless_armed() {
        hit(Path::new("/a"), "f", ["x.rs"]);
        assert_eq!(take(), Counts::default());
        assert_eq!(mark(), 0);
    }
}

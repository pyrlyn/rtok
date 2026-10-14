// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Walk overrides and extension map for the graph index (T68.10).

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use ignore::WalkBuilder;
use ignore::gitignore::{Gitignore, GitignoreBuilder};
use ignore::overrides::{Override, OverrideBuilder};

use crate::config::Graph;
use crate::plugins::read::outline;

/// Gitignore-style rules plus an extension → grammar map, shared by the walker and the
/// background watcher (T68.10). `exclude` always wins over `include`.
pub struct Matcher {
    root: PathBuf,
    /// `exclude` only: the ordinary walk, which keeps honouring the ignore files.
    overrides: Override,
    /// `include` then `exclude`: what an explicitly included file is judged by.
    included: Override,
    exclude: Gitignore,
    /// Where each `include` pattern's walk starts, outermost first (see [`include_start`]).
    include_starts: Vec<PathBuf>,
    extensions: HashMap<String, String>,
}

/// The directory an `include` pattern's walk starts in: its leading literal components when
/// the pattern is anchored (has a `/` before the end), else the root, since an unanchored
/// pattern matches at any depth.
fn include_start(pat: &str) -> PathBuf {
    let body = pat.trim_end_matches('/');
    if !body.contains('/') {
        return PathBuf::new();
    }
    body.trim_start_matches('/')
        .split('/')
        .take_while(|part| !part.is_empty() && !part.contains(['*', '?', '[', '{', '\\']))
        .collect()
}

fn walker(start: &Path, overrides: &Override, ignore_files: bool) -> ignore::Walk {
    let mut b = WalkBuilder::new(start);
    if !ignore_files {
        b.standard_filters(false);
    }
    b.hidden(false)
        .filter_entry(crate::plugins::read::search::skip_git)
        .overrides(overrides.clone());
    b.build()
}

impl Matcher {
    pub fn new(root: &Path, cfg: &Graph) -> Self {
        let build = |ob: OverrideBuilder| {
            ob.build()
                .unwrap_or_else(|_| OverrideBuilder::new(root).build().unwrap())
        };
        // `Override` inverts gitignore rules: `!pat` excludes, bare `pat` whitelists, and a
        // whitelist beats the ignore files and puts the walk in whitelist mode. So includes
        // live in their own override, used only to walk the subtrees they name and to judge
        // watcher events; the ordinary walk gets `exclude` alone (T544).
        let mut walk = OverrideBuilder::new(root);
        let mut with_includes = OverrideBuilder::new(root);
        for pat in &cfg.include {
            let glob = if pat.ends_with('/') {
                format!("{pat}**")
            } else {
                pat.clone()
            };
            let _ = with_includes.add(&glob);
        }
        let mut exclude = GitignoreBuilder::new(root);
        for pat in &cfg.exclude {
            let _ = walk.add(&format!("!{pat}"));
            let _ = with_includes.add(&format!("!{pat}"));
            let _ = exclude.add_line(None, pat);
        }
        let mut starts: Vec<PathBuf> = cfg.include.iter().map(|p| include_start(p)).collect();
        starts.sort();
        // A start inside an earlier one is walked by it already.
        starts.dedup_by(|b, a| b.starts_with(&*a));
        Self {
            root: root.to_path_buf(),
            overrides: build(walk),
            included: build(with_includes),
            exclude: exclude.build().unwrap_or_else(|_| Gitignore::empty()),
            include_starts: starts,
            extensions: cfg.extensions.clone(),
        }
    }

    pub fn extensions(&self) -> &HashMap<String, String> {
        &self.extensions
    }

    /// Every entry of the root: the ordinary walk, then the subtrees `include` names with the
    /// ignore files switched off, each file once. Excluded paths are pruned by both.
    pub fn walk(&self) -> impl Iterator<Item = ignore::DirEntry> + use<> {
        let seen = Rc::new(RefCell::new(HashSet::new()));
        let (remember, included) = (Rc::clone(&seen), self.included.clone());
        let track = !self.include_starts.is_empty();
        let ordinary = walker(&self.root, &self.overrides, true)
            .flatten()
            .inspect(move |e| {
                if track && included.matched(e.path(), false).is_whitelist() {
                    remember.borrow_mut().insert(e.path().to_path_buf());
                }
            });
        let (root, included) = (self.root.clone(), self.included.clone());
        let extra = self
            .include_starts
            .clone()
            .into_iter()
            .flat_map(move |start| walker(&root.join(start), &included, false).flatten())
            .filter(move |e| {
                e.file_type().is_some_and(|t| t.is_file())
                    && seen.borrow_mut().insert(e.path().to_path_buf())
            });
        ordinary.chain(extra)
    }

    /// Whether an `include` pattern names `path` and no `exclude` covers it.
    pub(super) fn is_included(&self, path: &Path) -> bool {
        self.included.matched(path, false).is_whitelist()
    }

    /// Whether `path` is inside a linked worktree nested below the root (one under
    /// `.rtok/worktrees`): the walk skips those, so a watcher event there is not this index's.
    pub(super) fn in_linked_worktree(&self, path: &Path) -> bool {
        crate::fs::under_linked_worktree(&self.root, path)
    }

    pub(super) fn is_excluded(&self, path: &Path) -> bool {
        let rel = match path.strip_prefix(&self.root) {
            Ok(rel) => rel,
            Err(_) if path.has_root() => return false,
            Err(_) => path,
        };
        self.exclude
            .matched_path_or_any_parents(rel, false)
            .is_ignore()
    }

    pub(super) fn has_supported_ext(&self, path: &Path) -> bool {
        outline::supported_with(path, &self.extensions)
    }

    /// Whether a file path should be indexed: `exclude`, then built-in or mapped extension.
    pub fn indexable(&self, path: &Path) -> bool {
        if self.is_excluded(path) {
            return false;
        }
        self.has_supported_ext(path)
    }

    /// True when `path` is only indexable because of `extensions` (not built-in `supported`).
    pub fn uses_extension_map(&self, path: &Path) -> bool {
        let Some(ext) = path.extension().and_then(OsStr::to_str) else {
            return false;
        };
        self.extensions.contains_key(ext) && !outline::supported(path)
    }
}

/// Grammar names accepted in `[plugins.graph.extensions]` (validated in `config/validate.rs`).
pub const GRAMMAR_NAMES: &[&str] = &[
    "rust", "ts", "tsx", "js", "mjs", "cjs", "py", "dart", "c", "h", "go",
];

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Graph;
    use rstest::rstest;
    use std::fs;

    fn matcher(
        dir: &Path,
        exclude: &[&str],
        include: &[&str],
        extensions: &[(&str, &str)],
    ) -> Matcher {
        let cfg = Graph {
            exclude: exclude.iter().map(|s| s.to_string()).collect(),
            include: include.iter().map(|s| s.to_string()).collect(),
            extensions: extensions
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect(),
            ..Default::default()
        };
        Matcher::new(dir, &cfg)
    }

    #[test]
    fn exclude_drops_a_subtree_and_include_brings_gitignored_back() {
        let dir = std::env::temp_dir().join(format!("rtok-graph-walk-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("skip")).unwrap();
        fs::create_dir_all(dir.join(".gitignored")).unwrap();
        fs::write(dir.join("skip/a.rs"), "fn a() {}\n").unwrap();
        fs::write(dir.join("keep.rs"), "fn keep() {}\n").unwrap();
        fs::write(dir.join(".gitignored/hidden.rs"), "fn hidden() {}\n").unwrap();
        fs::write(dir.join(".gitignore"), ".gitignored/\n").unwrap();

        let m = matcher(&dir, &["skip/"], &[".gitignored/"], &[]);
        assert!(!m.indexable(&dir.join("skip/a.rs")));
        assert!(m.indexable(&dir.join("keep.rs")));
        assert!(m.indexable(&dir.join(".gitignored/hidden.rs")));

        let mut seen = Vec::new();
        for entry in m.walk() {
            let p = entry.path();
            if p.is_file() && m.indexable(p) {
                seen.push(
                    p.strip_prefix(&dir)
                        .unwrap()
                        .to_string_lossy()
                        .replace('\\', "/"),
                );
            }
        }
        seen.sort();
        assert_eq!(seen, [".gitignored/hidden.rs", "keep.rs"]);
        let _ = fs::remove_dir_all(&dir);
    }

    /// A git repository whose `.gitignore` covers `wanted/`, `unwanted/` and `vendor/`, so the
    /// walk applies it; `ignore` honours a `.gitignore` only inside a repository.
    fn ignored_tree(tag: &str) -> PathBuf {
        let dir = crate::testutil::tmp_dir(tag);
        fs::create_dir_all(dir.join(".git")).unwrap();
        fs::write(dir.join(".gitignore"), "wanted/\nunwanted/\nvendor/\n").unwrap();
        for path in [
            "wanted/a.rs",
            "wanted/sub/b.rs",
            "unwanted/a.rs",
            "vendor/pkg/lib.rs",
            "vendor/other/lib.rs",
            "src/lib.rs",
            "src/generated/g.rs",
        ] {
            fs::create_dir_all(dir.join(path).parent().unwrap()).unwrap();
            fs::write(dir.join(path), "fn f() {}\n").unwrap();
        }
        dir
    }

    fn indexed(m: &Matcher, dir: &Path) -> Vec<String> {
        let mut seen: Vec<String> = m
            .walk()
            .filter(|e| e.path().is_file() && m.indexable(e.path()))
            .map(|e| {
                e.path()
                    .strip_prefix(dir)
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/")
            })
            .collect();
        seen.sort();
        seen
    }

    /// T544: an include admits its own subtree only; every other ignored file stays out, and
    /// ordinary source needs no include.
    #[rstest]
    #[case::no_include(&[], &["src/generated/g.rs", "src/lib.rs"])]
    #[case::one_subtree(&["wanted/"], &["src/generated/g.rs", "src/lib.rs", "wanted/a.rs", "wanted/sub/b.rs"])]
    #[case::nested(&["vendor/pkg/"], &["src/generated/g.rs", "src/lib.rs", "vendor/pkg/lib.rs"])]
    #[case::glob(&["wanted/**/b.rs"], &["src/generated/g.rs", "src/lib.rs", "wanted/sub/b.rs"])]
    fn an_include_does_not_revive_unrelated_ignored_files(
        #[case] include: &[&str],
        #[case] want: &[&str],
    ) {
        let dir = ignored_tree("t544-walk");
        assert_eq!(indexed(&matcher(&dir, &[], include, &[]), &dir), want);
        let _ = fs::remove_dir_all(&dir);
    }

    /// T544: exclude wins over include, and a glob exclude drops what a walk would have kept.
    #[test]
    fn exclude_wins_over_include_and_understands_globs() {
        let dir = ignored_tree("t545-walk");
        let m = matcher(&dir, &["**/generated/**", "wanted/sub/"], &["wanted/"], &[]);
        assert_eq!(indexed(&m, &dir), ["src/lib.rs", "wanted/a.rs"]);
        let _ = fs::remove_dir_all(&dir);
    }

    /// T660.3: a linked worktree nested in the project (one under `.rtok/worktrees`) is another
    /// checkout's files, though no ignore rule names it; a submodule and a nested clone stay in
    /// the walk, and the walk root itself, a worktree or not, is always walked.
    #[test]
    fn a_nested_linked_worktree_is_not_walked_into() {
        let dir = ignored_tree("t660-walk");
        let nested = crate::testutil::linked_worktree(&dir, ".rtok/worktrees/x");
        let sub = crate::testutil::submodule(&dir, "mods/sub");
        let clone = dir.join("deps/clone");
        fs::create_dir_all(clone.join(".git")).unwrap();
        for (at, name) in [(&nested, "n.rs"), (&sub, "s.rs"), (&clone, "c.rs")] {
            fs::write(at.join(name), "fn f() {}\n").unwrap();
        }
        fs::write(dir.join(".rtok/notes.rs"), "fn notes() {}\n").unwrap();
        let m = matcher(&dir, &[], &[], &[]);
        assert_eq!(
            indexed(&m, &dir),
            [
                ".rtok/notes.rs",
                "deps/clone/c.rs",
                "mods/sub/s.rs",
                "src/generated/g.rs",
                "src/lib.rs"
            ]
        );
        let m = matcher(&nested, &[], &[], &[]);
        assert_eq!(indexed(&m, &nested), ["n.rs"]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn extension_map_indexes_a_custom_suffix() {
        let dir = std::env::temp_dir().join(format!("rtok-graph-ext-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("tpl.rs"), "fn rs() {}\n").unwrap();
        fs::write(dir.join("weird.tpl"), "fn tpl() {}\n").unwrap();
        let m = matcher(&dir, &[], &[], &[("tpl", "rust")]);
        assert!(!outline::supported(Path::new("x.tpl")));
        assert!(m.indexable(&dir.join("weird.tpl")));
        assert!(m.uses_extension_map(&dir.join("weird.tpl")));
        assert!(!m.uses_extension_map(&dir.join("tpl.rs")));
        let _ = fs::remove_dir_all(&dir);
    }
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T374: which project files a note, a prompt or the session is about. A note linked to a file
//! the session has read, or the prompt names, is preferred by recall over an unlinked note that
//! matches the text equally well.
//!
//! Everything here reads untrusted text (a note body, a prompt, a transcript's paths), so a
//! path only counts once it is relative, free of `..` and inside the project root; paths are
//! stored root-relative so a note follows its project into every checkout and worktree.

use std::path::{Component, Path, PathBuf};

use rtok_plugin_sdk::{Ctx, NoteHit};

use crate::store::embed::rrf_merge_lists;

/// Candidate tokens statted per text: the hook path must stay cheap however long a body is.
const MAX_CANDIDATES: usize = 32;
/// Files in play per recall; bounds the `IN (…)` list the store queries.
const MAX_FILES: usize = 256;

/// The project root `cwd` lies in: the nearest `.git` ancestor, else `cwd` itself.
fn project_root(cwd: Option<&str>) -> Option<PathBuf> {
    let cwd = cwd
        .map(PathBuf::from)
        .or_else(|| std::env::current_dir().ok())?;
    Some(crate::config::layers::git_root(&cwd).unwrap_or(cwd))
}

/// `raw` as a root-relative `/`-separated path, or `None` when it is outside `root` or climbs.
fn relative(root: &Path, canon: &Path, raw: &str) -> Option<String> {
    let path = Path::new(raw);
    let rel = if path.is_absolute() {
        path.strip_prefix(root)
            .or_else(|_| path.strip_prefix(canon))
            .ok()?
    } else {
        path
    };
    let mut parts = Vec::new();
    for c in rel.components() {
        match c {
            Component::Normal(s) => parts.push(s.to_str()?),
            Component::CurDir => {}
            _ => return None,
        }
    }
    (!parts.is_empty()).then(|| parts.join("/"))
}

/// Strip the `:12`, `:12-30` and `#L12` position suffixes and the sentence punctuation a path
/// picks up in prose.
fn clean(token: &str) -> &str {
    let t = token.trim_matches(|c: char| ".:,;!?".contains(c));
    let t = t.split_once("#L").map_or(t, |(head, _)| head);
    match t.split_once(':') {
        Some((head, tail)) if tail.starts_with(|c: char| c.is_ascii_digit()) => head,
        _ => t,
    }
}

/// Existing files under `root` that `text` names, in order of first mention.
fn mentioned(root: &Path, canon: &Path, text: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut seen = 0;
    for token in text.split(|c: char| c.is_whitespace() || "`'\"()[]{}<>,|".contains(c)) {
        let t = clean(token);
        if !(t.contains('/') || t.contains('.')) {
            continue;
        }
        if seen == MAX_CANDIDATES {
            break;
        }
        seen += 1;
        if let Some(rel) = relative(root, canon, t)
            && root.join(&rel).is_file()
            && !out.contains(&rel)
        {
            out.push(rel);
        }
    }
    out
}

/// The path inside one read-cache key: `path\tmode\trange` (the read plugin) or
/// `read\tpath…` (the guard); the guard's own `bash` / `read` bookkeeping keys name no file.
fn key_path(key: &str) -> Option<&str> {
    let mut parts = key.split('\t');
    match parts.next()? {
        "bash" => None,
        "read" => parts.next(),
        path => Some(path),
    }
}

/// Link note `id` to the files its `body` names and to the session checkpoint's paths. Fails
/// open: a note whose links cannot be written is still saved, just never boosted.
pub(super) fn link_note(cx: &Ctx, id: i32, body: &str) {
    let Some(root) = project_root(cx.cwd()) else {
        return;
    };
    let canon = dunce::canonicalize(&root).unwrap_or_else(|_| root.clone());
    let mut files = mentioned(&root, &canon, body);
    let root_str = root.to_string_lossy();
    for p in crate::plugins::checkpoint::last_paths(cx, &root_str) {
        if let Some(rel) = relative(&root, &canon, &p)
            && !files.contains(&rel)
        {
            files.push(rel);
        }
    }
    files.truncate(MAX_FILES);
    let _ = cx.set_note_files(id, &files);
}

/// Notes linked to the files in play: those the session has read plus those `prompt` names.
/// Any failure is an empty list, so recall falls back to the text match alone.
pub(super) fn linked_notes(cx: &Ctx, prompt: &str, limit: u32) -> Vec<NoteHit> {
    let Some(root) = project_root(cx.cwd()) else {
        return Vec::new();
    };
    let canon = dunce::canonicalize(&root).unwrap_or_else(|_| root.clone());
    let mut files = mentioned(&root, &canon, prompt);
    for key in cx.read_cache_keys().unwrap_or_default() {
        if let Some(rel) = key_path(&key).and_then(|p| relative(&root, &canon, p))
            && !files.contains(&rel)
        {
            files.push(rel);
        }
    }
    files.truncate(MAX_FILES);
    let project = crate::project::project_name(&root);
    cx.notes_for_files(project.as_deref(), &files, limit)
        .unwrap_or_default()
}

/// The text hits with the file-linked notes fused in (RRF): a note in both lists outranks an
/// equal text match that is in one. With no linked note the text hits come back untouched.
pub(super) fn boost(text: Vec<NoteHit>, linked: Vec<NoteHit>, n: usize) -> Vec<NoteHit> {
    if linked.is_empty() {
        let mut text = text;
        text.truncate(n);
        return text;
    }
    rrf_merge_lists(&[&text, &linked], u32::try_from(n).unwrap_or(u32::MAX))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn root(tag: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("rtok-t374-files-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("src/proxy")).unwrap();
        std::fs::write(dir.join("src/proxy/semantic_cache.rs"), "x").unwrap();
        std::fs::write(dir.join("Cargo.toml"), "x").unwrap();
        dir
    }

    #[test]
    fn only_existing_files_under_the_root_count() {
        let r = root("mentioned");
        let text = "see `src/proxy/semantic_cache.rs:120-130`, (Cargo.toml). Not src/missing.rs, \
                    nor ../etc/passwd, nor /etc/hosts, nor e.g. this.";
        assert_eq!(
            mentioned(&r, &r, text),
            vec!["src/proxy/semantic_cache.rs", "Cargo.toml"]
        );
        let abs = format!("{}/src/proxy/semantic_cache.rs#L9", r.display());
        assert_eq!(mentioned(&r, &r, &abs), vec!["src/proxy/semantic_cache.rs"]);
        let _ = std::fs::remove_dir_all(&r);
    }

    #[test]
    fn climbing_and_foreign_paths_are_refused() {
        let r = Path::new("/p");
        assert_eq!(relative(r, r, "src/../../x"), None);
        assert_eq!(relative(r, r, "/other/a.rs"), None);
        assert_eq!(relative(r, r, "/p/a/b.rs").as_deref(), Some("a/b.rs"));
        assert_eq!(relative(r, r, "./a.rs").as_deref(), Some("a.rs"));
        assert_eq!(relative(r, r, "/p"), None);
    }

    #[test]
    fn a_long_body_is_statted_up_to_the_candidate_cap() {
        let r = root("cap");
        let filler = (0..MAX_CANDIDATES)
            .map(|i| format!("no/{i}.rs "))
            .collect::<String>();
        let text = format!("{filler} Cargo.toml");
        assert!(
            mentioned(&r, &r, &text).is_empty(),
            "past the cap nothing is statted"
        );
        let _ = std::fs::remove_dir_all(&r);
    }

    #[test]
    fn key_path_reads_both_key_shapes() {
        assert_eq!(key_path("src/a.rs\tfull\t"), Some("src/a.rs"));
        assert_eq!(key_path("read\tsrc/a.rs\t1:9"), Some("src/a.rs"));
        assert_eq!(key_path("bash"), None);
        assert_eq!(key_path("read"), None);
    }

    fn hit(id: i32) -> NoteHit {
        NoteHit {
            id,
            title: format!("n{id}"),
            snippet: String::new(),
        }
    }

    #[test]
    fn boost_prefers_the_linked_note_and_leaves_plain_text_hits_alone() {
        let text = vec![hit(1), hit(2), hit(3)];
        assert_eq!(boost(text.clone(), vec![], 2).len(), 2);
        let ids: Vec<i32> = boost(text, vec![hit(2)], 3).iter().map(|h| h.id).collect();
        assert_eq!(ids, vec![2, 1, 3]);
    }
}

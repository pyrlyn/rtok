// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T374: which project files a note, a prompt or the session is about. A note linked to a file
//! the session has read, or the prompt names, is preferred by recall over an unlinked note that
//! matches the text equally well. T452: a file the prompt names also recalls its notes (at most 2)
//! when the text found none of them.
//!
//! Everything here reads untrusted text (a note body, a prompt, a transcript's paths), so a
//! path only counts once it is relative, free of `..` and inside the project root; paths are
//! stored root-relative so a note follows its project into every checkout and worktree.

use std::path::{Component, Path, PathBuf};

use rtok_config::root::{ProjectRoot, contain};
use rtok_plugin_sdk::{Ctx, NoteHit};

use crate::store::embed::rrf_merge_lists;

/// Candidate tokens statted per text: the hook path must stay cheap however long a body is.
const MAX_CANDIDATES: usize = 32;
/// Files in play per recall; bounds the `IN (…)` list the store queries.
const MAX_FILES: usize = 256;

/// The checkout `cwd` lies in, else `cwd` itself. `checkout`, not `main`: the files a note
/// names are read from the tree the session works in; notes store root-relative paths, so they
/// still match in every worktree.
fn project_root(cwd: Option<&str>) -> Option<PathBuf> {
    let cwd = cwd
        .map(PathBuf::from)
        .or_else(|| std::env::current_dir().ok())?;
    Some(ProjectRoot::find(&cwd).map_or(cwd, |r| r.checkout))
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
    if parts.is_empty() {
        return None;
    }
    let rel = parts.join("/");
    // The component check is lexical; a symlink inside the project can still point out of it.
    contain(root, Path::new(&rel)).ok()?;
    Some(rel)
}

/// Strip the `:12`, `:12-30` and `#L12` position suffixes and the sentence punctuation a path
/// picks up in prose. A leading `.` is path syntax (`.env`, `./x`), so only a trailing one is
/// sentence punctuation.
fn clean(token: &str) -> &str {
    let t = token
        .trim_start_matches(|c: char| ":,;!?".contains(c))
        .trim_end_matches(|c: char| ".:,;!?".contains(c));
    let t = t.split_once("#L").map_or(t, |(head, _)| head);
    match t.split_once(':') {
        Some((head, tail)) if tail.starts_with(|c: char| c.is_ascii_digit()) => head,
        _ => t,
    }
}

/// Root-relative paths among `raw` that exist as files under the project. A path outside
/// the root, or one that climbs, is dropped.
pub(super) fn existing_rels(cx: &Ctx, raw: &[String]) -> Vec<String> {
    let Some(root) = project_root(cx.cwd()) else {
        return Vec::new();
    };
    let canon = fs_kit::path::canonicalize(&root).unwrap_or_else(|_| root.clone());
    let mut out = Vec::new();
    for r in raw {
        if let Some(rel) = relative(&root, &canon, r)
            && root.join(&rel).is_file()
            && !out.contains(&rel)
        {
            out.push(rel);
        }
    }
    out.truncate(MAX_FILES);
    out
}

/// Files the prompt names that exist under the project, root-relative.
pub(super) fn prompt_paths(cx: &Ctx, prompt: &str) -> Vec<String> {
    let Some(root) = project_root(cx.cwd()) else {
        return Vec::new();
    };
    let canon = fs_kit::path::canonicalize(&root).unwrap_or_else(|_| root.clone());
    mentioned(&root, &canon, prompt)
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
    let canon = fs_kit::path::canonicalize(&root).unwrap_or_else(|_| root.clone());
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

/// Named-file notes recalled without a text match, per prompt. A cap keeps a prompt that names
/// a heavily linked file from crowding out the text hits.
const MAX_NAMED_EXTRA: usize = 2;

/// At most `n` hits for `prompt`. The session's read files only re-rank `text`; a file the prompt
/// names is a stronger signal, so up to `MAX_NAMED_EXTRA` of its notes that `text` missed take
/// slots from the end of the same `n`, never beyond it. Any failure leaves the text hits.
pub(super) fn recall_hits(cx: &Ctx, prompt: &str, text: Vec<NoteHit>, n: usize) -> Vec<NoteHit> {
    let Some(root) = project_root(cx.cwd()) else {
        return boost(text, Vec::new(), n);
    };
    let canon = fs_kit::path::canonicalize(&root).unwrap_or_else(|_| root.clone());
    let named = mentioned(&root, &canon, prompt);
    let mut files = named.clone();
    for key in cx.read_cache_keys().unwrap_or_default() {
        if let Some(rel) = key_path(&key).and_then(|p| relative(&root, &canon, p))
            && !files.contains(&rel)
        {
            files.push(rel);
        }
    }
    files.truncate(MAX_FILES);
    let project = crate::project::project_name(&root);
    let limit = u32::try_from(n.saturating_mul(2)).unwrap_or(u32::MAX);
    let notes = |files: &[String]| {
        cx.notes_for_files(project.as_deref(), files, limit)
            .unwrap_or_default()
    };
    let in_text = |h: &NoteHit| text.iter().any(|t| t.id == h.id);
    let mut extra = notes(&named);
    extra.retain(|h| !in_text(h));
    extra.truncate(MAX_NAMED_EXTRA.min(n));
    let mut linked = notes(&files);
    linked.retain(in_text);
    let mut hits = if n > extra.len() {
        boost(text, linked, n - extra.len())
    } else {
        Vec::new()
    };
    hits.extend(extra);
    hits
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

    /// T566: leading dots are path syntax; only prose punctuation is trimmed, so `.env`
    /// and `./src/lib.rs` resolve and `.config/file` never degrades to `config/file`.
    #[test]
    fn leading_dots_name_hidden_and_dot_relative_files() {
        let r = root("dots");
        for f in [".env", "src/lib.rs", ".config/file", "config/file"] {
            let p = r.join(f);
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
            std::fs::write(p, "x").unwrap();
        }
        let got = |text: &str| mentioned(&r, &r, text);
        assert_eq!(got("set `.env`."), vec![".env"]);
        assert_eq!(got("see ./src/lib.rs:12-30, then"), vec!["src/lib.rs"]);
        assert_eq!(got("in .config/file."), vec![".config/file"]);
        assert_eq!(got("in config/file#L3!"), vec!["config/file"]);
        assert_eq!(
            got("[.env](.env), (.config/file)"),
            vec![".env", ".config/file"]
        );
        // The dot-less sibling is never substituted when only the hidden twin is named.
        std::fs::remove_file(r.join("config/file")).unwrap();
        assert_eq!(got(".config/file"), vec![".config/file"]);
        std::fs::remove_file(r.join(".config/file")).unwrap();
        assert!(got(".config/file").is_empty());
        assert!(got("../.env and .../x").is_empty());
        let _ = std::fs::remove_dir_all(&r);
    }

    #[test]
    fn climbing_and_foreign_paths_are_refused() {
        // Built from a real absolute directory: on Windows `/p` has no drive, so is not absolute.
        let base = std::env::temp_dir();
        let r = &base.join("p");
        let abs = |p: &Path| p.to_str().unwrap().to_string();
        assert_eq!(relative(r, r, "src/../../x"), None);
        assert_eq!(relative(r, r, &abs(&base.join("other").join("a.rs"))), None);
        assert_eq!(
            relative(r, r, &abs(&r.join("a").join("b.rs"))).as_deref(),
            Some("a/b.rs")
        );
        assert_eq!(relative(r, r, "./a.rs").as_deref(), Some("a.rs"));
        assert_eq!(relative(r, r, &abs(r)), None);
    }

    /// T660.2: notes link files of the worktree the session is in, and fall back to `cwd`.
    #[test]
    fn project_root_is_the_linked_worktree_checkout() {
        let dir = crate::testutil::tmp_dir("t660-memory-root");
        let (main, wt) = crate::testutil::worktree_layout(&dir);
        std::fs::create_dir_all(wt.join("src")).unwrap();
        let at = |p: &Path| project_root(p.to_str());
        assert_eq!(at(&wt.join("src")), Some(wt.clone()));
        assert_eq!(at(&main), Some(main.clone()));
        assert_eq!(at(&dir), Some(dir.clone()));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T660.2: a symlink inside the project that points out of it is not a project file.
    #[cfg(unix)]
    #[test]
    fn a_symlink_out_of_the_root_is_refused() {
        let r = root("symlink");
        let outside = r.with_file_name(format!(
            "{}-outside",
            r.file_name().unwrap().to_string_lossy()
        ));
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("secret.txt"), "x").unwrap();
        std::os::unix::fs::symlink(&outside, r.join("escape")).unwrap();
        std::os::unix::fs::symlink(r.join("src"), r.join("inside")).unwrap();
        assert_eq!(relative(&r, &r, "escape/secret.txt"), None);
        let abs = r.join("escape/secret.txt");
        assert_eq!(relative(&r, &r, &abs.to_string_lossy()), None);
        assert!(mentioned(&r, &r, "see escape/secret.txt").is_empty());
        // A link that stays inside the project is still one of its files.
        assert_eq!(
            relative(&r, &r, "inside/proxy/semantic_cache.rs").as_deref(),
            Some("inside/proxy/semantic_cache.rs")
        );
        let _ = std::fs::remove_dir_all(&r);
        let _ = std::fs::remove_dir_all(&outside);
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

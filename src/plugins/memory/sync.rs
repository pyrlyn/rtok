// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok memory sync` — a managed block in `CLAUDE.md` / `AGENTS.md` (T69.6).
//!
//! Not a hook: writing a host file on SessionStart would break fail-open.

use anyhow::{Result, bail};
use std::ffi::OsStr;
use std::path::{Path, PathBuf};

use crate::config::Config;
use crate::tokens::{self, Class};

pub const START: &str = "<!-- rtok:memory -->";
pub const END: &str = "<!-- /rtok:memory -->";

/// T59.7 overlap line: the same titles in the synced block and hook recall.
pub fn overlap_line() -> &'static str {
    "duplicate: a memory sync block and hook recall both carry titles — rtok side: [plugins.memory] enabled = false"
}

pub fn has_block(text: &str) -> bool {
    matches!(block_range(text), Ok(Some(_)))
}

/// The guard digest is per destination file: the basename alone made `a/CLAUDE.md` and
/// `b/CLAUDE.md` overwrite and delete each other's protection. The parent is resolved even
/// when the file does not exist yet, so a first sync and a later one agree on the key.
fn kv_key(path: &Path) -> String {
    let identity = match (path.parent(), path.file_name()) {
        (Some(dir), Some(name)) => crate::fs::canon(dir).join(name),
        _ => crate::fs::canon(path),
    };
    format!("memory.sync.sha:{}", identity.display())
}

/// Pre-T563 key, shared by every file of the same name. Read-only fallback: a digest only
/// vouches for a block whose bytes it matches, so it cannot trust unrelated content.
fn legacy_kv_key(path: &Path) -> String {
    format!(
        "memory.sync.sha:{}",
        path.file_name().unwrap_or_default().to_string_lossy()
    )
}

/// A title as one host-file line that cannot open or close the block: control characters
/// (newlines included) become spaces and `<!--` is escaped, which removes both markers.
/// The stored note title is untouched.
fn single_line(title: &str) -> String {
    title
        .chars()
        .map(|c| {
            if c.is_control() || c == '\u{2028}' || c == '\u{2029}' {
                ' '
            } else {
                c
            }
        })
        .collect::<String>()
        .replace("<!--", "&lt;!--")
}

/// Pinned-first, then remaining live notes by id desc, trimmed to `budget` tokens.
pub fn render(notes: &[(i32, String)], budget: u32, estimate: impl Fn(&str) -> u32) -> String {
    let mut entries = notes.to_vec();
    loop {
        let mut lines = Vec::with_capacity(entries.len() + 2);
        lines.push(START.to_string());
        for (id, title) in &entries {
            lines.push(format!("{id} {}", single_line(title)));
        }
        lines.push(END.to_string());
        let text = lines.join("\n");
        if estimate(&text) <= budget || entries.is_empty() {
            return text;
        }
        entries.pop();
    }
}

fn block_range(text: &str) -> Result<Option<(usize, usize)>> {
    let Some(start) = text.find(START) else {
        return Ok(None);
    };
    let rest = &text[start + START.len()..];
    if rest.contains(START) {
        bail!("multiple rtok memory blocks");
    }
    let Some(rel) = rest.find(END) else {
        bail!("unclosed rtok memory block");
    };
    Ok(Some((start, start + START.len() + rel + END.len())))
}

/// The generated block must be exactly one START..END pair; anything else would be
/// rejected as hand-edited by the very next sync.
fn check_block(block: &str) -> Result<()> {
    match block_range(block)? {
        Some((0, end)) if end == block.len() => Ok(()),
        _ => bail!("generated memory block is malformed"),
    }
}

/// Replace or remove the managed block. Bytes outside the markers are unchanged.
pub fn splice(file: &str, block: Option<&str>) -> Result<String> {
    match (block_range(file)?, block) {
        (None, None) => Ok(file.to_string()),
        (None, Some(b)) => {
            let mut out = file.to_string();
            if !out.is_empty() && !out.ends_with('\n') {
                out.push('\n');
            }
            out.push_str(b);
            if !out.ends_with('\n') {
                out.push('\n');
            }
            Ok(out)
        }
        (Some((a, b)), None) => Ok(format!("{}{}", &file[..a], &file[b..])),
        (Some((a, b)), Some(block)) => Ok(format!("{}{}{}", &file[..a], block, &file[b..])),
    }
}

pub fn guarded_splice(
    file: &str,
    block: Option<&str>,
    stored_sha: Option<&str>,
    force: bool,
) -> Result<String> {
    if let Some((a, b)) = block_range(file)? {
        let sha = crate::store::hex_sha256(&file.as_bytes()[a..b]);
        if !force && stored_sha != Some(sha.as_str()) {
            bail!("memory block was hand-edited; pass --force to overwrite");
        }
    }
    splice(file, block)
}

pub fn run(
    cfg: &Config,
    file: PathBuf,
    budget: Option<u32>,
    dry_run: bool,
    remove: bool,
    force: bool,
) -> Result<()> {
    let name = file.file_name().and_then(OsStr::to_str).unwrap_or("");
    if name != "CLAUDE.md" && name != "AGENTS.md" {
        bail!("--file must be CLAUDE.md or AGENTS.md");
    }
    let path = if file.is_absolute() {
        file
    } else {
        std::env::current_dir()?.join(&file)
    };
    // Only an absent file starts empty: reading a damaged one as empty would back it up and
    // overwrite every byte outside the managed block.
    let before = fs_kit::read::text_or_empty(&path)?;
    let cx = crate::plugin::Runtime::open(cfg.clone(), "memory")?;
    let key = kv_key(&path);
    let stored = match cx.store.kv_get(&key)? {
        Some(sha) => Some(sha),
        None => cx.store.kv_get(&legacy_kv_key(&path))?,
    };
    let budget = budget.unwrap_or(cfg.plugins.memory.sync_tokens);
    let estimate = |s: &str| tokens::estimate(s, Class::Prose, &cfg.estimator);
    let new_block = if remove {
        None
    } else {
        let project = std::env::current_dir()
            .ok()
            .and_then(|d| super::project_name(&d));
        let notes = cx.store.list_note_titles(project.as_deref(), u32::MAX)?;
        let block = render(&notes, budget, estimate);
        check_block(&block)?;
        Some(block)
    };
    let after = guarded_splice(&before, new_block.as_deref(), stored.as_deref(), force)?;
    if dry_run {
        let diff = change_preview::unified_diff(&path, &before, &after);
        crate::log::stdout(&crate::stat_preview::show_file(&path, &diff));
    } else {
        if after != before {
            rtok_agent_sdk::backup(&path, cfg.setup.backup_files as usize)?;
            rtok_agent_sdk::write(
                &rtok_agent_sdk::Apply::default(),
                &path,
                &after,
                "memory sync",
            )?;
        }
        match &new_block {
            Some(b) => cx
                .store
                .kv_set(&key, &crate::store::hex_sha256(b.as_bytes()))?,
            None => cx.store.kv_delete(&key)?,
        }
    }
    let recall_on = cfg.plugin_enabled("memory", true) && cfg.plugins.memory.recall_tokens > 0;
    if !remove && recall_on {
        crate::log::stdout_ln(overlap_line());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::Vfs;

    fn est(s: &str) -> u32 {
        s.len() as u32
    }

    fn notes() -> Vec<(i32, String)> {
        vec![
            (3, "pinned-high".into()),
            (2, "newer".into()),
            (1, "older".into()),
        ]
    }

    fn vfs_file(body: &str) -> Vfs {
        let mut v = Vfs::new();
        v.write("CLAUDE.md", body.as_bytes());
        v
    }

    #[test]
    fn vfs_create_appends_block_at_end() {
        let mut v = vfs_file("# intro\nkeep me\n");
        let block = render(&notes(), 10_000, est);
        let after =
            guarded_splice(v.read_str("CLAUDE.md").unwrap(), Some(&block), None, false).unwrap();
        v.write("CLAUDE.md", after.as_bytes());
        let got = v.read_str("CLAUDE.md").unwrap();
        assert!(got.starts_with("# intro\nkeep me\n"), "{got}");
        assert!(got.contains("3 pinned-high\n2 newer\n1 older\n"), "{got}");
        assert!(got.ends_with(&format!("{END}\n")), "{got}");
    }

    #[test]
    fn vfs_update_leaves_outside_bytes_identical() {
        let block1 = render(&[(1, "a".into())], 10_000, est);
        let file = splice("pre\n", Some(&block1)).unwrap() + "post\n";
        let block2 = render(&[(2, "b".into())], 10_000, est);
        let sha = crate::store::hex_sha256(block1.as_bytes());
        let after = guarded_splice(&file, Some(&block2), Some(&sha), false).unwrap();
        assert!(after.starts_with("pre\n"), "{after}");
        assert!(after.ends_with("post\n"), "{after}");
        assert!(after.contains("2 b"), "{after}");
        assert!(!after.contains("1 a"), "{after}");
    }

    #[test]
    fn vfs_hand_edit_refused_unless_force() {
        let block = render(&[(1, "title".into())], 10_000, est);
        let file = splice("pre\n", Some(&block)).unwrap();
        let sha = crate::store::hex_sha256(block.as_bytes());
        let edited = file.replace("1 title", "1 edited");
        let mut v = vfs_file(&edited);
        let err = guarded_splice(
            v.read_str("CLAUDE.md").unwrap(),
            Some(&block),
            Some(&sha),
            false,
        )
        .unwrap_err();
        assert!(err.to_string().contains("--force"), "{err}");
        let after = guarded_splice(
            v.read_str("CLAUDE.md").unwrap(),
            Some(&block),
            Some(&sha),
            true,
        )
        .unwrap();
        v.write("CLAUDE.md", after.as_bytes());
        assert!(v.read_str("CLAUDE.md").unwrap().contains("1 title"));
    }

    #[test]
    fn vfs_remove_deletes_only_the_block() {
        let block = render(&[(1, "x".into())], 10_000, est);
        let file = format!("alpha\n{block}omega\n");
        let sha = crate::store::hex_sha256(block.as_bytes());
        let after = guarded_splice(&file, None, Some(&sha), false).unwrap();
        let v = vfs_file(&after);
        let got = v.read_str("CLAUDE.md").unwrap();
        assert_eq!(got, "alpha\nomega\n");
        assert!(!has_block(got));
    }

    #[test]
    fn vfs_budget_trims_from_the_end() {
        let block = render(&notes(), 65, est);
        assert!(block.contains("3 pinned-high"), "{block}");
        assert!(block.contains("2 newer"), "{block}");
        assert!(!block.contains("1 older"), "{block}");
        assert!(est(&block) <= 65, "{} {block}", est(&block));
    }

    fn seed_note(cfg: &Config, title: &str) {
        let cx = crate::plugin::Runtime::open(cfg.clone(), "seed").unwrap();
        let project = std::env::current_dir()
            .ok()
            .and_then(|d| super::super::project_name(&d));
        cx.store
            .insert_note(project.as_deref(), "note", title, "body")
            .unwrap();
    }

    fn sync(cfg: &Config, file: &Path, remove: bool, force: bool) -> Result<()> {
        run(cfg, file.to_path_buf(), None, false, remove, force)
    }

    /// T563: the guard digest was keyed by basename, so syncing `b/CLAUDE.md` replaced
    /// `a/CLAUDE.md`'s digest and an unchanged `a` then failed as hand-edited.
    #[test]
    fn each_destination_keeps_its_own_hand_edit_guard() {
        let (c, dir) = crate::testutil::config("t563-sync-key");
        let (a, b) = (dir.join("a/CLAUDE.md"), dir.join("b/CLAUDE.md"));
        for f in [&a, &b] {
            std::fs::create_dir_all(f.parent().unwrap()).unwrap();
            std::fs::write(f, "# host\n").unwrap();
        }
        seed_note(&c, "one");
        sync(&c, &a, false, false).unwrap();
        seed_note(&c, "two");
        sync(&c, &b, false, false).unwrap();
        sync(&c, &a, false, false).expect("a is unchanged since its own sync");
        sync(&c, &b, true, false).unwrap();
        seed_note(&c, "three");
        sync(&c, &a, false, false).expect("removing b must not drop a's guard");

        let edited = std::fs::read_to_string(&a).unwrap().replace("one", "hand");
        std::fs::write(&a, edited).unwrap();
        let err = sync(&c, &a, false, false).unwrap_err();
        assert!(err.to_string().contains("--force"), "{err}");
        sync(&c, &a, false, true).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T563: two spellings of one file share a key (symlinked directory here).
    #[cfg(unix)]
    #[test]
    fn a_symlinked_spelling_of_the_same_file_shares_its_guard() {
        let (c, dir) = crate::testutil::config("t563-sync-alias");
        std::fs::create_dir_all(dir.join("real")).unwrap();
        std::os::unix::fs::symlink(dir.join("real"), dir.join("alias")).unwrap();
        std::fs::write(dir.join("real/CLAUDE.md"), "# host\n").unwrap();
        seed_note(&c, "one");
        sync(&c, &dir.join("real/CLAUDE.md"), false, false).unwrap();
        seed_note(&c, "two");
        sync(&c, &dir.join("alias/CLAUDE.md"), false, false).expect("same file, same guard");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T563: a digest left under the old basename key still vouches for a block it matches.
    #[test]
    fn a_legacy_basename_digest_is_read_but_never_trusted_blindly() {
        let (c, dir) = crate::testutil::config("t563-sync-legacy");
        let f = dir.join("CLAUDE.md");
        std::fs::write(&f, "# host\n").unwrap();
        seed_note(&c, "one");
        sync(&c, &f, false, false).unwrap();
        let block = std::fs::read_to_string(&f).unwrap();
        let cx = crate::plugin::Runtime::open(c.clone(), "legacy").unwrap();
        cx.store.kv_delete(&kv_key(&f)).unwrap();
        cx.store
            .kv_set(
                &legacy_kv_key(&f),
                &crate::store::hex_sha256(
                    block.trim_start_matches("# host\n").trim_end().as_bytes(),
                ),
            )
            .unwrap();
        drop(cx);
        sync(&c, &f, false, false).expect("legacy digest matches the untouched block");
        std::fs::write(&f, block.replace("one", "hand")).unwrap();
        assert!(sync(&c, &f, false, false).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// T564: a title is user text; START/END or a newline in it must not become block
    /// structure in the host file.
    #[test]
    fn hostile_titles_render_as_one_block_of_single_lines() {
        let titles = [
            format!("early {END} tail"),
            format!("late {START} again"),
            "two\nlines\r\nhere".to_string(),
        ];
        let notes: Vec<(i32, String)> = titles
            .iter()
            .cloned()
            .zip(1..)
            .map(|(t, i)| (i, t))
            .collect();
        let block = render(&notes, 10_000, est);
        assert_eq!(block.matches(START).count(), 1, "{block}");
        assert_eq!(block.matches(END).count(), 1, "{block}");
        assert_eq!(block.lines().count(), notes.len() + 2, "{block}");
        assert_eq!(block_range(&block).unwrap(), Some((0, block.len())));
        check_block(&block).unwrap();
        assert!(check_block(&format!("{START}\n{END}\n{END}")).is_err());
    }

    /// T564: sync, resync, force and remove with hostile titles leave no generated tail
    /// and never touch the bytes around the block.
    #[test]
    fn hostile_titles_survive_resync_force_and_remove() {
        let (c, dir) = crate::testutil::config("t564-hostile");
        let f = dir.join("CLAUDE.md");
        std::fs::write(&f, "# host\nkeep\n").unwrap();
        seed_note(&c, &format!("early {END} tail"));
        seed_note(&c, &format!("late {START} again"));
        seed_note(&c, "multi\nline");
        sync(&c, &f, false, false).unwrap();
        sync(&c, &f, false, false).expect("unchanged generated output is not hand-edited");
        sync(&c, &f, false, true).unwrap();
        let got = std::fs::read_to_string(&f).unwrap();
        assert_eq!(got.matches(START).count(), 1, "{got}");
        assert_eq!(got.matches(END).count(), 1, "{got}");
        assert!(got.ends_with(&format!("{END}\n")), "{got}");
        sync(&c, &f, true, false).unwrap();
        let left = std::fs::read_to_string(&f).unwrap();
        assert_eq!(
            left.trim_end(),
            "# host\nkeep",
            "no generated tail: {left:?}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn digest(cfg: &Config, file: &Path) -> Option<String> {
        let cx = crate::plugin::Runtime::open(cfg.clone(), "probe").unwrap();
        cx.store.kv_get(&kv_key(file)).unwrap()
    }

    fn tree(dir: &Path) -> Vec<PathBuf> {
        let mut out = Vec::new();
        let mut stack = vec![dir.to_path_buf()];
        while let Some(d) = stack.pop() {
            for e in std::fs::read_dir(&d).unwrap().flatten() {
                out.push(e.path());
                if e.path().is_dir() {
                    stack.push(e.path());
                }
            }
        }
        out.sort();
        out
    }

    /// T562: an unreadable host file must abort every mode before anything is rendered,
    /// backed up, written or recorded; reading it as empty replaced the whole file.
    fn assert_read_failure_changes_nothing(c: &Config, dir: &Path, f: &Path, before: &[u8]) {
        let digest_before = digest(c, f);
        let tree_before = tree(dir);
        for (dry, remove, force) in [
            (false, false, false),
            (true, false, false),
            (false, true, false),
            (false, false, true),
            (true, true, true),
        ] {
            let err = run(c, f.to_path_buf(), None, dry, remove, force).unwrap_err();
            assert!(
                err.to_string().contains("CLAUDE.md"),
                "dry={dry} remove={remove} force={force}: {err:#}"
            );
            assert_eq!(std::fs::read(f).unwrap(), before, "host bytes changed");
            assert_eq!(digest(c, f), digest_before, "digest changed");
            assert_eq!(tree(dir), tree_before, "backup or stray file created");
        }
    }

    #[test]
    fn a_non_utf8_host_file_aborts_sync_without_touching_anything() {
        let (c, dir) = crate::testutil::config("t562-non-utf8");
        let f = dir.join("CLAUDE.md");
        let mut bytes = b"# host\nkeep this outside text\n".to_vec();
        bytes.extend_from_slice(b"bad \xff byte\n");
        std::fs::write(&f, &bytes).unwrap();
        seed_note(&c, "one");
        assert_read_failure_changes_nothing(&c, &dir, &f, &bytes);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_non_utf8_host_file_keeps_an_existing_digest() {
        let (c, dir) = crate::testutil::config("t562-non-utf8-digest");
        let f = dir.join("CLAUDE.md");
        std::fs::write(&f, "# host\n").unwrap();
        seed_note(&c, "one");
        sync(&c, &f, false, false).unwrap();
        assert!(digest(&c, &f).is_some());
        let mut bytes = std::fs::read(&f).unwrap();
        bytes.push(0xff);
        std::fs::write(&f, &bytes).unwrap();
        assert_read_failure_changes_nothing(&c, &dir, &f, &bytes);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn an_unreadable_host_file_aborts_sync_without_touching_anything() {
        use std::os::unix::fs::PermissionsExt;
        let (c, dir) = crate::testutil::config("t562-unreadable");
        let f = dir.join("CLAUDE.md");
        std::fs::write(&f, "# host\nkeep\n").unwrap();
        std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o000)).unwrap();
        if std::fs::read(&f).is_ok() {
            // Root ignores the mode bits, so the fixture cannot produce a read failure.
            std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o644)).unwrap();
            let _ = std::fs::remove_dir_all(&dir);
            return;
        }
        seed_note(&c, "one");
        let digest_before = digest(&c, &f);
        let tree_before = tree(&dir);
        for (dry, remove, force) in [
            (false, false, false),
            (true, false, false),
            (false, true, false),
            (false, false, true),
        ] {
            let err = run(&c, f.clone(), None, dry, remove, force).unwrap_err();
            assert!(err.to_string().contains("CLAUDE.md"), "{err:#}");
            assert_eq!(digest(&c, &f), digest_before);
            assert_eq!(tree(&dir), tree_before);
        }
        std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert_eq!(std::fs::read_to_string(&f).unwrap(), "# host\nkeep\n");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_host_file_is_still_created() {
        let (c, dir) = crate::testutil::config("t562-missing");
        let f = dir.join("CLAUDE.md");
        seed_note(&c, "one");
        sync(&c, &f, true, false).expect("removing from a missing file is a no-op");
        assert!(!f.exists());
        run(&c, f.clone(), None, true, false, false).unwrap();
        assert!(!f.exists(), "dry-run must not create the file");
        sync(&c, &f, false, false).unwrap();
        let got = std::fs::read_to_string(&f).unwrap();
        assert!(got.starts_with(START) && got.contains("one"), "{got}");
        assert!(digest(&c, &f).is_some());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn unchanged_store_is_byte_stable() {
        let a = render(&notes(), 300, est);
        let b = render(&notes(), 300, est);
        assert_eq!(a, b);
        assert!(!a.contains("20"), "{a}");
    }
}

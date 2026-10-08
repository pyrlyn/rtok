// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The `sessions` junk kind (T330.5.2, D36): old sessions, class `explicit` (only
//! `--kind sessions` clears them), judged by time alone. The per-host verdict is recorded in
//! `research.md` §22.1: a host takes part only when its sessions cell documents the whole
//! session unit and the index the host keeps beside it. That is Claude Code today
//! (`projects/<project>/<session>.jsonl`, `<session>/`, `file-history/<session>/`, no index);
//! Codex (fork index and state DB), Copilot (`session-store.db`), Kimi (`session_index.jsonl`),
//! opencode (one store per project), Gemini (the files of one session are not named) and the
//! rest are not. The host's memory (`projects/<project>/memory/`) never matches a session.
//! Nothing here deletes.

use std::path::PathBuf;
use std::time::{Duration, SystemTime};

use super::junk_cache::{Ctx, Item, SECTION_22, is_symlink, make_item};
use super::junk_clear::newest;
use super::junk_map::Roots;

/// A session written to in this window may be open in its agent, whatever the threshold says.
const IN_USE: Duration = Duration::from_secs(600);

/// Claude Code names a session by a UUID.
fn is_session_id(s: &str) -> bool {
    let dash = |i| matches!(i, 8 | 13 | 18 | 23);
    s.len() == 36
        && s.bytes().enumerate().all(|(i, b)| {
            if dash(i) {
                b == b'-'
            } else {
                b.is_ascii_hexdigit()
            }
        })
}

fn dir(p: PathBuf) -> Option<PathBuf> {
    (p.is_dir() && !is_symlink(&p)).then_some(p)
}

/// Why a session with these parts stays, or `None` when it is old. One answer for all parts,
/// so a session goes whole or not at all. The reason names no age: a host can hold hundreds
/// of sessions and `list` groups the ones that share a reason.
fn stays(parts: &[PathBuf], days: u32, now: SystemTime) -> Option<String> {
    let mut last: Option<SystemTime> = None;
    for p in parts {
        // A part that cannot be dated says nothing about the session's age.
        let Some(Some(t)) = newest(p) else {
            return Some("last used unknown: not cleared".into());
        };
        last = last.max(Some(t));
    }
    let t = last?;
    let Ok(age) = now.duration_since(t) else {
        return Some("future timestamp: counted as just touched".into());
    };
    if age < IN_USE {
        Some("skipped: in use (modified in the last 10 minutes)".into())
    } else if age <= Duration::from_secs(u64::from(days) * 86_400) {
        Some(format!("touched within the {days}-day threshold"))
    } else {
        None
    }
}

/// Claude Code's sessions: per session its transcript, its `<session>/` folder (subagents and
/// tool results) and its `file-history/<session>/` restore points, each an item of its own
/// with the same answer. `days` is `[agents.junk] stale_session_days`.
pub fn session_items(roots: &Roots, days: u32, cx: &Ctx, limit: Duration) -> Vec<Item> {
    let claude = roots.resolve("{claude}");
    let projects = std::fs::read_dir(claude.join("projects"));
    let mut items = Vec::new();
    for project in projects.into_iter().flatten().flatten().map(|e| e.path()) {
        if !project.is_dir() || is_symlink(&project) {
            continue;
        }
        for file in std::fs::read_dir(&project).into_iter().flatten().flatten() {
            let transcript = file.path();
            let id = transcript
                .file_name()
                .and_then(|n| n.to_str())
                .and_then(|n| n.strip_suffix(".jsonl"))
                .filter(|id| is_session_id(id))
                .map(str::to_owned);
            let Some(id) = id else { continue };
            if !transcript.is_file() || is_symlink(&transcript) {
                continue;
            }
            let mut parts = vec![transcript];
            parts.extend(dir(project.join(&id)));
            parts.extend(dir(claude.join("file-history").join(&id)));
            let kept = stays(&parts, days, cx.policy.now);
            for p in parts {
                items.push(Item {
                    class: "explicit",
                    ..make_item("sessions", &p, SECTION_22, kept.clone(), limit)
                });
            }
        }
    }
    items
}

/// What the host prunes by itself, shown next to rtok's threshold: Claude Code's
/// `cleanupPeriodDays` (default 30) and Gemini CLI's `general.sessionRetention.maxAge`
/// (default 30d), both from the host's `settings.json` (`research.md` §22.1).
pub fn retention(roots: &Roots, host: &str) -> Option<String> {
    let (file, key, default) = match host {
        "claude" => (
            roots.resolve("{claude}/settings.json"),
            "cleanupPeriodDays",
            "30",
        ),
        "gemini" => (
            roots.resolve("{home}/.gemini/settings.json"),
            "general.sessionRetention.maxAge",
            "30d",
        ),
        _ => return None,
    };
    // A missing or unreadable file is the host's default, not an error.
    let json: serde_json::Value = std::fs::read_to_string(&file)
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default();
    let set = key.split('.').try_fold(&json, |v, k| v.get(k));
    let text = match set {
        Some(serde_json::Value::String(s)) => format!("{key} = {s}"),
        Some(v) => format!("{key} = {v}"),
        None => format!("{key} = {default} (default)"),
    };
    Some(format!("host retention: {text}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::junk_cache::DEFAULT_IDLE;
    use crate::testutil::tmp_dir;
    use std::path::Path;

    const LIMIT: Duration = Duration::from_secs(10);
    const DAY: u64 = 86_400;
    const ID: &str = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0";

    fn put(path: &Path, age_secs: u64) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, b"x").unwrap();
        let f = std::fs::File::options().write(true).open(path).unwrap();
        f.set_modified(SystemTime::now() - Duration::from_secs(age_secs))
            .unwrap();
    }

    fn roots(home: &Path) -> Roots {
        Roots::new(home.to_path_buf(), |_| None)
    }

    fn items(home: &Path, days: u32) -> Vec<Item> {
        let cx = Ctx::new(home, DEFAULT_IDLE, SystemTime::now());
        session_items(&roots(home), days, &cx, LIMIT)
    }

    /// One session of `age` days: transcript, subagent folder, restore points.
    fn write_session(home: &Path, id: &str, age: u64) {
        let secs = age * DAY;
        put(&home.join(format!(".claude/projects/p/{id}.jsonl")), secs);
        put(
            &home.join(format!(".claude/projects/p/{id}/subagents/a.jsonl")),
            secs,
        );
        put(&home.join(format!(".claude/file-history/{id}/f@v1")), secs);
    }

    #[test]
    fn an_old_session_goes_whole_and_the_hosts_memory_and_a_young_one_stay() {
        let home = tmp_dir("sessions-old");
        let young = "11111111-2222-3333-4444-555555555555";
        write_session(&home, ID, 31);
        write_session(&home, young, 29);
        put(&home.join(".claude/projects/p/memory/MEMORY.md"), 90 * DAY);
        put(&home.join(".claude/projects/p/notes.jsonl"), 90 * DAY);

        let found = items(&home, 30);

        assert_eq!(found.len(), 6, "{found:?}");
        let (old, kept): (Vec<_>, Vec<_>) = found.iter().partition(|i| i.counted());
        assert_eq!(old.len(), 3);
        assert!(old.iter().all(|i| i.path.contains(ID)));
        assert!(
            old.iter()
                .all(|i| (i.kind, i.class) == ("sessions", "explicit"))
        );
        let why = "touched within the 30-day threshold";
        assert!(kept.iter().all(|i| i.kept.as_deref() == Some(why)));
        assert!(found.iter().all(|i| !i.path.contains("memory")));
    }

    #[test]
    fn the_threshold_is_strict_and_zero_still_spares_a_session_in_use() {
        let home = tmp_dir("sessions-threshold");
        let near = "22222222-2222-3333-4444-555555555555";
        let busy = "33333333-2222-3333-4444-555555555555";
        // A minute past 30 days is old, a minute short of it is not.
        write_session(&home, ID, 30);
        put(
            &home.join(format!(".claude/projects/p/{ID}.jsonl")),
            30 * DAY + 60,
        );
        put(
            &home.join(format!(".claude/projects/p/{ID}/subagents/a.jsonl")),
            30 * DAY + 60,
        );
        put(
            &home.join(format!(".claude/file-history/{ID}/f@v1")),
            30 * DAY + 60,
        );
        put(
            &home.join(format!(".claude/projects/p/{near}.jsonl")),
            30 * DAY - 60,
        );
        put(
            &home.join(format!(".claude/projects/p/{busy}.jsonl")),
            5 * 60,
        );

        let found = items(&home, 30);
        let state = |id: &str| {
            found
                .iter()
                .find(|i| i.path.contains(id))
                .unwrap()
                .counted()
        };
        assert!(state(ID) && !state(near) && !state(busy));

        let all = items(&home, 0);
        let state = |id: &str| all.iter().find(|i| i.path.contains(id)).unwrap();
        assert!(state(near).counted());
        assert!(state(busy).kept.as_deref().unwrap().contains("in use"));
    }

    #[test]
    fn a_future_timestamp_counts_as_just_touched_and_a_linked_session_is_ignored() {
        let home = tmp_dir("sessions-future");
        put(&home.join(format!(".claude/projects/p/{ID}.jsonl")), 0);
        let f = std::fs::File::options()
            .write(true)
            .open(home.join(format!(".claude/projects/p/{ID}.jsonl")))
            .unwrap();
        f.set_modified(SystemTime::now() + Duration::from_secs(5 * DAY))
            .unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(
            home.join(format!(".claude/projects/p/{ID}.jsonl")),
            home.join(".claude/projects/p/44444444-2222-3333-4444-555555555555.jsonl"),
        )
        .unwrap();

        let found = items(&home, 0);

        assert_eq!(found.len(), 1, "{found:?}");
        assert!(
            found[0]
                .kept
                .as_deref()
                .unwrap()
                .starts_with("future timestamp")
        );
    }

    #[test]
    fn the_hosts_own_retention_is_read_with_its_default() {
        let home = tmp_dir("sessions-retention");
        let r = roots(&home);
        assert_eq!(
            retention(&r, "claude").unwrap(),
            "host retention: cleanupPeriodDays = 30 (default)"
        );
        put(&home.join(".claude/settings.json"), 0);
        std::fs::write(
            home.join(".claude/settings.json"),
            r#"{"cleanupPeriodDays":7}"#,
        )
        .unwrap();
        std::fs::create_dir_all(home.join(".gemini")).unwrap();
        std::fs::write(
            home.join(".gemini/settings.json"),
            r#"{"general":{"sessionRetention":{"maxAge":"14d"}}}"#,
        )
        .unwrap();
        assert_eq!(
            retention(&r, "claude").unwrap(),
            "host retention: cleanupPeriodDays = 7"
        );
        assert_eq!(
            retention(&r, "gemini").unwrap(),
            "host retention: general.sessionRetention.maxAge = 14d"
        );
        assert!(retention(&r, "codex").is_none());
    }
}

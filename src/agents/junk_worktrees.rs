// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The `stale-worktrees` junk kind (T330.5.3, decided in T341): a worktree is junk exactly when
//! `rtok worktree gc` would remove it, and `clear` removes it through gc's own per-record
//! `remove::detach`. No second verdict and no blanket `git worktree prune` (T153): an orphan or
//! a record whose directory is gone is listed with gc's wording and never removed here. Junk is
//! the narrower of the two: gc's override verdicts (an abandoned foreign lock, a finished task
//! that a live agent or a lock holds) are listed and left to `rtok worktree gc`, because the
//! decided kind removes only what no one else holds.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

use super::HOSTS;
use super::junk::disk_usage_until;
use super::junk_cache::{GC_VERDICT, Item};
use crate::config::Config;
use crate::store::Store;
use crate::worktree::gc::{self, Judged, Verdict};
use crate::worktree::{State, list, ops};

pub const KIND: &str = "stale-worktrees";

/// A linked worktree of the repository and the host that made it, when known.
pub struct Worktree {
    pub host: Option<&'static str>,
    pub path: PathBuf,
    /// `list::Row::state`: `stale` means the directory is gone.
    pub state: &'static str,
}

/// Every linked worktree of the repository `cwd` is in, orphans included, with the host the
/// store, the lock or the host's own pool (T289) names. Without a repository or a store the
/// list is short, never an error.
pub fn worktrees(cfg: &Config, cwd: &Path) -> Vec<Worktree> {
    let Ok(mut rows) = list::rows(cwd) else {
        return Vec::new();
    };
    let store = Store::open(&cfg.core.db_path).ok();
    list::attribute_with_store(&mut rows, store.as_ref(), &cfg.agents.idle);
    rows.into_iter()
        .filter(|r| r.state != "main")
        .map(|r| {
            let named = r.agent.and_then(|a| a.host);
            let named = named.or(r.session.and_then(|s| s.host));
            let host = named.unwrap_or_else(|| r.origin.to_string());
            Worktree {
                host: HOSTS.iter().find(|h| **h == host).copied(),
                path: r.path,
                state: r.state,
            }
        })
        .collect()
}

/// The gc policy junk judges by: the idle window is `stale_worktree_days` and no lock is
/// "ours", so any lock holds the worktree.
fn policy(cfg: &Config, now: SystemTime) -> gc::Policy<'static> {
    let idle = Duration::from_secs(u64::from(cfg.agents.junk.stale_worktree_days) * 86_400);
    gc::Policy {
        owner: None,
        idle,
        stale_lock: idle,
        now,
        live: ops::live_ids(cfg),
    }
}

/// One item per non-main worktree, under the host that made it, else under `rtok`. A removal
/// verdict is a counted `review` item; every other verdict is listed with gc's reason.
pub fn items(
    cfg: &Config,
    cwd: &Path,
    owners: &[Worktree],
    now: SystemTime,
    limit: Duration,
) -> Vec<(&'static str, Item)> {
    let Ok((_, judged)) = gc::judge(cwd, &policy(cfg, now)) else {
        return Vec::new();
    };
    let same =
        |a: &Path, b: &Path| crate::fs::same_path(&crate::fs::canon(a), &crate::fs::canon(b));
    let orphans = owners.iter().filter(|w| w.state == "orphan").map(|w| {
        let item = Item {
            kind: KIND,
            class: "review",
            path: w.path.display().to_string(),
            bytes: disk_usage_until(&w.path, Some(Instant::now() + limit)).bytes,
            evidence: GC_VERDICT,
            kept: Some(
                "orphan: git no longer lists it; check it and delete the folder yourself".into(),
            ),
        };
        (w.host.unwrap_or("rtok"), item)
    });
    let listed = judged
        .into_iter()
        .filter(|j| j.entry.state != State::Main)
        .map(|Judged { entry, verdict }| {
            let path = &entry.record.path;
            let host = owners.iter().find(|w| same(&w.path, path));
            let (_, note) = verdict.plan();
            let kept = match verdict {
                Verdict::Remove => None,
                // gc drops such a record itself; junk never does (T341).
                Verdict::DropRecord => {
                    Some(format!("{note}: only `rtok worktree gc` drops its record"))
                }
                Verdict::Reclaim(_) | Verdict::Finished(_) => {
                    Some(format!("{note}: left to `rtok worktree gc`"))
                }
                Verdict::Keep(_) => Some(note),
            };
            let item = Item {
                kind: KIND,
                class: "review",
                path: path.display().to_string(),
                bytes: disk_usage_until(path, Some(Instant::now() + limit)).bytes,
                evidence: GC_VERDICT,
                kept,
            };
            (host.and_then(|w| w.host).unwrap_or("rtok"), item)
        });
    listed.chain(orphans).collect()
}

/// gc's verdicts for `clear`, taken once per run and only if a worktree is actually removed.
#[derive(Default)]
pub struct Remover {
    judged: Option<Option<(PathBuf, Vec<Judged>)>>,
}

impl Remover {
    /// Remove the worktree at `path` if `gc` still would, one record at a time, with its merged
    /// branch. The error is why it stayed.
    pub fn remove(&mut self, cfg: &Config, now: SystemTime, path: &Path) -> Result<String, String> {
        let judged = self.judged.get_or_insert_with(|| {
            let cwd = std::env::current_dir().ok()?;
            gc::judge(&cwd, &policy(cfg, now)).ok()
        });
        let (repo, judged) = judged
            .as_ref()
            .ok_or("cannot judge the worktrees: not inside their repository")?;
        let canon = crate::fs::canon(path);
        let found = judged
            .iter()
            .find(|j| crate::fs::same_path(&crate::fs::canon(&j.entry.record.path), &canon));
        let Some(Judged { entry, verdict }) = found else {
            return Err("no longer a worktree of this repository".into());
        };
        match verdict {
            Verdict::Remove => crate::worktree::remove::detach(repo, &entry.record, entry.merged)
                .map_err(|e| format!("failed, kept: {e:#}")),
            other => Err(format!("not removable now: {}", other.plan().1)),
        }
    }
}

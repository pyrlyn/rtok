// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T952.2: worktrees `rtok worktree finish` handed to review leave once their PR merges. The
//! creator fixed the order: the task closes first, then the worktree goes, then its claim, so
//! a failed step keeps everything after it for the next round. `rtok mcp` runs [`run`] on a
//! background thread, never a hook (the 10 ms budget).

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};

use super::{State, git, inventory, remove};
use crate::store::Store;

/// Closes a task in the project of a main checkout: the task id it closed, `None` when there
/// was nothing to close. Passed in, since `tasks` already depends on `worktree`.
pub type Close<'a> = &'a (dyn Fn(&Path, &str) -> Result<Option<String>> + Sync);

/// How often `rtok mcp` looks: a merge waits minutes for CI anyway.
pub const EVERY: Duration = Duration::from_secs(300);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Removed with its branch; `closed` is the task closed just before.
    Removed {
        closed: Option<String>,
        note: String,
    },
    /// The directory or its git record was already gone: only the claim was released.
    Released,
    /// Not merged yet, dirty, or this process's own directory: kept for the next round.
    Waiting(State),
}

/// One round over every finished claim: `(path, what happened)`.
pub fn sweep(store: &Store, close: Close) -> Vec<(String, Result<Outcome>)> {
    let claims = match store.finished_worktree_claims() {
        Ok(claims) => claims,
        Err(e) => return vec![("store".into(), Err(e.into()))],
    };
    claims
        .into_iter()
        .map(|(path, task)| {
            let out = reap(store, close, &path, &task);
            (path, out)
        })
        .collect()
}

fn reap(store: &Store, close: Close, path: &str, task: &str) -> Result<Outcome> {
    let dir = Path::new(path);
    if !dir.exists() {
        store.release_worktree_claim(path)?;
        return Ok(Outcome::Released);
    }
    let base = git::default_base(dir);
    // A failed fetch judges against the local base, which can only see the merge late.
    let _ = git::fetch(dir, &base);
    let entries = inventory(dir)?;
    let main = &entries
        .first()
        .context("git lists no worktree")?
        .record
        .path;
    let here = crate::fs::canon(dir);
    let Some(entry) = entries
        .iter()
        .find(|e| crate::fs::same_path(&crate::fs::canon(&e.record.path), &here))
    else {
        store.release_worktree_claim(path)?;
        return Ok(Outcome::Released);
    };
    // `git worktree remove` would pull the directory out from under this process.
    let in_use = std::env::current_dir().is_ok_and(|cwd| crate::fs::canon(&cwd).starts_with(&here));
    if !entry.done || in_use {
        return Ok(Outcome::Waiting(entry.state));
    }
    let closed = close(main, task)?;
    let note = remove::detach(main, &entry.record, true)?;
    store.release_worktree_claim(path)?;
    Ok(Outcome::Removed { closed, note })
}

/// `rtok mcp`'s loop: a round now, then every [`EVERY`] until `stop`. Fails open: a panic or an
/// error costs that round and is logged.
pub fn run(store: &Store, close: Close, stop: &AtomicBool) {
    while !stop.load(Ordering::Relaxed) {
        let round = Instant::now();
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            for (path, out) in sweep(store, close) {
                match out {
                    Ok(Outcome::Removed { closed, note }) => {
                        let task = closed.map(|t| format!("closed {t}; ")).unwrap_or_default();
                        crate::stdio::stderr_ln(&format!("rtok mcp: {path}: merged, {task}{note}"));
                    }
                    Err(e) => crate::stdio::stderr_ln(&format!(
                        "rtok mcp: {path}: kept after its merge, retrying: {e:#}"
                    )),
                    Ok(_) => {}
                }
            }
        }));
        while round.elapsed() < EVERY && !stop.load(Ordering::Relaxed) {
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

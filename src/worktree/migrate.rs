// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok worktree migrate` (T660.5): moves this repository's worktrees that live outside the
//! configured root into it, as `<root>/<repo>-<task>`, with `git worktree move`. Lock and claim
//! follow the worktree. [`plan`] decides from paths and records; [`run`] acts only with `yes`.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::Serialize;

use super::add::landing;
use super::{Record, git};
use crate::render::Col;
use crate::store::Store;

/// Who runs the command: a worktree locked or claimed by anyone else is not moved.
#[derive(Debug, Default)]
pub struct Me<'a> {
    pub agent: Option<&'a str>,
    /// `--owner`: matches an old lock that names no agent.
    pub owner: Option<&'a str>,
}

/// What else holds worktrees, read from the store.
#[derive(Debug, Default)]
pub struct Held {
    /// `(path, agent)` of every open claim.
    pub claims: Vec<(String, String)>,
    /// Worktrees a post-create script parked before any agent took them.
    pub pending: Vec<PathBuf>,
    /// `(agent, cwd)` of every live agent.
    pub live: Vec<(String, PathBuf)>,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Outcome {
    pub path: String,
    /// Where it goes; `None` when kept.
    pub to: Option<String>,
    /// `move` or `keep`.
    pub action: &'static str,
    /// Why it was kept, or git's message when the move failed.
    pub note: String,
    pub failed: bool,
}

/// One worktree and where it would go, or why it stays.
#[derive(Debug, PartialEq, Eq)]
pub struct Step {
    pub record: Record,
    pub to: Result<PathBuf, String>,
}

/// Every worktree of the repository `cwd` is in, with its target under `root` or the reason it
/// stays. The first record is main.
pub fn plan(cwd: &Path, root: &Path, me: &Me, held: &Held) -> Result<(PathBuf, Vec<Step>)> {
    let records = git::list(cwd)?;
    let main = records
        .first()
        .context("git lists no worktree")?
        .path
        .clone();
    let base = landing(&main, root);
    let here = crate::fs::canon(cwd);
    let current = super::deepest(&here, 0..records.len(), |i| records[*i].path.clone());
    let mut taken = std::collections::HashSet::new();
    let steps = records
        .into_iter()
        .enumerate()
        .map(|(i, record)| {
            let to = target(&record, i, current == Some(i), &main, &base, me, held)
                // Two worktrees of one task: the first takes the name, the second would fail.
                .and_then(|to| match taken.insert(to.clone()) {
                    true => Ok(to),
                    false => Err(format!("{} is another worktree's target", to.display())),
                });
            Step { record, to }
        })
        .collect();
    Ok((main, steps))
}

fn target(
    r: &Record,
    i: usize,
    current: bool,
    main: &Path,
    base: &Path,
    me: &Me,
    held: &Held,
) -> Result<PathBuf, String> {
    if i == 0 {
        return Err("main checkout".into());
    }
    let dir = crate::fs::canon(&r.path);
    if crate::fs::path_starts_with(&dir, &crate::fs::canon(base)) {
        return Err("already in the root".into());
    }
    if r.prunable.is_some() || !r.path.is_dir() {
        return Err("directory is gone".into());
    }
    if current {
        return Err("the worktree this command runs from".into());
    }
    if let Some(why) = held_by_another(r, &dir, me, held) {
        return Err(why);
    }
    if r.path.join(".gitmodules").exists() {
        return Err("has submodules, which `git worktree move` refuses".into());
    }
    let name = match r.owner() {
        Some(o) => {
            let repo = main.file_name().unwrap_or_default().to_string_lossy();
            format!("{repo}-{}", o.task)
        }
        // No task to name it by: it keeps its directory name.
        None => r
            .path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into(),
    };
    let to = base.join(name);
    if to.exists() {
        return Err(format!("{} already exists", to.display()));
    }
    Ok(to)
}

/// Why someone other than `me` holds the worktree at `dir` (canonical), if anyone does.
fn held_by_another(r: &Record, dir: &Path, me: &Me, held: &Held) -> Option<String> {
    let short = |a: &str| a.chars().take(8).collect::<String>();
    if r.locked.is_some() {
        let Some(owner) = r.owner() else {
            return Some("locked, owner unknown".into());
        };
        let mine = match &owner.agent {
            Some(agent) => me.agent == Some(agent.as_str()),
            None => me.owner == Some(owner.owner.as_str()),
        };
        if !mine {
            return Some(format!("locked by {}", owner.owner));
        }
    }
    let at = |p: &Path| crate::fs::same_path(&crate::fs::canon(p), dir);
    if let Some((_, agent)) = held
        .claims
        .iter()
        .find(|(p, a)| at(Path::new(p)) && me.agent != Some(a.as_str()))
    {
        return Some(format!("claimed by agent {}", short(agent)));
    }
    if held.pending.iter().any(|p| at(p)) {
        return Some("pending claim, no agent has taken it".into());
    }
    held.live
        .iter()
        .find(|(a, cwd)| {
            me.agent != Some(a.as_str()) && crate::fs::path_starts_with(&crate::fs::canon(cwd), dir)
        })
        .map(|(a, _)| format!("live session of agent {} works in it", short(a)))
}

/// [`plan`], then with `yes` the moves: unlock, `git worktree move`, lock again with the same
/// reason, and the store's rows follow. A failed move is re-locked where it was.
pub fn run(
    cwd: &Path,
    root: &Path,
    me: &Me,
    held: &Held,
    store: Option<&Store>,
    yes: bool,
) -> Result<Vec<Outcome>> {
    let (main, steps) = plan(cwd, root, me, held)?;
    let mut outcomes = Vec::with_capacity(steps.len());
    for Step { record, to } in steps {
        let path = record.path.display().to_string();
        let outcome = match to {
            Err(why) => Outcome {
                path,
                to: None,
                action: "keep",
                note: why,
                failed: false,
            },
            Ok(to) => {
                let done = yes.then(|| apply(&main, &record, &to, store));
                Outcome {
                    path,
                    to: Some(to.display().to_string()),
                    action: "move",
                    failed: matches!(done, Some(Err(_))),
                    note: match done {
                        Some(Ok(())) => "moved".into(),
                        Some(Err(e)) => format!("failed, kept: {e:#}"),
                        None => "outside the root".into(),
                    },
                }
            }
        };
        outcomes.push(outcome);
    }
    Ok(outcomes)
}

fn apply(main: &Path, record: &Record, to: &Path, store: Option<&Store>) -> Result<()> {
    if crate::fs::path_starts_with(&rtok_config::root::resolved(to), &crate::fs::canon(main)) {
        super::add::ensure_ignored(main)?;
    }
    if let Some(parent) = to.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("cannot create {}", parent.display()))?;
    }
    let from = &record.path;
    // Canonical before the move: afterwards `from` no longer exists to resolve.
    let old = crate::fs::canon(from).to_string_lossy().into_owned();
    if let Some(reason) = &record.locked {
        git::unlock(main, from)?;
        if let Err(e) = git::move_to(main, from, to) {
            // Put the lock back: an unlocked worktree is one `gc` may take.
            git::lock(main, from, reason)?;
            return Err(e);
        }
        git::lock(main, to, reason)?;
    } else {
        git::move_to(main, from, to)?;
    }
    if let Some(store) = store {
        let new = crate::fs::canon(to).to_string_lossy().into_owned();
        store.move_worktree_rows(&old, &new)?;
    }
    Ok(())
}

pub fn to_table(outcomes: &[Outcome], yes: bool) -> String {
    let dash = || "-".to_string();
    let mut lines = vec![["action", "path", "to"].map(String::from).to_vec()];
    lines.extend(outcomes.iter().map(|o| {
        let to = o.to.clone().unwrap_or_else(dash);
        vec![o.action.into(), o.path.clone(), to]
    }));
    let cols = [Col::left(0), Col::left(0), Col::left(0)];
    let notes = outcomes.iter().map(|o| o.note.as_str());
    let mut out = super::noted_table(&cols, &lines, notes);
    let planned = outcomes.iter().filter(|o| o.action == "move").count();
    if !yes && planned > 0 {
        out.push_str(&format!(
            "\ndry run: {planned} to move, nothing changed; rerun with --yes\n"
        ));
    }
    out
}

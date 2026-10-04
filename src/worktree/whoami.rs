// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok worktree whoami` (T411): what an agent needs before worktree work — who it is, where
//! new worktrees go, which one it stands in and which ones it already holds. No size scans,
//! unlike `list`, so it stays cheap enough to run at the start of every task.

use std::path::{Path, PathBuf};

use serde::Serialize;

use super::git;
use crate::store::{AgentDetail, Store};

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Whoami {
    /// Full rtok agent id; `None` when the caller is no known agent.
    pub agent: Option<String>,
    pub host: Option<String>,
    pub root: PathBuf,
    /// The linked worktree the cwd is in; `None` on the main checkout or outside a repository.
    pub here: Option<PathBuf>,
    pub worktrees: Vec<Mine>,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Mine {
    pub path: PathBuf,
    pub branch: Option<String>,
    pub task: Option<String>,
}

pub fn run(cwd: &Path, root: &Path, agent: Option<&AgentDetail>, store: Option<&Store>) -> Whoami {
    let real = |p: &Path| p.canonicalize().unwrap_or_else(|_| p.to_path_buf());
    // Outside a repository there is still an agent and a root to report.
    let records = git::list(cwd).unwrap_or_default();
    let linked = records.iter().skip(1);
    let here_dir = real(cwd);
    let here = linked
        .clone()
        .find(|r| here_dir.starts_with(real(&r.path)))
        .map(|r| r.path.clone());
    let claims = store
        .and_then(|s| s.open_worktree_claims().ok())
        .unwrap_or_default();
    let id = agent.map(|a| a.id.as_str());
    let held = |r: &&super::Record| {
        let Some(id) = id else { return false };
        let owner = r.owner();
        let by_lock = owner.as_ref().and_then(|o| o.agent.as_deref()) == Some(id);
        let dir = real(&r.path);
        let by_claim = r.locked.is_none()
            && claims
                .iter()
                .any(|(p, a)| a == id && real(Path::new(p)) == dir);
        by_lock || by_claim
    };
    let worktrees = linked
        .filter(held)
        .map(|r| Mine {
            path: r.path.clone(),
            branch: r.branch.clone(),
            task: r.owner().map(|o| o.task),
        })
        .collect();
    Whoami {
        agent: agent.map(|a| a.id.clone()),
        host: agent.map(|a| a.host.clone()),
        root: root.to_path_buf(),
        here,
        worktrees,
    }
}

pub fn to_text(w: &Whoami) -> String {
    let dash = |p: Option<&Path>| p.map_or("-".into(), |p| p.display().to_string());
    let agent = match (&w.agent, &w.host) {
        (Some(id), Some(host)) => format!("{} ({host})", &id[..id.len().min(8)]),
        _ => "-".into(),
    };
    let mut out = format!(
        "agent: {agent}\nroot: {}\nhere: {}\n",
        w.root.display(),
        dash(w.here.as_deref())
    );
    for m in &w.worktrees {
        let branch = m.branch.as_deref().unwrap_or("-");
        out.push_str(&format!("worktree: {} ({branch})\n", m.path.display()));
    }
    out
}

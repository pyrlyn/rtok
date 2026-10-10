// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T285: bind a worktree to the calling rtok agent (T282) — in the git lock reason (v2, the
//! source of truth) and in the store's `worktree_claims` (the fast join).

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};

use super::{Owner, git, origin};
use crate::store::{AgentDetail, Origin, Store};

/// The calling agent: `--agent <prefix>` (must resolve), else `RTOK_AGENT_ID` (T283; an id
/// the store does not know is ignored) or the host session's agent (T473), else none.
pub fn caller(store: Option<&Store>, flag: Option<&str>) -> Result<Option<AgentDetail>> {
    let env = crate::shell_agent::shell_agent(store, |k| std::env::var(k).ok());
    let (raw, explicit) = match (flag, env) {
        (Some(flag), _) => (flag.to_string(), true),
        (None, Some(env)) => (env, false),
        (None, None) => return Ok(None),
    };
    let found = store
        .context("no store")
        .and_then(|s| s.agent_detail(&s.resolve_agent(&raw)?).map_err(Into::into));
    match found {
        Ok(Some(detail)) => Ok(Some(detail)),
        Ok(None) if !explicit => Ok(None),
        Err(_) if !explicit => {
            crate::log::stderr_ln(&format!(
                "warning: RTOK_AGENT_ID {raw} is not in the store; no agent bound"
            ));
            Ok(None)
        }
        Ok(None) => bail!("--agent {raw}: unknown"),
        Err(e) => bail!("--agent {raw}: {e:#}"),
    }
}

/// `--owner`, else `<host> / <model>` of the agent (`<host>` alone while no call has named
/// a model), else an error: without an agent the owner is the only name a lock has.
pub fn owner(
    flag: Option<String>,
    agent: Option<&AgentDetail>,
    store: Option<&Store>,
) -> Result<String> {
    if let Some(owner) = flag {
        return Ok(owner);
    }
    let Some(agent) = agent else {
        bail!("--owner is required when no agent is known (--agent or RTOK_AGENT_ID)");
    };
    let model = store.and_then(|s| s.session_model(&agent.host_session_id).ok().flatten());
    Ok(match model {
        Some(model) => format!("{} / {model}", agent.host),
        None => agent.host.clone(),
    })
}

/// Record the claim in the store; a store error only warns — the lock already holds it.
pub fn remember(store: Option<&Store>, path: &Path, agent: &str, task: &str) {
    let path = crate::fs::canon(path);
    let saved = store.map(|s| s.claim_worktree(&path.to_string_lossy(), agent, task));
    if let Some(Err(e)) = saved {
        crate::log::stderr_ln(&format!("warning: claim not stored: {e:#}"));
    }
}

/// Seed the new worktree's symbol rows from the main checkout so the next index
/// skips identical blobs. A copy error is a warning: the worktree already exists.
fn copy_symbol_index(store: Option<&Store>, cwd: &Path, dest: &Path) {
    let Some(store) = store else {
        return;
    };
    let listed = match super::git::list(cwd) {
        Ok(listed) => listed,
        Err(e) => {
            crate::log::stderr_ln(&format!("warning: symbol index not copied: {e:#}"));
            return;
        }
    };
    let Some(main) = listed.first() else {
        return;
    };
    let from = crate::store::canon_root(&main.path);
    let to = crate::store::canon_root(dest);
    if from == to {
        return;
    }
    if let Err(e) = store.copy_symbol_rows(&from, &to) {
        crate::log::stderr_ln(&format!("warning: symbol index not copied: {e:#}"));
    }
}

/// T329.6: with `[plugins.graph] auto_add_projects`, the worktree becomes a project named by its
/// branch. Best effort like [`remember`]: the lock and the claim already hold the worktree.
fn register_project(store: Option<&Store>, auto_add: bool, path: &Path, branch: Option<&str>) {
    let Some(store) = store.filter(|_| auto_add) else {
        return;
    };
    if let Err(e) = store.auto_add_project(path, Origin::Worktree, branch) {
        crate::log::stderr_ln(&format!("warning: project not registered: {e:#}"));
    }
}

/// `rtok worktree add`, MCP `worktree_add` and the `WorktreeCreate` hook (T159): create the
/// worktree for `agent` (or for `owner` alone when no agent is known) and record the claim.
/// One path, so every surface binds the lock and the store row the same way.
pub fn add(
    store: Option<&Store>,
    cwd: &Path,
    root: &Path,
    id: (&str, Option<&str>),
    agent: Option<&AgentDetail>,
    owner_flag: Option<String>,
    auto_add: bool,
) -> Result<super::add::Plan> {
    let owner = owner(owner_flag, agent, store)?;
    let agent_id = agent.map(|a| a.id.as_str());
    let plan = super::add::run(cwd, root, id, (&owner, agent_id))?;
    copy_symbol_index(store, cwd, &plan.path);
    if let Some(agent) = agent_id {
        remember(store, &plan.path, agent, &plan.task);
    }
    register_project(store, auto_add, &plan.path, Some(&plan.branch));
    Ok(plan)
}

/// What [`run`] bound: the worktree, its task and origin, and whether a git lock was written.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct Adopted {
    pub path: PathBuf,
    pub task: String,
    pub origin: &'static str,
    pub locked: bool,
    /// The branch the worktree has checked out; the project's name when auto-added.
    #[serde(skip)]
    pub branch: Option<String>,
}

/// The main checkout and the linked worktree that holds `path`. The deepest one wins: an
/// agent's cwd may be a subdirectory of it.
fn linked(path: &Path) -> Result<(PathBuf, super::Record)> {
    let real = |p: &Path| crate::fs::canon(p);
    let target = real(path);
    let worktrees = git::list(&target)?;
    let main = worktrees
        .first()
        .context("git lists no worktree")?
        .path
        .clone();
    let Some(record) = worktrees
        .into_iter()
        .skip(1)
        .filter(|r| crate::fs::path_starts_with(&target, &real(&r.path)))
        .max_by_key(|r| real(&r.path).components().count())
    else {
        bail!("{} is not a linked worktree", path.display());
    };
    Ok((main, record))
}

/// `task`, else the old lock's, else the branch's first `-` segment.
fn task_of(path: &Path, task: Option<&str>, record: &super::Record) -> Result<String> {
    match (task, record.owner(), record.branch.as_deref()) {
        (Some(task), ..) => Ok(task.to_string()),
        (None, Some(o), _) => Ok(o.task),
        (None, None, Some(branch)) => Ok(branch.split('-').next().unwrap_or(branch).to_string()),
        (None, None, None) => bail!(
            "{}: no lock and no branch to name its task; name it with --task (MCP: `task`)",
            path.display()
        ),
    }
}

/// `rtok worktree claim` / `adopt` and MCP `worktree_adopt`: bind the linked worktree that
/// holds `path` to `agent` when it has no lock or the lock is already theirs. The task is
/// `task`, else the old lock's, else the branch's first `-` segment. With `spare_evicting` a
/// worktree in a pool its host evicts (T289, [`origin::evicts`]) gets no git lock: the store
/// claim alone binds it.
pub fn run(
    path: &Path,
    (owner, agent): (&str, &str),
    task: Option<&str>,
    spare_evicting: bool,
) -> Result<Adopted> {
    let (main, record) = linked(path)?;
    if !record.claimable_by(owner, agent) {
        let held = record
            .owner()
            .map_or("an unknown owner".into(), |o| o.reason());
        bail!("{} is locked by {held}; not taken", path.display());
    }
    let task = task_of(path, task, &record)?;
    let reason = Owner {
        owner: owner.into(),
        task: task.clone(),
        date: super::add::today()?,
        agent: Some(agent.into()),
    }
    .reason();
    anyhow::ensure!(
        Owner::parse(&reason).is_some_and(|o| o.owner == owner) && owner.is_ascii(),
        "owner `{owner}` must be non-empty ASCII without ` | `"
    );
    let origin = origin::of(&record.path);
    let locked = !(spare_evicting && origin::evicts(origin));
    if locked {
        if let Some(old) = &record.locked {
            git::unlock(&main, &record.path)?;
            if let Err(e) = git::lock(&main, &record.path, &reason) {
                git::lock(&main, &record.path, old)?;
                return Err(e);
            }
        } else {
            git::lock(&main, &record.path, &reason)?;
        }
    }
    Ok(Adopted {
        path: record.path.clone(),
        task,
        origin,
        locked,
        branch: record.branch.clone(),
    })
}

/// [`run`] for a resolved agent, then the store claim: the one path of the CLI and MCP.
pub fn bind(
    store: Option<&Store>,
    path: &Path,
    agent: &AgentDetail,
    owner_flag: Option<String>,
    task: Option<&str>,
    spare_evicting: bool,
    auto_add: bool,
) -> Result<Adopted> {
    let owner = owner(owner_flag, Some(agent), store)?;
    let done = run(path, (&owner, &agent.id), task, spare_evicting)?;
    remember(store, &done.path, &agent.id, &done.task);
    register_project(store, auto_add, &done.path, done.branch.as_deref());
    Ok(done)
}

/// `adopt` with no agent to name, as from a host's post-create script (T289.3): the one live
/// agent of the pool's host working in the repository takes the worktree. Several or none are
/// an error rather than a guess, which would hand the worktree to another agent.
pub fn bind_unattended(
    store: &Store,
    (path, task): (&Path, Option<&str>),
    idle: &str,
    auto_add: bool,
) -> Result<Adopted> {
    let (main, record) = linked(path)?;
    let hosts = origin::hosts(origin::of(&record.path));
    // Only a pool whose host runs a post-create script has a caller without a session; for any
    // other worktree a missing agent is still the user's mistake.
    if hosts.is_empty() {
        bail!("no agent to bind: pass --agent or set RTOK_AGENT_ID");
    }
    let repo = main.to_string_lossy();
    let host_ids: Vec<i32> = hosts
        .iter()
        .filter_map(|h| store.host_id(h).ok().flatten())
        .collect();
    // The cwd rule of `agents::link::resolve` (T283.1); only top-level sessions, since a
    // sub-agent shares its parent's cwd and would make every repository ambiguous.
    let mut here: Vec<_> = store
        .live_agents(idle)?
        .into_iter()
        .filter(|a| host_ids.contains(&a.host_id) && a.parent_key.is_empty())
        .filter(|a| {
            a.cwd
                .as_deref()
                .is_some_and(|c| crate::fs::same_dir(c, &repo))
        })
        .collect();
    match here.len() {
        1 => {
            let agent = store
                .agent_detail(&here.remove(0).id)?
                .context("the live agent vanished")?;
            bind(Some(store), path, &agent, None, task, true, auto_add)
        }
        0 => bail!(
            "no live {} agent works in {repo}; pass --agent or set RTOK_AGENT_ID",
            hosts.join("/")
        ),
        n => bail!(
            "{n} live {} agents work in {repo}; pass --agent to name one",
            hosts.join("/")
        ),
    }
}

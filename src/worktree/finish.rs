// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok worktree finish` (T952.1): hand a task's worktree to review in one step — commit
//! what is left, push, open (or reuse) the PR, ask for auto-merge, then unlock. It never
//! closes the task: that waits for the merge (T952.2).

use std::path::Path;

use anyhow::{Context, Result, bail};
use rtok_sys::proc::ProgressRunner;
use serde::Serialize;

use super::{Record, claim, git};
use crate::store::{AgentDetail, Store};

/// What `finish` asks of `gh` beyond the defaults.
#[derive(Debug, Default)]
pub struct Options {
    /// The commit message for uncommitted work; else `<task>: <branch slug>`.
    pub message: Option<String>,
    /// `owner/name` when the `origin` URL does not name the repository the PR belongs to.
    pub repo: Option<String>,
    pub no_auto_merge: bool,
}

#[derive(Debug, Serialize)]
pub struct Finished {
    pub path: String,
    pub branch: String,
    /// The commit `finish` made, when the tree was dirty.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub committed: Option<String>,
    pub pr: String,
    pub auto_merge: bool,
    pub unlocked: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
}

impl Finished {
    pub fn to_text(&self) -> String {
        let mut out = format!("{}: {}\n", self.path, self.pr);
        if let Some(message) = &self.committed {
            out.push_str(&format!("committed: {message}\n"));
        }
        out.push_str(&format!(
            "auto-merge: {}\n",
            if self.auto_merge { "on" } else { "off" }
        ));
        if self.unlocked {
            out.push_str("unlocked\n");
        }
        for warning in &self.warnings {
            out.push_str(&format!("warning: {warning}\n"));
        }
        out
    }
}

/// The default commit message: `<task>: <slug>` from a `<task>-<slug>` branch, the branch
/// alone when no task names it.
fn message_for(task: Option<&str>, branch: &str) -> String {
    let Some(task) = task else {
        return branch.to_string();
    };
    let prefix = format!("{}-", task.to_ascii_lowercase());
    let slug = branch
        .to_ascii_lowercase()
        .strip_prefix(&prefix)
        .map(|s| s.replace('-', " "))
        .filter(|s| !s.is_empty());
    match slug {
        Some(slug) => format!("{task}: {slug}"),
        None => format!("{task}: {branch}"),
    }
}

fn gh(dir: &Path, label: &str, args: &[&str]) -> Result<String> {
    let out = ProgressRunner::new(label, "gh", args)
        .cwd(dir)
        .run()
        .context("cannot run gh; install GitHub CLI and run `gh auth login`")?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        bail!("gh {}: {}", args.join(" "), err.trim());
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_owned())
}

fn with_repo<'a>(args: &[&'a str], repo: Option<&'a str>) -> Vec<&'a str> {
    let mut args = args.to_vec();
    if let Some(repo) = repo {
        args.extend(["--repo", repo]);
    }
    args
}

/// The open PR for `branch`, else a new one filled from its commits.
fn open_pr(dir: &Path, branch: &str, base: &str, repo: Option<&str>) -> Result<String> {
    let view = ["pr", "view", branch, "--json", "url,state"];
    let open = gh(dir, "looking for the PR…", &with_repo(&view, repo))
        .ok()
        .and_then(|json| serde_json::from_str::<serde_json::Value>(&json).ok())
        .filter(|pr| pr["state"] == "OPEN")
        .and_then(|pr| pr["url"].as_str().map(str::to_owned));
    if let Some(url) = open {
        return Ok(url);
    }
    let create = ["pr", "create", "--head", branch, "--base", base, "--fill-first"];
    let out = gh(dir, "opening the PR…", &with_repo(&create, repo))?;
    // `gh pr create` prints progress lines before the URL.
    Ok(out.lines().last().unwrap_or_default().to_owned())
}

/// Finish the worktree holding `path` for `agent` (named by `owner_flag` when given). Every
/// refusal comes before the first change; the lock goes last, so a failed push or PR leaves
/// the worktree as protected as it was.
pub fn for_agent(
    store: Option<&Store>,
    path: &Path,
    (agent, owner_flag): (Option<&AgentDetail>, Option<String>),
    opts: &Options,
) -> Result<Finished> {
    let here = crate::fs::canon(path);
    let records = git::list(&here)?;
    let main = records
        .first()
        .context("git lists no worktree")?
        .path
        .clone();
    let record: &Record = super::deepest(&here, &records, |r| r.path.clone())
        .context("not inside a worktree of this repository")?;
    let shown = crate::fs::canon(&record.path).display().to_string();
    if crate::fs::same_path(&crate::fs::canon(&record.path), &crate::fs::canon(&main))
        || record.bare
    {
        bail!("{shown} is the main checkout; run finish inside a task worktree");
    }
    let Some(branch) = record.branch.clone() else {
        bail!("{shown}: detached HEAD; check out the task branch first");
    };
    let owner = match (owner_flag, agent) {
        (None, None) => None,
        (flag, agent) => Some(claim::owner(flag, agent, store)?),
    };
    let me = (
        owner.as_deref().unwrap_or(""),
        agent.map_or("", |a| a.id.as_str()),
    );
    if let Some(held) = claim::held_against(record, me, store).filter(|h| h.holds) {
        bail!("{shown} is {}; not finished", held.locked());
    }

    let base = git::default_base(&main);
    let mut warnings = Vec::new();
    if let Err(e) = git::fetch(&main, &base) {
        warnings.push(format!(
            "{e:#}; own commits are judged against the local {base}"
        ));
    }
    let pending = git::pending_paths(&record.path)?;
    let secrets: Vec<_> = pending
        .iter()
        .filter(|p| crate::fs::is_secret(Path::new(p)))
        .collect();
    if !secrets.is_empty() {
        let list = secrets
            .iter()
            .map(|s| s.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        bail!(
            "{shown}: refusing to commit credential files: {list}; ignore or remove them, or commit by hand"
        );
    }
    if pending.is_empty() && !git::has_own_commits(&main, &base, &branch)? {
        bail!("{shown}: nothing to finish; {branch} has no commits of its own");
    }

    let task = record.owner().map(|o| o.task);
    let committed = match pending.is_empty() {
        true => None,
        false => {
            let message = opts
                .message
                .clone()
                .unwrap_or_else(|| message_for(task.as_deref(), &branch));
            git::commit_all(&record.path, &message)?;
            Some(message)
        }
    };
    git::push(&record.path, &branch)?;
    let repo = opts.repo.as_deref();
    let base_branch = base.strip_prefix("origin/").unwrap_or(&base);
    let pr = open_pr(&record.path, &branch, base_branch, repo)?;
    let auto_merge = !opts.no_auto_merge && {
        let merge = ["pr", "merge", pr.as_str(), "--auto", "--squash"];
        match gh(
            &record.path,
            "asking for auto-merge…",
            &with_repo(&merge, repo),
        ) {
            Ok(_) => true,
            Err(e) => {
                warnings.push(format!("auto-merge not set: {e:#}"));
                false
            }
        }
    };
    let unlocked = record.locked.is_some();
    if unlocked {
        git::unlock(&main, &record.path)?;
    }
    Ok(Finished {
        path: shown,
        branch,
        committed,
        pr,
        auto_merge,
        unlocked,
        warnings,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rstest::rstest;

    #[rstest]
    #[case(Some("T952.1"), "t952.1-worktree-finish", "T952.1: worktree finish")]
    #[case(Some("T7"), "t7", "T7: t7")]
    #[case(Some("T7"), "other-name", "T7: other-name")]
    #[case(None, "fix-typo", "fix-typo")]
    fn the_default_message_names_the_task_and_the_slug(
        #[case] task: Option<&str>,
        #[case] branch: &str,
        #[case] want: &str,
    ) {
        assert_eq!(message_for(task, branch), want);
    }
}

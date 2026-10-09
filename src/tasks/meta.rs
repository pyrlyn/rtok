// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Claim, dependency and priority labels the GitHub and GitLab adapters share (T442).
//! `rtok`, `rtok:<id>` and the status labels stay as they are; these three are the only
//! ones `save` rewrites. A user's own labels are kept.

use super::{DEFAULT_PRIORITY, PRIORITY_MAX, Task, TaskId};

pub const OWNER_PREFIX: &str = "rtok:owner:";
pub const NEEDS_PREFIX: &str = "rtok:needs:";
pub const PRIORITY_PREFIX: &str = "rtok:p:";

/// A label `save` owns: the assignee, a blocker, or a non-default priority.
pub fn is_managed(label: &str) -> bool {
    label.starts_with(OWNER_PREFIX)
        || label.starts_with(NEEDS_PREFIX)
        || label.starts_with(PRIORITY_PREFIX)
}

/// Priority, assignee and blockers carried by `labels`. Unknown or out-of-range values are
/// dropped, so a hand-edited label cannot push a task outside `0..=4`.
pub fn read_meta<S: AsRef<str>>(labels: &[S]) -> (u8, Option<String>, Vec<TaskId>) {
    let mut priority = DEFAULT_PRIORITY;
    let mut assignee = None;
    let mut blocked_by = Vec::new();
    for label in labels {
        let label = label.as_ref();
        if let Some(rest) = label.strip_prefix(OWNER_PREFIX) {
            if assignee.is_none() && !rest.is_empty() {
                assignee = Some(rest.to_string());
            }
        } else if let Some(rest) = label.strip_prefix(NEEDS_PREFIX) {
            if let Ok(id) = rest.parse() {
                blocked_by.push(id);
            }
        } else if let Some(rest) = label.strip_prefix(PRIORITY_PREFIX)
            && let Ok(n) = rest.parse::<u8>()
            && n <= PRIORITY_MAX
        {
            priority = n;
        }
    }
    blocked_by.sort();
    blocked_by.dedup();
    (priority, assignee, blocked_by)
}

/// The managed labels `task` should carry. Priority `2` adds none.
pub fn managed_for(task: &Task) -> Vec<String> {
    let mut out = Vec::new();
    if let Some(agent) = task.assignee.as_deref().filter(|s| !s.is_empty()) {
        out.push(format!("{OWNER_PREFIX}{agent}"));
    }
    for id in &task.blocked_by {
        out.push(format!("{NEEDS_PREFIX}{id}"));
    }
    if task.priority != DEFAULT_PRIORITY {
        out.push(format!("{PRIORITY_PREFIX}{}", task.priority));
    }
    out
}

/// `existing` with its managed labels replaced by [`managed_for`]. Order of the other labels
/// is kept, so a user's `bug` stays where they put it.
pub fn merge_labels(existing: &[String], task: &Task) -> Vec<String> {
    let mut out: Vec<String> = existing
        .iter()
        .filter(|l| !is_managed(l))
        .cloned()
        .collect();
    out.extend(managed_for(task));
    out
}

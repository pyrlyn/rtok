// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T442: the claim a hook can name without reading the task adapter. The file or the issue
//! is the source of truth; this table is written after a successful save and removed when
//! the task is released or finished.

use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::Result;
use diesel::prelude::*;

use super::Store;
use super::schema::task_claims;

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

/// One `task_claims` row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskClaim {
    pub project: String,
    pub task_id: String,
    pub agent_id: String,
    pub title: String,
    pub since: i64,
}

impl Store {
    /// Record that `agent` holds `task_id` in `project`. A repeat replaces the title and the
    /// time, so a re-claim still has a row when the first write to the store failed.
    pub fn upsert_task_claim(
        &self,
        project: &str,
        task_id: &str,
        agent: &str,
        title: &str,
    ) -> Result<()> {
        let since = unix_now();
        let mut conn = self.lock()?;
        diesel::insert_into(task_claims::table)
            .values((
                task_claims::project.eq(project),
                task_claims::task_id.eq(task_id),
                task_claims::agent_id.eq(agent),
                task_claims::title.eq(title),
                task_claims::since.eq(since),
            ))
            .on_conflict((task_claims::project, task_claims::task_id))
            .do_update()
            .set((
                task_claims::agent_id.eq(agent),
                task_claims::title.eq(title),
                task_claims::since.eq(since),
            ))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// Forget the claim on `task_id`. Missing is fine: a release of an unclaimed task and a
    /// second finish both land here.
    pub fn clear_task_claim(&self, project: &str, task_id: &str) -> Result<()> {
        let mut conn = self.lock()?;
        diesel::delete(
            task_claims::table
                .filter(task_claims::project.eq(project))
                .filter(task_claims::task_id.eq(task_id)),
        )
        .execute(&mut *conn)?;
        Ok(())
    }

    /// The claim `agent` made most recently in `project`, for the SessionStart line.
    pub fn latest_task_claim(&self, project: &str, agent: &str) -> Result<Option<TaskClaim>> {
        let mut conn = self.lock()?;
        Ok(task_claims::table
            .filter(task_claims::project.eq(project))
            .filter(task_claims::agent_id.eq(agent))
            .order((task_claims::since.desc(), task_claims::task_id.asc()))
            .select((
                task_claims::project,
                task_claims::task_id,
                task_claims::agent_id,
                task_claims::title,
                task_claims::since,
            ))
            .first::<(String, String, String, String, i64)>(&mut *conn)
            .optional()?
            .map(|(project, task_id, agent_id, title, since)| TaskClaim {
                project,
                task_id,
                agent_id,
                title,
                since,
            }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_newest_claim_is_the_one_a_hook_reads() {
        let store = Store::open_in_memory().unwrap();
        store
            .upsert_task_claim("p", "R1", "agent", "First")
            .unwrap();
        store
            .upsert_task_claim("p", "R2", "agent", "Second\nline")
            .unwrap();
        store
            .upsert_task_claim("p", "R1", "agent", "First again")
            .unwrap();
        let latest = store.latest_task_claim("p", "agent").unwrap().unwrap();
        assert_eq!(latest.task_id, "R1");
        assert_eq!(latest.title, "First again");
        assert!(store.latest_task_claim("p", "other").unwrap().is_none());
        store.clear_task_claim("p", "R1").unwrap();
        let left = store.latest_task_claim("p", "agent").unwrap().unwrap();
        assert_eq!(left.task_id, "R2");
        store.clear_task_claim("p", "R2").unwrap();
        assert!(store.latest_task_claim("p", "agent").unwrap().is_none());
    }
}

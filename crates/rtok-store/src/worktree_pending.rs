// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T289.5: `worktree_pending` — worktrees a host's post-create script adopted while no single
//! agent could be named. The first agent to adopt one, or to start a session inside it, takes it.

use crate::Result;
use diesel::prelude::*;

use super::Store;
use super::schema::worktree_pending;

impl Store {
    /// Park `path` until an agent takes it; a second post-create run only renames the task.
    pub fn add_pending_worktree(&self, path: &str, task: &str) -> Result<()> {
        let mut conn = self.lock()?;
        diesel::insert_into(worktree_pending::table)
            .values((
                worktree_pending::path.eq(path),
                worktree_pending::task.eq(task),
            ))
            .on_conflict(worktree_pending::path)
            .do_update()
            .set(worktree_pending::task.eq(task))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// `(path, task)` of every parked worktree. The table stays a handful of rows long, so the
    /// hook can read all of it instead of matching in SQL.
    pub fn pending_worktrees(&self) -> Result<Vec<(String, String)>> {
        let mut conn = self.lock()?;
        Ok(worktree_pending::table
            .select((worktree_pending::path, worktree_pending::task))
            .load(&mut *conn)?)
    }

    /// Drop the parked row for `path`; `true` when this call removed it, so of two sessions
    /// racing for one worktree only one wins. SQL equality misses a `\\?\` prefix and Windows
    /// case, so the match is [`crate::same_path`].
    pub fn take_pending_worktree(&self, path: &str) -> Result<bool> {
        let mut conn = self.lock()?;
        let want = std::path::Path::new(path);
        let all: Vec<String> = worktree_pending::table
            .select(worktree_pending::path)
            .load(&mut *conn)?;
        let hit: Vec<String> = all
            .into_iter()
            .filter(|stored| crate::same_path(std::path::Path::new(stored), want))
            .collect();
        if hit.is_empty() {
            return Ok(false);
        }
        let gone =
            diesel::delete(worktree_pending::table.filter(worktree_pending::path.eq_any(hit)))
                .execute(&mut *conn)?;
        Ok(gone > 0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_parked_worktree_is_taken_once() {
        let store = Store::open_in_memory().unwrap();
        store.add_pending_worktree("/w/x", "t1").unwrap();
        store.add_pending_worktree("/w/x", "t2").unwrap();
        store.add_pending_worktree("/w/y", "t3").unwrap();
        let mut rows = store.pending_worktrees().unwrap();
        rows.sort();
        assert_eq!(
            rows,
            [("/w/x".into(), "t2".into()), ("/w/y".into(), "t3".into())]
        );
        assert!(store.take_pending_worktree("/w/x").unwrap());
        assert!(!store.take_pending_worktree("/w/x").unwrap());
        assert_eq!(store.pending_worktrees().unwrap().len(), 1);
    }

    #[test]
    fn releasing_a_worktree_drops_its_parked_row() {
        let store = Store::open_in_memory().unwrap();
        store.add_pending_worktree("/w/x", "t1").unwrap();
        store.release_worktree_claim("/w/x").unwrap();
        assert!(store.pending_worktrees().unwrap().is_empty());
    }
}

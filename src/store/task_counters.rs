// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T441.3: the task id allocator. One counter per project (and one per parent for subtasks),
//! shared by every process that opens this store, so parallel agents never get the same id.

use anyhow::{Context, Result, bail};
use diesel::prelude::*;
use diesel::sqlite::SqliteConnection;

use super::schema::task_counters;
use super::{Store, is_locked};
use crate::task_id::{TaskId, check_prefix};

/// `''` for the top-level counter, else the parent's number path (`2` for `R2.1`).
fn counter_key(parent: Option<&TaskId>) -> String {
    parent.map_or_else(String::new, |p| {
        p.path()
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join(".")
    })
}

fn read_last(conn: &mut SqliteConnection, project: &str, key: &str) -> Result<i64> {
    Ok(task_counters::table
        .find((project, key))
        .select(task_counters::last)
        .first::<i64>(conn)
        .optional()?
        .unwrap_or(0))
}

fn write_last(conn: &mut SqliteConnection, project: &str, key: &str, last: i64) -> Result<()> {
    diesel::insert_into(task_counters::table)
        .values((
            task_counters::project.eq(project),
            task_counters::parent.eq(key),
            task_counters::last.eq(last),
        ))
        .on_conflict((task_counters::project, task_counters::parent))
        .do_update()
        .set(task_counters::last.eq(last))
        .execute(conn)?;
    Ok(())
}

/// A clear error instead of SQLite's bare "database is locked" when another process held the
/// write lock past `busy_timeout`.
fn locked_hint(e: anyhow::Error) -> anyhow::Error {
    if is_locked(&e) {
        e.context("task id allocation: another rtok process held the store's write lock past its busy timeout; retry")
    } else {
        e
    }
}

impl Store {
    /// The next task id of `project` (a [`crate::project::project_key`]): `R13` after `R12`,
    /// or `R2.4` after `R2.3` with `parent` `R2`. Read, increment and write are one
    /// `BEGIN EXCLUSIVE`, so concurrent processes each get their own number and none is reused.
    pub fn allocate_task_id(
        &self,
        project: &str,
        prefix: &str,
        parent: Option<&TaskId>,
    ) -> Result<TaskId> {
        // Refused before the transaction, so a bad prefix or depth never burns a number.
        check_prefix(prefix)?;
        if let Some(p) = parent {
            p.child(1)?;
        }
        let key = counter_key(parent);
        let mut conn = self.lock()?;
        let n = conn
            .exclusive_transaction::<_, anyhow::Error, _>(|c| {
                if let Some(p) = parent {
                    // Ids are only handed out here or seeded above remote ones, so a parent
                    // past the top counter was never a task of this project.
                    let top = read_last(c, project, "")?;
                    if i64::from(p.path()[0]) > top {
                        bail!("parent {p} was never allocated in this project (last is {top})");
                    }
                }
                let next = read_last(c, project, &key)? + 1;
                write_last(c, project, &key, next)?;
                Ok(next)
            })
            .map_err(locked_hint)?;
        let n = u32::try_from(n).context("task counter overflow")?;
        match parent {
            Some(p) => p.child(n),
            None => TaskId::new(prefix, n),
        }
    }

    /// A counter as it stands, without touching it; 0 when nothing was ever allocated.
    pub fn task_counter(&self, project: &str, parent: Option<&TaskId>) -> Result<u32> {
        let mut conn = self.lock()?;
        let last = read_last(&mut conn, project, &counter_key(parent))?;
        u32::try_from(last).context("task counter overflow")
    }

    /// Raise a counter to at least `at_least`, never lower it: seeding from a tracker that
    /// already has ids, or skipping past an id another machine took. Returns the counter.
    pub fn seed_task_counter(
        &self,
        project: &str,
        parent: Option<&TaskId>,
        at_least: u32,
    ) -> Result<u32> {
        let key = counter_key(parent);
        let mut conn = self.lock()?;
        let last = conn
            .exclusive_transaction::<_, anyhow::Error, _>(|c| {
                let last = read_last(c, project, &key)?.max(i64::from(at_least));
                write_last(c, project, &key, last)?;
                Ok(last)
            })
            .map_err(locked_hint)?;
        u32::try_from(last).context("task counter overflow")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(s: &str) -> TaskId {
        s.parse().unwrap()
    }

    #[test]
    fn ids_count_up_per_project_and_per_parent() {
        let store = Store::open_in_memory().unwrap();
        let next = |project, parent: Option<&TaskId>| {
            store
                .allocate_task_id(project, "R", parent)
                .unwrap()
                .to_string()
        };
        assert_eq!(next("a", None), "R1");
        assert_eq!(next("a", None), "R2");
        // Same prefix, other project: its own counter.
        assert_eq!(next("b", None), "R1");
        assert_eq!(next("a", Some(&id("R2"))), "R2.1");
        assert_eq!(next("a", Some(&id("R2"))), "R2.2");
        assert_eq!(next("a", Some(&id("R1"))), "R1.1");
        assert_eq!(next("a", None), "R3");
    }

    #[test]
    fn a_new_prefix_keeps_the_numbering() {
        let store = Store::open_in_memory().unwrap();
        store.allocate_task_id("a", "R", None).unwrap();
        let id = store.allocate_task_id("a", "at", None).unwrap();
        assert_eq!(id.to_string(), "AT2");
    }

    #[test]
    fn refusals_burn_no_number() {
        let store = Store::open_in_memory().unwrap();
        assert!(store.allocate_task_id("a", "R2", None).is_err());
        assert!(
            store.allocate_task_id("a", "R", Some(&id("R1.1"))).is_err(),
            "depth"
        );
        let err = store
            .allocate_task_id("a", "R", Some(&id("R1")))
            .unwrap_err();
        assert!(format!("{err:#}").contains("never allocated"), "{err:#}");
        assert_eq!(
            store.allocate_task_id("a", "R", None).unwrap().to_string(),
            "R1"
        );
    }

    #[test]
    fn seeding_only_raises_the_counter() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(store.seed_task_counter("a", None, 40).unwrap(), 40);
        assert_eq!(store.seed_task_counter("a", None, 7).unwrap(), 40);
        assert_eq!(
            store.allocate_task_id("a", "R", None).unwrap().to_string(),
            "R41"
        );
        store.seed_task_counter("a", Some(&id("R41")), 3).unwrap();
        let sub = store.allocate_task_id("a", "R", Some(&id("R41"))).unwrap();
        assert_eq!(sub.to_string(), "R41.4");
    }
}

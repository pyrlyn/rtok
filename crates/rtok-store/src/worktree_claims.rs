// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T285: `worktree_claims` — the agent (T282) each worktree is bound to. The git lock reason
//! is the source of truth; these rows are the fast join `rtok worktree list` falls back on.

use std::time::Duration;

use crate::Result;
use diesel::prelude::*;

use super::Store;
use super::schema::{agents, calls, models, worktree_claims};
use super::unixepoch;

/// T527: the holder of a worktree claim (T285's `agent_id`, the same id a v2 lock reason's
/// `agent` field names) and the two `agents` facts (T282) that say whether the claim still
/// holds: `ended_at` and `last_seen`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Holder {
    pub agent: String,
    /// `agents.last_seen`, unix seconds.
    pub last_seen: i64,
    /// `agents.ended_at` — `Some` once the agent's session is over.
    pub ended_at: Option<i64>,
}

impl Holder {
    /// T527: the claim holds only while its holder is around: not ended, and seen within
    /// `stale_lock` seconds of `now`. `stale_lock` is gc's `--stale-lock` window (T418) —
    /// one policy, not a second one — so `remove`/`adopt` stop refusing over an agent idle
    /// for days.
    pub fn holds(&self, now: i64, stale_lock: Duration) -> bool {
        self.ended_at.is_none() && self.seen_ago(now) <= stale_lock.as_secs() as i64
    }

    /// T527: seconds since the store last saw the holder — what a refusal reports.
    pub fn seen_ago(&self, now: i64) -> i64 {
        now.saturating_sub(self.last_seen)
    }
}

impl Store {
    /// T527: the holder behind the worktree claims `agent` took (T285) — the `agents` row
    /// (T282) their `agent_id` and a v2 lock reason's `agent` field both name. `None` when
    /// the store has no such agent: a lost store keeps the lock (`worktree list` shows such
    /// a holder as `unknown`).
    pub fn worktree_claim_holder(&self, agent: &str) -> Result<Option<Holder>> {
        let mut conn = self.lock()?;
        let row = agents::table
            .filter(agents::id.eq(agent))
            .select((agents::id, agents::last_seen, agents::ended_at))
            .first::<(String, i64, Option<i64>)>(&mut *conn)
            .optional()?;
        Ok(row.map(|(agent, last_seen, ended_at)| Holder {
            agent,
            last_seen,
            ended_at,
        }))
    }

    /// Bind `path` to `agent_id`, replacing any earlier claim on the same path.
    pub fn claim_worktree(&self, path: &str, agent_id: &str, task: &str) -> Result<()> {
        let mut conn = self.lock()?;
        let row = (
            worktree_claims::path.eq(path),
            worktree_claims::agent_id.eq(agent_id),
            worktree_claims::task.eq(task),
        );
        diesel::insert_into(worktree_claims::table)
            .values(row)
            .on_conflict(worktree_claims::path)
            .do_update()
            .set((
                worktree_claims::agent_id.eq(agent_id),
                worktree_claims::task.eq(task),
                worktree_claims::claimed_at.eq(unixepoch().assume_not_null()),
                worktree_claims::released_at.eq(None::<i64>),
            ))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// `rtok worktree remove` (T286): the open claim on `path`, if any, is released.
    /// SQL equality misses a `\\?\` prefix and Windows case, so the match is [`crate::same_path`].
    pub fn release_worktree_claim(&self, path: &str) -> Result<()> {
        // A worktree that goes has nobody left to take it (T289.5).
        self.take_pending_worktree(path)?;
        let mut conn = self.lock()?;
        let open: Vec<String> = worktree_claims::table
            .filter(worktree_claims::released_at.is_null())
            .select(worktree_claims::path)
            .load(&mut *conn)?;
        let want = std::path::Path::new(path);
        let hit: Vec<String> = open
            .into_iter()
            .filter(|stored| crate::same_path(std::path::Path::new(stored), want))
            .collect();
        if hit.is_empty() {
            return Ok(());
        }
        diesel::update(
            worktree_claims::table
                .filter(worktree_claims::path.eq_any(hit))
                .filter(worktree_claims::released_at.is_null()),
        )
        .set(worktree_claims::released_at.eq(unixepoch()))
        .execute(&mut *conn)?;
        Ok(())
    }

    /// `(path, agent_id)` of every claim not yet released.
    pub fn open_worktree_claims(&self) -> Result<Vec<(String, String)>> {
        let mut conn = self.lock()?;
        Ok(worktree_claims::table
            .filter(worktree_claims::released_at.is_null())
            .select((worktree_claims::path, worktree_claims::agent_id))
            .load(&mut *conn)?)
    }

    /// The model of the newest call a host session made — `rtok worktree add`'s default
    /// `--owner` is `<host> / <model>`.
    pub fn session_model(&self, session: &str) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        Ok(calls::table
            .inner_join(models::table)
            .filter(calls::session_id.eq(session))
            .order(calls::ts.desc())
            .select(models::slug)
            .first(&mut *conn)
            .optional()?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// gc's `--stale-lock` default (T418), the one window a claim holder is judged by (T527).
    const STALE_LOCK: Duration = Duration::from_secs(7 * 86_400);
    const NOW: i64 = 1_800_000_000;

    /// T527: a claim held by an agent whose session is over does not hold its worktree.
    #[test]
    fn an_ended_holders_claim_does_not_hold() {
        let store = Store::open_in_memory().unwrap();
        let claude = store.host_id("claude").unwrap().unwrap();
        let a = store
            .register_agent(claude, "s-a", None, None, None)
            .unwrap();
        store.claim_worktree("/w/x", &a, "t1").unwrap();
        store.end_agent(&a, NOW).unwrap();
        let holder = store.worktree_claim_holder(&a).unwrap().unwrap();
        assert!(!holder.holds(NOW, STALE_LOCK), "{holder:?}");
    }

    /// T527: an agent idle past `stale_lock` has abandoned its claims, however unended.
    #[test]
    fn a_holder_idle_past_stale_lock_does_not_hold() {
        let store = Store::open_in_memory().unwrap();
        let claude = store.host_id("claude").unwrap().unwrap();
        let a = store
            .register_agent(claude, "s-a", None, None, None)
            .unwrap();
        store.claim_worktree("/w/x", &a, "t1").unwrap();
        store
            .set_agent_last_seen(&a, NOW - STALE_LOCK.as_secs() as i64 - 1)
            .unwrap();
        let holder = store.worktree_claim_holder(&a).unwrap().unwrap();
        assert!(!holder.holds(NOW, STALE_LOCK), "{holder:?}");
    }

    /// T527: a claim touched inside the window still holds, and says when its holder was seen.
    #[test]
    fn a_holder_seen_today_holds_and_names_their_last_seen() {
        let store = Store::open_in_memory().unwrap();
        let claude = store.host_id("claude").unwrap().unwrap();
        let a = store
            .register_agent(claude, "s-a", None, None, None)
            .unwrap();
        store.claim_worktree("/w/x", &a, "t1").unwrap();
        store.set_agent_last_seen(&a, NOW - 60).unwrap();
        let holder = store.worktree_claim_holder(&a).unwrap().unwrap();
        assert!(holder.holds(NOW, STALE_LOCK), "{holder:?}");
        assert_eq!(holder.seen_ago(NOW), 60);
    }

    #[test]
    fn a_claim_upserts_per_path() {
        let store = Store::open_in_memory().unwrap();
        let claude = store.host_id("claude").unwrap().unwrap();
        let a = store
            .register_agent(claude, "s-a", None, None, None)
            .unwrap();
        let b = store
            .register_agent(claude, "s-b", None, None, None)
            .unwrap();
        store.claim_worktree("/w/x", &a, "t1").unwrap();
        store.claim_worktree("/w/y", &a, "t2").unwrap();
        store.claim_worktree("/w/x", &b, "t1").unwrap();
        let mut claims = store.open_worktree_claims().unwrap();
        claims.sort();
        assert_eq!(claims, [("/w/x".into(), b), ("/w/y".into(), a.clone())]);
        store.release_worktree_claim("/w/x").unwrap();
        assert_eq!(store.open_worktree_claims().unwrap(), [("/w/y".into(), a)]);
        assert_eq!(store.session_model("s-a").unwrap(), None);
    }

    /// T430: a claim stored with `std::fs::canonicalize`'s prefix releases on the simplified path.
    #[cfg(windows)]
    #[test]
    fn release_matches_a_verbatim_prefix() {
        let store = Store::open_in_memory().unwrap();
        let claude = store.host_id("claude").unwrap().unwrap();
        let a = store
            .register_agent(claude, "s-a", None, None, None)
            .unwrap();
        store
            .claim_worktree(r"\\?\C:\Users\Me\wt", &a, "t1")
            .unwrap();
        store.release_worktree_claim(r"C:\Users\Me\wt").unwrap();
        assert!(store.open_worktree_claims().unwrap().is_empty());
    }
}

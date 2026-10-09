// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T374: the files a note is about, for recall that prefers the notes of the files in play.

use std::collections::HashMap;

use crate::Result;
use diesel::prelude::*;

use super::schema::{note_files, notes, read_cache};
use super::{NoteHit, Store, substr};

/// Keys of one session's read cache are bounded by what the session read; the cap keeps a
/// very long session from turning one recall into an unbounded `IN (…)` list.
const MAX_READ_KEYS: i64 = 512;

impl Store {
    /// Replace the files `note_id` is linked to with `paths` (root-relative). A re-saved note
    /// is re-derived from its new body, so stale links must not survive it.
    pub fn set_note_files(&self, note_id: i32, paths: &[String]) -> Result<()> {
        let mut conn = self.lock()?;
        conn.transaction(|conn| {
            diesel::delete(note_files::table.filter(note_files::note_id.eq(note_id)))
                .execute(conn)?;
            let rows: Vec<_> = paths
                .iter()
                .map(|p| (note_files::note_id.eq(note_id), note_files::path.eq(p)))
                .collect();
            diesel::insert_or_ignore_into(note_files::table)
                .values(&rows)
                .execute(conn)?;
            Ok(())
        })
    }

    /// Live notes linked to any of `paths`: the one linked to most of them first, then the
    /// newest, so the order is the same on every run.
    pub fn notes_for_files(
        &self,
        project: Option<&str>,
        paths: &[String],
        limit: u32,
    ) -> Result<Vec<NoteHit>> {
        if paths.is_empty() {
            return Ok(Vec::new());
        }
        let mut conn = self.lock()?;
        let mut q = note_files::table
            .inner_join(notes::table)
            .filter(note_files::path.eq_any(paths))
            .filter(notes::retired.is_null())
            .select((notes::id, notes::title, substr(notes::body, 1, 120)))
            .into_boxed();
        if let Some(p) = project {
            q = q.filter(notes::project.eq(p).or(notes::project.is_null()));
        }
        let rows: Vec<(i32, String, String)> = q.load(&mut *conn)?;
        let mut linked: HashMap<i32, (usize, NoteHit)> = HashMap::new();
        for (id, title, snippet) in rows {
            linked
                .entry(id)
                .or_insert_with(|| (0, NoteHit { id, title, snippet }))
                .0 += 1;
        }
        let mut ranked: Vec<(usize, NoteHit)> = linked.into_values().collect();
        ranked.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| b.1.id.cmp(&a.1.id)));
        Ok(ranked
            .into_iter()
            .take(limit.max(1) as usize)
            .map(|(_, h)| h)
            .collect())
    }

    /// The raw read-cache keys of `session`, path order. Keys are `path\tmode\trange` or
    /// `read\tpath…` (the guard's), so the caller picks the path out of each.
    pub fn read_cache_keys(&self, session: &str) -> Result<Vec<String>> {
        let mut conn = self.lock()?;
        read_cache::table
            .filter(read_cache::session.eq(session))
            .order(read_cache::path.asc())
            .limit(MAX_READ_KEYS)
            .select(read_cache::path)
            .load(&mut *conn)
            .map_err(Into::into)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> String {
        s.to_string()
    }

    #[test]
    fn links_round_trip_and_a_resave_replaces_them() {
        let store = Store::open_in_memory().unwrap();
        let (a, _) = store.upsert_note(None, "note", "a", "body").unwrap();
        store
            .set_note_files(a, &[p("src/a.rs"), p("src/a.rs"), p("src/b.rs")])
            .unwrap();
        let hit = store.notes_for_files(None, &[p("src/b.rs")], 5).unwrap();
        assert_eq!(hit.iter().map(|h| h.id).collect::<Vec<_>>(), vec![a]);
        store.set_note_files(a, &[p("src/c.rs")]).unwrap();
        assert!(
            store
                .notes_for_files(None, &[p("src/b.rs")], 5)
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            store
                .notes_for_files(None, &[p("src/c.rs")], 5)
                .unwrap()
                .len(),
            1
        );
        assert!(store.notes_for_files(None, &[], 5).unwrap().is_empty());
    }

    #[test]
    fn most_linked_first_then_newest_and_retired_notes_are_skipped() {
        let store = Store::open_in_memory().unwrap();
        let (one, _) = store.upsert_note(None, "note", "one", "x").unwrap();
        let (two, _) = store.upsert_note(None, "note", "two", "x").unwrap();
        let (both, _) = store.upsert_note(None, "note", "both", "x").unwrap();
        let (gone, _) = store.upsert_note(None, "note", "gone", "x").unwrap();
        store.set_note_files(one, &[p("a")]).unwrap();
        store.set_note_files(two, &[p("a")]).unwrap();
        store.set_note_files(both, &[p("a"), p("b")]).unwrap();
        store.set_note_files(gone, &[p("a"), p("b")]).unwrap();
        store.retire_note(gone, None).unwrap();
        let ids: Vec<i32> = store
            .notes_for_files(None, &[p("a"), p("b")], 10)
            .unwrap()
            .iter()
            .map(|h| h.id)
            .collect();
        assert_eq!(ids, vec![both, two, one]);
    }

    #[test]
    fn a_project_scope_keeps_other_projects_notes_out() {
        let store = Store::open_in_memory().unwrap();
        let mine = store.upsert_note(Some("rtok"), "note", "m", "x").unwrap().0;
        let loose = store.upsert_note(None, "note", "l", "x").unwrap().0;
        let other = store
            .upsert_note(Some("other"), "note", "o", "x")
            .unwrap()
            .0;
        for id in [mine, loose, other] {
            store.set_note_files(id, &[p("src/main.rs")]).unwrap();
        }
        let ids = |project| {
            let mut v: Vec<i32> = store
                .notes_for_files(project, &[p("src/main.rs")], 10)
                .unwrap()
                .iter()
                .map(|h| h.id)
                .collect();
            v.sort();
            v
        };
        assert_eq!(ids(Some("rtok")), vec![mine, loose]);
        assert_eq!(ids(None), vec![mine, loose, other]);
    }

    #[test]
    fn read_cache_keys_are_scoped_to_the_session() {
        let store = Store::open_in_memory().unwrap();
        store
            .put_read_cache("s1", "src/a.rs\tfull\t", "h", None)
            .unwrap();
        store
            .put_read_cache("s2", "src/b.rs\tfull\t", "h", None)
            .unwrap();
        assert_eq!(
            store.read_cache_keys("s1").unwrap(),
            vec![p("src/a.rs\tfull\t")]
        );
    }
}

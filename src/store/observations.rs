// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T454/T455: synthetic observations — insert, FTS5 search, ranked title recall. Never DELETE.

use anyhow::Result;
use diesel::prelude::*;

use super::schema::observations;
use super::sql_ext;
use super::{NoteHit, Store, fts_phrase_query};

/// One live observation for ranked title recall (T455).
#[derive(Debug, Clone)]
pub struct ObservationRecall {
    pub id: i32,
    pub session: String,
    pub title: String,
    pub narrative: String,
    pub importance: i32,
    pub uses: i32,
    pub last_used: Option<i64>,
    pub ts: i64,
    pub pinned: i32,
}

impl Store {
    /// Insert one scrubbed synthetic observation. `files` is stored newline-joined.
    #[allow(clippy::too_many_arguments)]
    pub fn insert_observation(
        &self,
        session: &str,
        project: Option<&str>,
        kind: &str,
        title: &str,
        narrative: &str,
        files: &[String],
        archive_id: Option<&str>,
        importance: i32,
        confidence: f64,
    ) -> Result<i32> {
        let files_joined = files.join("\n");
        let mut conn = self.lock()?;
        diesel::insert_into(observations::table)
            .values((
                observations::session.eq(session),
                observations::project.eq(project),
                observations::kind.eq(kind),
                observations::title.eq(title),
                observations::narrative.eq(narrative),
                observations::files.eq(files_joined),
                observations::archive_id.eq(archive_id),
                observations::importance.eq(importance),
                observations::confidence.eq(confidence),
            ))
            .returning(observations::id)
            .get_result(&mut *conn)
            .map_err(Into::into)
    }

    /// FTS5 search over observation titles and narratives; retired rows are skipped.
    pub fn search_observations(&self, query: &str, limit: u32) -> Result<Vec<NoteHit>> {
        let Some(q) = fts_phrase_query(query) else {
            return Ok(Vec::new());
        };
        let mut conn = self.lock()?;
        let hits = sql_ext::SearchObservations {
            query: q,
            limit: i32::try_from(limit).unwrap_or(5),
        }
        .load::<(i32, String, String)>(&mut *conn)?
        .into_iter()
        .map(|(id, title, snippet)| NoteHit { id, title, snippet })
        .collect();
        Ok(hits)
    }

    /// Archive id for an observation, when the full tool output was kept (D4).
    pub fn observation_archive_id(&self, id: i32) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        observations::table
            .find(id)
            .select(observations::archive_id)
            .first::<Option<String>>(&mut *conn)
            .optional()
            .map(|row| row.flatten())
            .map_err(Into::into)
    }

    /// Live observations for `project` (and unbound), newest-biased fetch for in-memory rank.
    pub fn list_observations_for_recall(
        &self,
        project: Option<&str>,
        limit: u32,
    ) -> Result<Vec<ObservationRecall>> {
        let mut conn = self.lock()?;
        let lim = i64::from(limit.max(1));
        let mut q = observations::table
            .filter(observations::retired.is_null())
            .order((observations::pinned.desc(), observations::id.desc()))
            .limit(lim)
            .select((
                observations::id,
                observations::session,
                observations::title,
                observations::narrative,
                observations::importance,
                observations::uses,
                observations::last_used,
                observations::ts,
                observations::pinned,
            ))
            .into_boxed();
        q = match project {
            Some(p) => q.filter(
                observations::project
                    .eq(p)
                    .or(observations::project.is_null()),
            ),
            None => q,
        };
        Ok(
            q.load::<(i32, String, String, String, i32, i32, Option<i64>, i64, i32)>(&mut *conn)?
                .into_iter()
                .map(
                    |(id, session, title, narrative, importance, uses, last_used, ts, pinned)| {
                        ObservationRecall {
                            id,
                            session,
                            title,
                            narrative,
                            importance,
                            uses,
                            last_used,
                            ts,
                            pinned,
                        }
                    },
                )
                .collect(),
        )
    }

    /// Bump `uses` / `last_used` for recalled observation ids (rank inputs; never a delete).
    pub fn touch_observations(&self, ids: &[i32]) -> Result<()> {
        if ids.is_empty() {
            return Ok(());
        }
        let now = i64::try_from(crate::log::now()).unwrap_or(i64::MAX);
        let mut conn = self.lock()?;
        for id in ids {
            diesel::update(observations::table.find(id))
                .set((
                    observations::uses.eq(observations::uses + 1),
                    observations::last_used.eq(now),
                ))
                .execute(&mut *conn)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insert_and_search_hit_the_title() {
        let store = Store::open_in_memory().unwrap();
        let id = store
            .insert_observation(
                "s1",
                Some("rtok"),
                "file_read",
                "Read",
                "src/main.rs | fn main",
                &["src/main.rs".into()],
                Some("deadbeef"),
                5,
                0.3,
            )
            .unwrap();
        let hits = store.search_observations("main", 5).unwrap();
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, id);
        assert_eq!(hits[0].title, "Read");
        assert!(hits[0].snippet.contains("main"));
        assert_eq!(
            store.observation_archive_id(id).unwrap().as_deref(),
            Some("deadbeef")
        );
    }
}

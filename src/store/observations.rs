// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T454: synthetic observations — insert and FTS5 search. Lifecycle is retire later; never DELETE.

use anyhow::Result;
use diesel::prelude::*;

use super::schema::observations;
use super::sql_ext;
use super::{NoteHit, Store, fts_phrase_query};

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

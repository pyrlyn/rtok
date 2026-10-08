// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T454: mechanical observations. FTS5 `MATCH` stays in `sql_ext`; the row writes are Diesel.

use anyhow::Result;
use diesel::prelude::*;

use super::schema::{observation_files, observations};
use super::{Store, sql_ext, substr};
use rtok_plugin_sdk::{NewObservation, ObsHit};

/// A repeated hook inside this many seconds does not insert a second row.
const DEDUP_SECS: i64 = 5;

impl Store {
    /// Insert one observation and its file links. `Ok(None)` when the same session already
    /// stored this narrative in the last [`DEDUP_SECS`] seconds.
    pub fn insert_observation(&self, obs: &NewObservation<'_>) -> Result<Option<i32>> {
        let mut conn = self.lock()?;
        let now = i64::try_from(crate::log::now()).unwrap_or(i64::MAX);
        let existing: Option<i32> = sql_ext::RecentObservationDup {
            session_id: obs.session_id.to_string(),
            dedup: obs.dedup.to_string(),
            since: now.saturating_sub(DEDUP_SECS),
        }
        .get_result(&mut *conn)
        .optional()?;
        if existing.is_some() {
            return Ok(None);
        }
        let id = conn.transaction(|conn| {
            let id: i32 = diesel::insert_into(observations::table)
                .values((
                    observations::session_id.eq(obs.session_id),
                    observations::project.eq(obs.project),
                    observations::obs_type.eq(obs.obs_type),
                    observations::title.eq(obs.title),
                    observations::narrative.eq(obs.narrative),
                    observations::dedup.eq(obs.dedup),
                ))
                .returning(observations::id)
                .get_result(conn)?;
            let rows: Vec<_> = obs
                .files
                .iter()
                .map(|p| {
                    (
                        observation_files::observation_id.eq(id),
                        observation_files::path.eq(p),
                    )
                })
                .collect();
            if !rows.is_empty() {
                diesel::insert_or_ignore_into(observation_files::table)
                    .values(&rows)
                    .execute(conn)?;
            }
            Ok::<i32, diesel::result::Error>(id)
        })?;
        Ok(Some(id))
    }

    /// FTS over titles and narratives. An empty query returns no hits.
    pub fn search_observations(
        &self,
        project: Option<&str>,
        query: &str,
        limit: u32,
    ) -> Result<Vec<ObsHit>> {
        let Some(q) = super::fts_phrase_query(query) else {
            return Ok(Vec::new());
        };
        let mut conn = self.lock()?;
        let rows = sql_ext::SearchObservations {
            query: q,
            project: project.map(str::to_string),
            limit: i32::try_from(limit.max(1)).unwrap_or(i32::MAX),
        }
        .load::<(i32, String, String, String)>(&mut *conn)?;
        Ok(rows
            .into_iter()
            .map(|(id, title, session_id, snippet)| ObsHit {
                id,
                title,
                session_id,
                snippet,
            })
            .collect())
    }

    /// Observations linked to any of `paths`, newest first.
    pub fn observations_for_files(
        &self,
        project: Option<&str>,
        paths: &[String],
        limit: u32,
    ) -> Result<Vec<ObsHit>> {
        if paths.is_empty() {
            return Ok(Vec::new());
        }
        let mut conn = self.lock()?;
        let mut q = observation_files::table
            .inner_join(observations::table)
            .filter(observation_files::path.eq_any(paths))
            .order(observations::id.desc())
            .limit(i64::from(limit.max(1)))
            .select((
                observations::id,
                observations::title,
                observations::session_id,
                substr(observations::narrative, 1, 120),
            ))
            .into_boxed();
        if let Some(p) = project {
            q = q.filter(
                observations::project
                    .is_null()
                    .or(observations::project.eq(p)),
            );
        }
        let rows: Vec<(i32, String, String, String)> = q.load(&mut *conn)?;
        Ok(rows
            .into_iter()
            .map(|(id, title, session_id, snippet)| ObsHit {
                id,
                title,
                session_id,
                snippet,
            })
            .collect())
    }

    /// Newest observations recorded in `session_id`.
    pub fn recent_observations(&self, session_id: &str, limit: u32) -> Result<Vec<ObsHit>> {
        let mut conn = self.lock()?;
        let rows: Vec<(i32, String, String, String)> = observations::table
            .filter(observations::session_id.eq(session_id))
            .order(observations::id.desc())
            .limit(i64::from(limit.max(1)))
            .select((
                observations::id,
                observations::title,
                observations::session_id,
                substr(observations::narrative, 1, 120),
            ))
            .load(&mut *conn)?;
        Ok(rows
            .into_iter()
            .map(|(id, title, session_id, snippet)| ObsHit {
                id,
                title,
                session_id,
                snippet,
            })
            .collect())
    }

    /// The stored narrative, or `None` when the id is unknown.
    pub fn observation_narrative(&self, id: i32) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        observations::table
            .find(id)
            .select(observations::narrative)
            .first(&mut *conn)
            .optional()
            .map_err(Into::into)
    }
}

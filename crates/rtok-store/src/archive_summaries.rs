// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T385.9.1: `archive_summaries` — the P28 compressor's summary beside its archive row.

use crate::Result;
use diesel::prelude::*;

use super::Store;
use super::schema::archive_summaries;

impl Store {
    /// The stored summary of `archive_id` for compressor `version`, if one was accepted.
    pub fn archive_summary(&self, archive_id: &str, version: &str) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        Ok(archive_summaries::table
            .find((archive_id, version))
            .select(archive_summaries::summary)
            .first(&mut *conn)
            .optional()?)
    }

    /// Every stored `(archive_id, summary)`, any version, ordered by id: the Gate P28 bench
    /// (T385.9.3) checks each one against the must-keep fixture and `expand`.
    pub fn archive_summaries(&self) -> Result<Vec<(String, String)>> {
        let mut conn = self.lock()?;
        Ok(archive_summaries::table
            .select((archive_summaries::archive_id, archive_summaries::summary))
            .order((archive_summaries::archive_id, archive_summaries::version))
            .load(&mut *conn)?)
    }

    /// Store a summary; `true` when this call wrote it. A second writer for the same
    /// `(archive_id, version)` changes nothing: the first text is the one in the live zone.
    pub fn put_archive_summary(
        &self,
        archive_id: &str,
        version: &str,
        summary: &str,
    ) -> Result<bool> {
        let mut conn = self.lock()?;
        let n = diesel::insert_or_ignore_into(archive_summaries::table)
            .values((
                archive_summaries::archive_id.eq(archive_id),
                archive_summaries::version.eq(version),
                archive_summaries::summary.eq(summary),
            ))
            .execute(&mut *conn)?;
        Ok(n > 0)
    }
}

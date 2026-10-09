// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Store methods: notes and the kv table.

use super::*;

impl Store {
    /// Insert a note (T2.5 checkpoints, later memory).
    pub fn insert_note(
        &self,
        project: Option<&str>,
        kind: &str,
        title: &str,
        body: &str,
    ) -> Result<i32> {
        let mut conn = self.lock()?;
        diesel::insert_into(notes::table)
            .values(note_values(project, kind, title, body))
            .returning(notes::id)
            .get_result(&mut *conn)
            .map_err(Into::into)
    }

    /// Insert only if `(project, kind, title)` is still free; `None` when a row already
    /// holds that topic key (T209: `memory import` must never let an older export
    /// overwrite a newer local body — unlike [`Store::upsert_note`], this never touches
    /// an existing row). `INSERT OR IGNORE` names no conflict target, so it works against
    /// the `notes_topic` expression index without the raw SQL `upsert_note` needs;
    /// `RETURNING` yields no row when the insert was ignored, which `.optional()` reads
    /// as the "already there" case.
    pub fn insert_note_if_absent(
        &self,
        project: Option<&str>,
        kind: &str,
        title: &str,
        body: &str,
    ) -> Result<Option<i32>> {
        let mut conn = self.lock()?;
        diesel::insert_or_ignore_into(notes::table)
            .values(note_values(project, kind, title, body))
            .returning(notes::id)
            .get_result(&mut *conn)
            .optional()
            .map_err(Into::into)
    }

    /// Like [`Store::insert_note_if_absent`], but keeps portable JSONL metadata (T294):
    /// explicit `id`, `ts`, and lifecycle columns when the export row carried them.
    pub fn insert_portable_note_if_absent(&self, note: PortableNote<'_>) -> Result<Option<i32>> {
        let PortableNote {
            project,
            kind,
            title,
            body,
            id,
            ts,
            retired,
            superseded_by,
            pinned,
        } = note;
        let portable = id.is_some()
            || ts.is_some()
            || retired.is_some()
            || superseded_by.is_some()
            || pinned.is_some_and(|p| p != 0);
        if !portable {
            return self.insert_note_if_absent(project, kind, title, body);
        }
        let mut conn = self.lock()?;
        let pinned = pinned.unwrap_or(0);
        let inserted = match (id, ts) {
            (Some(id), Some(ts)) => diesel::insert_or_ignore_into(notes::table)
                .values((
                    notes::id.eq(id),
                    notes::ts.eq(ts),
                    notes::project.eq(project),
                    notes::kind.eq(kind),
                    notes::title.eq(title),
                    notes::body.eq(body),
                    notes::retired.eq(retired),
                    notes::superseded_by.eq(superseded_by),
                    notes::pinned.eq(pinned),
                ))
                .returning(notes::id)
                .get_result(&mut *conn)
                .optional()?,
            (Some(id), None) => diesel::insert_or_ignore_into(notes::table)
                .values((
                    notes::id.eq(id),
                    notes::project.eq(project),
                    notes::kind.eq(kind),
                    notes::title.eq(title),
                    notes::body.eq(body),
                    notes::retired.eq(retired),
                    notes::superseded_by.eq(superseded_by),
                    notes::pinned.eq(pinned),
                ))
                .returning(notes::id)
                .get_result(&mut *conn)
                .optional()?,
            (None, Some(ts)) => diesel::insert_or_ignore_into(notes::table)
                .values((
                    notes::ts.eq(ts),
                    notes::project.eq(project),
                    notes::kind.eq(kind),
                    notes::title.eq(title),
                    notes::body.eq(body),
                    notes::retired.eq(retired),
                    notes::superseded_by.eq(superseded_by),
                    notes::pinned.eq(pinned),
                ))
                .returning(notes::id)
                .get_result(&mut *conn)
                .optional()?,
            (None, None) => diesel::insert_or_ignore_into(notes::table)
                .values((
                    notes::project.eq(project),
                    notes::kind.eq(kind),
                    notes::title.eq(title),
                    notes::body.eq(body),
                    notes::retired.eq(retired),
                    notes::superseded_by.eq(superseded_by),
                    notes::pinned.eq(pinned),
                ))
                .returning(notes::id)
                .get_result(&mut *conn)
                .optional()?,
        };
        Ok(inserted)
    }

    /// One row per `(project, kind, title)` — the title is the topic key (T66.1). An
    /// existing row gets the new body and a fresh `ts`; returns `(id, updated)`.
    ///
    /// T209: this used to be a SELECT for the existing id followed by an UPDATE or
    /// INSERT, with the mutex dropped before the INSERT — two writers (hooks, MCP, proxy
    /// and `otel flush` are separate processes) racing the same topic key could both
    /// insert. The write below is one atomic `INSERT … ON CONFLICT … DO UPDATE`, backed
    /// by the `notes_topic` UNIQUE index (migration 0020), so a race resolves inside
    /// SQLite instead of in this gap.
    ///
    /// `notes_topic` indexes `COALESCE(project, '')` rather than `project`: SQLite treats
    /// NULL as a distinct value in a UNIQUE index, so a plain `(project, kind, title)`
    /// index would not stop two NULL-project ("no project") notes from duplicating. Diesel's
    /// `on_conflict` can only target a column tuple, not an expression index, so this one
    /// statement is raw SQL — the DSL cannot express an expression conflict target.
    ///
    /// T472: when a row already exists and the new body differs, the previous title and
    /// body are inserted into `note_versions` first, in this same immediate transaction,
    /// with `version = COALESCE(MAX(version), 0) + 1`. Kinds `checkpoint:*` and
    /// `session:*` are skipped so checkpoints do not fill the table. A same-body upsert
    /// writes no version row. Recall and `mem_get` keep reading the current body.
    pub fn upsert_note(
        &self,
        project: Option<&str>,
        kind: &str,
        title: &str,
        body: &str,
    ) -> Result<(i32, bool)> {
        let mut conn = self.lock()?;
        // BEGIN IMMEDIATE: the version insert and the upsert must see one snapshot, and a
        // second process (hooks, MCP, proxy) must wait instead of picking the same version.
        conn.immediate_transaction(|conn| -> Result<(i32, bool)> {
            let mut existed_q = notes::table
                .filter(notes::kind.eq(kind))
                .filter(notes::title.eq(title))
                .select((notes::id, notes::title, notes::body))
                .into_boxed();
            existed_q = match project {
                Some(p) => existed_q.filter(notes::project.eq(p)),
                None => existed_q.filter(notes::project.is_null()),
            };
            let existing = existed_q.first::<(i32, String, String)>(conn).optional()?;
            if let Some((note_id, old_title, old_body)) = &existing
                && old_body.as_str() != body
                && keeps_note_versions(kind)
            {
                record_note_version(conn, *note_id, old_title, old_body)?;
            }
            let id = sql_ext::UpsertNote {
                project: project.map(str::to_string),
                kind: kind.to_string(),
                title: title.to_string(),
                body: body.to_string(),
            }
            .get_result(conn)?;
            Ok((id, existing.is_some()))
        })
    }

    /// Earlier title and body for `id`, oldest version first (T472). Empty when the note
    /// was never rewritten, or when its kind is `checkpoint:*` / `session:*`.
    pub fn note_versions(&self, id: i32) -> Result<Vec<(i32, String, String)>> {
        let mut conn = self.lock()?;
        note_versions::table
            .filter(note_versions::note_id.eq(id))
            .order(note_versions::version.asc())
            .select((
                note_versions::version,
                note_versions::title,
                note_versions::body,
            ))
            .load(&mut *conn)
            .map_err(Into::into)
    }

    /// Every note but the session-local `checkpoint:*` / `session:*` rows, id order
    /// (`memory import`'s topic-key dedup, T6.3): `(project, kind, title, body)`.
    /// `include_retired` decides whether tombstoned notes are in the results. `memory import`
    /// passes `true` (T304) because the `notes_topic` unique index still covers a retired row,
    /// so a local key must block an imported line whether or not it is retired. `memory export`
    /// does not use this query: it writes full rows, retired included, via
    /// [`Store::list_export_notes`] so the tombstone round-trips (T294) instead of coming
    /// back live.
    #[allow(clippy::type_complexity)]
    pub fn list_notes(
        &self,
        project: Option<&str>,
        include_retired: bool,
    ) -> Result<Vec<(Option<String>, String, String, String)>> {
        let mut conn = self.lock()?;
        let mut q = notes::table
            .filter(notes::kind.not_like("checkpoint:%"))
            .filter(notes::kind.not_like("session:%"))
            .order(notes::id.asc())
            .select((notes::project, notes::kind, notes::title, notes::body))
            .into_boxed();
        if !include_retired {
            q = q.filter(notes::retired.is_null());
        }
        if let Some(p) = project {
            q = q.filter(notes::project.eq(p));
        }
        q.load(&mut *conn).map_err(Into::into)
    }

    /// Full portable rows for `memory export` (T294): same filters as [`Store::list_notes`],
    /// plus `id`, `ts`, and lifecycle columns.
    pub fn list_export_notes(&self, project: Option<&str>) -> Result<Vec<ExportNote>> {
        let mut conn = self.lock()?;
        let mut q = notes::table
            .filter(notes::kind.not_like("checkpoint:%"))
            .filter(notes::kind.not_like("session:%"))
            .order(notes::id.asc())
            .select((
                notes::id,
                notes::ts,
                notes::project,
                notes::kind,
                notes::title,
                notes::body,
                notes::retired,
                notes::superseded_by,
                notes::pinned,
            ))
            .into_boxed();
        if let Some(p) = project {
            q = q.filter(notes::project.eq(p));
        }
        q.load(&mut *conn).map_err(Into::into)
    }

    pub fn latest_note_for_project(
        &self,
        project: Option<&str>,
        kind_prefix: &str,
    ) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        let mut q = notes::table
            .filter(notes::kind.like(format!("{kind_prefix}%")))
            .order(notes::id.desc())
            .select(notes::body)
            .into_boxed();
        if let Some(p) = project {
            q = q.filter(notes::project.eq(p));
        } else {
            q = q.filter(notes::project.is_null());
        }
        q.first(&mut *conn).optional().map_err(Into::into)
    }

    /// Newest note body for `kind`, if any.
    pub fn latest_note(&self, kind: &str) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        notes::table
            .filter(notes::kind.eq(kind))
            .order(notes::id.desc())
            .select(notes::body)
            .first(&mut *conn)
            .optional()
            .map_err(Into::into)
    }

    /// Session ids that already have a compaction (`checkpoint:<id>`) or handoff
    /// (`session:<id>`) note. Suffixes only; a session with both kinds is one id.
    pub fn checkpoint_session_ids(&self) -> Result<Vec<String>> {
        let mut conn = self.lock()?;
        let kinds: Vec<String> = notes::table
            .filter(
                notes::kind
                    .like("checkpoint:%")
                    .or(notes::kind.like("session:%")),
            )
            .select(notes::kind)
            .load(&mut *conn)?;
        Ok(kinds
            .into_iter()
            .filter_map(|k| {
                k.strip_prefix("checkpoint:")
                    .or_else(|| k.strip_prefix("session:"))
                    .filter(|id| !id.is_empty())
                    .map(str::to_string)
            })
            .collect())
    }

    /// Newest `session:*` note body for `project` (`None` = unbound), id order.
    pub fn latest_session_note(&self, project: Option<&str>) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        let mut q = notes::table
            .filter(notes::kind.like("session:%"))
            .order(notes::id.desc())
            .select(notes::body)
            .into_boxed();
        q = match project {
            Some(p) => q.filter(notes::project.eq(p)),
            None => q.filter(notes::project.is_null()),
        };
        q.first(&mut *conn).optional().map_err(Into::into)
    }

    /// Remember a Read/Bash result so `guard` can deny the duplicate (T2.6).
    /// Newest note titles for SessionStart recall (T6.2). Never bodies. Retired notes
    /// never recall; pinned ones lead. Among the rest, [`retention_score`] leads, and
    /// equal scores stay newest-first so the title cap still drops the oldest.
    pub fn list_note_titles(
        &self,
        project: Option<&str>,
        limit: u32,
    ) -> Result<Vec<(i32, String)>> {
        let mut conn = self.lock()?;
        let lim = i64::from(limit.max(1));
        // A wider window so a note just below the cut can still lead once `uses` lifts it.
        // Pinned rows sort first in SQL, so an old pin stays inside the window.
        let mut q = notes::table
            .filter(notes::retired.is_null())
            .filter(notes::kind.not_like("checkpoint:%"))
            .filter(notes::kind.not_like("session:%"))
            .order((notes::pinned.desc(), notes::id.desc()))
            .limit(lim.saturating_mul(4))
            .select((
                notes::id,
                notes::title,
                notes::pinned,
                notes::uses,
                notes::ts,
                notes::last_used,
            ))
            .into_boxed();
        if let Some(p) = project {
            q = q.filter(notes::project.eq(p));
        }
        let mut rows: Vec<(i32, String, i32, i32, i64, Option<i64>)> = q.load(&mut *conn)?;
        let now = i64::try_from(unix_now()).unwrap_or(0);
        rows.sort_by(|a, b| {
            b.2.cmp(&a.2)
                .then_with(|| {
                    retention_score(b.2 != 0, b.3, age_days(now, b.4), days_since(now, b.5))
                        .partial_cmp(&retention_score(
                            a.2 != 0,
                            a.3,
                            age_days(now, a.4),
                            days_since(now, a.5),
                        ))
                        .unwrap_or(std::cmp::Ordering::Equal)
                })
                // Equal scores keep newest-first, which is what the title cap drops last.
                .then_with(|| b.0.cmp(&a.0))
        });
        rows.truncate(limit.max(1) as usize);
        Ok(rows
            .into_iter()
            .map(|(id, title, ..)| (id, title))
            .collect())
    }

    /// Count one read of `id` toward retention ranking (T454). Unknown ids change nothing.
    pub fn touch_note(&self, id: i32) -> Result<()> {
        let mut conn = self.lock()?;
        diesel::update(notes::table.find(id))
            .set((
                notes::uses.eq(notes::uses + 1),
                notes::last_used.eq(unixepoch()),
            ))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// One note's lifecycle row (T69.1): kind/project for a revise, the retired line and
    /// pinned flag for `mem_get` / `mem_update`.
    pub fn note_row(&self, id: i32) -> Result<Option<NoteRow>> {
        let mut conn = self.lock()?;
        notes::table
            .find(id)
            .select((
                notes::id,
                notes::project,
                notes::kind,
                notes::title,
                notes::body,
                notes::retired,
                notes::superseded_by,
                notes::pinned,
            ))
            .first::<NoteRow>(&mut *conn)
            .optional()
            .map_err(Into::into)
    }

    /// Retire `id` — a tombstone, not a delete (D4): recall and search skip the note,
    /// `mem_get` keeps returning the body with a `retired` prefix. `superseded_by` names
    /// the replacement note. Returns `false` for an unknown id.
    pub fn retire_note(&self, id: i32, superseded_by: Option<i32>) -> Result<bool> {
        let mut conn = self.lock()?;
        let n = diesel::update(notes::table.find(id))
            .set((
                notes::retired.eq(unixepoch()),
                notes::superseded_by.eq(superseded_by),
            ))
            .execute(&mut *conn)?;
        Ok(n == 1)
    }

    /// Last-written `rtok memory sync` block digest (T69.6 hand-edit guard).
    pub fn kv_get(&self, key: &str) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        kv::table
            .filter(kv::key.eq(key))
            .select(kv::value)
            .first(&mut *conn)
            .optional()
            .map_err(Into::into)
    }

    pub fn kv_set(&self, key: &str, value: &str) -> Result<()> {
        let mut conn = self.lock()?;
        diesel::insert_into(kv::table)
            .values((kv::key.eq(key), kv::value.eq(value)))
            .on_conflict(kv::key)
            .do_update()
            .set(kv::value.eq(value))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// Every `(key, value)` whose key starts with `prefix`, key order. `%`/`_` in the
    /// prefix are escaped so a key segment never acts as a wildcard; SQLite's `LIKE`
    /// ignores ASCII case, so the rows are narrowed again to an exact prefix.
    pub fn kv_prefix(&self, prefix: &str) -> Result<Vec<(String, String)>> {
        let mut conn = self.lock()?;
        let pattern = format!(
            "{}%",
            prefix
                .replace('\\', "\\\\")
                .replace('%', "\\%")
                .replace('_', "\\_")
        );
        kv::table
            .filter(kv::key.like(pattern).escape('\\'))
            .order(kv::key)
            .select((kv::key, kv::value))
            .load::<(String, String)>(&mut *conn)
            .map(|rows| {
                rows.into_iter()
                    .filter(|(k, _)| k.starts_with(prefix))
                    .collect()
            })
            .map_err(Into::into)
    }

    pub fn kv_delete(&self, key: &str) -> Result<()> {
        let mut conn = self.lock()?;
        diesel::delete(kv::table.filter(kv::key.eq(key))).execute(&mut *conn)?;
        Ok(())
    }

    /// Pin or unpin `id`; pinned notes lead recall (T69.1). Returns `false` for an
    /// unknown id.
    pub fn set_note_pinned(&self, id: i32, pinned: bool) -> Result<bool> {
        let mut conn = self.lock()?;
        let n = diesel::update(notes::table.find(id))
            .set(notes::pinned.eq(i32::from(pinned)))
            .execute(&mut *conn)?;
        Ok(n == 1)
    }

    /// All note bodies (for import dedupe, T6.3).
    pub fn note_bodies(&self) -> Result<Vec<String>> {
        let mut conn = self.lock()?;
        notes::table
            .select(notes::body)
            .load(&mut *conn)
            .map_err(Into::into)
    }

    /// Full note body by row id.
    pub fn get_note_body(&self, id: i32) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        notes::table
            .find(id)
            .select(notes::body)
            .first(&mut *conn)
            .optional()
            .map_err(Into::into)
    }

    /// FTS5 search, BM25 order, 120-char snippets.
    ///
    /// The query is user text (MCP `mem_search`), and FTS5 reads bare `*`, `(`, `-`, `AND`
    /// and friends as query syntax: `read(` was a syntax error, not an empty result. Every
    /// token is quoted into a phrase, so the words are searched for literally; a query with
    /// nothing quotable left returns no hits instead of an error.
    pub fn search_notes(&self, query: &str, limit: u32) -> Result<Vec<NoteHit>> {
        let Some(q) = fts_phrase_query(query) else {
            return Ok(Vec::new());
        };
        let mut conn = self.lock()?;
        let hits = sql_ext::SearchNotes {
            query: q,
            limit: i32::try_from(limit).unwrap_or(5),
        }
        .load::<(i32, String, String)>(&mut *conn)?
        .into_iter()
        .map(|(id, title, snippet)| NoteHit { id, title, snippet })
        .collect::<Vec<_>>();
        Ok(hits)
    }
}

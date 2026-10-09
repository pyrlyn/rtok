// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Store methods: archive and call payloads.

use super::*;

impl Store {
    /// T208: the up-to-two payload files are written before the transaction (SQLite cannot
    /// hold them), then their `archive` rows and the `call_io` row commit together. A failed
    /// insert rolls back the rows and removes only the files this call created — a body
    /// that repeats an existing sha is left alone, since another row may still reference it.
    pub fn insert_call_io(
        &self,
        call_id: i32,
        request: Option<&[u8]>,
        response: Option<&[u8]>,
        inline_cap: usize,
        archive_dir: Option<&Path>,
    ) -> Result<()> {
        // T431: cleaned before the spill, so the sha, the size and the archive file all
        // describe the bytes that were saved.
        let raw = self.store_raw.load(std::sync::atomic::Ordering::Relaxed);
        let request = request.map(|r| match raw {
            true => std::borrow::Cow::Borrowed(r),
            false => crate::sanitize::body(r),
        });
        self.write_call_io(
            call_id,
            request.as_deref(),
            None,
            response,
            inline_cap,
            archive_dir,
        )
    }

    /// The write behind [`Store::insert_call_io`] and [`Store::insert_hook_call_io`]:
    /// `request` is already the bytes to save; `hook_fields` (T433) the session fields split
    /// off it, saved once in `hook_sessions` inside the same transaction.
    pub(crate) fn write_call_io(
        &self,
        call_id: i32,
        request: Option<&[u8]>,
        hook_fields: Option<&str>,
        response: Option<&[u8]>,
        inline_cap: usize,
        archive_dir: Option<&Path>,
    ) -> Result<()> {
        let session = self.call_session(call_id)?;
        let (req_json, req_arch, req_bytes, req_sha, req_path, req_created, req_raw) =
            self.spill(request, inline_cap, archive_dir)?;
        let (res_json, res_arch, res_bytes, res_sha, res_path, res_created, res_raw) =
            self.spill(response, inline_cap, archive_dir)?;
        let mut created_files = Vec::new();
        if req_created {
            created_files.extend(req_path.clone());
        }
        if res_created {
            created_files.extend(res_path.clone());
        }
        let mut conn = self.lock()?;
        let result = conn.immediate_transaction(|conn| -> Result<()> {
            if let (Some(sha), Some(path)) = (&req_arch, &req_path) {
                insert_archive_row_conn(&mut *conn, sha, &session, req_bytes, path, None)?;
            }
            if let (Some(sha), Some(path)) = (&res_arch, &res_path) {
                insert_archive_row_conn(&mut *conn, sha, &session, res_bytes, path, None)?;
            }
            let hook_session = hook_fields
                .map(|f| hook_fields::upsert(&mut *conn, f))
                .transpose()?;
            diesel::insert_into(call_io::table)
                .values((
                    call_io::call_id.eq(call_id),
                    call_io::request_bytes.eq(req_bytes),
                    call_io::response_bytes.eq(res_bytes),
                    call_io::request_sha256.eq(req_sha.as_deref()),
                    call_io::response_sha256.eq(res_sha.as_deref()),
                    call_io::request_json.eq(req_json.as_deref()),
                    call_io::response_json.eq(res_json.as_deref()),
                    call_io::request_archive.eq(req_arch.as_deref()),
                    call_io::response_archive.eq(res_arch.as_deref()),
                    call_io::request_raw.eq(req_raw.as_deref()),
                    call_io::response_raw.eq(res_raw.as_deref()),
                    call_io::hook_session_id.eq(hook_session),
                ))
                .execute(&mut *conn)?;
            Ok(())
        });
        if result.is_err() {
            for p in &created_files {
                // Another writer may have committed a row for the same sha after our write.
                let sha = p.file_name().map(|f| f.to_string_lossy().into_owned());
                let referenced = archive::table
                    .filter(archive::id.eq(sha.unwrap_or_default()))
                    .count()
                    .get_result::<i64>(&mut *conn)
                    .map_or(true, |n| n > 0);
                if !referenced {
                    let _ = std::fs::remove_file(p);
                }
            }
        }
        result
    }

    /// Stages a body for `insert_call_io`: over `cap` it is written to `archive_dir` right
    /// away (files live outside SQLite), but its `archive` row is left to the caller's
    /// transaction. The last two fields are `Some`/`true` only when this call's write
    /// created the file, so a failed transaction knows which files are safe to remove.
    fn spill(&self, body: Option<&[u8]>, cap: usize, archive_dir: Option<&Path>) -> Result<Spill> {
        let Some(body) = body else {
            return Ok((None, None, 0, None, None, false, None));
        };
        let n = i64::try_from(body.len()).unwrap_or(i64::MAX);
        if body.len() <= cap {
            let (text, sha, raw) = inline_body(body);
            return Ok((Some(text), None, n, Some(sha), None, false, raw));
        }
        // Over cap: metadata always. Archive only when a directory is supplied (never on
        // hook) — T201: without one, the sha is never written or expanded from anywhere,
        // so the hash itself is skipped too rather than paying a full pass over a body the
        // hook path can only ever throw away. The archive file already holds the exact
        // bytes, so no `raw` column is needed here.
        let Some(dir) = archive_dir else {
            return Ok((None, None, n, None, None, false, None));
        };
        let sha = hex_sha256(body);
        let (path, created) = write_archive_file(dir, &sha, body)?;
        Ok((
            None,
            Some(sha.clone()),
            n,
            Some(sha),
            Some(path),
            created,
            None,
        ))
    }

    /// Write `body` to `dir/<sha256>` and upsert the `archive` row. Returns the id.
    pub fn put_archive(&self, session: &str, body: &[u8], dir: &Path) -> Result<String> {
        let sha = hex_sha256(body);
        self.write_archive(session, body, &sha, dir, None)?;
        Ok(sha)
    }

    /// [`Self::put_archive`], tagged with the context window that wrote it (T127): a
    /// sub-agent's `agent_id`, or `None` for the main window. The first (session, context)
    /// pair to archive a given body owns the row — the same "other writers never dedup
    /// content they did not archive themselves" tradeoff [`Self::archive_in_session`] already
    /// makes across sessions, now also made across contexts within one session.
    pub fn put_archive_for(
        &self,
        session: &str,
        body: &[u8],
        dir: &Path,
        agent_id: Option<&str>,
    ) -> Result<String> {
        let sha = hex_sha256(body);
        self.write_archive(session, body, &sha, dir, agent_id)?;
        Ok(sha)
    }

    /// T65.1: one PK lookup on `archive.id` (= sha256) scoped to `session` and, since T127,
    /// to `agent_id` — the sub-agent's context window, or `None` for the main one. A body
    /// the row's own writer never saw in its context returns no hit, so the caller prints
    /// the body it actually has rather than a pointer to bytes it never received.
    /// `turns` is later `measurements` in that session (a proxy for "N turns ago"); 0 if none.
    pub fn archive_in_session(
        &self,
        session: &str,
        sha: &str,
        agent_id: Option<&str>,
    ) -> Result<Option<(String, u64)>> {
        let mut conn = self.lock()?;
        // Correlated subquery: turns is later measurements in archive's own session, as a
        // scalar column on the archive row — `.single_value()` keeps it one query.
        let turns = measurements::table
            .filter(measurements::session.eq(archive::session))
            .filter(measurements::ts.gt(archive::ts))
            .count()
            .single_value();
        let query = archive::table
            .filter(archive::id.eq(sha))
            .filter(archive::session.eq(session))
            .select((archive::id, turns))
            .into_boxed();
        // SQL `= NULL` never matches, so the "main window" side needs `IS NULL` instead.
        let query = match agent_id {
            Some(a) => query.filter(archive::agent_id.eq(a.to_owned())),
            None => query.filter(archive::agent_id.is_null()),
        };
        let row: Option<(String, Option<i64>)> = query.first(&mut *conn).optional()?;
        Ok(row.map(|(id, turns)| (id, turns.unwrap_or(0).max(0) as u64)))
    }

    /// The one archive write behind [`Self::put_archive`]: the body under its sha256 in
    /// `dir`, then one row per distinct body (the same body twice — T5.3 repeat requests —
    /// is one row). `tool` stays NULL: neither caller knows which plugin archived, and the
    /// column used to say `cmd` for every plugin. `call_io` spills stage the file the same
    /// way ([`write_archive_file`]) but insert the row inside their own transaction (T208).
    fn write_archive(
        &self,
        session: &str,
        body: &[u8],
        sha: &str,
        dir: &Path,
        agent_id: Option<&str>,
    ) -> Result<()> {
        let (path, created) = write_archive_file(dir, sha, body)?;
        let n = i64::try_from(body.len()).unwrap_or(i64::MAX);
        let mut conn = self.lock()?;
        let result = insert_archive_row_conn(&mut conn, sha, session, n, &path, agent_id);
        if result.is_err() && created {
            let _ = std::fs::remove_file(&path);
        }
        result
    }

    /// T5.3: the persisted decision for this `tool_use_id`, scoped to `session`.
    ///
    /// Scoping is what keeps one session's pointer out of another's context: a decision is
    /// an `(archive id, pointer)` pair cut from the payload it replaced, so replaying a
    /// foreign one overwrites a live tool result with unrelated head/tail lines — and the
    /// payload it overwrote was never archived, so D4 has nothing to expand.
    pub fn archive_decision(
        &self,
        session: &str,
        tool_use_id: &str,
    ) -> Result<Option<ArchiveDecision>> {
        let mut conn = self.lock()?;
        let row: Option<(String, String, bool)> = archive_decisions::table
            .filter(archive_decisions::session.eq(session))
            .filter(archive_decisions::tool_use_id.eq(tool_use_id))
            .select((
                archive_decisions::archive_id,
                archive_decisions::pointer,
                archive_decisions::expanded_ts.is_not_null(),
            ))
            .first(&mut *conn)
            .optional()?;
        Ok(row.map(|(archive_id, pointer, expanded)| ArchiveDecision {
            archive_id,
            pointer,
            expanded,
        }))
    }

    /// T5.3: persist a decision. First writer wins — the pointer must never change.
    pub fn put_archive_decision(
        &self,
        tool_use_id: &str,
        archive_id: &str,
        session: &str,
        pointer: &str,
    ) -> Result<()> {
        let mut conn = self.lock()?;
        diesel::insert_or_ignore_into(archive_decisions::table)
            .values((
                archive_decisions::tool_use_id.eq(tool_use_id),
                archive_decisions::archive_id.eq(archive_id),
                archive_decisions::session.eq(session),
                archive_decisions::pointer.eq(pointer),
            ))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// This session's archived tool results still in the live window, newest first (T58.2),
    /// each archive id once (T306). Ordered by `archive_decisions::ts` — when *this
    /// session's* pointer to the body was created — not `archive::ts`: archive rows dedupe
    /// by sha256 and are never re-stamped, so a body re-archived unchanged (e.g. an
    /// unchanged file re-read) would rank by its first-ever archive time and could be
    /// dropped by the checkpoint budget as if stale, even though the pointer to it is fresh.
    /// `tool_use_id` breaks ties deterministically when two decisions land in the same
    /// second. The same archive id can also back two decisions in one session; keep only
    /// the newest.
    pub fn session_live_archives(&self, session: &str) -> Result<Vec<(String, String, i64)>> {
        let mut conn = self.lock()?;
        let rows: Vec<(String, Option<String>, i64)> = archive_decisions::table
            .inner_join(archive::table)
            .filter(archive_decisions::session.eq(session))
            .order((
                archive_decisions::ts.desc(),
                archive_decisions::tool_use_id.desc(),
            ))
            .select((archive::id, archive::tool, archive::bytes))
            .load(&mut *conn)?;
        let mut seen = HashSet::new();
        Ok(rows
            .into_iter()
            .filter(|(id, _, _)| seen.insert(id.clone()))
            .map(|(id, tool, bytes)| {
                let tool = tool.filter(|t| !t.is_empty()).unwrap_or_else(|| "-".into());
                (id, tool, bytes)
            })
            .collect())
    }

    /// Any pointer text for one archive id (T36.2: attribute expand rows to toon vs archive;
    /// T55.11: the expander — CLI session `expand`, MCP `mcp-<pid>` — never shares a session
    /// with the proxy that wrote the decision, so the lookup is by archive id alone).
    pub fn live_zone_pointer(&self, archive_id: &str) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        archive_decisions::table
            .filter(archive_decisions::archive_id.eq(archive_id))
            .order((archive_decisions::tool_use_id, archive_decisions::session))
            .select(archive_decisions::pointer)
            .first(&mut *conn)
            .optional()
            .map_err(Into::into)
    }

    /// T5.4/T55.11: an `expand <id>` freezes every decision pointing at that archive id.
    /// The expander's session is never the writer's, so the freeze is keyed by archive id
    /// alone: every session following that pointer starts receiving the original from its
    /// next request (more tokens; never wrong bytes — the archive holds the exact payload).
    /// Returns how many decisions changed (0 = nothing pointed at the id).
    ///
    /// Prefer [`Self::mark_expanded_recorded`] when the freeze and its expand `Measurement`
    /// must commit together.
    pub fn mark_expanded(&self, archive_id: &str) -> Result<usize> {
        let mut conn = self.lock()?;
        mark_expanded_conn(&mut conn, archive_id)
    }

    /// T208: freeze the decision and insert its expand `Measurement` in one transaction — a
    /// crash or a failed insert (e.g. an overflowing `Measurement` field) rolls back the
    /// freeze too, instead of leaving the decision expanded with no ledger row and
    /// `report_expand.cost` under-counted. `m` is only recorded when something was actually
    /// frozen, matching [`Self::mark_expanded`]'s callers. Returns the freeze count.
    pub fn mark_expanded_recorded(
        &self,
        session: &str,
        archive_id: &str,
        m: &Measurement,
    ) -> Result<usize> {
        let mut conn = self.lock()?;
        conn.immediate_transaction(|conn| -> Result<usize> {
            let n = mark_expanded_conn(&mut *conn, archive_id)?;
            if n > 0 {
                insert_measurement_conn(&mut *conn, session, m, None)?;
            }
            Ok(n)
        })
    }

    /// `(decisions, expanded)` — the expand rate is the archive plugin's honesty metric (T5.4).
    pub fn archive_decision_counts(&self) -> Result<(i64, i64)> {
        let mut conn = self.lock()?;
        let total: i64 = archive_decisions::table.count().get_result(&mut *conn)?;
        let expanded: i64 = archive_decisions::table
            .filter(archive_decisions::expanded_ts.is_not_null())
            .count()
            .get_result(&mut *conn)?;
        Ok((total, expanded))
    }

    /// The exact request bytes recorded for a call: `call_io.request_raw` when present (T211
    /// — an inline body that was not valid UTF-8), else inline `request_json` (valid UTF-8,
    /// so its bytes already are the wire bytes), else the archive. A row written before T211
    /// has no `request_raw` and falls back to the lossy `request_json` text, lazily.
    /// T433: a hook body saved without its session fields comes back with them spliced in.
    pub fn call_io_request(&self, call_id: i32) -> Result<Option<Vec<u8>>> {
        // `(request_json, request_raw, request_archive, hook_sessions.fields)`.
        type RequestRow = (
            Option<String>,
            Option<Vec<u8>>,
            Option<String>,
            Option<String>,
        );
        let row: Option<RequestRow> = {
            let mut conn = self.lock()?;
            call_io::table
                .left_join(hook_sessions::table)
                .filter(call_io::call_id.eq(call_id))
                .select((
                    call_io::request_json,
                    call_io::request_raw,
                    call_io::request_archive,
                    hook_sessions::fields.nullable(),
                ))
                .first(&mut *conn)
                .optional()?
        };
        match row {
            Some((_, Some(raw), _, _)) => Ok(Some(raw)),
            Some((Some(json), None, _, fields)) => Ok(Some(
                hook_fields::rebuild(json, fields.as_deref()).into_bytes(),
            )),
            Some((None, None, Some(id), _)) => self.get_archive(&id, None),
            _ => Ok(None),
        }
    }

    /// Path and bytes for `rtok expand <id>`. `None` if the id is unknown
    /// or the payload file is gone. `dir` is the live `[core] archive_dir`;
    /// the stored path is only a fallback for rows written under an old dir.
    pub fn get_archive(&self, id: &str, dir: Option<&Path>) -> Result<Option<Vec<u8>>> {
        let mut conn = self.lock()?;
        let stored: Option<String> = archive::table
            .find(id)
            .select(archive::path)
            .first(&mut *conn)
            .optional()?;
        drop(conn);
        let Some(stored) = stored else {
            return Ok(None);
        };
        let mut paths = Vec::new();
        if let Some(d) = dir {
            paths.push(d.join(id));
        }
        let stored = PathBuf::from(stored);
        if !paths.iter().any(|p| p == &stored) {
            paths.push(stored);
        }
        for p in paths {
            match std::fs::read(&p) {
                Ok(b) => return Ok(Some(b)),
                Err(e) if e.kind() == ErrorKind::NotFound => continue,
                Err(e) => {
                    return Err(e).context(p.display().to_string()).map_err(Into::into);
                }
            }
        }
        Ok(None)
    }

    /// Size-only variant of [`Self::get_archive`] for hot paths (T55.16: the guard deny):
    /// the `archive` row's `bytes` when the payload file still exists (`dir/<id>`, else
    /// the stored path), `None` otherwise. Never reads the body.
    pub fn archive_size(&self, id: &str, dir: Option<&Path>) -> Result<Option<u64>> {
        let mut conn = self.lock()?;
        let row: Option<(i64, String)> = archive::table
            .find(id)
            .select((archive::bytes, archive::path))
            .first(&mut *conn)
            .optional()?;
        drop(conn);
        let Some((bytes, stored)) = row else {
            return Ok(None);
        };
        let mut paths = Vec::new();
        if let Some(d) = dir {
            paths.push(d.join(id));
        }
        let stored_path = PathBuf::from(stored);
        if !paths.contains(&stored_path) {
            paths.push(stored_path);
        }
        Ok(paths
            .iter()
            .find(|p| std::fs::metadata(p).is_ok())
            .map(|_| bytes.max(0) as u64))
    }
}

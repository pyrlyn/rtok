// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Store methods: read cache and recent hook inputs.

use super::*;

impl Store {
    pub fn put_read_cache(
        &self,
        session: &str,
        path: &str,
        sha256: &str,
        archive_id: Option<&str>,
    ) -> Result<()> {
        let mut conn = self.lock()?;
        // `unixepoch()` has no typed-DSL form; bind Rust's now.
        let now = i64::try_from(unix_now()).unwrap_or(i64::MAX);
        diesel::insert_into(read_cache::table)
            .values((
                read_cache::session.eq(session),
                read_cache::path.eq(path),
                read_cache::sha256.eq(sha256),
                read_cache::archive_id.eq(archive_id),
            ))
            .on_conflict((read_cache::session, read_cache::path))
            .do_update()
            .set((
                read_cache::sha256.eq(sha256),
                read_cache::ts.eq(now),
                read_cache::archive_id.eq(archive_id),
            ))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// `(archive_id, ts)` for a prior Read/Bash in this session.
    pub fn get_read_cache(
        &self,
        session: &str,
        path: &str,
    ) -> Result<Option<(Option<String>, i64)>> {
        let mut conn = self.lock()?;
        read_cache::table
            .find((session, path))
            .select((read_cache::archive_id, read_cache::ts))
            .first(&mut *conn)
            .optional()
            .map_err(Into::into)
    }

    /// Drop cache rows for `path` and `path\t…` mode/range keys (T4.4). A prefix compare, not
    /// `LIKE`: `%` and `_` are ordinary file-name characters, and `LIKE` ignores ASCII case.
    pub fn clear_read_cache(&self, session: &str, path: &str) -> Result<()> {
        let mut conn = self.lock()?;
        let keyed = format!("{path}\t");
        let keyed_len = i32::try_from(keyed.chars().count()).unwrap_or(i32::MAX);
        diesel::delete(
            read_cache::table
                .filter(read_cache::session.eq(session))
                .filter(
                    read_cache::path
                        .eq(path)
                        .or(substr(read_cache::path, 1, keyed_len).eq(keyed)),
                ),
        )
        .execute(&mut *conn)?;
        Ok(())
    }

    /// Request bodies of the newest `limit` hook calls in this session (T4.6
    /// edit window: a PreToolUse(Read) checks them for a recent Edit|Write).
    /// The live call's own row has no `call_io` yet, so it never matches itself.
    /// The newest `limit` hook bodies for this session, as JSON where the body was kept.
    ///
    /// A row whose body exceeded `core.call_io_inline_bytes` comes back as `""` rather than
    /// being left out: the caller (`read`'s edit window) must see that *something* happened
    /// it cannot read and fail open, instead of concluding no edit happened. Dropping these
    /// rows is what made a 70 KiB `Write` followed by a native `Read` of the same file end in
    /// a deny.
    pub fn recent_hook_inputs(&self, session: &str, limit: i64) -> Result<Vec<String>> {
        let mut conn = self.lock()?;
        let rows: Vec<HookInputRow> = calls::table
            .inner_join(call_io::table.left_join(hook_sessions::table))
            .filter(calls::session_id.eq(session))
            .filter(calls::kind.eq("hook"))
            .order(calls::id.desc())
            .limit(limit)
            .select((call_io::request_json, hook_sessions::fields.nullable()))
            .load(&mut *conn)?;
        Ok(rebuild_hook_inputs(rows))
    }

    /// Like [`Store::recent_hook_inputs`], but filtered to rows whose `hook_event_name` is
    /// `event` (T202). `record_call` (`src/hooks/mod.rs`) already stores that name in
    /// `calls.name`, so the filter is a `WHERE` on an existing column, not a JSON re-scan:
    /// callers that only care about `PostToolUse` or `PreToolUse` rows no longer fetch and
    /// parse every other event type to find them.
    pub fn recent_hook_inputs_for_event(
        &self,
        session: &str,
        event: &str,
        limit: i64,
    ) -> Result<Vec<String>> {
        let mut conn = self.lock()?;
        let rows: Vec<HookInputRow> = calls::table
            .inner_join(call_io::table.left_join(hook_sessions::table))
            .filter(calls::session_id.eq(session))
            .filter(calls::kind.eq("hook"))
            .filter(calls::name.eq(event))
            .order(calls::id.desc())
            .limit(limit)
            .select((call_io::request_json, hook_sessions::fields.nullable()))
            .load(&mut *conn)?;
        Ok(rebuild_hook_inputs(rows))
    }

    /// Hook/call rows in this session at or after `ts` (window for `guard`).
    pub fn calls_since(&self, session: &str, ts: i64) -> Result<i64> {
        let mut conn = self.lock()?;
        Ok(calls::table
            .filter(calls::session_id.eq(session))
            .filter(calls::ts.ge(ts))
            .count()
            .get_result(&mut *conn)?)
    }
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T433: the session fields every hook stdin repeats are saved once per distinct value set in
//! `hook_sessions`; `call_io.request_json` keeps only the event's own fields. Readers splice
//! the two objects back together as text, so a read never reparses either side.

use anyhow::Result;
use diesel::prelude::*;
use diesel::sqlite::SqliteConnection;
use serde_json::{Map, Value};

use super::Store;
use super::schema::{call_io, hook_sessions};

/// The fields a host sends with every hook event of one session (or sub-agent).
const SESSION_KEYS: [&str; 8] = [
    "session_id",
    "transcript_path",
    "cwd",
    "scratchpad_dir",
    "permission_mode",
    "effort",
    "agent_id",
    "agent_type",
];

/// `stdin` cleaned like [`crate::sanitize::body`] and split into the event's own fields and
/// the session fields, both compact JSON objects. `None` when it is not a JSON object or
/// carries no session field: the caller then saves it whole, as before T433.
pub(crate) fn split(stdin: &[u8]) -> Option<(Vec<u8>, String)> {
    let mut event: Value = serde_json::from_slice(stdin).ok()?;
    let map = event.as_object_mut()?;
    let fields: Map<String, Value> = SESSION_KEYS
        .iter()
        .filter_map(|k| map.remove(*k).map(|v| ((*k).to_owned(), v)))
        .collect();
    if fields.is_empty() {
        return None;
    }
    let mut fields = Value::Object(fields);
    crate::sanitize::strings(&mut event, crate::sanitize::text);
    crate::sanitize::strings(&mut fields, crate::sanitize::text);
    // serde_json's map is sorted, so one value set always serializes to the same text — the
    // `UNIQUE` key that makes it one row.
    Some((
        serde_json::to_vec(&event).ok()?,
        serde_json::to_string(&fields).ok()?,
    ))
}

/// The full stdin from a saved event body and its session fields. Both are objects
/// `serde_json` wrote compactly with disjoint keys, so splicing their members is valid JSON
/// without a parse; anything else (never written by [`split`]) comes back unchanged.
pub(crate) fn rebuild(body: String, fields: Option<&str>) -> String {
    let inner = |s: &str| s.strip_prefix('{')?.strip_suffix('}').map(str::to_owned);
    let (Some(f), Some(b)) = (fields.and_then(inner), inner(&body)) else {
        return body;
    };
    let sep = if f.is_empty() || b.is_empty() {
        ""
    } else {
        ","
    };
    format!("{{{f}{sep}{b}}}")
}

/// The `hook_sessions` id for `fields`, inserted on first sight. Runs inside the caller's
/// `call_io` transaction, so a new session costs one indexed insert and one lookup, never a
/// second lock.
pub(super) fn upsert(conn: &mut SqliteConnection, fields: &str) -> QueryResult<i32> {
    diesel::insert_into(hook_sessions::table)
        .values(hook_sessions::fields.eq(fields))
        .on_conflict(hook_sessions::fields)
        .do_nothing()
        .execute(conn)?;
    hook_sessions::table
        .filter(hook_sessions::fields.eq(fields))
        .select(hook_sessions::id)
        .first(conn)
}

/// Retention: drop the session rows no `call_io` row points at any more (their calls were
/// purged or their bodies cleared). Returns how many went.
pub(super) fn drop_orphans(conn: &mut SqliteConnection) -> QueryResult<usize> {
    let used = call_io::table
        .filter(call_io::hook_session_id.is_not_null())
        .select(call_io::hook_session_id.assume_not_null());
    diesel::delete(hook_sessions::table.filter(hook_sessions::id.ne_all(used))).execute(conn)
}

impl Store {
    /// [`Store::insert_call_io`] for a hook: the stdin's session fields go to `hook_sessions`
    /// and the saved request keeps only the event's fields. `[core] store_raw`, a body over
    /// `inline_cap` (never saved on the hook path) or one [`split`] declines saves as before.
    pub fn insert_hook_call_io(
        &self,
        call_id: i32,
        stdin: &[u8],
        response: Option<&[u8]>,
        inline_cap: usize,
    ) -> Result<()> {
        let raw = self.store_raw.load(std::sync::atomic::Ordering::Relaxed);
        let split = match raw || stdin.len() > inline_cap {
            true => None,
            false => split(stdin),
        };
        match split {
            Some((event, fields)) => self.write_call_io(
                call_id,
                Some(&event),
                Some(&fields),
                response,
                inline_cap,
                None,
            ),
            None => self.insert_call_io(call_id, Some(stdin), response, inline_cap, None),
        }
    }

    #[cfg(test)]
    pub(crate) fn hook_session_count(&self) -> Result<i64> {
        let mut conn = self.lock()?;
        Ok(hook_sessions::table.count().get_result(&mut *conn)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_then_rebuild_is_the_same_json() {
        let stdin = br#"{"session_id":"s","cwd":"/r","hook_event_name":"PreToolUse","tool_input":{"cwd":"x"}}"#;
        let (event, fields) = split(stdin).unwrap();
        assert_eq!(
            String::from_utf8(event.clone()).unwrap(),
            r#"{"hook_event_name":"PreToolUse","tool_input":{"cwd":"x"}}"#
        );
        assert_eq!(fields, r#"{"cwd":"/r","session_id":"s"}"#);
        let back = rebuild(String::from_utf8(event).unwrap(), Some(&fields));
        let want: Value = serde_json::from_slice(stdin).unwrap();
        assert_eq!(serde_json::from_str::<Value>(&back).unwrap(), want);
    }

    #[test]
    fn split_declines_bodies_without_session_fields() {
        assert!(split(br#"{"hook_event_name":"Stop"}"#).is_none());
        assert!(split(b"[1]").is_none());
        assert!(split(b"not json").is_none());
    }

    fn hook_call(s: &Store, event: &str) -> i32 {
        s.insert_call("s1", "hook", "hook", None, None, None, None, Some(event))
            .unwrap()
    }

    fn json(bytes: &[u8]) -> Value {
        serde_json::from_slice(bytes).unwrap()
    }

    const PRE: &str = r#"{"session_id":"s1","transcript_path":"/t.jsonl","cwd":"/r","permission_mode":"default","hook_event_name":"PreToolUse","tool_name":"Read","tool_input":{"file_path":"/r/a"}}"#;
    const POST: &str = r#"{"session_id":"s1","transcript_path":"/t.jsonl","cwd":"/r","permission_mode":"default","hook_event_name":"PostToolUse","tool_name":"Read","tool_response":"ok"}"#;

    /// The card's check: two hook calls of one session share one session row, each saved
    /// body drops the session fields, and every reader gets both full bodies back.
    #[test]
    fn two_hook_calls_share_one_session_row_and_read_back_whole() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, None, None, None).unwrap();
        let (pre, post) = (hook_call(&s, "PreToolUse"), hook_call(&s, "PostToolUse"));
        s.insert_hook_call_io(pre, PRE.as_bytes(), Some(b"{}"), 65536)
            .unwrap();
        s.insert_hook_call_io(post, POST.as_bytes(), Some(b"{}"), 65536)
            .unwrap();
        assert_eq!(s.hook_session_count().unwrap(), 1);
        let saved: Vec<String> = {
            let mut conn = s.lock().unwrap();
            call_io::table
                .order(call_io::call_id.asc())
                .select(call_io::request_json.assume_not_null())
                .load(&mut *conn)
                .unwrap()
        };
        assert!(
            saved.iter().all(|b| !b.contains("transcript_path")),
            "{saved:?}"
        );
        for (id, want) in [(pre, PRE), (post, POST)] {
            let got = s.call_io_request(id).unwrap().unwrap();
            assert_eq!(json(&got), json(want.as_bytes()));
        }
        let recent = s.recent_hook_inputs("s1", 10).unwrap();
        assert_eq!(json(recent[0].as_bytes()), json(POST.as_bytes()));
        assert_eq!(json(recent[1].as_bytes()), json(PRE.as_bytes()));
        let only_pre = s
            .recent_hook_inputs_for_event("s1", "PreToolUse", 10)
            .unwrap();
        assert_eq!(json(only_pre[0].as_bytes()), json(PRE.as_bytes()));
        let calls = s.calls_after(0, 10).unwrap();
        let detail = s.call_detail(&calls[0]).unwrap();
        let otel = detail.io.unwrap().request_json.unwrap();
        assert_eq!(json(otel.as_bytes()), json(PRE.as_bytes()));
    }

    /// `[core] store_raw` keeps the stdin byte for byte; a row saved before T433 (or by any
    /// other writer) keeps its full body and reads back unchanged.
    #[test]
    fn store_raw_and_old_rows_keep_the_full_body() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, None, None, None).unwrap();
        let old = hook_call(&s, "PreToolUse");
        s.insert_call_io(old, Some(PRE.as_bytes()), None, 65536, None)
            .unwrap();
        s.set_store_raw(true);
        let raw = hook_call(&s, "PostToolUse");
        s.insert_hook_call_io(raw, POST.as_bytes(), None, 65536)
            .unwrap();
        assert_eq!(s.hook_session_count().unwrap(), 0);
        assert_eq!(s.call_io_request(raw).unwrap().unwrap(), POST.as_bytes());
        assert_eq!(s.call_io_request(old).unwrap().unwrap(), PRE.as_bytes());
    }

    /// Retention: a session row goes once the bodies that used it are cleared; one still in
    /// use stays.
    #[test]
    fn retention_drops_unreferenced_session_rows() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, None, None, None).unwrap();
        let (old, new) = (hook_call(&s, "PreToolUse"), hook_call(&s, "PostToolUse"));
        s.insert_hook_call_io(old, PRE.as_bytes(), None, 65536)
            .unwrap();
        let sub = POST.replace(r#""cwd":"/r""#, r#""cwd":"/r","agent_id":"a1""#);
        s.insert_hook_call_io(new, sub.as_bytes(), None, 65536)
            .unwrap();
        assert_eq!(s.hook_session_count().unwrap(), 2);
        s.set_call_ts(old, 0).unwrap();
        s.run_retention(0, 1).unwrap();
        assert_eq!(s.hook_session_count().unwrap(), 1);
        assert_eq!(s.call_io_request(old).unwrap(), None);
        let got = s.call_io_request(new).unwrap().unwrap();
        assert_eq!(json(&got), json(sub.as_bytes()));
    }

    #[test]
    fn rebuild_handles_an_empty_event_and_a_missing_row() {
        assert_eq!(
            rebuild("{}".into(), Some(r#"{"session_id":"s"}"#)),
            r#"{"session_id":"s"}"#
        );
        assert_eq!(rebuild(r#"{"a":1}"#.into(), None), r#"{"a":1}"#);
    }
}

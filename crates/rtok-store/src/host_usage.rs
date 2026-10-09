// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Read-only access to a host's own session database (OpenCode and Kilo). The file is not
//! rtok's store: one connection, `mode=ro`, and only the columns the usage reader needs.

use diesel::prelude::*;
use diesel::sqlite::SqliteConnection;

use crate::Result;

diesel::table! {
    message (id) {
        id -> Text,
        session_id -> Text,
        time_created -> BigInt,
        data -> Text,
    }
}

/// Rows of `(session_id, time_created_ms, data)` from `sqlite_uri`, oldest first, with
/// `time_created >= since_ms`. The caller builds the URI (`mode=ro`); this function only
/// queries.
pub fn read_host_messages(sqlite_uri: &str, since_ms: i64) -> Result<Vec<(String, i64, String)>> {
    let mut conn = SqliteConnection::establish(sqlite_uri)?;
    message::table
        .filter(message::time_created.ge(since_ms))
        .order(message::time_created.asc())
        .select((message::session_id, message::time_created, message::data))
        .load(&mut conn)
        .map_err(Into::into)
}

/// A fixture database with the one table [`read_host_messages`] declares.
#[cfg(any(test, feature = "test-util"))]
pub fn seed_host_messages(path: &std::path::Path, rows: &[(&str, i64, &str)]) -> Result<()> {
    use diesel::connection::SimpleConnection;
    let mut conn = SqliteConnection::establish(&path.display().to_string())?;
    conn.batch_execute(
        "CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, \
         time_created INTEGER NOT NULL, data TEXT NOT NULL)",
    )?;
    for (i, (session, created, data)) in rows.iter().enumerate() {
        diesel::insert_into(message::table)
            .values((
                message::id.eq(format!("m{i}")),
                message::session_id.eq(*session),
                message::time_created.eq(*created),
                message::data.eq(*data),
            ))
            .execute(&mut conn)?;
    }
    Ok(())
}

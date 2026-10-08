// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Cached dependency docs (T471). FTS lives in `sql_ext::SearchDocs`.

use anyhow::Result;
use diesel::prelude::*;

use super::Store;
use super::schema::{doc_crates, doc_items};
use super::sql_ext;

/// One indexed item, full `docs` included so a query can measure what it considered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocHit {
    pub id: i32,
    pub path: String,
    pub kind: String,
    pub snippet: String,
    pub docs: String,
}

#[derive(Insertable)]
#[diesel(table_name = doc_items)]
struct NewDocItem<'a> {
    crate_name: &'a str,
    version: &'a str,
    path: &'a str,
    kind: &'a str,
    docs: &'a str,
}

impl Store {
    /// True when `rtok docs fetch` (or an llms index) has written this version.
    pub fn doc_cached(&self, name: &str, version: &str) -> Result<bool> {
        let mut conn = self.lock()?;
        let n: i64 = doc_crates::table
            .filter(
                doc_crates::name
                    .eq(name)
                    .and(doc_crates::version.eq(version)),
            )
            .count()
            .get_result(&mut *conn)?;
        Ok(n > 0)
    }

    /// Replace every item of one crate version. Empty `items` still marks the version cached.
    pub fn replace_docs(
        &self,
        name: &str,
        version: &str,
        sha256: &str,
        format_version: i32,
        fetched_unix: i64,
        items: &[(String, String, String)],
    ) -> Result<usize> {
        let mut conn = self.lock()?;
        conn.transaction::<_, diesel::result::Error, _>(|conn| {
            diesel::delete(
                doc_items::table.filter(
                    doc_items::crate_name
                        .eq(name)
                        .and(doc_items::version.eq(version)),
                ),
            )
            .execute(conn)?;
            diesel::delete(
                doc_crates::table.filter(
                    doc_crates::name
                        .eq(name)
                        .and(doc_crates::version.eq(version)),
                ),
            )
            .execute(conn)?;
            diesel::insert_into(doc_crates::table)
                .values((
                    doc_crates::name.eq(name),
                    doc_crates::version.eq(version),
                    doc_crates::sha256.eq(sha256),
                    doc_crates::format_version.eq(format_version),
                    doc_crates::fetched_unix.eq(fetched_unix),
                ))
                .execute(conn)?;
            // Five binds per row; stay under SQLite's default 999 variables.
            for chunk in items.chunks(150) {
                let rows: Vec<NewDocItem<'_>> = chunk
                    .iter()
                    .map(|(path, kind, docs)| NewDocItem {
                        crate_name: name,
                        version,
                        path,
                        kind,
                        docs,
                    })
                    .collect();
                diesel::insert_into(doc_items::table)
                    .values(&rows)
                    .execute(conn)?;
            }
            Ok(())
        })?;
        Ok(items.len())
    }

    /// BM25 hits for one cached version. An empty or unquotable query returns no rows.
    pub fn search_docs(
        &self,
        name: &str,
        version: &str,
        query: &str,
        snippet: u32,
        limit: u32,
    ) -> Result<Vec<DocHit>> {
        let Some(q) = super::fts_phrase_query(query) else {
            return Ok(Vec::new());
        };
        let mut conn = self.lock()?;
        let rows = sql_ext::SearchDocs {
            query: q,
            name: name.to_string(),
            version: version.to_string(),
            snippet: i32::try_from(snippet.max(1)).unwrap_or(i32::MAX),
            limit: i32::try_from(limit.max(1)).unwrap_or(5),
        }
        .load::<(i32, String, String, String, String)>(&mut *conn)?;
        Ok(rows
            .into_iter()
            .map(|(id, path, kind, snippet, docs)| DocHit {
                id,
                path,
                kind,
                snippet,
                docs,
            })
            .collect())
    }

    /// One item's path, kind and full docs.
    pub fn get_doc(&self, id: i32) -> Result<Option<(String, String, String)>> {
        let mut conn = self.lock()?;
        Ok(doc_items::table
            .filter(doc_items::id.eq(id))
            .select((doc_items::path, doc_items::kind, doc_items::docs))
            .first(&mut *conn)
            .optional()?)
    }
}

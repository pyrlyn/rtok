// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Cached rustdoc rows for the `docs` plugin (T455). Diesel only; FTS MATCH lives in sql_ext.

use anyhow::Result;
use diesel::prelude::*;

use super::schema::{doc_crates, doc_items};
use super::{Store, fts_phrase_query, sql_ext};

/// One FTS hit: id, path, kind, snippet (same shape `docs_query` prints).
#[derive(Debug, Clone)]
pub struct DocHit {
    pub id: i32,
    pub path: String,
    pub kind: String,
    pub snippet: String,
}

/// Full cached item for `docs_get`.
#[derive(Debug, Clone)]
pub struct DocItem {
    pub id: i32,
    pub crate_name: String,
    pub version: String,
    pub path: String,
    pub kind: String,
    pub docs: String,
}

impl Store {
    pub fn doc_crate_cached(&self, name: &str, version: &str) -> Result<bool> {
        let mut conn = self.lock()?;
        let n: i64 = doc_crates::table
            .filter(doc_crates::name.eq(name))
            .filter(doc_crates::version.eq(version))
            .count()
            .get_result(&mut *conn)?;
        Ok(n > 0)
    }

    /// FTS5 search, BM25 order, `snippet_chars` snippets. Follows `search_notes`:
    /// `fts_phrase_query` quotes every token; nothing quotable → no hits, not an error.
    pub fn search_doc_items(
        &self,
        crate_name: &str,
        version: &str,
        query: &str,
        limit: u32,
        snippet_chars: u32,
    ) -> Result<Vec<DocHit>> {
        let Some(q) = fts_phrase_query(query) else {
            return Ok(Vec::new());
        };
        let mut conn = self.lock()?;
        let hits = sql_ext::SearchDocItems {
            query: q,
            crate_name: crate_name.to_string(),
            version: version.to_string(),
            snippet: i32::try_from(snippet_chars).unwrap_or(400),
            limit: i32::try_from(limit).unwrap_or(5),
        }
        .load::<(i32, String, String, String)>(&mut *conn)?
        .into_iter()
        .map(|(id, path, kind, snippet)| DocHit {
            id,
            path,
            kind,
            snippet,
        })
        .collect();
        Ok(hits)
    }

    pub fn get_doc_item(&self, id: i32) -> Result<Option<DocItem>> {
        let mut conn = self.lock()?;
        doc_items::table
            .find(id)
            .select((
                doc_items::id,
                doc_items::crate_name,
                doc_items::version,
                doc_items::path,
                doc_items::kind,
                doc_items::docs,
            ))
            .first::<(i32, String, String, String, String, String)>(&mut *conn)
            .optional()
            .map(|row| {
                row.map(|(id, crate_name, version, path, kind, docs)| DocItem {
                    id,
                    crate_name,
                    version,
                    path,
                    kind,
                    docs,
                })
            })
            .map_err(Into::into)
    }

    /// Bytes of `docs` for the FTS hits that were considered (before a token-budget trim).
    pub fn doc_items_docs_len(&self, ids: &[i32]) -> Result<u64> {
        if ids.is_empty() {
            return Ok(0);
        }
        let mut conn = self.lock()?;
        let rows: Vec<String> = doc_items::table
            .filter(doc_items::id.eq_any(ids))
            .select(doc_items::docs)
            .load(&mut *conn)?;
        Ok(rows.iter().map(|d| d.len() as u64).sum())
    }

    /// Replace the cached crate row and its items in one transaction.
    pub fn replace_doc_crate(
        &self,
        name: &str,
        version: &str,
        sha256: &str,
        format_version: i32,
        fetched_unix: i64,
        items: &[(String, String, String)],
    ) -> Result<usize> {
        let mut conn = self.lock()?;
        conn.transaction(|conn| {
            diesel::delete(
                doc_items::table
                    .filter(doc_items::crate_name.eq(name))
                    .filter(doc_items::version.eq(version)),
            )
            .execute(conn)?;
            diesel::delete(
                doc_crates::table
                    .filter(doc_crates::name.eq(name))
                    .filter(doc_crates::version.eq(version)),
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
            let mut n = 0usize;
            for (path, kind, docs) in items {
                diesel::insert_into(doc_items::table)
                    .values((
                        doc_items::crate_name.eq(name),
                        doc_items::version.eq(version),
                        doc_items::path.eq(path),
                        doc_items::kind.eq(kind),
                        doc_items::docs.eq(docs),
                    ))
                    .execute(conn)?;
                n += 1;
            }
            Ok(n)
        })
    }
}

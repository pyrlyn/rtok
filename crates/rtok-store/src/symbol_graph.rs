// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.14: the reads the graph page's drill-down builds from. Each is one scan of a root (or a
//! batch of names), so the page's nodes and edges come from the index without a query per symbol.

use std::collections::{HashMap, HashSet};

use diesel::dsl::count_star;
use diesel::prelude::*;

use super::Store;
use super::schema::symbols;
use super::symbols::{file_key, import_segs};
use crate::Result;

/// SQLite's default bind limit is 999; a name batch stays well under it.
const NAME_CHUNK: usize = 500;

const DEF_COLUMNS: (
    symbols::path,
    symbols::name,
    symbols::kind,
    symbols::line,
    symbols::end_line,
    symbols::signature,
    symbols::content_hash,
    symbols::start_byte,
    symbols::end_byte,
) = (
    symbols::path,
    symbols::name,
    symbols::kind,
    symbols::line,
    symbols::end_line,
    symbols::signature,
    symbols::content_hash,
    symbols::start_byte,
    symbols::end_byte,
);

/// One definition: where it is and the line that declares it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DefRow {
    pub path: String,
    pub name: String,
    pub kind: String,
    pub line: i32,
    pub end_line: i32,
    pub signature: String,
    /// Sha256 of the definition's bytes (T329.18 compares it between two trees).
    pub content_hash: String,
    pub start_byte: i64,
    pub end_byte: i64,
}

type DefTuple = (String, String, String, i32, i32, String, String, i64, i64);

impl From<DefTuple> for DefRow {
    fn from(
        (path, name, kind, line, end_line, signature, content_hash, start_byte, end_byte): DefTuple,
    ) -> Self {
        Self {
            path,
            name,
            kind,
            line,
            end_line,
            signature,
            content_hash,
            start_byte,
            end_byte,
        }
    }
}

/// References grouped by the definition that holds them: `scope` is that definition's name, empty
/// at file level.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RefGroup {
    pub path: String,
    pub scope: String,
    pub name: String,
    pub kind: String,
    pub count: i64,
}

impl Store {
    /// Every named definition of `root`, ordered by path then line.
    pub fn symbol_def_scan(&self, root: &str) -> Result<Vec<DefRow>> {
        let mut conn = self.lock()?;
        let rows: Vec<DefTuple> = symbols::table
            .filter(symbols::root.eq(root))
            .filter(symbols::is_def.eq(1))
            .filter(symbols::name.ne(""))
            .order((symbols::path.asc(), symbols::line.asc()))
            .select(DEF_COLUMNS)
            .load(&mut *conn)?;
        Ok(rows.into_iter().map(DefRow::from).collect())
    }

    /// Non-import references of `root` grouped by `(path, scope, name, kind)`.
    pub fn symbol_ref_scan(&self, root: &str) -> Result<Vec<RefGroup>> {
        let mut conn = self.lock()?;
        let rows: Vec<(String, String, String, String, i64)> = symbols::table
            .filter(symbols::root.eq(root))
            .filter(symbols::is_def.eq(0))
            .filter(symbols::kind.ne("import"))
            .filter(symbols::name.ne(""))
            .group_by((symbols::path, symbols::scope, symbols::name, symbols::kind))
            .select((
                symbols::path,
                symbols::scope,
                symbols::name,
                symbols::kind,
                count_star(),
            ))
            .order((
                symbols::path.asc(),
                symbols::scope.asc(),
                symbols::name.asc(),
            ))
            .load(&mut *conn)?;
        Ok(rows
            .into_iter()
            .map(|(path, scope, name, kind, count)| RefGroup {
                path,
                scope,
                name,
                kind,
                count,
            })
            .collect())
    }

    /// `(importing file, imported file)` pairs. An import specifier names the file whose path
    /// segments are its longest prefix (the rule `symbol_imported_defs` uses), so `a::b::C` points at
    /// `a/b.rs` and not also at `a/mod.rs`.
    pub fn symbol_import_edges(&self, root: &str) -> Result<Vec<(String, String)>> {
        let mut conn = self.lock()?;
        let files: Vec<String> = symbols::table
            .filter(symbols::root.eq(root))
            .filter(symbols::is_def.eq(1))
            .select(symbols::path)
            .distinct()
            .load(&mut *conn)?;
        let imports: Vec<(String, String)> = symbols::table
            .filter(symbols::root.eq(root))
            .filter(symbols::kind.eq("import"))
            .filter(symbols::is_def.eq(0))
            .filter(symbols::scope.ne(""))
            .select((symbols::path, symbols::scope))
            .distinct()
            .load(&mut *conn)?;
        drop(conn);
        let mut by_key: HashMap<Vec<String>, Vec<&str>> = HashMap::new();
        for f in &files {
            let key = file_key(f);
            if !key.is_empty() {
                by_key.entry(key).or_default().push(f);
            }
        }
        let mut out = HashSet::new();
        for (from, spec) in &imports {
            // A bare identifier is a name, not a path.
            if !spec.contains([':', '/', '.', '\\']) {
                continue;
            }
            let segs = import_segs(spec);
            let hit = (1..=segs.len())
                .rev()
                .find_map(|k| by_key.get(&segs[..k]).filter(|v| !v.is_empty()));
            for to in hit.into_iter().flatten().filter(|to| **to != from.as_str()) {
                out.insert((from.clone(), (*to).to_string()));
            }
        }
        let mut out: Vec<_> = out.into_iter().collect();
        out.sort();
        Ok(out)
    }

    /// Definitions of any of `names`, for resolving a call into a linked project.
    pub fn symbol_defs_named(&self, root: &str, names: &[String]) -> Result<Vec<DefRow>> {
        let mut conn = self.lock()?;
        let mut out = Vec::new();
        for chunk in names.chunks(NAME_CHUNK) {
            let rows: Vec<DefTuple> = symbols::table
                .filter(symbols::root.eq(root))
                .filter(symbols::is_def.eq(1))
                .filter(symbols::name.eq_any(chunk))
                .select(DEF_COLUMNS)
                .load(&mut *conn)?;
            out.extend(rows.into_iter().map(DefRow::from));
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rtok_plugin_sdk::SymbolRow;

    fn def(name: &str, line: i32, end: i32) -> SymbolRow {
        SymbolRow::new(name, "function", line, true, end, "")
    }

    fn call(name: &str, line: i32, scope: &str) -> SymbolRow {
        SymbolRow::new(name, "call", line, false, line, scope)
    }

    fn import(spec: &str, line: i32) -> SymbolRow {
        SymbolRow::new("X", "import", line, false, line, spec)
    }

    #[test]
    fn scans_group_references_by_their_enclosing_definition() {
        let s = Store::open_in_memory().unwrap();
        let rows = [
            def("a", 1, 5),
            call("b", 2, "a"),
            call("b", 3, "a"),
            call("b", 9, ""),
        ];
        s.replace_symbols("/r", "x.rs", "s", (0, 0), &rows).unwrap();
        let defs = s.symbol_def_scan("/r").unwrap();
        assert_eq!((defs[0].name.as_str(), defs[0].end_line), ("a", 5));
        let refs = s.symbol_ref_scan("/r").unwrap();
        let counts: Vec<_> = refs.iter().map(|r| (r.scope.as_str(), r.count)).collect();
        assert_eq!(counts, [("", 1), ("a", 2)]);
        assert!(s.symbol_ref_scan("/other").unwrap().is_empty());
    }

    #[test]
    fn an_import_points_at_the_longest_matching_file_only() {
        let s = Store::open_in_memory().unwrap();
        s.replace_symbols("/r", "src/a/mod.rs", "1", (0, 0), &[def("m", 1, 2)])
            .unwrap();
        s.replace_symbols("/r", "src/a/b.rs", "2", (0, 0), &[def("b", 1, 2)])
            .unwrap();
        s.replace_symbols(
            "/r",
            "src/main.rs",
            "3",
            (0, 0),
            &[
                def("main", 1, 3),
                import("crate::a::b::X", 1),
                import("Y", 2),
            ],
        )
        .unwrap();
        let edges = s.symbol_import_edges("/r").unwrap();
        assert_eq!(
            edges,
            [("src/main.rs".to_string(), "src/a/b.rs".to_string())]
        );
    }

    #[test]
    fn defs_named_reads_only_the_asked_names() {
        let s = Store::open_in_memory().unwrap();
        s.replace_symbols("/r", "x.rs", "s", (0, 0), &[def("a", 1, 2), def("b", 3, 4)])
            .unwrap();
        let got = s.symbol_defs_named("/r", &["b".to_string()]).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].line, 3);
    }
}

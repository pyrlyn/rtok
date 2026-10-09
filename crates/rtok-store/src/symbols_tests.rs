// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::*;

fn row(name: &str, line: i32, is_def: bool) -> SymbolRow {
    SymbolRow::new(name, "function", line, is_def, line, "")
}

fn import(name: &str, line: i32) -> SymbolRow {
    SymbolRow::new(name, "import", line, false, line, "")
}

/// A reference row with an explicit enclosing `scope`, unlike `row`/`import` (always
/// `scope: ""`) — needed to build the `symbol_impact` chains below.
fn reference(name: &str, line: i32, scope: &str) -> SymbolRow {
    SymbolRow::new(name, "function", line, false, line, scope)
}

#[test]
fn top_refs_rank_by_count_then_name() {
    let store = Store::open_in_memory().unwrap();
    store
        .replace_symbols(
            "/r",
            "a.rs",
            "s",
            (0, 0),
            &[
                row("foo", 1, true),
                row("bar", 2, true),
                row("aaa", 3, true),
                row("zed", 4, true),
                row("foo", 10, false),
                row("foo", 11, false),
                row("bar", 12, false),
                row("bar", 13, false),
                row("aaa", 14, false),
                import("foo", 20),
            ],
        )
        .unwrap();
    let got = store.symbol_top_refs("/r", 10).unwrap();
    assert_eq!(
        got.iter().map(|r| (r.0.as_str(), r.1)).collect::<Vec<_>>(),
        [("bar", 2), ("foo", 2), ("aaa", 1), ("zed", 0)]
    );
    assert_eq!(store.symbol_top_refs("/r", 10).unwrap(), got);
}

#[test]
fn top_refs_picks_first_def_site() {
    let store = Store::open_in_memory().unwrap();
    store
        .replace_symbols("/r", "b.rs", "s", (0, 0), &[row("dup", 5, true)])
        .unwrap();
    store
        .replace_symbols(
            "/r",
            "a.rs",
            "s",
            (0, 0),
            &[
                row("dup", 9, true),
                row("dup", 3, true),
                row("dup", 1, false),
            ],
        )
        .unwrap();
    let got = store.symbol_top_refs("/r", 4).unwrap();
    assert_eq!(got, vec![("dup".into(), 1, "a.rs".into(), 3)]);
}

// T163.1 regression (PR #206 review): `symbol_impact`'s BFS must exclude a candidate
// already on *that specific chain's* history, not just prune it from further expansion.
// Expected rows below were checked against the old `WITH RECURSIVE` query (from
// `origin/main` before this rework) run on the same fixtures via the sqlite3 CLI.

#[test]
fn impact_excludes_ref_edge_name_already_on_the_chain() {
    // a.rs: fn X references N (depth-1 row: a.rs/X, chain seen={X}).
    // b.rs: a different fn X calls X (self-recursive) — the depth-2 candidate is
    // (b.rs, X), but X is already in that chain's seen, so the old CTE never emits it
    // and no other chain reaches it either.
    let store = Store::open_in_memory().unwrap();
    store
        .replace_symbols(
            "/r1",
            "a.rs",
            "s",
            (0, 0),
            &[row("X", 1, true), reference("N", 2, "X")],
        )
        .unwrap();
    store
        .replace_symbols(
            "/r1",
            "b.rs",
            "s",
            (0, 0),
            &[row("X", 1, true), reference("X", 2, "X")],
        )
        .unwrap();
    let got = store.symbol_impact("/r1", "N", 4).unwrap();
    assert_eq!(got, vec![(1, "a.rs".into(), "X".into())]);
}

#[test]
fn impact_excludes_import_follow_name_already_on_the_chain() {
    // c.rs imports N2, which resolves to def M (depth-1 row: c.rs/M, chain seen={M}).
    // e.rs imports M and also defines M — the depth-2 candidate is (e.rs, M), but M is
    // already in that chain's seen, so the old CTE never emits it either.
    let store = Store::open_in_memory().unwrap();
    store
        .replace_symbols(
            "/r2",
            "c.rs",
            "s",
            (0, 0),
            &[import("N2", 1), row("M", 2, true)],
        )
        .unwrap();
    store
        .replace_symbols(
            "/r2",
            "e.rs",
            "s",
            (0, 0),
            &[import("M", 1), row("M", 2, true)],
        )
        .unwrap();
    let got = store.symbol_impact("/r2", "N2", 4).unwrap();
    assert_eq!(got, vec![(1, "c.rs".into(), "M".into())]);
}

#[test]
fn impact_records_an_empty_out_name_but_does_not_follow_it() {
    // a.rs: a bare (unscoped) reference to N3 -- depth-1 row (a.rs, out_name ""), since
    // `impact_refs` carries the row's `scope` through unfiltered and `row()` leaves it
    // "". The old CTE's SELECT has no `scope != ''` guard either, so this row must still
    // land in `results` -- but its `WHERE w.scope != ''` guard stops it from ever being
    // used as a `w.tip` for the next level.
    // b.rs: an import with an empty `name` next to a real def LEAK. If the empty
    // out_name above were pushed as the next chain's tip, `impact_import_follow` (no
    // `i.name != ''` filter) would match this import and surface (2, b.rs, LEAK), which
    // the old CTE could never reach.
    let store = Store::open_in_memory().unwrap();
    store
        .replace_symbols("/r3", "a.rs", "s", (0, 0), &[row("N3", 2, false)])
        .unwrap();
    store
        .replace_symbols(
            "/r3",
            "b.rs",
            "s",
            (0, 0),
            &[import("", 1), row("LEAK", 2, true)],
        )
        .unwrap();
    let got = store.symbol_impact("/r3", "N3", 4).unwrap();
    assert_eq!(got, vec![(1, "a.rs".into(), "".into())]);
}

fn import_at(name: &str, line: i32, spec: &str) -> SymbolRow {
    SymbolRow::new(name, "import", line, false, line, spec)
}

#[test]
fn import_spec_matches_the_defining_file() {
    assert!(import_matches_file("crate::a::parse", "src/a.rs"));
    assert!(import_matches_file(
        "crate::plugins::toon::encode",
        "src/plugins/toon/mod.rs"
    ));
    assert!(import_matches_file(
        "rtok_sys::unlock",
        "crates/rtok-sys/src/lib.rs"
    ));
    assert!(import_matches_file(
        "rtok_mcp::spec::Format",
        "crates/rtok-mcp/src/spec.rs"
    ));
    assert!(!import_matches_file("crate::a::parse", "src/b.rs"));
    assert!(!import_matches_file(
        "figment::providers::Format",
        "src/doctor/hooks.rs"
    ));
    assert!(!import_matches_file("parse", "src/a.rs"));
}

#[test]
fn imported_defs_are_the_candidate_files_a_reference_imports() {
    let store = Store::open_in_memory().unwrap();
    store
        .replace_symbols("/r", "src/a.rs", "s", (0, 0), &[row("parse", 1, true)])
        .unwrap();
    store
        .replace_symbols("/r", "src/b.rs", "s", (0, 0), &[row("parse", 1, true)])
        .unwrap();
    store
        .replace_symbols(
            "/r",
            "src/c.rs",
            "s",
            (0, 0),
            &[
                import_at("parse", 1, "crate::a::parse"),
                reference("parse", 3, "go"),
            ],
        )
        .unwrap();
    assert_eq!(
        store.symbol_imported_defs("/r", "parse").unwrap(),
        vec![("src/c.rs".into(), "src/a.rs".into())]
    );
    store.rebuild_symbol_idf("/r").unwrap();
    assert_eq!(store.symbol_name_freq("/r", "parse").unwrap(), (1, 3));
    assert_eq!(store.symbol_name_freq("/r", "missing").unwrap(), (0, 3));
}

fn documented(name: &str, line: i32, signature: &str, doc: &str) -> SymbolRow {
    SymbolRow {
        signature: signature.into(),
        doc: doc.into(),
        ..SymbolRow::new(name, "function", line, true, line, "")
    }
}

/// T474: a name that contains a query token outranks a signature hit, which outranks a doc hit.
#[test]
fn symbol_fts_ranks_a_name_token_above_a_doc_hit() {
    let store = Store::open_in_memory().unwrap();
    store
        .replace_symbols(
            "/r",
            "a.rs",
            "s",
            (0, 0),
            &[
                documented(
                    "body_lines",
                    1,
                    "fn body_lines() {}",
                    "Source of one definition",
                ),
                documented("mid", 2, "fn mid(source: Lines)", ""),
                documented(
                    "other",
                    3,
                    "fn other()",
                    "truncated source lines in the comment",
                ),
            ],
        )
        .unwrap();
    assert!(store.symbol_fts("/r", "", 10).unwrap().is_empty());
    let hits = store
        .symbol_fts("/r", "truncated source lines", 10)
        .unwrap();
    let names: Vec<_> = hits.iter().map(|h| h.1.as_str()).collect();
    assert_eq!(names, ["body_lines", "mid", "other"], "{names:?}");
}

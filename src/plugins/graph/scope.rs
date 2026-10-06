// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.4.1: `symbol` and `callers` over a project scope — the project asked for (or the
//! working directory's) followed by what it links to. Each project keeps its own index, so
//! the scope is a loop over the per-root queries; the answer is one text under one cap.
//! `Ctx` carries no project registry, so the scope is resolved by the caller (`mcp.rs`).

use std::path::{Path, PathBuf};

use anyhow::{Result, bail};

use rtok_plugin_sdk::Ctx;

use super::{
    Filter, Tag, ambiguous_banner, callers_filtered, cap, defs_text, flag_ambiguous, index,
    index_for, lsp, projects, stale_banner, symbol_filtered,
};
use crate::store::Store;

/// One project of a scope: the label its rows carry and the root its index is keyed by.
#[derive(Debug, Clone)]
pub struct Member {
    pub name: String,
    pub root: PathBuf,
}

/// The scope of a call. `project` is an id or path; without it the scope starts at the working
/// directory, and an unregistered directory is a scope of one.
pub fn resolve(store: &Store, project: Option<&str>, cwd: &Path) -> Result<Vec<Member>> {
    let start = match project.filter(|p| !p.is_empty()) {
        Some(target) => Some(projects::resolve(store, target)?),
        None => store.project_by_root(cwd)?,
    };
    let Some(p) = start else {
        return Ok(vec![Member {
            name: cwd
                .file_name()
                .map_or_else(String::new, |n| n.to_string_lossy().into_owned()),
            root: cwd.to_path_buf(),
        }]);
    };
    let scope = store.project_scope(p.id)?;
    if scope.is_empty() {
        bail!(
            "{} no longer exists; remove it or restore the directory",
            p.root
        );
    }
    Ok(scope
        .into_iter()
        .map(|p| Member {
            name: p.display_name().to_string(),
            root: PathBuf::from(&p.root),
        })
        .collect())
}

/// Runs `f` for each member. A failure in the first member is the caller's error; a linked
/// project that cannot answer is skipped with a note, so one broken link never hides the rest.
fn fan_out<T>(
    scope: &[Member],
    mut f: impl FnMut(&Member) -> Result<T>,
) -> Result<(Vec<(&Member, T)>, String)> {
    let (mut done, mut notes) = (Vec::new(), String::new());
    for (i, m) in scope.iter().enumerate() {
        match f(m) {
            Ok(v) => done.push((m, v)),
            Err(e) if i > 0 => notes.push_str(&format!("[{}] skipped: {e}\n", m.name)),
            Err(e) => return Err(e),
        }
    }
    Ok((done, notes))
}

/// Each answering project's stale banner, named, then the skip notes.
fn banners<T>(cx: &Ctx, done: &[(&Member, T)], notes: String) -> Result<String> {
    let mut out = String::new();
    for (m, _) in done {
        let b = stale_banner(cx, &m.root)?;
        if !b.is_empty() {
            out.push_str(&format!("[{}] {b}", m.name));
        }
    }
    Ok(out + &notes)
}

/// The LSP server answers one root, so a scope of several is cut to its first project.
fn lsp_note(scope: &[Member]) -> String {
    format!(
        "note: backend = \"lsp\" answers [{}] only; {} linked projects not searched\n",
        scope[0].name,
        scope.len() - 1
    )
}

fn label(m: &Member) -> String {
    format!("[{}] ", m.name)
}

fn walkable(m: &Member) -> Result<()> {
    crate::plugins::read::walk_root_ok(&m.root)
}

pub fn symbol(cx: &Ctx, scope: &[Member], name: &str, filter: &Filter) -> Result<String> {
    let lsp_pinned = cx.plugin_config::<crate::config::Graph>("graph").backend == "lsp";
    if let [one] = scope {
        walkable(one)?;
        return symbol_filtered(cx, &one.root, name, filter);
    }
    if lsp_pinned {
        walkable(&scope[0])?;
        let text = lsp::symbol(cx, &scope[0].root, name, filter)?;
        return Ok(text + &lsp_note(scope));
    }
    let (done, notes) = fan_out(scope, |m| {
        walkable(m)?;
        index_for(cx, &m.root)?;
        let key = index::canon(&m.root);
        let rows: Vec<_> = cx
            .symbol_defs(&key, name)?
            .into_iter()
            .filter(|(path, kind, ..)| filter.path_ok(path) && filter.kind_ok(kind))
            .collect();
        let callees = if rows.is_empty() {
            Vec::new()
        } else {
            cx.symbol_callees(&key, name)?
        };
        Ok((rows, callees))
    })?;
    let head = banners(cx, &done, notes)?;
    let found: Vec<_> = done
        .iter()
        .filter(|(_, (rows, _))| !rows.is_empty())
        .collect();
    if found.is_empty() {
        return Ok(format!(
            "{head}no definition of {name}{}",
            filter.scope_note()
        ));
    }
    let many = found.len() > 1;
    let mut body = if many {
        ambiguous_banner(1)
    } else {
        String::new()
    };
    for (m, (rows, callees)) in found {
        let tag = Tag {
            prefix: &label(m),
            suffix: if many { " ?" } else { "" },
        };
        body.push_str(&defs_text(cx, &m.root, rows, callees, &tag));
    }
    Ok(format!("{head}{}", cap(cx, body)?))
}

pub fn callers(cx: &Ctx, scope: &[Member], name: &str, filter: &Filter) -> Result<String> {
    let lsp_pinned = cx.plugin_config::<crate::config::Graph>("graph").backend == "lsp";
    if let [one] = scope {
        walkable(one)?;
        return callers_filtered(cx, &one.root, name, filter);
    }
    if lsp_pinned {
        walkable(&scope[0])?;
        let text = lsp::callers(cx, &scope[0].root, name, filter)?;
        return Ok(text + &lsp_note(scope));
    }
    let (done, notes) = fan_out(scope, |m| {
        walkable(m)?;
        index_for(cx, &m.root)?;
        let key = index::canon(&m.root);
        let rows: Vec<_> = cx
            .symbol_ref_groups(&key, name)?
            .into_iter()
            .filter(|(path, ..)| filter.path_ok(path))
            .collect();
        Ok((rows, cx.symbol_defs(&key, name)?.len()))
    })?;
    let head = banners(cx, &done, notes)?;
    let defs: usize = done.iter().map(|(_, (_, n))| n).sum();
    let mut body = String::new();
    for (m, (rows, _)) in &done {
        for (path, scope, n, line) in rows {
            let scope = if scope.is_empty() {
                String::new()
            } else {
                format!("  {scope}")
            };
            body.push_str(&format!("{}{path}{scope} ×{n} (L{line})\n", label(m)));
        }
    }
    if body.is_empty() {
        body = format!("no references to {name}{}", filter.scope_note());
        return Ok(head + &flag_ambiguous(defs, body));
    }
    Ok(format!("{head}{}", cap(cx, flag_ambiguous(defs, body))?))
}

#[cfg(test)]
mod tests {
    use super::super::index::tests::cx;
    use super::*;
    use crate::plugin::Runtime;
    use crate::store::{LinkKind, Origin};
    use std::fs;

    /// Projects `a`, `b`, `c` and `d` under one temp dir with `a` linked to `b` and `b` to `c`;
    /// `files` gives each project's `lib.rs`.
    fn world(tag: &str, files: [&str; 4]) -> (Runtime, PathBuf) {
        let (cx, dir) = cx(tag);
        let mut id = Vec::new();
        for (name, src) in ["a", "b", "c", "d"].into_iter().zip(files) {
            fs::create_dir_all(dir.join(name)).unwrap();
            fs::write(dir.join(name).join("lib.rs"), src).unwrap();
            let p = cx
                .store
                .register_project(&dir.join(name), Origin::Manual)
                .unwrap();
            id.push(p.id);
        }
        for (from, to) in [(0, 1), (1, 2)] {
            cx.store
                .link_projects(id[from], id[to], LinkKind::Manual, None)
                .unwrap();
        }
        (cx, dir)
    }

    const CALL: &str = "fn shared() {}\n";

    fn fixture(tag: &str) -> (Runtime, PathBuf) {
        world(
            tag,
            [
                "fn a_caller() { shared(); }\n",
                "fn b_caller() { shared(); }\n",
                CALL,
                "fn d_caller() { shared(); }\n",
            ],
        )
    }

    fn scope_at(cx: &Runtime, dir: &Path, cwd: &str, project: Option<&str>) -> Vec<Member> {
        resolve(&cx.store, project, &dir.join(cwd)).unwrap()
    }

    #[test]
    fn callers_of_a_function_in_c_label_the_call_sites_in_a_and_b() {
        let (cx, dir) = fixture("t3294-callers");
        let scope = scope_at(&cx, &dir, "a", None);
        assert_eq!(scope.len(), 3);
        let out = callers(&Ctx::new(&cx), &scope, "shared", &Filter::none()).unwrap();
        assert_eq!(
            out,
            "[a] lib.rs  a_caller \u{d7}1 (L1)\n[b] lib.rs  b_caller \u{d7}1 (L1)\n"
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn project_d_does_not_cross_and_a_single_project_keeps_the_plain_output() {
        let (cx, dir) = fixture("t3294-d");
        let d = dir.join("d");
        let scope = scope_at(&cx, &dir, "a", d.to_str());
        assert_eq!(scope.len(), 1);
        let out = callers(&Ctx::new(&cx), &scope, "shared", &Filter::none()).unwrap();
        assert_eq!(out, "lib.rs  d_caller \u{d7}1 (L1)\n");
        assert_eq!(
            out,
            callers_filtered(&Ctx::new(&cx), &d, "shared", &Filter::none()).unwrap()
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn no_project_from_a_equals_a_by_path_and_by_id() {
        let (cx, dir) = fixture("t3294-default");
        let a = dir.join("a");
        let id = cx
            .store
            .project_by_root(&a)
            .unwrap()
            .unwrap()
            .id
            .to_string();
        let roots = |s: Vec<Member>| s.into_iter().map(|m| m.root).collect::<Vec<_>>();
        let plain = roots(scope_at(&cx, &dir, "a", None));
        assert_eq!(plain, roots(scope_at(&cx, &dir, "d", a.to_str())));
        assert_eq!(plain, roots(scope_at(&cx, &dir, "d", Some(&id))));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn an_unregistered_directory_is_a_scope_of_one_and_an_unknown_project_errors() {
        let (cx, dir) = fixture("t3294-unreg");
        let loose = dir.join("loose");
        fs::create_dir_all(&loose).unwrap();
        let scope = resolve(&cx.store, None, &loose).unwrap();
        assert_eq!((scope.len(), scope[0].root.as_path()), (1, loose.as_path()));
        let err = resolve(&cx.store, Some("9999"), &loose).unwrap_err();
        assert!(format!("{err}").contains("no project"), "{err}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_name_defined_in_two_projects_is_grouped_flagged_and_the_selected_one_leads() {
        let (cx, dir) = world(
            "t3294-dup",
            [
                "fn dup() {}\n",
                "fn b() {}\n",
                "fn dup() {}\nfn c_caller() { dup(); }\n",
                "",
            ],
        );
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        let out = symbol(&ctx, &scope, "dup", &Filter::none()).unwrap();
        assert_eq!(
            out,
            "1 names ambiguous (?): narrow with path or kind, or backend = \"lsp\"\n\
             [a] lib.rs:1 function ?\nfn dup() {}\n[c] lib.rs:1 function ?\nfn dup() {}\n"
        );
        let out = callers(&ctx, &scope, "dup", &Filter::none()).unwrap();
        assert!(out.starts_with("1 names ambiguous (?)"), "{out}");
        assert!(out.contains("[c] lib.rs  c_caller \u{d7}1 (L2) ?"), "{out}");
        // From b only c defines it, so nothing is ambiguous.
        let scope = scope_at(&cx, &dir, "b", None);
        assert_eq!(
            symbol(&ctx, &scope, "dup", &Filter::none()).unwrap(),
            "[c] lib.rs:1 function\nfn dup() {}\n"
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn one_cap_applies_to_the_whole_scope() {
        let callers_of_shared = |n: usize| {
            (0..n)
                .map(|i| format!("fn c{i}() {{ shared(); }}\n"))
                .collect::<String>()
        };
        let (cx, dir) = world(
            "t3294-cap",
            [&callers_of_shared(300), &callers_of_shared(300), CALL, ""],
        );
        let scope = scope_at(&cx, &dir, "a", None);
        let out = callers(&Ctx::new(&cx), &scope, "shared", &Filter::none()).unwrap();
        assert!(
            out.lines().last().unwrap().contains(" more, expand "),
            "{out}"
        );
        assert!(out.contains("[a] "), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn no_hit_in_any_project_says_so_once() {
        let (cx, dir) = fixture("t3294-none");
        let scope = scope_at(&cx, &dir, "a", None);
        let ctx = Ctx::new(&cx);
        assert_eq!(
            symbol(&ctx, &scope, "nothing", &Filter::none()).unwrap(),
            "no definition of nothing"
        );
        assert_eq!(
            callers(&ctx, &scope, "nothing", &Filter::none()).unwrap(),
            "no references to nothing"
        );
        let _ = fs::remove_dir_all(dir);
    }
}

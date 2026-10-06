// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.4.1, T329.4.2: the five tools over a project scope — the project asked for (or the
//! working directory's) followed by what it links to. Each project keeps its own index, so
//! the scope is a loop over the per-root queries; the answer is one text under one cap.
//! `Ctx` carries no project registry, so the scope is resolved by the caller (`mcp.rs`).

use std::path::{Path, PathBuf};

use anyhow::{Result, bail};

use rtok_plugin_sdk::Ctx;

use super::{
    ExploreParts, Filter, Tag, TagsExplore, ambiguous_banner, assemble_explore, callers_filtered,
    cap, cap_kind, defs_text, flag_ambiguous, impact_filtered, impact_lines_text,
    impact_walk_roots, index, index_for, lsp, lsp_backend, outline_in, projects,
    reverse_call_chain, stale_banner, symbol_filtered, with_stale,
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
    let lsp_pinned = lsp_backend(cx);
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
    let lsp_pinned = lsp_backend(cx);
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

/// `impact` over the scope: one walk asked of every member's index, so a change in C reaches its
/// callers in B and, through them, A. A call chain (`to`) is still found inside one project.
pub fn impact(
    cx: &Ctx,
    scope: &[Member],
    name: &str,
    depth: u32,
    filter: &Filter,
    to: Option<&str>,
) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return impact_filtered(cx, &one.root, name, depth, filter, to);
    }
    if lsp_backend(cx) {
        walkable(&scope[0])?;
        let root = &scope[0].root;
        let text = with_stale(cx, root, lsp::impact(cx, root, name, depth, filter, to)?)?;
        return Ok(text + &lsp_note(scope));
    }
    let (done, notes) = fan_out(scope, |m| {
        walkable(m)?;
        index_for(cx, &m.root)?;
        let key = index::canon(&m.root);
        let chains = match to.filter(|t| !t.is_empty()) {
            Some(target) => cx.symbol_paths(&key, target, name, depth)?,
            None => Vec::new(),
        };
        Ok((key.clone(), chains, cx.symbol_defs(&key, name)?.len()))
    })?;
    let head = banners(cx, &done, notes)?;
    let defs: usize = done.iter().map(|(_, (.., n))| n).sum();
    let body = if let Some(target) = to.filter(|t| !t.is_empty()) {
        let mut body = String::new();
        for (m, (_, chains, _)) in &done {
            for chain in chains {
                body.push_str(&format!("{}{}\n", label(m), reverse_call_chain(chain)));
            }
        }
        if body.is_empty() {
            return Ok(format!(
                "{head}no path from {name} to {target} within depth {depth}"
            ));
        }
        body
    } else {
        let labels: Vec<String> = done.iter().map(|(m, _)| label(m)).collect();
        let keys: Vec<&str> = done.iter().map(|(_, (k, ..))| k.as_str()).collect();
        let rows = walk_rows(cx, &labels, &keys, name, depth, filter)?;
        if rows.is_empty() {
            let text = format!("nothing reaches {name}{}", filter.scope_note());
            return Ok(head + &flag_ambiguous(defs, text));
        }
        impact_lines_text(&rows)
    };
    Ok(format!("{head}{}", cap(cx, flag_ambiguous(defs, body))?))
}

/// The scoped walk's rows, the member label in front of each path; the selected project's rows
/// lead within a depth.
fn walk_rows(
    cx: &Ctx,
    labels: &[String],
    keys: &[&str],
    name: &str,
    depth: u32,
    filter: &Filter,
) -> Result<Vec<(u32, String, String)>> {
    let mut rows = impact_walk_roots(cx, keys, name, depth, true)?;
    rows.retain(|(_, _, path, _)| filter.path_ok(path));
    rows.sort_by(|a, b| (a.1, a.0, &a.2, &a.3).cmp(&(b.1, b.0, &b.2, &b.3)));
    Ok(rows
        .into_iter()
        .map(|(i, d, path, scope)| (d, format!("{}{path}", labels[i]), scope))
        .collect())
}

/// `explore` over the scope: the usual assembler, each of its four questions asked of every
/// member and the answers labelled; call paths are found inside one project.
pub fn explore(cx: &Ctx, scope: &[Member], query: &str, filter: &Filter) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return super::explore(cx, &one.root, query, filter);
    }
    if lsp_backend(cx) {
        walkable(&scope[0])?;
        let text = lsp::explore(cx, &scope[0].root, query, filter)?;
        return Ok(text + &lsp_note(scope));
    }
    let (done, notes) = fan_out(scope, |m| {
        walkable(m)?;
        index_for(cx, &m.root)
    })?;
    let head = banners(cx, &done, notes)?;
    let labels: Vec<String> = done.iter().map(|(m, _)| label(m)).collect();
    let mut all = Scoped {
        parts: done
            .iter()
            .zip(&labels)
            .map(|((m, _), label)| TagsExplore {
                cx,
                root: &m.root,
                filter,
                key: index::canon(&m.root),
                label,
            })
            .collect(),
        labels: &labels,
    };
    let (text, before) = assemble_explore(query, filter, &mut all)?;
    Ok(head + &cap_kind(cx, text, before, "explore")?)
}

/// `ExploreParts` over several members' `TagsExplore`.
struct Scoped<'a> {
    parts: Vec<TagsExplore<'a>>,
    labels: &'a [String],
}

impl ExploreParts for Scoped<'_> {
    fn resolve(&mut self, token: &str) -> Result<Vec<String>> {
        if self.def_count(token)? > 0 {
            return Ok(vec![token.to_string()]);
        }
        let mut names: Vec<String> = Vec::new();
        for part in &mut self.parts {
            for name in part.resolve(token)? {
                if !names.contains(&name) {
                    names.push(name);
                }
            }
        }
        names.truncate(5);
        Ok(names)
    }

    fn defs(&mut self, name: &str) -> Result<String> {
        let mut out = String::new();
        for part in &mut self.parts {
            if part.def_count(name)? > 0 {
                out.push_str(&part.defs(name)?);
            }
        }
        if out.is_empty() {
            return self.parts[0].defs(name);
        }
        Ok(out)
    }

    fn def_count(&mut self, name: &str) -> Result<usize> {
        let mut n = 0;
        for part in &mut self.parts {
            n += part.def_count(name)?;
        }
        Ok(n)
    }

    fn paths(&mut self, a: &str, b: &str) -> Result<Vec<String>> {
        let mut out = Vec::new();
        for (part, label) in self.parts.iter_mut().zip(self.labels) {
            out.extend(part.paths(a, b)?.into_iter().map(|c| format!("{label}{c}")));
        }
        Ok(out)
    }

    fn impact1(&mut self, name: &str) -> Result<(String, usize)> {
        let (cx, filter) = (self.parts[0].cx, self.parts[0].filter);
        let keys: Vec<&str> = self.parts.iter().map(|p| p.key.as_str()).collect();
        let rows = walk_rows(cx, self.labels, &keys, name, 1, filter)?;
        if rows.is_empty() {
            return Ok((format!("nothing reaches {name}{}", filter.scope_note()), 0));
        }
        Ok((impact_lines_text(&rows), rows.len()))
    }
}

/// `outline` of a file in the scope: the first project that holds the path, else the first.
pub fn outline(cx: &Ctx, scope: &[Member], path: &str) -> Result<String> {
    let p = Path::new(path);
    // Both sides canonical: Windows' `canonicalize` adds `\\?\` and expands 8.3 names
    // (`RUNNER~1`), macOS resolves `/var` to `/private/var`.
    let canon = |q: &Path| dunce::canonicalize(q).unwrap_or_else(|_| q.to_path_buf());
    let real = canon(p);
    let holds = |m: &&Member| {
        if p.is_absolute() {
            real.starts_with(canon(&m.root))
        } else {
            m.root.join(p).exists()
        }
    };
    let m = scope.iter().find(holds).unwrap_or(&scope[0]);
    outline_in(cx, &m.root, path)
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

    /// `a_top` calls `b_mid`, which calls `shared` in `c`; `d` calls `shared` but is not linked.
    fn chain(tag: &str) -> (Runtime, PathBuf) {
        world(
            tag,
            [
                "fn a_top() { b_mid(); }\n",
                "fn b_mid() { shared(); }\n",
                CALL,
                "fn d_caller() { shared(); }\n",
            ],
        )
    }

    #[test]
    fn impact_of_a_function_in_c_walks_up_through_b_into_a() {
        let (cx, dir) = chain("t3294-impact");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        let out = impact(&ctx, &scope, "shared", 3, &Filter::none(), None).unwrap();
        assert_eq!(out, "1  [b] lib.rs  b_mid\n2  [a] lib.rs  a_top\n");
        // Depth bounds the climb.
        let out = impact(&ctx, &scope, "shared", 1, &Filter::none(), None).unwrap();
        assert_eq!(out, "1  [b] lib.rs  b_mid\n");
        // B's scope is B and C: A is not in it, and D never crosses.
        let scope = scope_at(&cx, &dir, "b", None);
        let out = impact(&ctx, &scope, "shared", 3, &Filter::none(), None).unwrap();
        assert_eq!(out, "1  [b] lib.rs  b_mid\n");
        let d = dir.join("d");
        let scope = scope_at(&cx, &dir, "a", d.to_str());
        let one = impact(&ctx, &scope, "shared", 3, &Filter::none(), None).unwrap();
        assert_eq!(one, "1  lib.rs  d_caller\n");
        assert_eq!(
            one,
            impact_filtered(&ctx, &d, "shared", 3, &Filter::none(), None).unwrap()
        );
        let scope = scope_at(&cx, &dir, "a", None);
        let nothing = impact(&ctx, &scope, "a_top", 3, &Filter::none(), None).unwrap();
        assert_eq!(nothing, "nothing reaches a_top");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn impact_to_names_the_project_of_each_chain() {
        let (cx, dir) = chain("t3294-impact-to");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        let to = |name, target| impact(&ctx, &scope, name, 3, &Filter::none(), Some(target));
        assert_eq!(
            to("b_mid", "shared").unwrap(),
            "[b] b_mid \u{2192} shared\n"
        );
        assert_eq!(
            to("shared", "b_mid").unwrap(),
            "no path from shared to b_mid within depth 3"
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn explore_answers_over_the_scope_and_labels_each_project() {
        let (cx, dir) = chain("t3294-explore");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        let out = explore(&ctx, &scope, "shared b_mid", &Filter::none()).unwrap();
        assert!(out.contains("= shared\n[c] lib.rs:1 function\n"), "{out}");
        assert!(out.contains("= b_mid\n[b] lib.rs:1 function\n"), "{out}");
        assert!(out.contains("shared \u{2190} 1\n"), "{out}");
        assert!(out.contains("b_mid \u{2190} 1\n"), "{out}");
        // One project asks the plain explore.
        let d = dir.join("d");
        let scope = scope_at(&cx, &dir, "a", d.to_str());
        assert_eq!(
            explore(&ctx, &scope, "shared", &Filter::none()).unwrap(),
            super::super::explore(&ctx, &d, "shared", &Filter::none()).unwrap()
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn outline_reads_the_file_of_the_project_asked_for() {
        let (cx, dir) = chain("t3294-outline");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        assert!(outline(&ctx, &scope, "lib.rs").unwrap().contains("a_top"));
        let d = dir.join("d");
        let scope = scope_at(&cx, &dir, "a", d.to_str());
        assert!(
            outline(&ctx, &scope, "lib.rs")
                .unwrap()
                .contains("d_caller")
        );
        // An absolute path picks the member that holds it.
        let scope = scope_at(&cx, &dir, "a", None);
        let c = dir.join("c").join("lib.rs");
        assert!(
            outline(&ctx, &scope, c.to_str().unwrap())
                .unwrap()
                .contains("shared")
        );
        let _ = fs::remove_dir_all(dir);
    }
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.4.1, T329.4.2, T329.5: the five tools, `dead` and `affected` over a project scope — the
//! project asked for (or the working directory's) followed by what it links to. Each project keeps
//! its own index, so the scope is a loop over the per-root queries; the answer is one text under
//! one cap. `Ctx` carries no project registry, so the scope is resolved by the caller (`mcp.rs`).

use std::collections::{BTreeSet, HashMap, HashSet};
use std::path::{Path, PathBuf};

use anyhow::{Result, bail};

use rtok_plugin_sdk::{Class, Ctx};

use super::Mode;
use super::{
    DeadRow, ExploreParts, Filter, Hits, Tag, TagsExplore, ambiguous_banner, assemble_explore,
    backend_name, blast, callers_filtered, cap, cap_kind, capability, changed_starts, defs_text,
    flag_ambiguous, format_affected, git_changed_files, impact_filtered, impact_lines_text,
    impact_walk_roots, index, index_for, is_test_path, lsp, lsp_none_answer, mode_of, outline_in,
    projects, rel_of, reverse_call_chain, stale_banner, symbol_filtered, tests_json, text, via_of,
    with_stale, without_mode_line,
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
pub(super) fn fan_out<T>(
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

/// How a scope of several projects is answered (T329.9).
enum Plan {
    Tags,
    /// `backend = "lsp"`: the server answers the first project only (T376).
    LspFirst,
    /// `auto` with a server installed for some project, or a project answered by text search
    /// (T329.10): each project asks for itself.
    PerProject,
}

fn plan(cx: &Ctx, scope: &[Member]) -> Plan {
    let usable = |m: &Member| {
        let name = backend_name(cx, &m.root);
        Mode::named(&name) == Mode::Auto && capability::server_ready(cx, &m.root, &name, lsp::probe)
    };
    if scope
        .iter()
        .any(|m| text::applies(cx, &m.root) || usable(m))
    {
        Plan::PerProject
    } else if mode_of(cx, &scope[0].root) == Mode::Lsp {
        Plan::LspFirst
    } else {
        Plan::Tags
    }
}

/// `auto` over a scope the tags index answers whole keeps the linked traversal, so its mode is
/// a line per project instead of a header on each part.
fn tags_modes(cx: &Ctx, scope: &[Member]) -> String {
    scope
        .iter()
        .filter(|m| mode_of(cx, &m.root) == Mode::Auto)
        .map(|m| format!("[{}] (tags)\n", m.name))
        .collect()
}

/// Each project answers through its own door, so one project's server and another's tags index
/// speak in the same answer, each part labelled and headed with the mode that gave it. A project
/// with nothing to say is left out unless nobody has anything. Links are not walked: a server
/// sees its own workspace, so a caller in a linked project is that project's own answer.
fn per_project(
    cx: &Ctx,
    scope: &[Member],
    ask: impl Fn(&Member) -> Result<String>,
) -> Result<String> {
    let (done, notes) = fan_out(scope, |m| {
        walkable(m)?;
        ask(m)
    })?;
    let mut said: Vec<_> = done
        .iter()
        .filter(|(_, text)| !lsp_none_answer(without_mode_line(text)))
        .collect();
    if said.is_empty() {
        said = done.iter().take(1).collect();
    }
    let mut body = String::new();
    for (m, text) in said {
        body.push_str(&format!("{}{text}", label(m)));
        if !text.ends_with('\n') {
            body.push('\n');
        }
    }
    capped(cx, &notes, body)
}

/// The notes and banners that head an answer count against its one cap, so linking projects
/// never grows a reply past `max_tokens` (T329.5).
fn capped(cx: &Ctx, head: &str, body: String) -> Result<String> {
    cap(cx, format!("{head}{body}"))
}

fn label(m: &Member) -> String {
    format!("[{}] ", m.name)
}

pub(super) fn walkable(m: &Member) -> Result<()> {
    crate::plugins::read::walk_root_ok(&m.root)
}

/// Several names in one call. A single name is [`symbol`], byte for byte.
/// Each extra name is headed `= name`; an unknown name is a line, not an error.
/// The joined text goes through [`cap`] once more so a long batch still archives.
pub fn symbols(cx: &Ctx, scope: &[Member], names: &[String], filter: &Filter) -> Result<String> {
    if names.len() <= 1 {
        let name = names.first().map(String::as_str).unwrap_or("");
        return symbol(cx, scope, name, filter);
    }
    let mut out = String::new();
    for name in names {
        let body = symbol(cx, scope, name, filter)?;
        if !out.is_empty() && !out.ends_with('\n') {
            out.push('\n');
        }
        out.push_str("= ");
        out.push_str(name);
        out.push('\n');
        out.push_str(&body);
        if !body.ends_with('\n') {
            out.push('\n');
        }
    }
    cap(cx, out)
}

pub fn symbol_id(cx: &Ctx, scope: &[Member], id: &str) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return super::symbol_by_id(cx, &one.root, id);
    }
    let mut missed = true;
    let mut out = String::new();
    for m in scope {
        walkable(m)?;
        let text = super::symbol_by_id(cx, &m.root, id)?;
        if text.starts_with("no definition of") {
            continue;
        }
        missed = false;
        out.push_str(&label(m));
        out.push_str(&text);
    }
    if missed {
        return Ok(format!("no definition of {id}"));
    }
    Ok(out)
}

pub fn symbol(cx: &Ctx, scope: &[Member], name: &str, filter: &Filter) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return symbol_filtered(cx, &one.root, name, filter);
    }
    match plan(cx, scope) {
        Plan::PerProject => {
            return per_project(cx, scope, |m| symbol_filtered(cx, &m.root, name, filter));
        }
        Plan::LspFirst => {
            walkable(&scope[0])?;
            let text = lsp::symbol(cx, &scope[0].root, name, filter)?;
            return Ok(text + &lsp_note(scope));
        }
        Plan::Tags => {}
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
    let head = tags_modes(cx, scope) + &banners(cx, &done, notes)?;
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
        body.push_str(&defs_text(cx, &m.root, name, rows, callees, &tag));
    }
    capped(cx, &head, body)
}

pub fn callers(cx: &Ctx, scope: &[Member], name: &str, filter: &Filter) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return callers_filtered(cx, &one.root, name, filter);
    }
    match plan(cx, scope) {
        Plan::PerProject => {
            return per_project(cx, scope, |m| callers_filtered(cx, &m.root, name, filter));
        }
        Plan::LspFirst => {
            walkable(&scope[0])?;
            let text = lsp::callers(cx, &scope[0].root, name, filter)?;
            return Ok(text + &lsp_note(scope));
        }
        Plan::Tags => {}
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
    let head = tags_modes(cx, scope) + &banners(cx, &done, notes)?;
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
    capped(cx, &head, flag_ambiguous(defs, body))
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
    match plan(cx, scope) {
        Plan::PerProject => {
            return per_project(cx, scope, |m| {
                impact_filtered(cx, &m.root, name, depth, filter, to)
            });
        }
        Plan::LspFirst => {
            walkable(&scope[0])?;
            let root = &scope[0].root;
            let text = with_stale(cx, root, lsp::impact(cx, root, name, depth, filter, to)?)?;
            return Ok(text + &lsp_note(scope));
        }
        Plan::Tags => {}
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
    let head = tags_modes(cx, scope) + &banners(cx, &done, notes)?;
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
        let budget = blast::Budget {
            overhead: cx.estimate(&format!("{head}{}", ambiguous_banner(1)), Class::Code),
            mark: defs > 1,
            ..blast::Budget::new(cx, filter.all)
        };
        blast::render(cx, &rows, name, &budget, HashMap::new)?
    };
    capped(cx, &head, flag_ambiguous(defs, body))
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
    match plan(cx, scope) {
        Plan::PerProject => {
            return per_project(cx, scope, |m| super::explore(cx, &m.root, query, filter));
        }
        Plan::LspFirst => {
            walkable(&scope[0])?;
            let text = lsp::explore(cx, &scope[0].root, query, filter)?;
            return Ok(text + &lsp_note(scope));
        }
        Plan::Tags => {}
    }
    let (done, notes) = fan_out(scope, |m| {
        walkable(m)?;
        index_for(cx, &m.root)
    })?;
    let head = tags_modes(cx, scope) + &banners(cx, &done, notes)?;
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
    cap_kind(cx, head + &text, before, "explore")
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

/// Index of the project that holds `path`: the first one that has it, else the first.
fn holder(scope: &[Member], path: &str) -> usize {
    let p = Path::new(path);
    // Both sides canonical: Windows' `canonicalize` adds `\\?\` and expands 8.3 names
    // (`RUNNER~1`), macOS resolves `/var` to `/private/var`.
    let canon = |q: &Path| dunce::canonicalize(q).unwrap_or_else(|_| q.to_path_buf());
    let real = canon(p);
    scope
        .iter()
        .position(|m| {
            if p.is_absolute() {
                real.starts_with(canon(&m.root))
            } else {
                m.root.join(p).exists()
            }
        })
        .unwrap_or(0)
}

/// `outline` of a file in the scope: the first project that holds the path, else the first.
pub fn outline(cx: &Ctx, scope: &[Member], path: &str) -> Result<String> {
    outline_in(cx, &scope[holder(scope, path)].root, path)
}

/// Dead rows per project of a scope.
type DeadByProject<'a> = Vec<(&'a Member, Vec<DeadRow>)>;

/// `dead` rows of each project of the scope. A definition no project in the scope references is
/// dead; one only a linked project references stays live, as `callers` counts that project's call
/// sites. The rows stay per project, so a symbol is still reported where it is defined.
fn dead_by_project<'a>(cx: &Ctx, scope: &'a [Member]) -> Result<(DeadByProject<'a>, String)> {
    // A text-mode project has no reference edges to judge by, so it says so and lists nothing.
    let texty: Vec<&Member> = scope
        .iter()
        .filter(|m| text::applies(cx, &m.root))
        .collect();
    let (mut done, mut notes) = fan_out(scope, |m| {
        walkable(m)?;
        if texty.iter().any(|t| t.root == m.root) {
            return Ok(Vec::new());
        }
        super::dead_rows(cx, &m.root)
    })?;
    for m in texty {
        notes.push_str(&format!("{}dead: {}\n", label(m), text::UNAVAILABLE));
    }
    let keys: Vec<String> = done.iter().map(|(m, _)| index::canon(&m.root)).collect();
    if keys.len() > 1 {
        for (i, (_, rows)) in done.iter_mut().enumerate() {
            let names: Vec<String> = rows
                .iter()
                .map(|r| r.name.clone())
                .collect::<BTreeSet<_>>()
                .into_iter()
                .collect();
            let mut used = HashSet::new();
            for other in keys
                .iter()
                .enumerate()
                .filter_map(|(j, k)| (j != i).then_some(k))
            {
                used.extend(cx.symbol_referenced_names(other, &names)?);
            }
            rows.retain(|r| !used.contains(&r.name));
        }
    }
    Ok((done, notes))
}

/// `graph dead --json` over the scope: a scope of one prints the plain rows, several add the
/// `project` each row belongs to. Uncapped, like the single-project form.
pub fn dead_json(cx: &Ctx, scope: &[Member]) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return Ok(serde_json::to_string_pretty(&super::dead_rows(
            cx, &one.root,
        )?)?);
    }
    let (done, _) = dead_by_project(cx, scope)?;
    let rows: Vec<_> = done
        .iter()
        .flat_map(|(m, rows)| {
            rows.iter().map(|r| {
                serde_json::json!({"project": m.name, "path": r.path, "line": r.line,
                                            "kind": r.kind, "name": r.name})
            })
        })
        .collect();
    Ok(serde_json::to_string_pretty(&rows)?)
}

/// `graph dead` over the scope: `[project] path:line kind name`, under one cap.
pub fn dead(cx: &Ctx, scope: &[Member]) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return super::dead(cx, &one.root);
    }
    let (done, notes) = dead_by_project(cx, scope)?;
    let mut body = String::new();
    for (m, rows) in &done {
        for r in rows {
            body.push_str(&format!(
                "{}{}:{} {} {}\n",
                label(m),
                r.path,
                r.line,
                r.kind,
                r.name
            ));
        }
    }
    if body.is_empty() {
        if done.iter().all(|(m, _)| text::applies(cx, &m.root)) {
            return Ok(notes);
        }
        let names: Vec<&str> = done.iter().map(|(m, _)| m.name.as_str()).collect();
        return Ok(format!("{notes}no dead code in [{}]", names.join("], [")));
    }
    capped(cx, &notes, body)
}

/// Tests that reach the changes: `changed[i]` are the changed paths of `scope[i]`. Each
/// project's changes start a walk over the whole scope, so a change in B finds the tests of A
/// that call it; every test is listed under the project that holds it, with its command to run
/// there. A scope of one is the plain answer.
pub fn affected(
    cx: &Ctx,
    scope: &[Member],
    changed: &[Vec<String>],
    depth: u32,
    json: bool,
) -> Result<String> {
    if let [one] = scope {
        walkable(one)?;
        return super::affected_from_paths(cx, &one.root, &changed[0], depth, json);
    }
    let mut at = 0;
    let (done, notes) = fan_out(scope, |m| {
        // Taken before anything can fail: a skipped project must not shift the rest.
        let paths = &changed[at];
        at += 1;
        walkable(m)?;
        index_for(cx, &m.root)?;
        let key = index::canon(&m.root);
        let (hits, starts) = changed_starts(cx, &m.root, &key, paths)?;
        Ok((key, hits, starts))
    })?;
    let keys: Vec<&str> = done.iter().map(|(_, (k, ..))| k.as_str()).collect();
    let mut hits: Vec<Hits> = done.iter().map(|(_, (_, h, _))| h.clone()).collect();
    let starts: BTreeSet<&String> = done.iter().flat_map(|(_, (.., s))| s).collect();
    for name in starts {
        for (i, _, path, via) in impact_walk_roots(cx, &keys, name, depth, true)? {
            if is_test_path(&path) {
                hits[i].insert((path, via_of(name, via)));
            }
        }
    }
    if json {
        let projects: Vec<_> = done
            .iter()
            .zip(&hits)
            .filter(|(_, h)| !h.is_empty())
            .map(|((m, _), h)| {
                serde_json::json!({"project": m.name, "root": m.root, "tests": tests_json(h)})
            })
            .collect();
        return Ok(if projects.is_empty() {
            serde_json::json!({"projects": [], "message": super::EMPTY_AFFECTED}).to_string()
        } else {
            serde_json::json!({"projects": projects}).to_string()
        });
    }
    if hits.iter().all(Hits::is_empty) {
        return Ok(format!("{notes}{}", super::EMPTY_AFFECTED));
    }
    let mut body = String::new();
    for ((m, _), h) in done.iter().zip(&hits).filter(|(_, h)| !h.is_empty()) {
        body.push_str(&format!("[{}]\n{}", m.name, format_affected(h, false)));
    }
    capped(cx, &notes, body)
}

/// `review` from `git diff` in every project of the scope. One project keeps the plain answer.
/// A project that is not a git repo adds nothing; a git failure inside a repo is returned.
pub fn review_git(
    cx: &Ctx,
    scope: &[Member],
    since: Option<&str>,
    staged: bool,
    json: bool,
) -> Result<String> {
    if let [one] = scope {
        return super::review::review(cx, &one.root, since, staged, json);
    }
    let mut parts = Vec::new();
    for m in scope {
        if super::git_stdout(m.root.as_path(), &["rev-parse", "--is-inside-work-tree"]).is_none() {
            continue;
        }
        let text = super::review::review(cx, &m.root, since, staged, json)?;
        if text.starts_with("no changes") {
            continue;
        }
        parts.push(format!("[{}]\n{text}", m.name));
    }
    if parts.is_empty() {
        Ok("no changes\n".to_string())
    } else {
        Ok(parts.concat())
    }
}

/// `affected` from `git diff` in every project of the scope; one that is not a git repo has no
/// changes and adds nothing.
pub fn affected_git(
    cx: &Ctx,
    scope: &[Member],
    since: Option<&str>,
    staged: bool,
    json: bool,
) -> Result<String> {
    let changed: Vec<_> = scope
        .iter()
        .map(|m| git_changed_files(&m.root, since, staged))
        .collect();
    affected(cx, scope, &changed, 3, json)
}

/// `affected` for one path (MCP `impact` without a name): the path belongs to the project that
/// holds it, as for `outline`.
pub fn affected_path(cx: &Ctx, scope: &[Member], path: &str, depth: u32) -> Result<String> {
    let mut changed = vec![Vec::new(); scope.len()];
    if !path.is_empty() {
        let at = holder(scope, path);
        changed[at] = vec![rel_of(&scope[at].root, path)];
    }
    affected(cx, scope, &changed, depth, false)
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
             [a] lib.rs::dup#function@1 lib.rs:1 function ?\nfn dup() {}\n[c] lib.rs::dup#function@1 lib.rs:1 function ?\nfn dup() {}\n"
        );
        let out = callers(&ctx, &scope, "dup", &Filter::none()).unwrap();
        assert!(out.starts_with("1 names ambiguous (?)"), "{out}");
        assert!(out.contains("[c] lib.rs  c_caller \u{d7}1 (L2) ?"), "{out}");
        // From b only c defines it, so nothing is ambiguous.
        let scope = scope_at(&cx, &dir, "b", None);
        assert_eq!(
            symbol(&ctx, &scope, "dup", &Filter::none()).unwrap(),
            "[c] lib.rs::dup#function@1 lib.rs:1 function\nfn dup() {}\n"
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
        assert!(
            out.contains("= shared\n[c] lib.rs::shared#function@1 lib.rs:1 function\n"),
            "{out}"
        );
        assert!(
            out.contains("= b_mid\n[b] lib.rs::b_mid#function@1 lib.rs:1 function\n"),
            "{out}"
        );
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

    /// `a_top` is the only caller of `b_only`, in a linked project; `b_dead` has no caller anywhere.
    fn dead_world(tag: &str) -> (Runtime, PathBuf) {
        world(
            tag,
            [
                "fn a_top() { b_only(); }\n",
                "fn b_only() {}\nfn b_dead() {}\n",
                "",
                "",
            ],
        )
    }

    #[test]
    fn dead_over_a_scope_spares_what_only_a_linked_project_calls() {
        let (cx, dir) = dead_world("t3295-dead");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        // `a_top` is nobody's callee, so it is still reported, under its own project.
        assert_eq!(
            dead(&ctx, &scope).unwrap(),
            "[a] lib.rs:1 function a_top\n[b] lib.rs:2 function b_dead\n"
        );
        // From b's own scope (b and c) nobody calls `b_only`.
        let scope = scope_at(&cx, &dir, "b", None);
        assert_eq!(
            dead(&ctx, &scope).unwrap(),
            "[b] lib.rs:1 function b_only\n[b] lib.rs:2 function b_dead\n"
        );
        // One project alone is the plain command.
        let b = dir.join("b");
        let scope = scope_at(&cx, &dir, "a", b.to_str());
        let scope = &scope[..1];
        assert_eq!(
            dead(&ctx, scope).unwrap(),
            super::super::dead(&ctx, &b).unwrap()
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn dead_json_names_the_project_of_each_row_and_a_single_project_keeps_plain_rows() {
        let (cx, dir) = dead_world("t3295-dead-json");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        let rows: serde_json::Value =
            serde_json::from_str(&dead_json(&ctx, &scope).unwrap()).unwrap();
        assert_eq!(
            rows,
            serde_json::json!([
                {"project": "a", "path": "lib.rs", "line": 1, "kind": "function", "name": "a_top"},
                {"project": "b", "path": "lib.rs", "line": 2, "kind": "function", "name": "b_dead"},
            ])
        );
        let a = dir.join("a");
        let one = &scope_at(&cx, &dir, "a", a.to_str())[..1];
        let rows: serde_json::Value = serde_json::from_str(&dead_json(&ctx, one).unwrap()).unwrap();
        assert!(rows[0].get("project").is_none(), "{rows}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn dead_with_nothing_dead_says_so_once_for_the_scope() {
        let (cx, dir) = world(
            "t3295-none",
            ["fn a() { b(); }\n", "fn b() { a(); }\n", "", ""],
        );
        let scope = scope_at(&cx, &dir, "a", None);
        assert_eq!(
            dead(&Ctx::new(&cx), &scope).unwrap(),
            "no dead code in [a], [b], [c]"
        );
        let _ = fs::remove_dir_all(dir);
    }

    fn git(dir: &Path, args: &[&str]) {
        let ok = std::process::Command::new("git")
            .current_dir(dir)
            .args(["-c", "user.name=t", "-c", "user.email=t@t"])
            .args(args)
            .status()
            .unwrap()
            .success();
        assert!(ok, "{args:?} in {}", dir.display());
    }

    /// Each of `a`, `b`, `c` is its own git repo with one function and the test that calls it; `a`
    /// also calls `b_fn`. Then `b` and `c` change their function, `a` does not change.
    fn git_world(tag: &str) -> (Runtime, PathBuf) {
        let (cx, dir) = world(
            tag,
            [
                "fn a_top() { b_fn(); }\n",
                "fn b_fn() {}\n",
                "fn c_fn() {}\n",
                "fn d_fn() {}\n",
            ],
        );
        for (name, test) in [("a", "a_top"), ("b", "b_fn"), ("c", "c_fn"), ("d", "d_fn")] {
            let p = dir.join(name);
            fs::create_dir_all(p.join("tests")).unwrap();
            fs::write(
                p.join("tests").join(format!("{name}.rs")),
                format!("fn test_{name}() {{ {test}(); }}\n"),
            )
            .unwrap();
            git(&p, &["init", "-q", "-b", "main"]);
            git(&p, &["add", "."]);
            git(&p, &["commit", "-q", "-m", "init"]);
        }
        for name in ["b", "c", "d"] {
            let f = dir.join(name).join("lib.rs");
            let src = fs::read_to_string(&f).unwrap();
            fs::write(&f, src.replace("{}", "{ let _ = 1; }")).unwrap();
        }
        (cx, dir)
    }

    #[test]
    fn affected_maps_the_tests_of_each_project_and_climbs_into_the_linked_one() {
        let (cx, dir) = git_world("t3295-affected");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        // `b_fn` changed: its own test in b, and a's test that reaches it through `a_top`. `d` is
        // outside the scope, so its change is not looked at.
        let out = affected_git(&ctx, &scope, None, false, false).unwrap();
        assert_eq!(
            out,
            "[a]\ntests/a.rs \u{2190} via test_a\ncargo test test_a\n\
             [b]\ntests/b.rs \u{2190} via test_b\ncargo test test_b\n\
             [c]\ntests/c.rs \u{2190} via test_c\ncargo test test_c\n"
        );
        let json: serde_json::Value =
            serde_json::from_str(&affected_git(&ctx, &scope, None, false, true).unwrap()).unwrap();
        let names: Vec<_> = json["projects"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| p["project"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["a", "b", "c"]);
        assert_eq!(
            json["projects"][1]["tests"][0]["command"],
            "cargo test test_b"
        );
        // Staged changes only: nothing is staged anywhere.
        assert_eq!(
            affected_git(&ctx, &scope, None, true, false).unwrap(),
            "no indexed test reaches the change; run the suite"
        );
        // One project is the plain answer, byte for byte.
        let d = dir.join("d");
        let one = &scope_at(&cx, &dir, "a", d.to_str())[..1];
        assert_eq!(
            affected_git(&ctx, one, None, false, false).unwrap(),
            super::super::affected(&ctx, &d, None, false, false).unwrap()
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn affected_path_belongs_to_the_project_that_holds_the_file() {
        let (cx, dir) = git_world("t3295-affected-path");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        let c = dir.join("c").join("lib.rs");
        let out = affected_path(&ctx, &scope, c.to_str().unwrap(), 3).unwrap();
        assert_eq!(
            out,
            "[c]\ntests/c.rs \u{2190} via test_c\ncargo test test_c\n"
        );
        assert_eq!(
            affected_path(&ctx, &scope, "", 3).unwrap(),
            "no indexed test reaches the change; run the suite"
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn three_linked_projects_answer_under_one_cap_notes_included() {
        let many = |n: usize| {
            (0..n)
                .map(|i| format!("fn f{i}() {{}}\n"))
                .collect::<String>()
        };
        let (mut cx, dir) = world("t3295-cap", [&many(400), &many(400), &many(400), ""]);
        cx.config.plugins.graph.max_tokens = 200;
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        assert_eq!(scope.len(), 3);
        // A skip note heads the answer: it is part of what the cap measures.
        let mut broken = scope.clone();
        broken.push(Member {
            name: "gone".into(),
            root: dir.join("gone"),
        });
        for out in [dead(&ctx, &scope).unwrap(), dead(&ctx, &broken).unwrap()] {
            assert!(out.contains(" more, expand "), "{out}");
            assert!(
                ctx.estimate(&out, rtok_plugin_sdk::Class::Code) <= 200,
                "{out}"
            );
        }
        let _ = fs::remove_dir_all(dir);
    }

    /// T329.9: no project of the scope has a server, so `auto` keeps the linked tags traversal
    /// and only names the mode of each project.
    #[test]
    fn auto_without_servers_answers_from_tags_and_names_each_project_mode() {
        let (mut cx, dir) = fixture("t3299-auto-tags");
        cx.config.plugins.graph.backend = "auto".into();
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        assert!(matches!(plan(&ctx, &scope), Plan::Tags));
        assert_eq!(
            callers(&ctx, &scope, "shared", &Filter::none()).unwrap(),
            "[a] (tags)\n[b] (tags)\n[c] (tags)\n\
             [a] lib.rs  a_caller \u{d7}1 (L1)\n[b] lib.rs  b_caller \u{d7}1 (L1)\n"
        );
        let _ = fs::remove_dir_all(dir);
    }

    /// T329.9: a pinned language keeps its own mode inside an `auto` scope.
    #[test]
    fn plan_follows_the_mode_of_each_project() {
        let (mut cx, dir) = fixture("t3299-plan");
        cx.config.plugins.graph.backend = "lsp".into();
        let scope = scope_at(&cx, &dir, "a", None);
        assert!(matches!(plan(&Ctx::new(&cx), &scope), Plan::LspFirst));
        cx.config.plugins.graph.backend = "tags".into();
        assert!(matches!(plan(&Ctx::new(&cx), &scope), Plan::Tags));
        let _ = fs::remove_dir_all(dir);
    }

    /// T329.9: each project answers through its own door; the part carries the mode line, a
    /// project with nothing to say is left out, and one that cannot answer is skipped.
    #[test]
    fn per_project_labels_each_part_and_drops_the_silent_ones() {
        let (cx, dir) = fixture("t3299-per");
        let ctx = Ctx::new(&cx);
        let scope = scope_at(&cx, &dir, "a", None);
        let said = |text: &'static str| {
            move |m: &Member| -> Result<String> {
                Ok(match m.name.as_str() {
                    "a" => "(lsp)\nlib.rs  a_caller \u{d7}1 (L1)\n".to_string(),
                    "b" => "(tags; lsp: rust-analyzer not on PATH)\nlib.rs  b_caller \u{d7}1 (L1)"
                        .to_string(),
                    _ => text.to_string(),
                })
            }
        };
        assert_eq!(
            per_project(&ctx, &scope, said("(tags)\nno references to shared")).unwrap(),
            "[a] (lsp)\nlib.rs  a_caller \u{d7}1 (L1)\n\
             [b] (tags; lsp: rust-analyzer not on PATH)\nlib.rs  b_caller \u{d7}1 (L1)\n"
        );
        // Nobody has anything: the first project's sentence stands for the scope.
        let nothing = |_: &Member| Ok("(tags)\nno references to shared".to_string());
        assert_eq!(
            per_project(&ctx, &scope, nothing).unwrap(),
            "[a] (tags)\nno references to shared\n"
        );
        // A linked project that fails is a note; the first one failing is the caller's error.
        let broken = |m: &Member| -> Result<String> {
            if m.name == "c" {
                bail!("server gone");
            }
            Ok("(lsp)\nlib.rs  x \u{d7}1 (L1)\n".to_string())
        };
        let out = per_project(&ctx, &scope, broken).unwrap();
        assert!(out.contains("[c] skipped: server gone"), "{out}");
        let first = |_: &Member| -> Result<String> { bail!("no root") };
        assert!(per_project(&ctx, &scope, first).is_err());
        let _ = fs::remove_dir_all(dir);
    }
}

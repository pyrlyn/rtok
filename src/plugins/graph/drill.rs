// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.14: the graph page's level 2 (T329 section 8a), the inside of one project as nodes and
//! edges. It starts at files with the call, import and implements links between them; an expanded
//! file shows its definitions and what they contain; a focused symbol shows its callers and callees.
//! A call into a linked project ends at a node of that project. Everything is read from the
//! index (a handful of scans per request), never from the files.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::path::Path;

use anyhow::Result;
use rtok_plugin_sdk::Ctx;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use super::{index, pending_paths, projects};
use crate::plugin::Runtime;
use crate::store::{DefRow, Project, RefGroup};

/// Visible nodes before the rest collapse into `more`.
pub const DEFAULT_LIMIT: usize = 500;
/// The depth `impact` allows, so a focus never reaches further than the tools do.
const MAX_DEPTH: u32 = 4;
/// A name defined in more files than this resolves to none of them: a guess would draw edges
/// that are not there (the same cut `resolve` makes for common names).
const AMBIGUOUS: usize = 3;
const HITS: usize = 8;

#[derive(Debug, Default, Deserialize, Serialize, JsonSchema)]
pub struct DrillFocus {
    pub path: String,
    pub name: String,
}

#[derive(Debug, Default, Deserialize, Serialize, JsonSchema)]
pub struct DrillRequest {
    /// An id or a root path, as in `rtok graph projects`.
    pub project: String,
    /// Files shown as their definitions.
    #[serde(default)]
    pub expand: Vec<String>,
    /// A symbol shown with its callers and callees.
    #[serde(default)]
    pub focus: Option<DrillFocus>,
    #[serde(default)]
    pub depth: Option<u32>,
    /// Nodes shown before "+N more"; the page raises it on a click.
    #[serde(default)]
    pub limit: Option<usize>,
    /// Finds symbols in the project and its scope.
    #[serde(default)]
    pub query: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum DrillNodeKind {
    File,
    Type,
    Module,
    Function,
    /// A symbol of a linked project: `project` says which.
    External,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum DrillEdgeKind {
    Contains,
    Calls,
    Implements,
    Imports,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DrillNode {
    pub id: String,
    pub kind: DrillNodeKind,
    pub label: String,
    pub path: String,
    pub line: i32,
    /// The project that owns the node: the one asked for, or the linked one for `external`.
    pub project: i32,
    pub signature: String,
    /// Edge count through the node; the page sizes it by this.
    pub weight: i64,
    /// The file changed since the last index run.
    pub stale: bool,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DrillEdge {
    pub from: String,
    pub to: String,
    pub kind: DrillEdgeKind,
    pub count: i64,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DrillHit {
    pub project: i32,
    pub name: String,
    pub path: String,
    pub kind: String,
    pub line: i32,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DrillGraph {
    pub project: i32,
    pub name: String,
    pub root: String,
    /// `ok`, `not indexed` or `missing`.
    pub state: &'static str,
    pub nodes: Vec<DrillNode>,
    pub edges: Vec<DrillEdge>,
    /// Nodes left out by the cap.
    pub more: usize,
    /// Some files changed since the last index run, so the picture lags the tree.
    pub partial: bool,
    pub hits: Vec<DrillHit>,
}

/// Where a link starts or ends, as an index into the scans.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum End {
    Def(usize),
    File(usize),
    Ext(usize),
}

struct Link {
    from: End,
    to: End,
    kind: DrillEdgeKind,
    n: i64,
}

fn node_kind(kind: &str) -> DrillNodeKind {
    match kind {
        "class" | "struct" | "enum" | "interface" | "trait" | "type" | "union" | "impl" => {
            DrillNodeKind::Type
        }
        "module" | "namespace" | "package" => DrillNodeKind::Module,
        _ => DrillNodeKind::Function,
    }
}

/// The scans of one project and what they resolve to.
struct Model {
    files: Vec<String>,
    file_of: HashMap<String, usize>,
    defs: Vec<DefRow>,
    by_name: HashMap<String, Vec<usize>>,
    /// The innermost definition around each definition, for `contains`.
    parent: Vec<Option<usize>>,
    /// Definitions of linked projects, with the project that holds each.
    ext: Vec<(i32, DefRow)>,
    links: Vec<Link>,
}

impl Model {
    fn load(rt: &Runtime, p: &Project, key: &str) -> Result<Self> {
        let defs = rt.store.symbol_def_scan(key)?;
        let refs = rt.store.symbol_ref_scan(key)?;
        let imports = rt.store.symbol_import_edges(key)?;
        let mut files: BTreeSet<&str> = defs.iter().map(|d| d.path.as_str()).collect();
        files.extend(refs.iter().map(|r| r.path.as_str()));
        files.extend(imports.iter().flat_map(|(a, b)| [a.as_str(), b.as_str()]));
        let files: Vec<String> = files.into_iter().map(String::from).collect();
        let file_of = files.iter().cloned().zip(0..).collect();
        let mut by_name: HashMap<String, Vec<usize>> = HashMap::new();
        for (i, d) in defs.iter().enumerate() {
            by_name.entry(d.name.clone()).or_default().push(i);
        }
        let mut m = Self {
            parent: parents(&defs),
            files,
            file_of,
            defs,
            by_name,
            ext: Vec::new(),
            links: Vec::new(),
        };
        m.ext = external_defs(rt, p, &m, &refs)?;
        m.links = m.resolve(&refs, &imports);
        Ok(m)
    }

    fn def_in(&self, path: &str, name: &str) -> Option<usize> {
        self.by_name
            .get(name)?
            .iter()
            .copied()
            .find(|&i| self.defs[i].path == path)
    }

    fn resolve(&self, refs: &[RefGroup], imports: &[(String, String)]) -> Vec<Link> {
        let mut ext_by_name: HashMap<&str, Vec<usize>> = HashMap::new();
        for (i, (_, d)) in self.ext.iter().enumerate() {
            ext_by_name.entry(&d.name).or_default().push(i);
        }
        let mut out = Vec::new();
        for r in refs {
            let from = match self
                .def_in(&r.path, &r.scope)
                .filter(|_| !r.scope.is_empty())
            {
                Some(d) => End::Def(d),
                None => End::File(self.file_of[&r.path]),
            };
            let kind = if r.kind == "implementation" {
                DrillEdgeKind::Implements
            } else {
                DrillEdgeKind::Calls
            };
            let local = self.by_name.get(&r.name).map_or(&[][..], Vec::as_slice);
            // A definition in the caller's own file wins over the same name elsewhere.
            let same: Vec<usize> = local
                .iter()
                .copied()
                .filter(|&i| self.defs[i].path == r.path)
                .collect();
            let to: Vec<End> = if !same.is_empty() {
                same.into_iter().map(End::Def).collect()
            } else if !local.is_empty() {
                local
                    .iter()
                    .take(AMBIGUOUS + 1)
                    .map(|&i| End::Def(i))
                    .collect()
            } else {
                ext_by_name
                    .get(r.name.as_str())
                    .map_or(&[][..], Vec::as_slice)
                    .iter()
                    .take(AMBIGUOUS + 1)
                    .map(|&i| End::Ext(i))
                    .collect()
            };
            if to.len() > AMBIGUOUS {
                continue;
            }
            out.extend(to.into_iter().filter(|t| *t != from).map(|to| Link {
                from,
                to,
                kind,
                n: r.count,
            }));
        }
        for (a, b) in imports {
            out.push(Link {
                from: End::File(self.file_of[a]),
                to: End::File(self.file_of[b]),
                kind: DrillEdgeKind::Imports,
                n: 1,
            });
        }
        out
    }
}

/// `contains` between definitions: a stack of the spans still open at each line.
fn parents(defs: &[DefRow]) -> Vec<Option<usize>> {
    let mut out = vec![None; defs.len()];
    let mut open: Vec<usize> = Vec::new();
    for (i, d) in defs.iter().enumerate() {
        while open
            .last()
            .is_some_and(|&o| defs[o].path != d.path || defs[o].end_line < d.line)
        {
            open.pop();
        }
        out[i] = open.last().copied();
        if d.end_line > d.line {
            open.push(i);
        }
    }
    out
}

/// Definitions in the linked projects of the scope for every name this project calls but does not
/// define.
fn external_defs(
    rt: &Runtime,
    p: &Project,
    m: &Model,
    refs: &[RefGroup],
) -> Result<Vec<(i32, DefRow)>> {
    let linked: Vec<Project> = rt.store.project_scope(p.id)?.into_iter().skip(1).collect();
    if linked.is_empty() {
        return Ok(Vec::new());
    }
    let names: Vec<String> = refs
        .iter()
        .filter(|r| !m.by_name.contains_key(&r.name))
        .map(|r| r.name.clone())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let mut out = Vec::new();
    for q in linked {
        let key = index::canon(Path::new(&q.root));
        out.extend(
            rt.store
                .symbol_defs_named(&key, &names)?
                .into_iter()
                .map(|d| (q.id, d)),
        );
    }
    Ok(out)
}

/// Definitions within `depth` calls of `focus`, in either direction.
fn neighbourhood(m: &Model, focus: usize, depth: u32) -> HashSet<usize> {
    let mut adj: HashMap<usize, Vec<usize>> = HashMap::new();
    for l in m.links.iter().filter(|l| l.kind == DrillEdgeKind::Calls) {
        if let (End::Def(a), End::Def(b)) = (l.from, l.to) {
            adj.entry(a).or_default().push(b);
            adj.entry(b).or_default().push(a);
        }
    }
    let mut seen = HashSet::from([focus]);
    let mut level = vec![focus];
    for _ in 0..depth {
        let mut next = Vec::new();
        for d in level {
            for &n in adj.get(&d).into_iter().flatten() {
                if seen.insert(n) {
                    next.push(n);
                }
            }
        }
        level = next;
    }
    seen
}

fn sym_id(d: &DefRow) -> String {
    format!("s:{}:{}:{}", d.path, d.line, d.name)
}

struct Draw<'a> {
    m: &'a Model,
    pid: i32,
    shown: HashSet<usize>,
    focus_only: bool,
}

impl Draw<'_> {
    /// A definition outside the shown set stands for its file.
    fn id(&self, e: End) -> String {
        match e {
            End::Def(i) if self.shown.contains(&i) => sym_id(&self.m.defs[i]),
            End::Def(i) => format!("f:{}", self.m.defs[i].path),
            End::File(f) => format!("f:{}", self.m.files[f]),
            End::Ext(i) => {
                let (p, d) = &self.m.ext[i];
                format!("x:{p}:{}:{}", d.path, d.name)
            }
        }
    }

    fn node(&self, id: &str, e: End, stale: &HashSet<String>) -> DrillNode {
        let m = self.m;
        let file = |path: &str| {
            (
                DrillNodeKind::File,
                path.rsplit('/').next().unwrap_or(path).to_string(),
                path.to_string(),
                0,
                self.pid,
                String::new(),
            )
        };
        let (kind, label, path, line, project, signature) = match e {
            End::Def(i) if self.shown.contains(&i) => {
                let d = &m.defs[i];
                (
                    node_kind(&d.kind),
                    d.name.clone(),
                    d.path.clone(),
                    d.line,
                    self.pid,
                    d.signature.clone(),
                )
            }
            End::Def(i) => file(&m.defs[i].path),
            End::File(f) => file(&m.files[f]),
            End::Ext(i) => {
                let (p, d) = &m.ext[i];
                (
                    DrillNodeKind::External,
                    d.name.clone(),
                    d.path.clone(),
                    d.line,
                    *p,
                    d.signature.clone(),
                )
            }
        };
        DrillNode {
            id: id.to_string(),
            kind,
            stale: stale.contains(&path),
            label,
            path,
            line,
            project,
            signature,
            weight: 0,
        }
    }
}

pub fn run(rt: &Runtime, req: &DrillRequest) -> Result<DrillGraph> {
    let p = projects::resolve(&rt.store, &req.project)?;
    let mut g = DrillGraph {
        project: p.id,
        name: p.display_name().to_string(),
        root: p.root.clone(),
        state: "ok",
        nodes: Vec::new(),
        edges: Vec::new(),
        more: 0,
        partial: false,
        hits: Vec::new(),
    };
    if p.missing() {
        g.state = "missing";
        return Ok(g);
    }
    let key = index::canon(Path::new(&p.root));
    let cx = Ctx::new(rt);
    if cx.symbol_count(&key)? == 0 {
        g.state = "not indexed";
        return Ok(g);
    }
    if !req.query.trim().is_empty() {
        g.hits = search(rt, &p, &req.query)?;
    }
    let pending: HashSet<String> = pending_paths(&cx, Path::new(&p.root))?
        .into_iter()
        .collect();
    g.partial = !pending.is_empty();
    let m = Model::load(rt, &p, &key)?;

    let focus = req.focus.as_ref().and_then(|f| m.def_in(&f.path, &f.name));
    let mut shown: HashSet<usize> = (0..m.defs.len())
        .filter(|&i| req.expand.contains(&m.defs[i].path))
        .collect();
    let around = focus.map(|f| neighbourhood(&m, f, req.depth.unwrap_or(1).clamp(1, MAX_DEPTH)));
    shown.extend(around.iter().flatten());
    let d = Draw {
        m: &m,
        pid: p.id,
        shown,
        focus_only: around.is_some(),
    };
    draw(&mut g, &d, &pending, req.limit.unwrap_or(DEFAULT_LIMIT));
    Ok(g)
}

fn draw(g: &mut DrillGraph, d: &Draw, stale: &HashSet<String>, limit: usize) {
    let m = d.m;
    let mut ends: BTreeMap<String, End> = BTreeMap::new();
    let mut edges: BTreeMap<(String, String, DrillEdgeKind), i64> = BTreeMap::new();
    let mut add = |ends: &mut BTreeMap<String, End>, a: End, b: End, kind, n: i64| {
        let (ia, ib) = (d.id(a), d.id(b));
        if ia == ib {
            return;
        }
        ends.entry(ia.clone()).or_insert(a);
        ends.entry(ib.clone()).or_insert(b);
        *edges.entry((ia, ib, kind)).or_default() += n;
    };
    for l in &m.links {
        let sym = |e: End| matches!(e, End::Def(i) if d.shown.contains(&i));
        // Around a focus only the calls between shown symbols matter; the other files are noise.
        if d.focus_only && !(sym(l.from) && (sym(l.to) || matches!(l.to, End::Ext(_)))) {
            continue;
        }
        add(&mut ends, l.from, l.to, l.kind, l.n);
    }
    for &i in &d.shown {
        let parent = m.parent[i]
            .filter(|p| d.shown.contains(p))
            .map_or_else(|| End::File(m.file_of[&m.defs[i].path]), End::Def);
        add(&mut ends, parent, End::Def(i), DrillEdgeKind::Contains, 1);
    }
    if !d.focus_only {
        // Files with no edge at all are still part of the project.
        for f in 0..m.files.len() {
            ends.entry(format!("f:{}", m.files[f]))
                .or_insert(End::File(f));
        }
    }
    let mut nodes: Vec<DrillNode> = ends.iter().map(|(id, e)| d.node(id, *e, stale)).collect();
    let mut weight: HashMap<&str, i64> = HashMap::new();
    for ((a, b, _), n) in &edges {
        *weight.entry(a).or_default() += n;
        *weight.entry(b).or_default() += n;
    }
    for n in &mut nodes {
        n.weight = weight.get(n.id.as_str()).copied().unwrap_or(0);
    }
    // What the user asked to see (an expanded file's definitions, the focus) outranks degree, so
    // the cap never hides it.
    let asked: HashSet<String> = d.shown.iter().map(|&i| sym_id(&m.defs[i])).collect();
    nodes.sort_by(|a, b| {
        (asked.contains(&b.id), b.weight, &a.id).cmp(&(asked.contains(&a.id), a.weight, &b.id))
    });
    g.more = nodes.len().saturating_sub(limit);
    nodes.truncate(limit);
    let kept: HashSet<&str> = nodes.iter().map(|n| n.id.as_str()).collect();
    g.edges = edges
        .into_iter()
        .filter(|((a, b, _), _)| kept.contains(a.as_str()) && kept.contains(b.as_str()))
        .map(|((from, to, kind), count)| DrillEdge {
            from,
            to,
            kind,
            count,
        })
        .collect();
    g.nodes = nodes;
}

fn search(rt: &Runtime, p: &Project, query: &str) -> Result<Vec<DrillHit>> {
    let mut out = Vec::new();
    for q in rt.store.project_scope(p.id)? {
        let key = index::canon(Path::new(&q.root));
        for (path, name, kind, line) in rt.store.symbol_fts(&key, query, HITS as i64)? {
            out.push(DrillHit {
                project: q.id,
                name,
                path,
                kind,
                line,
            });
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;

    use super::*;
    use crate::store::Origin;

    /// A registered, indexed project at `dir/name` with the given files.
    fn project(rt: &Runtime, dir: &Path, name: &str, files: &[(&str, &str)]) -> (i32, PathBuf) {
        let root = dir.join(name);
        fs::create_dir_all(&root).unwrap();
        for (f, body) in files {
            fs::write(root.join(f), body).unwrap();
        }
        index::run(&Ctx::new(rt), &root, false).unwrap();
        let id = rt.store.register_project(&root, Origin::Manual).unwrap().id;
        (id, root)
    }

    fn req(project: i32) -> DrillRequest {
        DrillRequest {
            project: project.to_string(),
            ..DrillRequest::default()
        }
    }

    fn edge(g: &DrillGraph, from: &str, to: &str, kind: DrillEdgeKind) -> Option<i64> {
        g.edges
            .iter()
            .find(|e| e.from == from && e.to == to && e.kind == kind)
            .map(|e| e.count)
    }

    const CALLS: &[(&str, &str)] = &[
        ("a.rs", "pub fn alpha() {\n    beta();\n    beta();\n}\n"),
        (
            "b.rs",
            "pub fn beta() {\n    gamma();\n}\npub fn gamma() {}\n",
        ),
    ];

    #[test]
    fn opening_a_project_shows_files_with_aggregated_edges() {
        let (rt, dir) = crate::testutil::runtime("t329-14-files");
        let (id, _) = project(&rt, &dir, "a", CALLS);
        let g = run(&rt, &req(id)).unwrap();
        assert_eq!(g.state, "ok");
        assert!(g.nodes.iter().all(|n| n.kind == DrillNodeKind::File));
        assert_eq!(edge(&g, "f:a.rs", "f:b.rs", DrillEdgeKind::Calls), Some(2));
        assert_eq!(g.more, 0);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn expanding_a_file_shows_its_functions_inside_it() {
        let (rt, dir) = crate::testutil::runtime("t329-14-expand");
        let (id, _) = project(&rt, &dir, "a", CALLS);
        let mut r = req(id);
        r.expand = vec!["b.rs".into()];
        let g = run(&rt, &r).unwrap();
        let beta = g.nodes.iter().find(|n| n.label == "beta").expect("beta");
        assert_eq!(beta.kind, DrillNodeKind::Function);
        assert_eq!(
            edge(&g, "f:b.rs", &beta.id, DrillEdgeKind::Contains),
            Some(1)
        );
        // alpha stays collapsed, so its call lands on the function, not the file.
        assert_eq!(edge(&g, "f:a.rs", &beta.id, DrillEdgeKind::Calls), Some(2));
        let gamma = g.nodes.iter().find(|n| n.label == "gamma").expect("gamma");
        assert_eq!(edge(&g, &beta.id, &gamma.id, DrillEdgeKind::Calls), Some(1));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_focus_shows_callers_and_callees_to_the_depth() {
        let (rt, dir) = crate::testutil::runtime("t329-14-focus");
        let (id, _) = project(
            &rt,
            &dir,
            "a",
            &[(
                "c.rs",
                "fn one() { two(); }\nfn two() { three(); }\nfn three() { four(); }\nfn four() {}\n",
            )],
        );
        let mut r = req(id);
        r.focus = Some(DrillFocus {
            path: "c.rs".into(),
            name: "two".into(),
        });
        let labels = |g: &DrillGraph| {
            let mut l: Vec<_> = g
                .nodes
                .iter()
                .filter(|n| n.kind != DrillNodeKind::File)
                .map(|n| n.label.clone())
                .collect();
            l.sort();
            l
        };
        assert_eq!(labels(&run(&rt, &r).unwrap()), ["one", "three", "two"]);
        r.depth = Some(2);
        assert_eq!(
            labels(&run(&rt, &r).unwrap()),
            ["four", "one", "three", "two"]
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_call_into_a_linked_project_ends_at_a_node_of_that_project() {
        let (rt, dir) = crate::testutil::runtime("t329-14-linked");
        let (a, _) = project(
            &rt,
            &dir,
            "a",
            &[("m.rs", "fn run() {\n    shared();\n}\n")],
        );
        let (c, _) = project(&rt, &dir, "c", &[("lib.rs", "pub fn shared() {}\n")]);
        let before = run(&rt, &req(a)).unwrap();
        assert!(
            before
                .nodes
                .iter()
                .all(|n| n.kind != DrillNodeKind::External)
        );
        rt.store
            .link_projects(a, c, crate::store::LinkKind::Manual, None)
            .unwrap();
        let g = run(&rt, &req(a)).unwrap();
        let x = g
            .nodes
            .iter()
            .find(|n| n.kind == DrillNodeKind::External)
            .expect("an external node");
        assert_eq!(
            (x.project, x.path.as_str(), x.label.as_str()),
            (c, "lib.rs", "shared")
        );
        assert_eq!(edge(&g, "f:m.rs", &x.id, DrillEdgeKind::Calls), Some(1));
        // The search covers the scope, and a hit names its project.
        let mut r = req(a);
        r.query = "shared".into();
        let hit = &run(&rt, &r).unwrap().hits[0];
        assert_eq!((hit.project, hit.path.as_str()), (c, "lib.rs"));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn nodes_beyond_the_cap_are_counted_not_drawn() {
        let (rt, dir) = crate::testutil::runtime("t329-14-cap");
        let files: Vec<(String, String)> = (0..120)
            .map(|i| (format!("f{i:03}.rs"), format!("fn f{i}() {{}}\n")))
            .collect();
        let files: Vec<(&str, &str)> = files
            .iter()
            .map(|(a, b)| (a.as_str(), b.as_str()))
            .collect();
        let (id, _) = project(&rt, &dir, "a", &files);
        let mut r = req(id);
        r.limit = Some(100);
        let g = run(&rt, &r).unwrap();
        assert_eq!((g.nodes.len(), g.more), (100, 20));
        r.limit = Some(500);
        let g = run(&rt, &r).unwrap();
        assert_eq!((g.nodes.len(), g.more), (120, 0));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn an_unindexed_or_missing_project_says_so() {
        let (rt, dir) = crate::testutil::runtime("t329-14-empty");
        let root = dir.join("empty");
        fs::create_dir_all(&root).unwrap();
        let id = rt.store.register_project(&root, Origin::Manual).unwrap().id;
        assert_eq!(run(&rt, &req(id)).unwrap().state, "not indexed");
        fs::remove_dir_all(&root).unwrap();
        assert_eq!(run(&rt, &req(id)).unwrap().state, "missing");
        let _ = fs::remove_dir_all(dir);
    }
}

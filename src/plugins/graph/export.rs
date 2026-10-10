// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.16: the graph as a file (T329 section 8c). One `Export` is built from the scope's registry
//! rows and symbol scans, or read back from a saved file, and every output is a view of it. Keeping
//! the output a function of the `Export`, not of the store, is what keeps an import away from the
//! registry and the index (the images of T329.31 draw from it too). Source text is never included;
//! file and symbol names are.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};
use std::path::Path;

use anyhow::{Context as _, Result, bail};
use base64::Engine as _;
use rtok_plugin_sdk::Ctx;
use schemars::JsonSchema;
use schemars::generate::SchemaSettings;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::capability::Chosen;
use super::scope::{Member, fan_out, walkable};
use super::{draw, index, index_for, projects, text};
use crate::plugin::Runtime;
use crate::store::{DefRow, RefGroup};

pub const SCHEMA: &str = "rtok.graph.v1";
pub const SCHEMA_PATH: &str = "docs/schemas/rtok.graph.v1.schema.json";
/// Past this many definitions of one name a call cannot be pinned to one of them, so it draws no
/// edge; a wrong edge would mislead more than a missing one.
const AMBIGUOUS: usize = 8;
/// A saved export is read whole; this keeps a wrong path from filling memory.
const MAX_IMPORT: u64 = 256 << 20;

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Export {
    #[schemars(extend("const" = "rtok.graph.v1"))]
    pub schema: String,
    pub projects: Vec<Proj>,
    pub links: Vec<Link>,
    /// Empty at the `overview` level.
    pub nodes: Vec<Node>,
    pub edges: Vec<Edge>,
    pub meta: Meta,
}

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Proj {
    /// 0 for a directory that is not in the registry.
    pub id: i32,
    pub name: String,
    /// `~/...` under the home directory, `.../name` elsewhere, unless redaction is off.
    pub root: String,
    pub origin: String,
    /// `tags`, `lsp` or `text`: the mode that answers for the project.
    pub backend: String,
    /// `ok`, `stale`, `not indexed`, `missing` or `unknown`.
    pub health: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub indexed_at: Option<i64>,
}

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Link {
    pub from: i32,
    pub to: i32,
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    /// Call references from `from` into `to` found in the indexes.
    pub references: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Node {
    pub id: String,
    pub project: i32,
    pub kind: String,
    pub name: String,
    /// Relative to the project root.
    pub path: String,
    pub line: i32,
}

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Edge {
    pub from: String,
    pub to: String,
    pub kind: String,
}

#[derive(Debug, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Meta {
    /// Project names of the scope.
    pub scope: Vec<String>,
    /// `overview`, `symbols` or `focus`.
    pub level: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub focus: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub depth: Option<u32>,
    pub exported_at: u64,
    pub rtok_version: String,
    pub redacted: bool,
    /// A project was still indexing, not indexed, or could not answer.
    pub partial: bool,
    pub notes: Vec<String>,
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, clap::ValueEnum, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "lowercase")]
pub enum Level {
    Overview,
    Symbols,
}

pub struct Query<'a> {
    pub level: Level,
    pub focus: Option<&'a str>,
    pub depth: u32,
    pub redact: bool,
    pub pretty: bool,
    /// A saved export to show instead of the store.
    pub from: Option<&'a Path>,
}

type Calls = BTreeSet<(usize, usize, String)>;

struct Part {
    row: Option<projects::ProjectRow>,
    defs: Vec<DefRow>,
    refs: Vec<RefGroup>,
    text: bool,
}

fn backend_of(p: &Part) -> &'static str {
    match p.row.as_ref().and_then(|r| r.backend.as_ref()) {
        _ if p.text => "text",
        Some(c) if c.backend == Chosen::Lsp => "lsp",
        _ => "tags",
    }
}

/// Definitions and call groups become nodes and edges. A call group names its caller by scope and
/// its callee by name, so an edge is drawn from the caller's definition to each definition of the
/// name, preferring the caller's own project (the same rule a linked scope answers by).
fn graph(
    done: &[(&Member, Part)],
    ids: &[i32],
) -> (Vec<Node>, Calls, BTreeMap<(usize, usize), u64>) {
    let (mut nodes, mut owner) = (Vec::new(), Vec::new());
    let mut at: HashMap<(usize, &str, &str), usize> = HashMap::new();
    let mut by_name: HashMap<&str, Vec<usize>> = HashMap::new();
    for (pi, (_, part)) in done.iter().enumerate() {
        for d in &part.defs {
            at.entry((pi, d.path.as_str(), d.name.as_str()))
                .or_insert(nodes.len());
            by_name
                .entry(d.name.as_str())
                .or_default()
                .push(nodes.len());
            owner.push(pi);
            nodes.push(Node {
                id: format!("{}:{}:{}:{}", ids[pi], d.path, d.line, d.name),
                project: ids[pi],
                kind: d.kind.clone(),
                name: d.name.clone(),
                path: d.path.clone(),
                line: d.line,
            });
        }
    }
    let (mut edges, mut cross) = (BTreeSet::new(), BTreeMap::new());
    for (pi, (_, part)) in done.iter().enumerate() {
        for r in part.refs.iter().filter(|r| !r.scope.is_empty()) {
            let (Some(&from), Some(all)) = (
                at.get(&(pi, r.path.as_str(), r.scope.as_str())),
                by_name.get(r.name.as_str()),
            ) else {
                continue;
            };
            let own: Vec<usize> = all.iter().copied().filter(|&n| owner[n] == pi).collect();
            let to = if own.is_empty() { all } else { &own };
            if to.len() > AMBIGUOUS {
                continue;
            }
            for &t in to.iter().filter(|&&t| t != from) {
                edges.insert((from, t, r.kind.clone()));
                if owner[t] != pi {
                    *cross.entry((pi, owner[t])).or_default() += r.count.max(0) as u64;
                }
            }
        }
    }
    (nodes, edges, cross)
}

/// The symbols within `depth` calls of the definitions named `name`, callers and callees alike.
fn near(nodes: &[Node], edges: &Calls, name: &str, depth: u32) -> Result<HashSet<usize>> {
    let mut seen: HashSet<usize> = (0..nodes.len())
        .filter(|&i| nodes[i].name == name)
        .collect();
    if seen.is_empty() {
        bail!("no definition of {name} in scope");
    }
    let mut ring: VecDeque<(usize, u32)> = seen.iter().map(|&i| (i, 0)).collect();
    while let Some((n, d)) = ring.pop_front().filter(|(_, d)| *d < depth) {
        for (a, b, _) in edges {
            let other = if *a == n {
                b
            } else if *b == n {
                a
            } else {
                continue;
            };
            if seen.insert(*other) {
                ring.push_back((*other, d + 1));
            }
        }
    }
    Ok(seen)
}

/// The export of `scope`. Reads the registry and the index and writes neither, apart from the
/// incremental refresh every graph question does first.
pub fn collect(rt: &Runtime, scope: &[Member], q: &Query) -> Result<Export> {
    let cx = Ctx::new(rt);
    let (done, skipped) = fan_out(scope, |m| {
        walkable(m)?;
        index_for(&cx, &m.root)?;
        let key = index::canon(&m.root);
        let row = rt
            .store
            .project_by_root(&m.root)?
            .map(|p| projects::row(rt, p))
            .transpose()?;
        Ok(Part {
            row,
            defs: rt.store.symbol_def_scan(&key)?,
            refs: rt.store.symbol_ref_scan(&key)?,
            text: text::applies(&cx, &m.root),
        })
    })?;
    let ids: Vec<i32> = done
        .iter()
        .map(|(_, p)| p.row.as_ref().map_or(0, |r| r.id))
        .collect();
    let (nodes, mut edges, cross) = graph(&done, &ids);
    let mut notes: Vec<String> = skipped.lines().map(str::to_string).collect();
    let mut partial = !notes.is_empty();
    let mut proj = Vec::new();
    for (m, p) in &done {
        let health = p.row.as_ref().map_or("unknown", |r| r.state);
        partial |= health != "ok" && health != "unknown";
        if p.text {
            notes.push(format!("{}: text search only, no call edges", m.name));
        }
        proj.push(Proj {
            id: p.row.as_ref().map_or(0, |r| r.id),
            name: m.name.clone(),
            root: p
                .row
                .as_ref()
                .map_or_else(|| m.root.display().to_string(), |r| r.root.clone()),
            origin: p
                .row
                .as_ref()
                .map_or("unregistered", |r| r.origin.as_str())
                .into(),
            backend: backend_of(p).into(),
            health: health.into(),
            indexed_at: p.row.as_ref().and_then(|r| r.index.as_ref()?.indexed_at),
        });
    }
    let mut links = Vec::new();
    for (pi, (_, p)) in done.iter().enumerate() {
        for l in p.row.iter().flat_map(|r| &r.links) {
            if let Some(pj) = ids.iter().position(|&id| id == l.to) {
                links.push(Link {
                    from: ids[pi],
                    to: l.to,
                    kind: l.kind.as_str().into(),
                    reason: l.reason.clone(),
                    references: cross.get(&(pi, pj)).copied().unwrap_or(0),
                });
            }
        }
    }
    let mut keep: Option<HashSet<usize>> = None;
    let level = match (q.focus, q.level) {
        (Some(f), _) => {
            keep = Some(near(&nodes, &edges, f, q.depth)?);
            "focus"
        }
        (None, Level::Symbols) => "symbols",
        (None, Level::Overview) => {
            edges.clear();
            keep = Some(HashSet::new());
            "overview"
        }
    };
    let kept = |i: &usize| keep.as_ref().is_none_or(|k| k.contains(i));
    let ids_of: Vec<&str> = nodes.iter().map(|n| n.id.as_str()).collect();
    let out_edges = edges
        .iter()
        .filter(|(a, b, _)| kept(a) && kept(b))
        .map(|(a, b, kind)| Edge {
            from: ids_of[*a].into(),
            to: ids_of[*b].into(),
            kind: kind.clone(),
        })
        .collect();
    let out_nodes = nodes
        .iter()
        .enumerate()
        .filter(|(i, _)| kept(i))
        .map(|(_, n)| n.clone())
        .collect();
    Ok(Export {
        schema: SCHEMA.into(),
        projects: proj,
        links,
        nodes: out_nodes,
        edges: out_edges,
        meta: Meta {
            scope: done.iter().map(|(m, _)| m.name.clone()).collect(),
            level: level.into(),
            focus: q.focus.map(str::to_string),
            depth: q.focus.map(|_| q.depth),
            exported_at: crate::log::now(),
            rtok_version: env!("CARGO_PKG_VERSION").into(),
            redacted: false,
            partial,
            notes,
        },
    })
}

/// Home, the user name and absolute roots out. The user name is the home directory's last
/// segment: it is what a path under `/Users/<name>` gives away, and a folder of that name inside
/// the project would read as the person too.
fn redact(e: &mut Export, home: Option<&Path>) {
    let user = home.and_then(Path::file_name).and_then(|n| n.to_str());
    // Only a whole path component is home: `/Users/al` must not turn `/Users/alice` into `~ice`.
    let home = home
        .and_then(Path::to_str)
        .filter(|h| h.len() > 1)
        .and_then(|h| regex::Regex::new(&format!(r"{}([/\\\s]|$)", regex::escape(h))).ok());
    // Both separators on every platform: an export made on one system is read on another.
    let text = |s: &str| {
        let s = home
            .as_ref()
            .map_or_else(|| s.to_string(), |h| h.replace_all(s, "~$1").into_owned());
        s.split_inclusive(['/', '\\'])
            .map(|piece| {
                let seg = piece.trim_end_matches(['/', '\\']);
                if Some(seg) == user {
                    format!("<user>{}", &piece[seg.len()..])
                } else {
                    piece.to_string()
                }
            })
            .collect::<String>()
    };
    for p in &mut e.projects {
        let root = text(&p.root);
        p.root = match Path::new(&root)
            .file_name()
            // `has_root`, not `is_absolute`: `/Volumes/x` has no drive on Windows yet is no less
            // a full path.
            .filter(|_| Path::new(&root).has_root())
        {
            Some(name) => format!("\u{2026}/{}", name.to_string_lossy()),
            None => root,
        };
        p.name = text(&p.name);
    }
    for l in &mut e.links {
        l.reason = l.reason.as_deref().map(&text);
    }
    // Node paths are relative, but a folder named after the user inside the project gives the
    // person away just the same, and the id carries the path.
    let mut ids = HashMap::new();
    for n in &mut e.nodes {
        let path = text(&n.path);
        let id =
            n.id.replacen(&format!(":{}:", n.path), &format!(":{path}:"), 1);
        n.path = path;
        ids.insert(std::mem::replace(&mut n.id, id.clone()), id);
    }
    for edge in &mut e.edges {
        for end in [&mut edge.from, &mut edge.to] {
            if let Some(id) = ids.get(end.as_str()) {
                end.clone_from(id);
            }
        }
    }
    e.meta.notes = e.meta.notes.iter().map(|n| text(n)).collect();
    e.meta.redacted = true;
}

/// Reads a saved export for viewing. It opens the file and parses it; nothing is written, and
/// the registry and the index are not consulted.
pub fn read(path: &Path) -> Result<Export> {
    let size = std::fs::metadata(path)
        .with_context(|| path.display().to_string())?
        .len();
    if size > MAX_IMPORT {
        bail!(
            "{}: {size} bytes is more than a graph export can be",
            path.display()
        );
    }
    let text = std::fs::read_to_string(path).with_context(|| path.display().to_string())?;
    parse(&text, &path.display().to_string())
}

/// The same checks on text that came from somewhere other than a path rtok opened, such as the
/// graph page's file picker; `label` names the source in the errors.
pub fn parse(text: &str, label: &str) -> Result<Export> {
    if text.len() as u64 > MAX_IMPORT {
        bail!(
            "{label}: {} bytes is more than a graph export can be",
            text.len()
        );
    }
    let value: Value =
        serde_json::from_str(text).with_context(|| format!("{label} is not a graph export"))?;
    match value["schema"].as_str() {
        Some(SCHEMA) => {}
        other => bail!("{label}: schema {other:?}, this rtok reads {SCHEMA}"),
    }
    serde_json::from_value(value).with_context(|| format!("{label} does not match {SCHEMA}"))
}

/// The committed schema file: pretty JSON with a trailing newline, generated from the types.
pub fn schema_json() -> String {
    let schema = SchemaSettings::draft2020_12()
        .for_serialize()
        .into_generator()
        .into_root_schema_for::<Export>();
    let mut out = serde_json::to_string_pretty(&schema).unwrap_or_default();
    out.push('\n');
    out
}

/// The export every format is a view of: collected from the scope or read from a saved file, then
/// redacted, so a picture never shows more than the JSON would.
fn build(rt: &Runtime, scope: &[Member], q: &Query) -> Result<Export> {
    let mut e = match q.from {
        Some(path) => read(path)?,
        None => collect(rt, scope, q)?,
    };
    if q.redact {
        redact(&mut e, std::env::home_dir().as_deref());
    }
    Ok(e)
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, clap::ValueEnum, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "lowercase")]
pub enum Format {
    Json,
    Svg,
    Png,
}

/// How `render` turns the export into bytes; the JSON ignores the rest.
pub struct Image {
    pub format: Format,
    pub transparent: bool,
    pub scale: u32,
}

pub fn render(rt: &Runtime, scope: &[Member], q: &Query, img: &Image) -> Result<Vec<u8>> {
    if img.format == Format::Json {
        return Ok(run(rt, scope, q)?.into_bytes());
    }
    let svg = draw::svg(&build(rt, scope, q)?, img.transparent);
    match img.format {
        Format::Png => draw::png(&svg, img.scale),
        _ => Ok(svg.into_bytes()),
    }
}

pub fn run(rt: &Runtime, scope: &[Member], q: &Query) -> Result<String> {
    let e = build(rt, scope, q)?;
    let mut out = if q.pretty {
        serde_json::to_string_pretty(&e)?
    } else {
        serde_json::to_string(&e)?
    };
    out.push('\n');
    Ok(out)
}

/// A download from the graph page's Export menu (T329.40): the file `rtok graph export` writes for
/// the same arguments.
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ExportRequest {
    /// An id or a root path, as in `rtok graph projects`.
    pub project: String,
    pub level: Level,
    /// A symbol name; it wins over `level`, as `--focus` does.
    #[serde(default)]
    pub focus: Option<String>,
    #[serde(default)]
    pub depth: Option<u32>,
    pub format: Format,
    /// PNG only: 1 to 4 times the SVG size.
    #[serde(default)]
    pub scale: Option<u32>,
    #[serde(default)]
    pub transparent: bool,
}

/// A saved JSON export the page read itself, to show read-only. Like Compare mode, it never names
/// a path: the websocket answers anything on localhost, so the file arrives as its text.
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct ImportRequest {
    /// The file's name, for the banner and the errors.
    pub name: String,
    pub text: String,
}

/// A finished download. The bytes travel as base64 because a PNG is not text and the frame is.
#[derive(Debug, Serialize, JsonSchema)]
pub struct ExportFile {
    pub name: String,
    pub mime: String,
    pub data: String,
}

/// The page's download. Redaction is always on, as for MCP: the page has no `--no-redact`, so a
/// file shared from the browser never carries the home directory or the user name.
pub fn page_file(rt: &Runtime, r: &ExportRequest) -> Result<ExportFile> {
    if r.project.is_empty() {
        bail!("export needs a project");
    }
    let scope = super::scope::resolve(&rt.store, Some(&r.project), Path::new("."))?;
    let bytes = render(
        rt,
        &scope,
        &Query {
            level: r.level,
            focus: r.focus.as_deref().filter(|f| !f.is_empty()),
            depth: r.depth.map_or(2, |d| d.clamp(1, 8)),
            redact: true,
            pretty: true,
            from: None,
        },
        &Image {
            format: r.format,
            transparent: r.transparent,
            scale: r.scale.map_or(1, |s| s.clamp(1, 4)),
        },
    )?;
    let (ext, mime) = match r.format {
        Format::Json => ("json", "application/json"),
        Format::Svg => ("svg", "image/svg+xml"),
        Format::Png => ("png", "image/png"),
    };
    let level = match (&r.focus, r.level) {
        (Some(f), _) if !f.is_empty() => "focus",
        (_, Level::Symbols) => "symbols",
        _ => "overview",
    };
    let slug: String = scope[0]
        .name
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { '-' })
        .collect();
    Ok(ExportFile {
        name: format!("rtok-graph-{slug}-{level}.{ext}"),
        mime: mime.into(),
        data: base64::engine::general_purpose::STANDARD.encode(bytes),
    })
}

/// MCP `graph_export`: JSON, always redacted, compact. Past `max_tokens` it archives like any
/// answer, so the whole file stays one `expand <id>` away.
pub fn call(rt: &Runtime, args: &Value, scope: &[Member]) -> Result<String> {
    let arg = |k: &str| args[k].as_str().filter(|s| !s.is_empty());
    let text = run(
        rt,
        scope,
        &Query {
            level: if arg("level") == Some("symbols") {
                Level::Symbols
            } else {
                Level::Overview
            },
            focus: arg("focus"),
            depth: args["depth"].as_u64().map_or(2, |d| d.clamp(1, 8) as u32),
            redact: true,
            pretty: false,
            from: None,
        },
    )?;
    super::cap(&Ctx::new(rt), text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plugins::graph::index::tests::cx;
    use crate::store::{LinkKind, Origin};
    use std::fs;
    use std::path::PathBuf;

    /// `a` calls `shared`, which `b` defines; `a` is linked to `b`.
    fn linked(tag: &str) -> (Runtime, PathBuf, Vec<Member>) {
        let (rt, dir) = cx(tag);
        let mut ids = Vec::new();
        for (name, src) in [
            ("a", "fn a_caller() {\n    shared();\n}\n"),
            ("b", "fn shared() {}\n"),
        ] {
            let root = dir.join(name);
            fs::create_dir_all(&root).unwrap();
            fs::write(root.join("lib.rs"), src).unwrap();
            ids.push(rt.store.register_project(&root, Origin::Manual).unwrap().id);
        }
        rt.store
            .link_projects(ids[0], ids[1], LinkKind::Manual, Some("shared helper"))
            .unwrap();
        let scope = super::super::scope::resolve(&rt.store, None, &dir.join("a")).unwrap();
        (rt, dir, scope)
    }

    fn query(level: Level) -> Query<'static> {
        Query {
            level,
            focus: None,
            depth: 2,
            redact: true,
            pretty: true,
            from: None,
        }
    }

    fn json(rt: &Runtime, scope: &[Member], q: &Query) -> Value {
        let mut v: Value = serde_json::from_str(&run(rt, scope, q).unwrap()).unwrap();
        v["meta"].as_object_mut().unwrap().remove("exported_at");
        v
    }

    fn bare(root: &str) -> Export {
        Export {
            schema: SCHEMA.into(),
            projects: vec![Proj {
                id: 1,
                name: "p".into(),
                root: root.into(),
                origin: "manual".into(),
                backend: "tags".into(),
                health: "ok".into(),
                indexed_at: None,
            }],
            links: Vec::new(),
            nodes: Vec::new(),
            edges: Vec::new(),
            meta: Meta {
                scope: vec!["p".into()],
                level: "overview".into(),
                focus: None,
                depth: None,
                exported_at: 0,
                rtok_version: "0".into(),
                redacted: false,
                partial: false,
                notes: Vec::new(),
            },
        }
    }

    /// Fails when an export type changed without regenerating the schema file.
    #[test]
    fn committed_schema_is_current() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join(SCHEMA_PATH);
        let want = schema_json();
        if std::env::var_os("RTOK_BLESS").is_some() {
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(&path, &want).unwrap();
            return;
        }
        let have = fs::read_to_string(&path).unwrap_or_default();
        assert!(
            have == want,
            "{SCHEMA_PATH} is stale: run `RTOK_BLESS=1 cargo nextest run --lib -E 'test(committed_schema_is_current)'`"
        );
    }

    #[test]
    fn the_json_validates_against_the_schema_file() {
        let (rt, dir, scope) = linked("t32916-schema");
        let schema: Value = serde_json::from_str(
            &fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join(SCHEMA_PATH)).unwrap(),
        )
        .unwrap();
        let check = jsonschema::validator_for(&schema).unwrap();
        for level in [Level::Overview, Level::Symbols] {
            let doc: Value =
                serde_json::from_str(&run(&rt, &scope, &query(level)).unwrap()).unwrap();
            if let Err(e) = check.validate(&doc) {
                panic!("{e}: {doc}");
            }
        }
        let mut wrong: Value =
            serde_json::from_str(&run(&rt, &scope, &query(Level::Overview)).unwrap()).unwrap();
        wrong["schema"] = "rtok.graph.v2".into();
        assert!(!check.is_valid(&wrong));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn symbols_cross_projects_and_the_link_counts_the_calls() {
        let (rt, dir, scope) = linked("t32916-edges");
        let doc = json(&rt, &scope, &query(Level::Symbols));
        let names: Vec<_> = doc["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|n| n["name"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["a_caller", "shared"], "{doc}");
        assert_eq!(doc["edges"].as_array().unwrap().len(), 1, "{doc}");
        assert_eq!(doc["links"][0]["kind"], "manual");
        assert_eq!(doc["links"][0]["references"], 1);
        assert_eq!(doc["meta"]["level"], "symbols");
        let overview = json(&rt, &scope, &query(Level::Overview));
        assert!(overview["nodes"].as_array().unwrap().is_empty());
        assert_eq!(overview["links"], doc["links"]);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_focus_keeps_the_symbols_within_depth_and_an_unknown_one_is_an_error() {
        let (rt, dir, scope) = linked("t32916-focus");
        let mut q = query(Level::Overview);
        q.focus = Some("shared");
        q.depth = 1;
        let doc = json(&rt, &scope, &q);
        assert_eq!(doc["meta"]["level"], "focus");
        assert_eq!(doc["nodes"].as_array().unwrap().len(), 2, "{doc}");
        q.focus = Some("nope");
        let err = run(&rt, &scope, &q).unwrap_err().to_string();
        assert!(err.contains("no definition of nope"), "{err}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn home_the_user_name_and_absolute_roots_are_redacted() {
        let mut e = bare("/Users/alice/work/alice/p");
        e.projects.push(Proj {
            root: "/Volumes/disk/other".into(),
            ..bare("").projects.remove(0)
        });
        e.meta.notes = vec!["see /Users/alice/x".into(), "/Users/alicex/y".into()];
        e.nodes.push(Node {
            id: "1:alice/lib.rs:1:f".into(),
            project: 1,
            kind: "function".into(),
            name: "f".into(),
            path: "alice/lib.rs".into(),
            line: 1,
        });
        e.edges.push(Edge {
            from: "1:alice/lib.rs:1:f".into(),
            to: "1:alice/lib.rs:1:f".into(),
            kind: "call".into(),
        });
        redact(&mut e, Some(Path::new("/Users/alice")));
        assert_eq!(e.projects[0].root, "~/work/<user>/p");
        assert_eq!(e.projects[1].root, "\u{2026}/other");
        // A sibling whose name only starts with the user's is not home.
        assert_eq!(e.meta.notes, ["see ~/x", "/Users/alicex/y"]);
        e.meta.notes.pop();
        assert_eq!(e.nodes[0].path, "<user>/lib.rs");
        assert_eq!(e.nodes[0].id, "1:<user>/lib.rs:1:f");
        assert_eq!(e.edges[0].from, e.nodes[0].id);
        assert!(e.meta.redacted);
        let text = serde_json::to_string(&e).unwrap();
        assert!(
            !text.contains("alice") && !text.contains("/Volumes"),
            "{text}"
        );
    }

    #[test]
    fn the_default_export_hides_the_real_paths_and_no_redact_keeps_them() {
        let (rt, dir, scope) = linked("t32916-paths");
        let doc = json(&rt, &scope, &query(Level::Overview));
        assert_eq!(doc["meta"]["redacted"], true);
        let mut q = query(Level::Overview);
        q.redact = false;
        let raw = json(&rt, &scope, &q);
        let root = raw["projects"][0]["root"].as_str().unwrap();
        assert!(
            Path::new(root).is_absolute() && root.ends_with('a'),
            "{root}"
        );
        assert_ne!(doc["projects"][0]["root"], raw["projects"][0]["root"]);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn the_cli_and_the_mcp_tool_give_the_same_json() {
        let (rt, dir, scope) = linked("t32916-same");
        let cli = json(&rt, &scope, &query(Level::Symbols));
        let mut mcp: Value = serde_json::from_str(
            &call(&rt, &serde_json::json!({"level": "symbols"}), &scope).unwrap(),
        )
        .unwrap();
        mcp["meta"].as_object_mut().unwrap().remove("exported_at");
        // Each call may refresh the index first, so the second can carry a later second.
        let mut cli = cli;
        for doc in [&mut cli, &mut mcp] {
            for p in doc["projects"].as_array_mut().unwrap() {
                p.as_object_mut().unwrap().remove("indexed_at");
            }
        }
        assert_eq!(cli, mcp);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn importing_a_saved_export_writes_nothing() {
        let (rt, dir, scope) = linked("t32916-import");
        let file = dir.join("saved.json");
        fs::write(&file, run(&rt, &scope, &query(Level::Symbols)).unwrap()).unwrap();
        let saved = fs::read(&file).unwrap();

        let (empty, edir) = cx("t32916-import-empty");
        let mut q = query(Level::Symbols);
        q.from = Some(&file);
        let shown: Value = serde_json::from_str(&run(&empty, &[], &q).unwrap()).unwrap();
        assert_eq!(shown["nodes"].as_array().unwrap().len(), 2, "{shown}");
        assert!(empty.store.projects().unwrap().is_empty());
        assert!(empty.store.project_links().unwrap().is_empty());
        assert_eq!(fs::read(&file).unwrap(), saved);

        fs::write(&file, r#"{"schema":"rtok.graph.v2"}"#).unwrap();
        assert!(
            read(&file)
                .unwrap_err()
                .to_string()
                .contains("this rtok reads rtok.graph.v1")
        );
        fs::write(&file, "not json").unwrap();
        assert!(
            read(&file)
                .unwrap_err()
                .to_string()
                .contains("is not a graph export")
        );
        let _ = (fs::remove_dir_all(dir), fs::remove_dir_all(edir));
        drop(rt);
    }

    fn make(project: &Path, level: Level, format: Format) -> ExportRequest {
        ExportRequest {
            project: project.display().to_string(),
            level,
            focus: None,
            depth: None,
            format,
            scale: None,
            transparent: false,
        }
    }

    fn decoded(f: &ExportFile) -> Vec<u8> {
        base64::engine::general_purpose::STANDARD
            .decode(&f.data)
            .unwrap()
    }

    /// Without `exported_at` and `indexed_at` (each call may refresh the index and take a later second).
    fn stable(bytes: &[u8]) -> Value {
        let mut v: Value = serde_json::from_slice(bytes).unwrap();
        v["meta"].as_object_mut().unwrap().remove("exported_at");
        for p in v["projects"].as_array_mut().unwrap() {
            p.as_object_mut().unwrap().remove("indexed_at");
        }
        v
    }

    #[test]
    fn the_page_download_is_the_file_the_cli_writes() {
        let (rt, dir, scope) = linked("t32940-same");
        let cli = render(
            &rt,
            &scope,
            &query(Level::Symbols),
            &Image {
                format: Format::Json,
                transparent: false,
                scale: 1,
            },
        )
        .unwrap();
        let file = page_file(&rt, &make(&dir.join("a"), Level::Symbols, Format::Json)).unwrap();
        assert_eq!(
            (file.mime.as_str(), file.name.as_str()),
            ("application/json", "rtok-graph-a-symbols.json")
        );
        let page = decoded(&file);
        assert_eq!(stable(&page), stable(&cli));
        // The same text layout, not only the same data: the file is pretty like the CLI's.
        assert!(page.starts_with(b"{\n  \"schema\""));
        assert_eq!(stable(&page)["meta"]["redacted"], true);

        let mut focus = make(&dir.join("a"), Level::Overview, Format::Json);
        focus.focus = Some("shared".into());
        focus.depth = Some(1);
        let f = page_file(&rt, &focus).unwrap();
        assert_eq!(f.name, "rtok-graph-a-focus.json");
        assert_eq!(stable(&decoded(&f))["meta"]["level"], "focus");

        let svg = page_file(&rt, &make(&dir.join("a"), Level::Symbols, Format::Svg)).unwrap();
        assert!(decoded(&svg).starts_with(b"<svg"), "{}", svg.name);
        assert_eq!(svg.mime, "image/svg+xml");
        let err = page_file(&rt, &make(Path::new(""), Level::Symbols, Format::Json));
        assert!(err.unwrap_err().to_string().contains("needs a project"));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn the_page_reads_a_download_back_without_a_store() {
        let (rt, dir, _) = linked("t32940-open");
        let file = page_file(&rt, &make(&dir.join("a"), Level::Symbols, Format::Json)).unwrap();
        let text = String::from_utf8(decoded(&file)).unwrap();
        let shown = parse(&text, "saved.json").unwrap();
        assert_eq!(shown.nodes.len(), 2);
        assert!(
            parse("{}", "x.json")
                .unwrap_err()
                .to_string()
                .contains("x.json")
        );
        let request: ImportRequest =
            serde_json::from_str(r#"{"name":"a.json","text":"{}"}"#).unwrap();
        assert_eq!(request.name, "a.json");
        let _ = fs::remove_dir_all(dir);
    }
}

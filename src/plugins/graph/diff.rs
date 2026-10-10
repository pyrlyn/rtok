// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.18: what a change did to the graph (T329 section 8e). The old side is read from git's
//! object database, parsed by the index's own extractor and kept in memory, so there is no checkout,
//! no second store and nothing for the working tree to notice. The new side is the project's index
//! (or a second revision read the same way). Only the files git reports as different are parsed
//! and compared: every other file has the same rows on both sides.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fmt::Write as _;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{Context as _, Result, anyhow, bail};
use rtok_plugin_sdk::Ctx;
use serde_json::{Value, json};

use super::scope::{Member, fan_out, walkable};
use super::walk::Matcher;
use super::{git_stdout_result, impact_walk_roots, index, index_for};
use crate::plugin::Runtime;
use crate::store::{DefRow, RefGroup};

/// Callers shown under one symbol, and the symbols whose callers are looked up at all; the rest
/// are counted, so a refactor of 500 functions still answers in one cap.
const CALLERS_SHOWN: usize = 5;
const CALLERS_SYMBOLS: usize = 60;
const CALLER_DEPTH: u32 = 2;
/// Past this many unmatched definitions on a side a rename cannot be told from noise, and reading
/// every body would cost more than the answer is worth.
const RENAME_CANDIDATES: usize = 400;
/// Blobs asked of `git cat-file --batch` before their replies are read. The requests (about 41
/// bytes each) then fit a pipe, so git never blocks on stdout while rtok blocks on stdin.
const BATCH: usize = 128;

pub struct Query<'a> {
    pub from: &'a str,
    /// `None` is the working tree.
    pub to: Option<&'a str>,
    pub json: bool,
}

type Edge = (String, String, String);

pub struct Changed {
    pub old: DefRow,
    pub new: DefRow,
}

#[derive(Default)]
pub struct Diff {
    pub added: Vec<DefRow>,
    pub removed: Vec<DefRow>,
    pub changed: Vec<Changed>,
    pub moved: Vec<(DefRow, DefRow)>,
    pub renamed: Vec<(DefRow, DefRow)>,
    pub edges_added: Vec<Edge>,
    pub edges_removed: Vec<Edge>,
}

impl Diff {
    fn is_empty(&self) -> bool {
        self.added.is_empty()
            && self.removed.is_empty()
            && self.changed.is_empty()
            && self.moved.is_empty()
            && self.renamed.is_empty()
            && self.edges_added.is_empty()
            && self.edges_removed.is_empty()
    }

    /// Symbols whose callers matter to a reviewer: what changed, vanished or changed name.
    fn touched(&self) -> Vec<&str> {
        let mut out: Vec<&str> = self.changed.iter().map(|c| c.new.name.as_str()).collect();
        out.extend(self.removed.iter().map(|d| d.name.as_str()));
        out.extend(self.renamed.iter().map(|(old, _)| old.name.as_str()));
        out.dedup();
        out.truncate(CALLERS_SYMBOLS);
        out
    }
}

/// One tree's definitions and call groups for the files being compared, and where to read a
/// definition's text from when a rename has to be told from a delete and an add.
struct Side {
    defs: Vec<DefRow>,
    refs: Vec<RefGroup>,
    src: Src,
    cache: HashMap<String, Vec<u8>>,
}

enum Src {
    Disk(PathBuf),
    Git {
        root: PathBuf,
        oids: HashMap<String, String>,
    },
}

impl Side {
    fn body(&mut self, d: &DefRow) -> Option<String> {
        if !self.cache.contains_key(&d.path) {
            let bytes = match &self.src {
                Src::Disk(root) => std::fs::read(root.join(&d.path)).ok()?,
                Src::Git { root, oids } => {
                    let oid = oids.get(&d.path)?;
                    super::git_stdout(root, &["cat-file", "blob", oid.as_str()])?
                }
            };
            self.cache.insert(d.path.clone(), bytes);
        }
        let (start, end) = (
            usize::try_from(d.start_byte).ok()?,
            usize::try_from(d.end_byte).ok()?,
        );
        let bytes = self.cache.get(&d.path)?.get(start..end)?;
        Some(String::from_utf8_lossy(bytes).into_owned())
    }
}

fn git(root: &Path, args: &[&str]) -> Result<Vec<u8>> {
    git_stdout_result(root, args).map_err(|e| {
        let text = e.to_string();
        anyhow!(text.replacen("git diff failed", "git failed", 1))
    })
}

/// The commit `rev` names. A leading `-` would be read as an option, so it is never a ref.
fn resolve_rev(root: &Path, rev: &str) -> Result<String> {
    if super::git_stdout(root, &["rev-parse", "--is-inside-work-tree"]).is_none() {
        bail!("{} is not a git repository", root.display());
    }
    let spec = format!("{rev}^{{commit}}");
    let oid = (!rev.is_empty() && !rev.starts_with('-'))
        .then(|| super::git_stdout(root, &["rev-parse", "--verify", "--quiet", &spec]))
        .flatten()
        .and_then(|out| String::from_utf8(out).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    oid.ok_or_else(|| anyhow!("unknown ref `{rev}` in {}", root.display()))
}

/// `path -> blob id` of every file in `rev`, relative to `root`.
fn tree_oids(root: &Path, rev: &str) -> Result<HashMap<String, String>> {
    let out = git(root, &["ls-tree", "-r", "-z", rev])?;
    Ok(out
        .split(|b| *b == 0)
        .filter_map(|entry| {
            let entry = std::str::from_utf8(entry).ok()?;
            let (meta, path) = entry.split_once('\t')?;
            let mut meta = meta.split(' ');
            let (_mode, kind, oid) = (meta.next()?, meta.next()?, meta.next()?);
            (kind == "blob").then(|| (path.to_string(), oid.to_string()))
        })
        .collect())
}

/// Files whose bytes differ between `from` and `to` (the working tree when `None`).
fn changed_paths(root: &Path, from: &str, to: Option<&str>) -> Result<BTreeSet<String>> {
    let mut args = vec![
        "diff",
        "--name-only",
        "-z",
        "--no-renames",
        "--relative",
        from,
    ];
    args.extend(to);
    let out = git(root, &args)?;
    Ok(out
        .split(|b| *b == 0)
        .filter(|s| !s.is_empty())
        .filter_map(|s| String::from_utf8(s.to_vec()).ok())
        .collect())
}

/// Reads each `(path, blob)` from one `git cat-file --batch` process and hands the bytes over.
fn each_blob(
    root: &Path,
    entries: &[(String, String)],
    mut f: impl FnMut(&str, Vec<u8>),
) -> Result<()> {
    if entries.is_empty() {
        return Ok(());
    }
    let mut child = Command::new("git")
        .arg("-C")
        .arg(root)
        .args(["cat-file", "--batch"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .context("git failed to start")?;
    let (mut stdin, stdout) = (
        child.stdin.take().context("git stdin")?,
        child.stdout.take().context("git stdout")?,
    );
    let mut out = BufReader::new(stdout);
    for chunk in entries.chunks(BATCH) {
        for (_, oid) in chunk {
            writeln!(stdin, "{oid}")?;
        }
        stdin.flush()?;
        for (path, _) in chunk {
            let mut head = String::new();
            out.read_line(&mut head)?;
            let size: usize = head
                .split_whitespace()
                .nth(2)
                .and_then(|n| n.parse().ok())
                .ok_or_else(|| anyhow!("git cat-file: unexpected reply for {path}"))?;
            let mut body = vec![0; size + 1];
            out.read_exact(&mut body)?;
            body.pop();
            f(path, body);
        }
    }
    drop(stdin);
    let _ = child.wait();
    Ok(())
}

/// The files `paths` names in `rev`, extracted as the index extracts a file on disk.
fn git_side(
    cx: &Ctx,
    root: &Path,
    tree: HashMap<String, String>,
    paths: &BTreeSet<String>,
) -> Result<Side> {
    let matcher = Matcher::new(root, &cx.plugin_config::<crate::config::Graph>("graph"));
    let entries: Vec<(String, String)> = paths
        .iter()
        .filter(|p| matcher.indexable(&root.join(p)))
        .filter_map(|p| Some((p.clone(), tree.get(p)?.clone())))
        .collect();
    let (mut defs, mut groups) = (
        Vec::new(),
        BTreeMap::<(String, String, String, String), i64>::new(),
    );
    each_blob(root, &entries, |path, bytes| {
        let Ok(src) = String::from_utf8(bytes) else {
            return;
        };
        let Some(rows) = index::rows_of(&root.join(path), &src, matcher.extensions()) else {
            return;
        };
        for r in rows.into_iter().filter(|r| !r.name.is_empty()) {
            if r.is_def {
                defs.push(DefRow {
                    path: path.to_string(),
                    name: r.name,
                    kind: r.kind,
                    line: r.line,
                    end_line: r.end_line,
                    signature: r.signature,
                    content_hash: r.content_hash,
                    start_byte: r.start_byte,
                    end_byte: r.end_byte,
                });
            } else if r.kind != "import" {
                *groups
                    .entry((path.to_string(), r.scope, r.name, r.kind))
                    .or_default() += 1;
            }
        }
    })?;
    let refs = groups
        .into_iter()
        .map(|((path, scope, name, kind), count)| RefGroup {
            path,
            scope,
            name,
            kind,
            count,
        })
        .collect();
    let oids = entries.into_iter().collect();
    Ok(Side {
        defs,
        refs,
        src: Src::Git {
            root: root.to_path_buf(),
            oids,
        },
        cache: HashMap::new(),
    })
}

/// Definitions of one file group by `(path, name, kind)`, in source order.
fn group(defs: &[DefRow]) -> BTreeMap<(&str, &str, &str), Vec<usize>> {
    let mut m: BTreeMap<_, Vec<usize>> = BTreeMap::new();
    for (i, d) in defs.iter().enumerate() {
        m.entry((d.path.as_str(), d.name.as_str(), d.kind.as_str()))
            .or_default()
            .push(i);
    }
    for v in m.values_mut() {
        v.sort_by_key(|&i| defs[i].line);
    }
    m
}

/// Definitions that hold other definitions (a module, a class). Their span changes whenever a
/// member does, which the member already reports, so only their own declaration counts.
fn containers(defs: &[DefRow]) -> Vec<bool> {
    let mut order: Vec<usize> = (0..defs.len()).collect();
    order.sort_by(|&a, &b| {
        let (a, b) = (&defs[a], &defs[b]);
        (&a.path, a.start_byte, -a.end_byte).cmp(&(&b.path, b.start_byte, -b.end_byte))
    });
    let mut out = vec![false; defs.len()];
    for w in order.windows(2) {
        let (a, b) = (&defs[w[0]], &defs[w[1]]);
        out[w[0]] = a.path == b.path && b.start_byte < a.end_byte;
    }
    out
}

fn edges(refs: &[RefGroup]) -> BTreeSet<Edge> {
    refs.iter()
        .map(|r| (r.path.clone(), r.scope.clone(), r.name.clone()))
        .collect()
}

/// Pairs one removed with one added definition that share a key; a key held by more than one on
/// either side pairs none, so a guess is never shown as a fact.
fn unique_pairs<K: std::hash::Hash + Eq>(
    removed: &mut Vec<DefRow>,
    added: &mut Vec<DefRow>,
    mut key: impl FnMut(bool, &DefRow) -> Option<K>,
) -> Vec<(DefRow, DefRow)> {
    let mut by: HashMap<K, (Vec<usize>, Vec<usize>)> = HashMap::new();
    for (i, d) in removed.iter().enumerate() {
        if let Some(k) = key(false, d) {
            by.entry(k).or_default().0.push(i);
        }
    }
    for (i, d) in added.iter().enumerate() {
        if let Some(k) = key(true, d) {
            by.entry(k).or_default().1.push(i);
        }
    }
    let pairs: Vec<(usize, usize)> = by
        .values()
        .filter(|(r, a)| r.len() == 1 && a.len() == 1)
        .map(|(r, a)| (r[0], a[0]))
        .collect();
    let gone: HashSet<usize> = pairs.iter().map(|p| p.0).collect();
    let new: HashSet<usize> = pairs.iter().map(|p| p.1).collect();
    let mut out: Vec<(DefRow, DefRow)> = pairs
        .iter()
        .map(|&(r, a)| (removed[r].clone(), added[a].clone()))
        .collect();
    out.sort_by(|a, b| (&a.0.path, a.0.line).cmp(&(&b.0.path, b.0.line)));
    for (list, drop) in [(removed, &gone), (added, &new)] {
        let mut i = 0;
        list.retain(|_| {
            i += 1;
            !drop.contains(&(i - 1))
        });
    }
    out
}

fn diff_sides(old: &mut Side, new: &mut Side) -> Diff {
    let mut d = Diff::default();
    let (old_box, new_box) = (containers(&old.defs), containers(&new.defs));
    let (go, gn) = (group(&old.defs), group(&new.defs));
    let keys: BTreeSet<_> = go.keys().chain(gn.keys()).copied().collect();
    for k in keys {
        let (o, n) = (
            go.get(&k).map_or(&[][..], Vec::as_slice),
            gn.get(&k).map_or(&[][..], Vec::as_slice),
        );
        for i in 0..o.len().max(n.len()) {
            match (o.get(i), n.get(i)) {
                (Some(&a), Some(&b)) => {
                    let (a_def, b_def) = (&old.defs[a], &new.defs[b]);
                    let hashes = !a_def.content_hash.is_empty() && !b_def.content_hash.is_empty();
                    let own_body = !old_box[a] && !new_box[b] && hashes;
                    if a_def.signature != b_def.signature
                        || (own_body && a_def.content_hash != b_def.content_hash)
                    {
                        d.changed.push(Changed {
                            old: a_def.clone(),
                            new: b_def.clone(),
                        });
                    }
                }
                (Some(&a), None) => d.removed.push(old.defs[a].clone()),
                (None, Some(&b)) => d.added.push(new.defs[b].clone()),
                (None, None) => {}
            }
        }
    }
    d.moved = unique_pairs(&mut d.removed, &mut d.added, |_, def| {
        (!def.content_hash.is_empty())
            .then(|| (def.name.clone(), def.kind.clone(), def.content_hash.clone()))
    });
    if d.removed.len() <= RENAME_CANDIDATES && d.added.len() <= RENAME_CANDIDATES {
        // The name sits inside the span, so a rename changes the hash; masking it compares the rest.
        d.renamed = unique_pairs(&mut d.removed, &mut d.added, |is_new, def| {
            let side = if is_new { &mut *new } else { &mut *old };
            let text = side.body(def)?;
            Some((def.kind.clone(), text.replace(&def.name, "\0")))
        });
    }
    // A renamed or moved caller keeps its calls; without this each of them shows as an edge pair.
    let follow: HashMap<(&str, &str), (&str, &str)> = d
        .renamed
        .iter()
        .chain(&d.moved)
        .map(|(a, b)| {
            (
                (a.path.as_str(), a.name.as_str()),
                (b.path.as_str(), b.name.as_str()),
            )
        })
        .collect();
    let before: BTreeSet<Edge> = old
        .refs
        .iter()
        .map(|r| match follow.get(&(r.path.as_str(), r.scope.as_str())) {
            Some((path, scope)) => (path.to_string(), scope.to_string(), r.name.clone()),
            None => (r.path.clone(), r.scope.clone(), r.name.clone()),
        })
        .collect();
    let after = edges(&new.refs);
    d.edges_added = after.difference(&before).cloned().collect();
    d.edges_removed = before.difference(&after).cloned().collect();
    d
}

/// The project's index as the new side, cut to `paths`; also adds the indexed files git does not
/// know at `tree` (new, untracked) to `paths`, because they are additions `git diff` cannot list.
fn working_side(
    rt: &Runtime,
    root: &Path,
    tree: &HashMap<String, String>,
    paths: &mut BTreeSet<String>,
) -> Result<Side> {
    let key = index::canon(root);
    let (mut defs, mut refs) = (
        rt.store.symbol_def_scan(&key)?,
        rt.store.symbol_ref_scan(&key)?,
    );
    for p in defs
        .iter()
        .map(|d| &d.path)
        .chain(refs.iter().map(|r| &r.path))
    {
        if !tree.contains_key(p) {
            paths.insert(p.clone());
        }
    }
    defs.retain(|d| paths.contains(&d.path));
    refs.retain(|r| paths.contains(&r.path));
    Ok(Side {
        defs,
        refs,
        src: Src::Disk(root.to_path_buf()),
        cache: HashMap::new(),
    })
}

/// One project's diff.
fn compute(rt: &Runtime, cx: &Ctx, root: &Path, q: &Query) -> Result<Diff> {
    let from = resolve_rev(root, q.from)?;
    let to = q.to.map(|t| resolve_rev(root, t)).transpose()?;
    index_for(cx, root)?;
    let old_tree = tree_oids(root, &from)?;
    let mut paths = changed_paths(root, &from, to.as_deref())?;
    let mut new = match &to {
        None => working_side(rt, root, &old_tree, &mut paths)?,
        Some(rev) => git_side(cx, root, tree_oids(root, rev)?, &paths)?,
    };
    let mut old = git_side(cx, root, old_tree, &paths)?;
    Ok(diff_sides(&mut old, &mut new))
}

/// Callers of `name` in the current indexes of the whole scope, so a change in B lists A's call
/// sites; each is `[project] path scope dN`.
fn callers(cx: &Ctx, keys: &[&str], labels: &[String], name: &str) -> Result<Vec<String>> {
    let mut rows = impact_walk_roots(cx, keys, name, CALLER_DEPTH, true)?;
    rows.sort_by(|a, b| (a.1, a.0, &a.2, &a.3).cmp(&(b.1, b.0, &b.2, &b.3)));
    Ok(rows
        .into_iter()
        .map(|(i, d, path, scope)| {
            let scope = if scope.is_empty() {
                String::new()
            } else {
                format!(" {scope}")
            };
            format!("{}{path}{scope} d{d}", labels[i])
        })
        .collect())
}

struct Part {
    name: String,
    diff: Diff,
    callers: HashMap<String, Vec<String>>,
}

fn def_text(d: &DefRow) -> String {
    format!("{} {}  {}:{}", d.kind, d.name, d.path, d.line)
}

fn listed<T>(out: &mut String, title: &str, rows: &[T], line: impl Fn(&T) -> String) {
    if rows.is_empty() {
        return;
    }
    let _ = writeln!(out, "{title} ({})", rows.len());
    for r in rows {
        let _ = writeln!(out, "  {}", line(r));
    }
}

fn edge_text((path, scope, name): &Edge) -> String {
    if scope.is_empty() {
        format!("{path} -> {name}")
    } else {
        format!("{path} {scope} -> {name}")
    }
}

fn render_part(out: &mut String, p: &Part, tag: &str) {
    let d = &p.diff;
    let _ = writeln!(
        out,
        "{tag}{} changed · {} added · {} removed · {} renamed · {} moved · edges +{} -{}",
        d.changed.len(),
        d.added.len(),
        d.removed.len(),
        d.renamed.len(),
        d.moved.len(),
        d.edges_added.len(),
        d.edges_removed.len()
    );
    let callers = |name: &str| p.callers.get(name).map_or(&[][..], Vec::as_slice);
    let mut changed: Vec<&Changed> = d.changed.iter().collect();
    changed.sort_by_key(|c| std::cmp::Reverse(callers(&c.new.name).len()));
    if !changed.is_empty() {
        let _ = writeln!(out, "changed ({})", changed.len());
    }
    for c in &changed {
        let what = if c.old.signature != c.new.signature {
            "signature"
        } else {
            "body"
        };
        let list = callers(&c.new.name);
        let _ = writeln!(
            out,
            "  {}  [{what}] {} callers",
            def_text(&c.new),
            list.len()
        );
        for l in list.iter().take(CALLERS_SHOWN) {
            let _ = writeln!(out, "    {l}");
        }
    }
    listed(out, "removed", &d.removed, |r| {
        format!("{}  {} callers", def_text(r), callers(&r.name).len())
    });
    listed(out, "renamed", &d.renamed, |(a, b)| {
        format!("{} -> {}  {}:{}", a.name, b.name, b.path, b.line)
    });
    listed(out, "moved", &d.moved, |(a, b)| {
        format!("{} {}  {} -> {}", a.kind, a.name, a.path, b.path)
    });
    listed(out, "added", &d.added, def_text);
    listed(out, "edges added", &d.edges_added, edge_text);
    listed(out, "edges removed", &d.edges_removed, edge_text);
}

fn def_json(d: &DefRow) -> Value {
    json!({"name": d.name, "kind": d.kind, "path": d.path, "line": d.line})
}

fn edge_json((path, scope, name): &Edge) -> Value {
    json!({"path": path, "scope": scope, "name": name})
}

fn part_json(p: &Part) -> Value {
    let d = &p.diff;
    let callers = |name: &str| p.callers.get(name).cloned().unwrap_or_default();
    json!({
        "project": p.name,
        "changed": d.changed.iter().map(|c| {
            let mut v = def_json(&c.new);
            v["signature_changed"] = json!(c.old.signature != c.new.signature);
            v["callers"] = json!(callers(&c.new.name));
            v
        }).collect::<Vec<_>>(),
        "added": d.added.iter().map(def_json).collect::<Vec<_>>(),
        "removed": d.removed.iter().map(|r| {
            let mut v = def_json(r);
            v["callers"] = json!(callers(&r.name));
            v
        }).collect::<Vec<_>>(),
        "renamed": d.renamed.iter()
            .map(|(a, b)| json!({"from": def_json(a), "to": def_json(b)}))
            .collect::<Vec<_>>(),
        "moved": d.moved.iter()
            .map(|(a, b)| json!({"from": def_json(a), "to": def_json(b)}))
            .collect::<Vec<_>>(),
        "edges_added": d.edges_added.iter().map(edge_json).collect::<Vec<_>>(),
        "edges_removed": d.edges_removed.iter().map(edge_json).collect::<Vec<_>>(),
    })
}

/// The diff of every project of `scope` between `q.from` and `q.to`. The text is ordered counts,
/// changed (most callers first), removed, renamed, moved, added, edges, and goes through the graph
/// cap, so a long one ends in `N more, expand <id>` with the rest archived; `--json` is whole.
pub fn run(rt: &Runtime, scope: &[Member], q: &Query) -> Result<String> {
    let cx = Ctx::new(rt);
    let (done, notes) = fan_out(scope, |m| {
        walkable(m)?;
        compute(rt, &cx, &m.root, q)
    })?;
    let labels: Vec<String> = done
        .iter()
        .map(|(m, _)| {
            if scope.len() > 1 {
                format!("[{}] ", m.name)
            } else {
                String::new()
            }
        })
        .collect();
    let keys: Vec<String> = done.iter().map(|(m, _)| index::canon(&m.root)).collect();
    let key_refs: Vec<&str> = keys.iter().map(String::as_str).collect();
    let mut parts = Vec::new();
    for (m, diff) in done {
        let mut found = HashMap::new();
        for name in diff.touched() {
            if !found.contains_key(name) {
                found.insert(name.to_string(), callers(&cx, &key_refs, &labels, name)?);
            }
        }
        parts.push(Part {
            name: m.name.clone(),
            diff,
            callers: found,
        });
    }
    let to = q.to.unwrap_or("working");
    if q.json {
        let projects: Vec<Value> = parts.iter().map(part_json).collect();
        return Ok(format!(
            "{}\n",
            json!({"from": q.from, "to": to, "projects": projects})
        ));
    }
    let mut body = format!("graph diff {} -> {to} (tags)\n", q.from);
    if parts.iter().all(|p| p.diff.is_empty()) {
        body.push_str("no graph changes\n");
    }
    for (p, label) in parts
        .iter()
        .zip(&labels)
        .filter(|(p, _)| !p.diff.is_empty())
    {
        render_part(&mut body, p, label);
    }
    super::cap(&cx, format!("{notes}{body}"))
}

/// MCP `graph_diff`: `from` (default `HEAD`), `to` (default the working tree).
pub fn call(rt: &Runtime, args: &Value, scope: &[Member]) -> Result<String> {
    let arg = |k: &str| args[k].as_str().filter(|s| !s.is_empty());
    let to = arg("to").filter(|t| *t != "working");
    run(
        rt,
        scope,
        &Query {
            from: arg("from").unwrap_or("HEAD"),
            to,
            json: false,
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plugins::graph::index::tests::cx;
    use crate::store::{LinkKind, Origin};
    use std::fs;

    fn git(dir: &Path, args: &[&str]) {
        let ok = Command::new("git")
            .current_dir(dir)
            .envs([
                ("GIT_AUTHOR_NAME", "t"),
                ("GIT_AUTHOR_EMAIL", "t@t"),
                ("GIT_COMMITTER_NAME", "t"),
                ("GIT_COMMITTER_EMAIL", "t@t"),
            ])
            .args(args)
            .status()
            .unwrap()
            .success();
        assert!(ok, "{args:?}");
    }

    fn commit(dir: &Path) {
        git(dir, &["add", "-A"]);
        git(dir, &["commit", "-q", "-m", "c"]);
    }

    /// A git project at `dir/name` holding `files`, committed once.
    fn project(dir: &Path, name: &str, files: &[(&str, &str)]) -> PathBuf {
        let root = dir.join(name);
        fs::create_dir_all(&root).unwrap();
        for (path, src) in files {
            fs::write(root.join(path), src).unwrap();
        }
        git(&root, &["init", "-q", "-b", "main"]);
        commit(&root);
        root
    }

    fn solo(root: &Path) -> Vec<Member> {
        vec![Member {
            name: String::new(),
            root: root.to_path_buf(),
        }]
    }

    fn ask(rt: &Runtime, scope: &[Member], from: &str, to: Option<&str>) -> Result<String> {
        run(
            rt,
            scope,
            &Query {
                from,
                to,
                json: false,
            },
        )
    }

    /// `a` calls `shared`, which `b` defines; `a` is linked to `b`.
    fn linked(tag: &str) -> (Runtime, PathBuf, Vec<Member>) {
        let (rt, dir) = cx(tag);
        let a = project(
            &dir,
            "a",
            &[("lib.rs", "fn a_caller() {\n    shared();\n}\n")],
        );
        let b = project(&dir, "b", &[("lib.rs", "fn shared() {}\n")]);
        let ids: Vec<i32> = [&a, &b]
            .iter()
            .map(|p| rt.store.register_project(p, Origin::Manual).unwrap().id)
            .collect();
        rt.store
            .link_projects(ids[0], ids[1], LinkKind::Manual, None)
            .unwrap();
        let scope = super::super::scope::resolve(&rt.store, None, &a).unwrap();
        (rt, dir, scope)
    }

    fn tree(dir: &Path) -> Vec<String> {
        let mut out = Vec::new();
        let mut stack = vec![dir.to_path_buf()];
        while let Some(d) = stack.pop() {
            for e in fs::read_dir(&d).unwrap().flatten() {
                let p = e.path();
                // Background `git maintenance` from the fixture's commits writes
                // lock files under `.git` at any moment; the check is about the
                // working tree.
                if p.file_name().is_some_and(|n| n == ".git") {
                    continue;
                }
                if p.is_dir() {
                    stack.push(p);
                } else {
                    out.push(format!(
                        "{} {}",
                        p.display(),
                        fs::read_to_string(&p).unwrap_or_default().len()
                    ));
                }
            }
        }
        out.sort();
        out
    }

    #[test]
    fn a_signature_change_in_b_lists_a_s_call_sites_and_leaves_the_tree_alone() {
        let (rt, dir, scope) = linked("t32918-sig");
        fs::write(dir.join("b/lib.rs"), "fn shared(x: i32) {}\n").unwrap();
        let before = tree(&dir.join("b"));
        let out = ask(&rt, &scope, "HEAD", None).unwrap();
        assert!(
            out.starts_with("graph diff HEAD -> working (tags)\n"),
            "{out}"
        );
        assert!(out.contains("[b] 1 changed"), "{out}");
        assert!(
            out.contains("function shared  lib.rs:1  [signature] 1 callers"),
            "{out}"
        );
        assert!(out.contains("    [a] lib.rs a_caller d1"), "{out}");
        assert_eq!(tree(&dir.join("b")), before);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_body_change_is_a_change_and_an_untouched_file_is_not_read() {
        let (cx, dir) = cx("t32918-body");
        let root = project(
            &dir,
            "p",
            &[
                ("lib.rs", "fn f() {\n    one();\n}\n"),
                ("same.rs", "fn s() {}\n"),
            ],
        );
        fs::write(root.join("lib.rs"), "fn f() {\n    two();\n}\n").unwrap();
        let out = ask(&cx, &solo(&root), "HEAD", None).unwrap();
        assert!(out.contains("1 changed · 0 added · 0 removed"), "{out}");
        assert!(out.contains("[body]"), "{out}");
        assert!(out.contains("edges +1 -1"), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_rename_is_a_rename_and_not_a_remove_plus_an_add() {
        let (cx, dir) = cx("t32918-rename");
        let root = project(
            &dir,
            "p",
            &[("lib.rs", "fn old_name() {\n    work();\n}\nfn work() {}\n")],
        );
        fs::write(
            root.join("lib.rs"),
            "fn new_name() {\n    work();\n}\nfn work() {}\n",
        )
        .unwrap();
        let out = ask(&cx, &solo(&root), "HEAD", None).unwrap();
        assert!(out.contains("1 renamed"), "{out}");
        assert!(out.contains("old_name -> new_name"), "{out}");
        assert!(!out.contains("\nadded ("), "{out}");
        assert!(!out.contains("\nremoved ("), "{out}");
        assert!(out.contains("edges +0 -0"), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn two_candidates_for_one_rename_stay_a_remove_plus_an_add() {
        let (cx, dir) = cx("t32918-ambiguous");
        let root = project(
            &dir,
            "p",
            &[(
                "lib.rs",
                "fn one() {\n    w();\n}\nfn two() {\n    w();\n}\n",
            )],
        );
        fs::write(
            root.join("lib.rs"),
            "fn uno() {\n    w();\n}\nfn dos() {\n    w();\n}\n",
        )
        .unwrap();
        let out = ask(&cx, &solo(&root), "HEAD", None).unwrap();
        assert!(out.contains("0 renamed"), "{out}");
        assert!(
            out.contains("2 added") && out.contains("2 removed"),
            "{out}"
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_definition_in_another_file_is_moved_and_a_new_file_is_seen() {
        let (cx, dir) = cx("t32918-moved");
        let root = project(
            &dir,
            "p",
            &[("a.rs", "fn helper() {\n    work();\n}\nfn keep() {}\n")],
        );
        fs::write(root.join("a.rs"), "fn keep() {}\n").unwrap();
        fs::write(root.join("b.rs"), "fn helper() {\n    work();\n}\n").unwrap();
        let out = ask(&cx, &solo(&root), "HEAD", None).unwrap();
        assert!(out.contains("1 moved"), "{out}");
        assert!(out.contains("function helper  a.rs -> b.rs"), "{out}");
        assert!(out.contains("0 added · 0 removed"), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn added_and_removed_definitions_are_listed() {
        let (cx, dir) = cx("t32918-addrm");
        let root = project(&dir, "p", &[("lib.rs", "fn gone() {}\nfn stays() {}\n")]);
        fs::write(
            root.join("lib.rs"),
            "fn stays() {}\nfn fresh() {\n    other();\n}\n",
        )
        .unwrap();
        let out = ask(&cx, &solo(&root), "HEAD", None).unwrap();
        assert!(out.contains("added (1)\n  function fresh"), "{out}");
        assert!(out.contains("removed (1)\n  function gone"), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn two_revisions_compare_without_the_working_tree() {
        let (cx, dir) = cx("t32918-revs");
        let root = project(&dir, "p", &[("lib.rs", "fn f() {}\n")]);
        fs::write(root.join("lib.rs"), "fn f(x: u8) {}\n").unwrap();
        commit(&root);
        fs::write(
            root.join("lib.rs"),
            "fn f(x: u8, y: u8) {}\nfn dirty() {}\n",
        )
        .unwrap();
        let out = ask(&cx, &solo(&root), "HEAD~1", Some("HEAD")).unwrap();
        assert!(
            out.starts_with("graph diff HEAD~1 -> HEAD (tags)\n"),
            "{out}"
        );
        assert!(out.contains("1 changed · 0 added"), "{out}");
        assert!(!out.contains("dirty"), "{out}");
        let same = ask(&cx, &solo(&root), "HEAD", Some("HEAD")).unwrap();
        assert!(same.contains("no graph changes"), "{same}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn an_unknown_ref_and_a_directory_without_git_are_errors_that_name_them() {
        let (cx, dir) = cx("t32918-errors");
        let root = project(&dir, "p", &[("lib.rs", "fn f() {}\n")]);
        for bad in ["nope", "--output=x", ""] {
            let err = ask(&cx, &solo(&root), bad, None).unwrap_err().to_string();
            assert!(err.contains(&format!("unknown ref `{bad}`")), "{err}");
        }
        let plain = dir.join("plain");
        fs::create_dir_all(&plain).unwrap();
        let err = ask(&cx, &solo(&plain), "HEAD", None)
            .unwrap_err()
            .to_string();
        assert!(err.contains("is not a git repository"), "{err}");
        assert!(!root.join("x").exists());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_long_answer_is_cut_with_an_id_that_pages_the_rest() {
        let (cx, dir) = cx("t32918-cap");
        let root = project(&dir, "p", &[("lib.rs", "fn base() {}\n")]);
        let many: String = (0..1500)
            .map(|i| format!("fn added{i:04}() {{}}\n"))
            .collect();
        fs::write(root.join("lib.rs"), format!("fn base() {{}}\n{many}")).unwrap();
        let out = call(&cx, &json!({}), &solo(&root)).unwrap();
        assert!(
            out.starts_with("graph diff HEAD -> working (tags)\n0 changed · 1500 added"),
            "{out}"
        );
        let (_, id) = out.rsplit_once("more, expand ").expect(&out);
        let full = Ctx::new(&cx).get_archive(id.trim()).unwrap().unwrap();
        assert!(
            String::from_utf8(full)
                .unwrap()
                .contains("function added1499")
        );
        assert!(!out.contains("added1499"));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn json_carries_every_row_and_the_callers() {
        let (rt, dir, scope) = linked("t32918-json");
        fs::write(dir.join("b/lib.rs"), "fn shared(x: i32) {}\n").unwrap();
        let out = run(
            &rt,
            &scope,
            &Query {
                from: "HEAD",
                to: None,
                json: true,
            },
        )
        .unwrap();
        let v: Value = serde_json::from_str(&out).unwrap();
        let b = v["projects"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["project"] == "b")
            .unwrap();
        assert_eq!(b["changed"][0]["name"], "shared");
        assert_eq!(b["changed"][0]["signature_changed"], true);
        assert_eq!(b["changed"][0]["callers"][0], "[a] lib.rs a_caller d1");
        let _ = fs::remove_dir_all(dir);
    }
}

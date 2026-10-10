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
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::export::{self, Export};
use super::projects;
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
    /// `REF` for every project, `PROJECT:REF` for one (a git ref never holds a colon); none is
    /// `HEAD`.
    pub from: &'a [String],
    /// `None` is the working tree.
    pub to: Option<&'a str>,
    pub json: bool,
    /// A saved `symbols` export as the old side instead of a revision (CLI only: an MCP caller
    /// must not make rtok read a path a model chose).
    pub export: Option<&'a Path>,
}

/// A link of the registry, by project names because an export's ids belong to another registry.
type LinkKey = (String, String, String);

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
    /// Files that differ but no grammar read, with why.
    pub not_analysed: Vec<(String, &'static str)>,
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
            && self.not_analysed.is_empty()
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
    /// Files present on this side that were not UTF-8 or that the grammar failed on.
    unread: Vec<String>,
}

enum Src {
    /// A saved export: names and places only, so no signature, hash or body to compare.
    Names,
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
                Src::Names => return None,
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
    matcher: &Matcher,
    root: &Path,
    tree: HashMap<String, String>,
    paths: &BTreeSet<String>,
) -> Result<Side> {
    let entries: Vec<(String, String)> = paths
        .iter()
        .filter(|p| matcher.indexable(&root.join(p)))
        .filter_map(|p| Some((p.clone(), tree.get(p)?.clone())))
        .collect();
    let (mut defs, mut groups, mut unread) = (
        Vec::new(),
        BTreeMap::<(String, String, String, String), i64>::new(),
        Vec::new(),
    );
    each_blob(root, &entries, |path, bytes| {
        let rows = String::from_utf8(bytes)
            .ok()
            .and_then(|src| index::rows_of(&root.join(path), &src, matcher.extensions()));
        let Some(rows) = rows else {
            unread.push(path.to_string());
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
        unread,
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
    // A saved export has no signatures or hashes, so it can say what appeared and vanished only.
    let blind = matches!(old.src, Src::Names);
    let keys: BTreeSet<_> = go.keys().chain(gn.keys()).copied().collect();
    for k in keys {
        let (o, n) = (
            go.get(&k).map_or(&[][..], Vec::as_slice),
            gn.get(&k).map_or(&[][..], Vec::as_slice),
        );
        for i in 0..o.len().max(n.len()) {
            match (o.get(i), n.get(i)) {
                (Some(&a), Some(&b)) => {
                    if blind {
                        continue;
                    }
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
        unread: Vec::new(),
    })
}

/// One project's diff between revisions.
fn compute(rt: &Runtime, cx: &Ctx, root: &Path, rev: &str, to: Option<&str>) -> Result<Diff> {
    let from = resolve_rev(root, rev)?;
    let to = to.map(|t| resolve_rev(root, t)).transpose()?;
    index_for(cx, root)?;
    let matcher = Matcher::new(root, &cx.plugin_config::<crate::config::Graph>("graph"));
    let old_tree = tree_oids(root, &from)?;
    let mut paths = changed_paths(root, &from, to.as_deref())?;
    // Taken before untracked files join `paths`: only what git reports as different can be a
    // file nobody read.
    let listed = paths.clone();
    let mut new = match &to {
        None => working_side(rt, root, &old_tree, &mut paths)?,
        Some(rev) => git_side(&matcher, root, tree_oids(root, rev)?, &paths)?,
    };
    let mut old = git_side(&matcher, root, old_tree, &paths)?;
    // The working side is the index, which keeps no record of a file it could not read.
    let unreadable_now: Vec<&str> = listed
        .iter()
        .filter(|_| to.is_none())
        .filter(|p| std::fs::read(root.join(p)).is_ok_and(|b| std::str::from_utf8(&b).is_err()))
        .map(String::as_str)
        .collect();
    let unread: HashSet<&str> = old
        .unread
        .iter()
        .chain(&new.unread)
        .map(String::as_str)
        .chain(unreadable_now)
        .collect();
    let not_analysed = listed
        .iter()
        .filter(|p| !matcher.is_excluded(&root.join(p)))
        .filter_map(|p| {
            if !matcher.has_supported_ext(Path::new(p)) {
                Some((p.clone(), "no grammar"))
            } else {
                unread
                    .contains(p.as_str())
                    .then(|| (p.clone(), "not parsed"))
            }
        })
        .collect();
    let mut d = diff_sides(&mut old, &mut new);
    d.not_analysed = not_analysed;
    Ok(d)
}

/// One project against a saved export: what appeared and vanished by `(path, name, kind)`. The
/// export keeps no signatures and its edges are between symbols whose ambiguous calls were
/// dropped, so a change and an edge cannot be told from the export alone and are not reported.
fn compute_saved(rt: &Runtime, cx: &Ctx, m: &Member, e: &Export) -> Result<Diff> {
    if e.meta.level == "overview" {
        return Ok(Diff::default());
    }
    let ids: HashSet<i32> = e
        .projects
        .iter()
        .filter(|p| p.name == m.name)
        .map(|p| p.id)
        .collect();
    if ids.is_empty() {
        bail!("{} is not in the export", m.name);
    }
    index_for(cx, &m.root)?;
    let defs = e
        .nodes
        .iter()
        .filter(|n| ids.contains(&n.project))
        .map(|n| DefRow {
            path: n.path.clone(),
            name: n.name.clone(),
            kind: n.kind.clone(),
            line: n.line,
            end_line: n.line,
            signature: String::new(),
            content_hash: String::new(),
            start_byte: 0,
            end_byte: 0,
        })
        .collect();
    let mut old = Side {
        defs,
        refs: Vec::new(),
        src: Src::Names,
        cache: HashMap::new(),
        unread: Vec::new(),
    };
    let mut new = working_side(rt, &m.root, &HashMap::new(), &mut BTreeSet::new())?;
    new.refs.clear();
    Ok(diff_sides(&mut old, &mut new))
}

/// Registry links of `old` that the current registry lacks, and the other way round, over the
/// projects both sides name; a link to a project only one side has says nothing about the link.
fn links_between(
    rt: &Runtime,
    scope: &[Member],
    old: &Export,
) -> Result<(Vec<LinkKey>, Vec<LinkKey>)> {
    let now = export::collect(
        rt,
        scope,
        &export::Query {
            level: export::Level::Overview,
            focus: None,
            depth: 2,
            redact: false,
            pretty: false,
            from: None,
        },
    )?;
    let keys = |e: &Export| -> BTreeSet<LinkKey> {
        let name = |id: i32| {
            e.projects
                .iter()
                .find(|p| p.id == id)
                .map(|p| p.name.clone())
        };
        e.links
            .iter()
            .filter_map(|l| Some((name(l.from)?, name(l.to)?, l.kind.clone())))
            .collect()
    };
    let (before, after) = (keys(old), keys(&now));
    let known = |names: &HashSet<&str>, l: &LinkKey| {
        names.contains(l.0.as_str()) && names.contains(l.1.as_str())
    };
    let both: HashSet<&str> = old
        .projects
        .iter()
        .map(|p| p.name.as_str())
        .filter(|n| now.projects.iter().any(|p| p.name == *n))
        .collect();
    Ok((
        after
            .iter()
            .filter(|l| !before.contains(*l) && known(&both, l))
            .cloned()
            .collect(),
        before
            .iter()
            .filter(|l| !after.contains(*l) && known(&both, l))
            .cloned()
            .collect(),
    ))
}

/// The revision each project is compared from: its own `PROJECT:REF`, else the shared `REF`.
struct Refs<'a> {
    shared: &'a str,
    own: Vec<(&'a str, &'a str)>,
}

impl<'a> Refs<'a> {
    fn parse(rt: &Runtime, scope: &[Member], specs: &'a [String]) -> Result<Self> {
        let mut shared = None;
        let mut own = Vec::new();
        for spec in specs {
            // A name that no project of the scope answers to leaves the whole text a ref, so a
            // reflog date such as `@{yesterday 10:00}` still works and a typo is named by the
            // unknown-ref error.
            match spec.rsplit_once(':') {
                Some((who, rev)) if scope.iter().any(|m| answers(rt, who, m)) => {
                    own.push((who, rev));
                }
                _ if shared.is_some() => bail!("--from names a ref for the other projects twice"),
                _ => shared = Some(spec.as_str()),
            }
        }
        Ok(Self {
            shared: shared.unwrap_or("HEAD"),
            own,
        })
    }

    fn of(&self, rt: &Runtime, m: &Member) -> &'a str {
        self.own
            .iter()
            .rev()
            .find(|(who, _)| answers(rt, who, m))
            .map_or(self.shared, |(_, rev)| rev)
    }
}

/// `who` is the member's name, or an id or directory `rtok graph projects` knows for its root.
fn answers(rt: &Runtime, who: &str, m: &Member) -> bool {
    who == m.name
        || projects::resolve(&rt.store, who)
            .is_ok_and(|p| index::canon(Path::new(&p.root)) == index::canon(&m.root))
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
    /// The revision this project was compared from.
    rev: String,
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
    if !d.not_analysed.is_empty() {
        let _ = writeln!(out, "{tag}{} changed, not analysed", d.not_analysed.len());
    }
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
    listed(
        out,
        "changed, not analysed",
        &d.not_analysed,
        |(path, why)| format!("{path}  ({why})"),
    );
}

fn link_text((from, to, kind): &LinkKey) -> String {
    format!("{from} -> {to} ({kind})")
}

/// `--json` and the graph page's `diff` frame: one typed shape, so the page never recomputes or
/// re-parses what the CLI prints.
#[derive(Debug, Serialize, JsonSchema)]
pub struct DiffReport {
    pub from: String,
    pub to: String,
    pub projects: Vec<DiffProject>,
    pub links_added: Vec<DiffLink>,
    pub links_removed: Vec<DiffLink>,
    /// Linked projects that could not answer, and what an export cannot compare.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub notes: String,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DiffProject {
    pub project: String,
    pub from: String,
    pub changed: Vec<DiffDef>,
    pub added: Vec<DiffDef>,
    pub removed: Vec<DiffDef>,
    pub renamed: Vec<DiffMove>,
    pub moved: Vec<DiffMove>,
    pub edges_added: Vec<DiffEdge>,
    pub edges_removed: Vec<DiffEdge>,
    pub not_analysed: Vec<DiffUnread>,
    /// Rows left out of the lists by [`DiffReport::capped`].
    #[serde(skip_serializing_if = "is_zero")]
    pub more: usize,
}

fn is_zero(n: &usize) -> bool {
    *n == 0
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DiffDef {
    pub name: String,
    pub kind: String,
    pub path: String,
    pub line: i32,
    /// `changed` rows only: the signature moved, not just the body.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signature_changed: Option<bool>,
    /// `changed` and `removed` rows only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub callers: Option<Vec<String>>,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DiffMove {
    pub from: DiffDef,
    pub to: DiffDef,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DiffEdge {
    pub path: String,
    pub scope: String,
    pub name: String,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DiffLink {
    pub from: String,
    pub to: String,
    pub kind: String,
}

#[derive(Debug, Serialize, JsonSchema)]
pub struct DiffUnread {
    pub path: String,
    pub reason: String,
}

impl DiffReport {
    /// At most `n` rows per list and project, the rest counted in `more`: a refactor of thousands
    /// of symbols must not become one websocket frame.
    pub fn capped(mut self, n: usize) -> Self {
        fn cut<T>(v: &mut Vec<T>, n: usize) -> usize {
            let dropped = v.len().saturating_sub(n);
            v.truncate(n);
            dropped
        }
        for p in &mut self.projects {
            p.more = cut(&mut p.changed, n)
                + cut(&mut p.added, n)
                + cut(&mut p.removed, n)
                + cut(&mut p.renamed, n)
                + cut(&mut p.moved, n)
                + cut(&mut p.edges_added, n)
                + cut(&mut p.edges_removed, n)
                + cut(&mut p.not_analysed, n);
        }
        self
    }
}

fn link_row((from, to, kind): &LinkKey) -> DiffLink {
    DiffLink {
        from: from.clone(),
        to: to.clone(),
        kind: kind.clone(),
    }
}

fn def_row(d: &DefRow) -> DiffDef {
    DiffDef {
        name: d.name.clone(),
        kind: d.kind.clone(),
        path: d.path.clone(),
        line: d.line,
        signature_changed: None,
        callers: None,
    }
}

fn move_row((a, b): &(DefRow, DefRow)) -> DiffMove {
    DiffMove {
        from: def_row(a),
        to: def_row(b),
    }
}

fn edge_row((path, scope, name): &Edge) -> DiffEdge {
    DiffEdge {
        path: path.clone(),
        scope: scope.clone(),
        name: name.clone(),
    }
}

fn project_report(p: &Part) -> DiffProject {
    let d = &p.diff;
    let callers = |name: &str| Some(p.callers.get(name).cloned().unwrap_or_default());
    DiffProject {
        project: p.name.clone(),
        from: p.rev.clone(),
        changed: d
            .changed
            .iter()
            .map(|c| DiffDef {
                signature_changed: Some(c.old.signature != c.new.signature),
                callers: callers(&c.new.name),
                ..def_row(&c.new)
            })
            .collect(),
        added: d.added.iter().map(def_row).collect(),
        removed: d
            .removed
            .iter()
            .map(|r| DiffDef {
                callers: callers(&r.name),
                ..def_row(r)
            })
            .collect(),
        renamed: d.renamed.iter().map(move_row).collect(),
        moved: d.moved.iter().map(move_row).collect(),
        edges_added: d.edges_added.iter().map(edge_row).collect(),
        edges_removed: d.edges_removed.iter().map(edge_row).collect(),
        not_analysed: d
            .not_analysed
            .iter()
            .map(|(path, why)| DiffUnread {
                path: path.clone(),
                reason: (*why).to_string(),
            })
            .collect(),
        more: 0,
    }
}

/// A saved export as the old side, with the name the answer calls it by.
pub struct Saved {
    pub export: Export,
    pub label: String,
}

impl Saved {
    /// A file the user named (`--export`, the TUI's compare view); `export::read` checks its size
    /// and schema before anything is compared.
    pub fn read(path: &Path) -> Result<Self> {
        Ok(Self {
            export: export::read(path)?,
            label: path.display().to_string(),
        })
    }
}

/// Everything `run` prints and `report` returns, before either shapes it.
struct Gathered {
    parts: Vec<Part>,
    labels: Vec<String>,
    links: (Vec<LinkKey>, Vec<LinkKey>),
    notes: String,
    from: String,
    to: String,
    shared: String,
    saved: bool,
}

fn gather(
    rt: &Runtime,
    scope: &[Member],
    from: &[String],
    to: Option<&str>,
    saved: Option<&Saved>,
) -> Result<Gathered> {
    let cx = Ctx::new(rt);
    if let Some(s) = saved {
        if to.is_some() || !from.is_empty() {
            bail!("a saved export is compared with the working tree; drop --from and --to");
        }
        if s.export.meta.level == "focus" {
            bail!(
                "{} holds a part of the graph; export with --level symbols to compare",
                s.label
            );
        }
    }
    let refs = Refs::parse(rt, scope, from)?;
    let (done, mut notes) = fan_out(scope, |m| {
        walkable(m)?;
        match saved {
            Some(s) => compute_saved(rt, &cx, m, &s.export),
            None => compute(rt, &cx, &m.root, refs.of(rt, m), to),
        }
    })?;
    let links = saved
        .map(|s| links_between(rt, scope, &s.export))
        .transpose()?
        .unwrap_or_default();
    if let Some(s) = saved {
        notes.push_str(&if s.export.meta.level == "overview" {
            "note: the export has no symbols (made with --level overview); only links are compared\n".to_string()
        } else {
            "note: against an export only added and removed symbols are listed; changes and edges need a revision\n".to_string()
        });
    }
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
            rev: refs.of(rt, m).to_string(),
            diff,
            callers: found,
        });
    }
    Ok(Gathered {
        parts,
        labels,
        links,
        notes,
        from: saved.map_or_else(
            || refs.shared.to_string(),
            |s| format!("export {}", s.label),
        ),
        to: to.unwrap_or("working").to_string(),
        shared: refs.shared.to_string(),
        saved: saved.is_some(),
    })
}

/// The diff of `scope` as the page and `--json` carry it: whole, with the callers of every
/// changed or removed symbol.
pub fn report(
    rt: &Runtime,
    scope: &[Member],
    from: &[String],
    to: Option<&str>,
    saved: Option<&Saved>,
) -> Result<DiffReport> {
    let g = gather(rt, scope, from, to, saved)?;
    Ok(DiffReport {
        from: g.from,
        to: g.to,
        projects: g.parts.iter().map(project_report).collect(),
        links_added: g.links.0.iter().map(link_row).collect(),
        links_removed: g.links.1.iter().map(link_row).collect(),
        notes: g.notes,
    })
}

/// The diff of every project of `scope` between `q.from` and `q.to`. The text is ordered counts,
/// changed (most callers first), removed, renamed, moved, added, edges, and goes through the graph
/// cap, so a long one ends in `N more, expand <id>` with the rest archived; `--json` is whole.
pub fn run(rt: &Runtime, scope: &[Member], q: &Query) -> Result<String> {
    let saved = q.export.map(Saved::read).transpose()?;
    if q.json {
        let r = report(rt, scope, q.from, q.to, saved.as_ref())?;
        return Ok(format!("{}\n", serde_json::to_string(&r)?));
    }
    let cx = Ctx::new(rt);
    let g = gather(rt, scope, q.from, q.to, saved.as_ref())?;
    let mut body = format!("graph diff {} -> {} (tags)\n", g.from, g.to);
    if g.parts.iter().all(|p| p.diff.is_empty()) && g.links.0.is_empty() && g.links.1.is_empty() {
        body.push_str("no graph changes\n");
    }
    for (p, label) in g
        .parts
        .iter()
        .zip(&g.labels)
        .filter(|(p, _)| !p.diff.is_empty())
    {
        // A project compared from its own ref says so; the shared one is in the title.
        let tag = if !g.saved && p.rev != g.shared {
            format!("[{} from {}] ", p.name, p.rev)
        } else {
            label.clone()
        };
        render_part(&mut body, p, &tag);
    }
    let mut tail = String::new();
    listed(&mut tail, "links added", &g.links.0, link_text);
    listed(&mut tail, "links removed", &g.links.1, link_text);
    body.push_str(&tail);
    super::cap(&cx, format!("{}{body}", g.notes))
}

/// What the graph page's Compare mode asks (T329.35). The old side is never a path: the page
/// sends the text of a saved export it opened itself, because the websocket answers anything on
/// localhost and must not be made to read a file somebody else chose.
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct DiffRequest {
    /// An id or a root path, as in `rtok graph projects`.
    pub project: String,
    /// As `rtok graph diff --from`: a ref, or `PROJECT:REF`; none is `HEAD`.
    #[serde(default)]
    pub from: Vec<String>,
    /// A ref; none is the working tree.
    #[serde(default)]
    pub to: Option<String>,
    #[serde(default)]
    pub export: Option<DiffExport>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct DiffExport {
    /// The file's name, for the answer's `from`.
    pub name: String,
    /// The file's content.
    pub text: String,
}

/// Rows per list and project in one frame; `DiffProject::more` counts the rest.
const PAGE_ROWS: usize = 500;

/// The page's answer: the same report `--json` prints, capped.
pub fn page(rt: &Runtime, req: &DiffRequest) -> Result<DiffReport> {
    let saved = req
        .export
        .as_ref()
        .map(|e| {
            Ok::<_, anyhow::Error>(Saved {
                export: export::parse(&e.text, &e.name)?,
                label: e.name.clone(),
            })
        })
        .transpose()?;
    let to = req
        .to
        .as_deref()
        .filter(|t| !t.is_empty() && *t != "working");
    page_of(rt, &req.project, &req.from, to, saved.as_ref())
}

/// What `page` does once the old side is known, so the TUI's compare view (T485), whose export
/// comes from a path the user typed, resolves the scope and caps the rows exactly as the page does.
pub fn page_of(
    rt: &Runtime,
    project: &str,
    from: &[String],
    to: Option<&str>,
    saved: Option<&Saved>,
) -> Result<DiffReport> {
    if project.is_empty() {
        bail!("diff needs a project");
    }
    let scope = super::scope::resolve(&rt.store, Some(project), Path::new("."))?;
    Ok(report(rt, &scope, from, to, saved)?.capped(PAGE_ROWS))
}

/// MCP `graph_diff`: `from` (a ref, or a list mixing refs and `project:ref`; default `HEAD`), `to` (default the working tree).
pub fn call(rt: &Runtime, args: &Value, scope: &[Member]) -> Result<String> {
    let arg = |k: &str| args[k].as_str().filter(|s| !s.is_empty());
    let to = arg("to").filter(|t| *t != "working");
    let from: Vec<String> = match &args["from"] {
        Value::Array(list) => list
            .iter()
            .filter_map(|v| v.as_str())
            .map(String::from)
            .collect(),
        other => other
            .as_str()
            .filter(|s| !s.is_empty())
            .map(String::from)
            .into_iter()
            .collect(),
    };
    run(
        rt,
        scope,
        &Query {
            from: &from,
            to,
            json: false,
            export: None,
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plugins::graph::index::tests::cx;
    use crate::store::{LinkKind, Origin};
    use serde_json::json;
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
            fs::create_dir_all(root.join(path).parent().unwrap()).unwrap();
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
                from: &[from.to_string()],
                to,
                json: false,
                export: None,
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
                from: &[],
                to: None,
                json: true,
                export: None,
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

    fn run_from(rt: &Runtime, scope: &[Member], from: &[&str]) -> Result<String> {
        let from: Vec<String> = from.iter().map(|f| f.to_string()).collect();
        run(
            rt,
            scope,
            &Query {
                from: &from,
                to: None,
                json: false,
                export: None,
            },
        )
    }

    #[test]
    fn a_ref_for_one_project_leaves_the_others_on_the_shared_ref() {
        let (rt, dir, scope) = linked("t32929-from");
        let b = dir.join("b");
        fs::write(b.join("lib.rs"), "fn shared(x: i32) {}\n").unwrap();
        commit(&b);
        let same = run_from(&rt, &scope, &["HEAD"]).unwrap();
        assert!(same.contains("no graph changes"), "{same}");
        let out = run_from(&rt, &scope, &["b:HEAD~1"]).unwrap();
        assert!(out.starts_with("graph diff HEAD -> working"), "{out}");
        assert!(out.contains("[b from HEAD~1] 1 changed"), "{out}");
        assert!(!out.contains("[a] 1 changed"), "{out}");
        // The same project by its registry id, and a second shared ref is refused.
        let id = rt
            .store
            .project_by_root(&b)
            .unwrap()
            .unwrap()
            .id
            .to_string();
        let by_id = run_from(&rt, &scope, &[&format!("{id}:HEAD~1")]).unwrap();
        assert!(by_id.contains("[b from HEAD~1] 1 changed"), "{by_id}");
        let err = run_from(&rt, &scope, &["HEAD", "main"])
            .unwrap_err()
            .to_string();
        assert!(err.contains("twice"), "{err}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn files_no_grammar_reads_are_listed_as_changed_and_not_analysed() {
        let (mut rt, dir) = cx("t32929-unread");
        rt.config.plugins.graph.exclude = vec!["vendor/".into()];
        let root = project(
            &dir,
            "p",
            &[
                ("lib.rs", "fn f() {}\n"),
                ("notes.md", "one\n"),
                ("bad.rs", "fn g() {}\n"),
                ("vendor/x.md", "one\n"),
            ],
        );
        fs::write(root.join("notes.md"), "two\n").unwrap();
        fs::write(root.join("vendor/x.md"), "two\n").unwrap();
        fs::write(root.join("bad.rs"), [0xff, 0xfe, 0x00]).unwrap();
        let out = ask(&rt, &solo(&root), "HEAD", None).unwrap();
        assert!(out.contains("2 changed, not analysed"), "{out}");
        assert!(out.contains("  notes.md  (no grammar)"), "{out}");
        assert!(out.contains("  bad.rs  (not parsed)"), "{out}");
        assert!(!out.contains("vendor"), "{out}");
        assert!(!out.contains("no graph changes"), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    /// An export of `scope` at the symbols level, written to `dir/saved.json`.
    fn save(rt: &Runtime, scope: &[Member], dir: &Path, level: export::Level) -> PathBuf {
        let text = export::run(
            rt,
            scope,
            &export::Query {
                level,
                focus: None,
                depth: 2,
                redact: false,
                pretty: false,
                from: None,
            },
        )
        .unwrap();
        let path = dir.join("saved.json");
        fs::write(&path, text).unwrap();
        path
    }

    fn against(rt: &Runtime, scope: &[Member], file: &Path) -> Result<String> {
        run(
            rt,
            scope,
            &Query {
                from: &[],
                to: None,
                json: false,
                export: Some(file),
            },
        )
    }

    #[test]
    fn a_saved_export_lists_what_appeared_and_vanished_and_the_links() {
        let (rt, dir, scope) = linked("t32929-export");
        let file = save(&rt, &scope, &dir, export::Level::Symbols);
        let same = against(&rt, &scope, &file).unwrap();
        assert!(same.contains("no graph changes"), "{same}");
        // A signature change is invisible to an export, a new and a removed function are not.
        fs::write(
            dir.join("b/lib.rs"),
            "fn shared(x: i32) {}\nfn fresh() {}\n",
        )
        .unwrap();
        fs::write(dir.join("a/lib.rs"), "fn other() {}\n").unwrap();
        let ids: Vec<i32> = scope
            .iter()
            .map(|m| rt.store.project_by_root(&m.root).unwrap().unwrap().id)
            .collect();
        rt.store.unlink_projects(ids[0], ids[1]).unwrap();
        let out = against(&rt, &scope, &file).unwrap();
        assert!(out.contains("graph diff export "), "{out}");
        assert!(out.contains("note: against an export"), "{out}");
        assert!(out.contains("added (1)\n  function fresh"), "{out}");
        assert!(out.contains("removed (1)\n  function a_caller"), "{out}");
        assert!(out.contains("added (1)\n  function other"), "{out}");
        assert!(!out.contains("changed ("), "{out}");
        assert!(
            out.contains("links removed (1)\n  a -> b (manual)"),
            "{out}"
        );
        assert!(!out.contains("links added"), "{out}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn an_export_that_cannot_be_compared_says_why() {
        let (rt, dir, scope) = linked("t32929-export-errors");
        let overview = save(&rt, &scope, &dir, export::Level::Overview);
        let out = against(&rt, &scope, &overview).unwrap();
        assert!(out.contains("only links are compared"), "{out}");
        assert!(out.contains("no graph changes"), "{out}");
        let symbols = save(&rt, &scope, &dir, export::Level::Symbols);
        let both = run(
            &rt,
            &scope,
            &Query {
                from: &["HEAD".to_string()],
                to: None,
                json: false,
                export: Some(&symbols),
            },
        )
        .unwrap_err()
        .to_string();
        assert!(both.contains("drop --from and --to"), "{both}");
        let missing = against(&rt, &scope, &dir.join("none.json")).unwrap_err();
        assert!(format!("{missing:#}").contains("none.json"), "{missing:#}");
        let _ = fs::remove_dir_all(dir);
    }

    fn page_req(rt: &Runtime, dir: &Path, export: Option<(&str, String)>) -> DiffRequest {
        let a = rt
            .store
            .project_by_root(&dir.join("a"))
            .unwrap()
            .unwrap()
            .id;
        DiffRequest {
            project: a.to_string(),
            from: Vec::new(),
            to: None,
            export: export.map(|(name, text)| DiffExport {
                name: name.into(),
                text,
            }),
        }
    }

    #[test]
    fn the_page_gets_the_report_the_json_prints() {
        let (rt, dir, scope) = linked("t32935-page");
        fs::write(
            dir.join("b/lib.rs"),
            "fn shared(x: i32) {}\nfn fresh() {}\n",
        )
        .unwrap();
        let r = page(&rt, &page_req(&rt, &dir, None)).unwrap();
        let b = r.projects.iter().find(|p| p.project == "b").unwrap();
        assert_eq!(b.changed[0].name, "shared");
        assert_eq!(b.changed[0].signature_changed, Some(true));
        assert_eq!(
            b.changed[0].callers.as_deref(),
            Some(&["[a] lib.rs a_caller d1".to_string()][..])
        );
        assert_eq!(b.added[0].name, "fresh");
        let cli = run(
            &rt,
            &scope,
            &Query {
                from: &[],
                to: None,
                json: true,
                export: None,
            },
        )
        .unwrap();
        assert_eq!(
            serde_json::to_value(&r).unwrap(),
            serde_json::from_str::<Value>(&cli).unwrap()
        );
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn the_page_sends_an_export_as_text_and_never_a_path() {
        let (rt, dir, scope) = linked("t32935-export");
        let file = save(&rt, &scope, &dir, export::Level::Symbols);
        fs::write(dir.join("b/lib.rs"), "fn shared() {}\nfn fresh() {}\n").unwrap();
        let text = fs::read_to_string(&file).unwrap();
        let r = page(
            &rt,
            &page_req(&rt, &dir, Some(("saved.json", text.clone()))),
        )
        .unwrap();
        assert_eq!(r.from, "export saved.json");
        let b = r.projects.iter().find(|p| p.project == "b").unwrap();
        assert_eq!(b.added[0].name, "fresh");
        assert!(
            r.notes.contains("only added and removed symbols"),
            "{}",
            r.notes
        );
        // A path in place of the content is not a graph export, so nothing is read from it.
        let e = page(
            &rt,
            &page_req(&rt, &dir, Some(("x", file.display().to_string()))),
        )
        .unwrap_err();
        assert!(format!("{e:#}").contains("is not a graph export"), "{e:#}");
        let mut with_ref = page_req(&rt, &dir, Some(("saved.json", text)));
        with_ref.from = vec!["HEAD".into()];
        assert!(page(&rt, &with_ref).is_err());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_long_report_is_cut_and_counted() {
        let (rt, dir, _) = linked("t32935-cap");
        fs::write(
            dir.join("b/lib.rs"),
            "fn shared() {}\nfn one() {}\nfn two() {}\n",
        )
        .unwrap();
        let r = page(&rt, &page_req(&rt, &dir, None)).unwrap().capped(1);
        let b = r.projects.iter().find(|p| p.project == "b").unwrap();
        assert_eq!((b.added.len(), b.more), (1, 1));
        let _ = fs::remove_dir_all(dir);
    }
}

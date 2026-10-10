// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.19: a 0 to 100 health score per project, from what the health check (T329.17) and the
//! capability record (T329.11) already know. Nothing here probes or spawns: freshness reads the
//! index status, the backend component reads the mirrored record, and the links component reads
//! the registry and the mirrored alerts. Every reason carries the fix that would remove it.

use std::collections::{HashMap, HashSet};
use std::sync::{LazyLock, Mutex, PoisonError};
use std::time::{Duration, Instant};

use rtok_plugin_sdk::Ctx;
use schemars::JsonSchema;
use serde::Serialize;

use super::{Kind, Reached, mirrored, now_s};
use crate::plugin::Runtime;
use crate::plugins::graph::capability::{self, Chosen};
use crate::plugins::graph::status::{self, GraphStatus};
use crate::plugins::graph::{backend_name, index};
use crate::store::Project;

/// Scores under this are reported to agents and in `rtok doctor`.
pub const NOTICE_BELOW: u8 = 80;
const WARN_BELOW: u8 = 50;
/// More pending files than this share of the project, and freshness is 0.
const PENDING_LIMIT: f64 = 0.20;
const STALE_S: i64 = 24 * 3600;
const TAGS_FALLBACK: f64 = 0.6;
const TEXT_FALLBACK: f64 = 0.3;
/// Why a second: a snapshot or an MCP call per second must not stat every file of every project.
const TTL: Duration = Duration::from_secs(1);

pub const MISSING_SERVER_FIX: &str =
    "install the server; it is picked up within one health-check interval, or restart";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Level {
    /// 80 and up.
    Good,
    /// 50 to 79.
    Warn,
    /// Under 50.
    Bad,
    /// The first index has not finished; there is no score yet.
    Indexing,
    Missing,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Component {
    Freshness,
    Backend,
    Links,
}

/// What lowered a component, and what to do about it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, JsonSchema)]
pub struct Reason {
    pub component: Component,
    pub text: String,
    pub fix: String,
}

/// Each component is 0 to 1, rounded to two places.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, JsonSchema)]
pub struct Components {
    pub freshness: f64,
    pub backend: f64,
    pub links: f64,
}

#[derive(Clone, Debug, PartialEq, Serialize, JsonSchema)]
pub struct Score {
    /// Absent while the project's first index runs.
    pub score: Option<u8>,
    pub level: Level,
    pub components: Components,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub reasons: Vec<Reason>,
}

fn reason(component: Component, text: impl Into<String>, fix: impl Into<String>) -> Reason {
    Reason {
        component,
        text: text.into(),
        fix: fix.into(),
    }
}

fn two(x: f64) -> f64 {
    (x.clamp(0.0, 1.0) * 100.0).round() / 100.0
}

/// A project whose directory is gone has nothing else to measure.
pub fn missing(p: &Project) -> Score {
    Score {
        score: Some(0),
        level: Level::Missing,
        components: Components {
            freshness: 0.0,
            backend: 0.0,
            links: 0.0,
        },
        reasons: vec![reason(
            Component::Freshness,
            "missing: the project directory does not exist",
            format!(
                "restore the directory, or `rtok graph projects remove {}`",
                p.id
            ),
        )],
    }
}

fn freshness(st: &GraphStatus, now: i64) -> (f64, Option<Reason>) {
    let pending = st.pending.len();
    if pending == 0 {
        return (1.0, None);
    }
    // Pending files can be new ones the index has not counted yet.
    let share = pending as f64 / st.files.max(pending as i64) as f64;
    let age = st.indexed_at.map_or(STALE_S, |t| now - t);
    let value = (1.0 - share / PENDING_LIMIT).min(1.0 - age as f64 / STALE_S as f64);
    let why = reason(
        Component::Freshness,
        format!("{pending} files pending ({:.0}%)", share * 100.0),
        format!("run `rtok graph index {}`", st.root),
    );
    (value.clamp(0.0, 1.0), Some(why))
}

/// 1 where the configured backend works. Under `auto` and `lsp` the record says whether the
/// server answers; without one nothing has failed yet.
fn backend(cx: &Ctx, p: &Project, st: &GraphStatus) -> (f64, Option<Reason>) {
    let root = std::path::Path::new(&p.root);
    let config = backend_name(cx, root);
    if !matches!(config.as_str(), "auto" | "lsp") {
        return (1.0, None);
    }
    let Some(rec) = capability::mirrored(cx, root).filter(|r| r.config == config) else {
        return (1.0, None);
    };
    // A language with no server at all has nothing to install; tags is what it is meant to use.
    if rec.backend == Chosen::Lsp || !rec.server {
        return (1.0, None);
    }
    let (value, using) = if st.rows > 0 {
        (TAGS_FALLBACK, "using tree-sitter")
    } else if config == "auto" {
        (TEXT_FALLBACK, "using text search")
    } else {
        (0.0, "no backend answers")
    };
    let cause = rec
        .reason
        .as_deref()
        .unwrap_or("language server stopped answering");
    let cause = cause.strip_prefix("lsp: ").unwrap_or(cause);
    let fix = if rec.down {
        "the health check retries the server on a backoff; or restart".into()
    } else {
        MISSING_SERVER_FIX.to_string()
    };
    (
        value,
        Some(reason(Component::Backend, format!("{cause}, {using}"), fix)),
    )
}

/// The share of the project's links that lead to a present, reachable, indexed project. A broken
/// manifest reference (a `link broken` alert) counts as a link that does not.
fn links(rt: &Runtime, cx: &Ctx, p: &Project) -> (f64, Vec<Reason>) {
    let mut why = Vec::new();
    let mut total = 0usize;
    let mut good = 0usize;
    for l in rt
        .store
        .project_links()
        .unwrap_or_default()
        .iter()
        .filter(|l| l.from == p.id)
    {
        let Ok(Some(to)) = rt.store.project(l.to) else {
            continue;
        };
        total += 1;
        let name = to.display_name();
        let down = to.missing()
            || mirrored(cx, &to.root)
                .iter()
                .any(|a| matches!(a.kind, Kind::Missing | Kind::Unreachable));
        let indexed = cx
            .symbol_indexed_at(&index::canon(std::path::Path::new(&to.root)))
            .ok()
            .flatten()
            .is_some();
        if down {
            why.push(reason(
                Component::Links,
                format!("link to {name} broken: the project is missing or unreachable"),
                format!(
                    "restore it, or `rtok graph projects unlink {} --from {}`",
                    to.id, p.id
                ),
            ));
        } else if !indexed {
            why.push(reason(
                Component::Links,
                format!("link to {name} not indexed"),
                format!("run `rtok graph index {}`", to.root),
            ));
        } else {
            good += 1;
        }
    }
    for a in mirrored(cx, &p.root)
        .iter()
        .filter(|a| a.kind == Kind::LinkBroken)
    {
        let refs = a.detail.split("; ").count();
        total += refs;
        why.push(reason(
            Component::Links,
            a.detail.clone(),
            "fix the path in the manifest, or remove the reference",
        ));
    }
    let value = if total == 0 {
        1.0
    } else {
        good as f64 / total as f64
    };
    (value, why)
}

/// The band of a score. The one place the 80 and 50 cut-offs live, so a surface that holds a
/// bare number (the scope's lowest) colours it as the server colours a project.
pub fn level_of(score: u8) -> Level {
    match score {
        s if s >= NOTICE_BELOW => Level::Good,
        s if s >= WARN_BELOW => Level::Warn,
        _ => Level::Bad,
    }
}

/// The score of one project from its index status.
pub fn of(rt: &Runtime, p: &Project, st: &GraphStatus) -> Score {
    if p.missing() {
        return missing(p);
    }
    let cx = &Ctx::new(rt);
    let (fresh, fresh_why) = freshness(st, now_s());
    let (back, back_why) = backend(cx, p, st);
    let (link, link_why) = links(rt, cx, p);
    let indexing = st.indexed_at.is_none() && st.rows == 0;
    let total = (100.0 * (0.4 * fresh + 0.3 * back + 0.3 * link)).round() as u8;
    let (score, level, reasons) = if indexing {
        let why = reason(
            Component::Freshness,
            "indexing: the first index has not finished",
            format!("run `rtok graph index {}`", st.root),
        );
        (None, Level::Indexing, vec![why])
    } else {
        let level = level_of(total);
        let reasons = fresh_why
            .into_iter()
            .chain(back_why)
            .chain(link_why)
            .collect();
        (Some(total), level, reasons)
    };
    Score {
        score,
        level,
        components: Components {
            freshness: two(fresh),
            backend: two(back),
            links: two(link),
        },
        reasons,
    }
}

type Cache = Mutex<HashMap<String, (Instant, Score)>>;
static CACHE: LazyLock<Cache> = LazyLock::new(Mutex::default);

/// [`of`] for a caller that has no status at hand, recomputed at most once a second per project.
/// Fails open: a store that cannot be read gives no score.
pub fn cached(rt: &Runtime, p: &Project) -> Option<Score> {
    if p.missing() {
        return Some(missing(p));
    }
    let mut cache = CACHE.lock().unwrap_or_else(PoisonError::into_inner);
    if let Some((_, s)) = cache.get(&p.root).filter(|(at, _)| at.elapsed() < TTL) {
        return Some(s.clone());
    }
    let st = status::collect(&Ctx::new(rt), std::path::Path::new(&p.root)).ok()?;
    let s = of(rt, p, &st);
    cache.insert(p.root.clone(), (Instant::now(), s.clone()));
    Some(s)
}

/// The weakest score among `scores`: the scope's score is its lowest, not an average. A project
/// still on its first index has no score and never decides.
pub fn lowest<'a>(scores: impl IntoIterator<Item = &'a Score>) -> Option<u8> {
    scores.into_iter().filter_map(|s| s.score).min()
}

/// The lowest score of the scope that starts at `reached[0]`, whose own score is `own`.
pub(crate) fn scope_lowest(rt: &Runtime, reached: &[Reached], own: &Score) -> Option<u8> {
    let others: Vec<_> = reached
        .iter()
        .skip(1)
        .filter_map(|r| cached(rt, &r.project))
        .collect();
    lowest(others.iter().chain([own]))
}

/// The line that heads a graph answer when the scope scores under [`NOTICE_BELOW`]. A project an
/// alert line already names is left out, and so is a broken link while the scope has an alert
/// (the link is broken because of it), so one problem is not said twice.
pub(super) fn note(rt: &Runtime, reached: &[Reached], alerting: &HashSet<&str>) -> Option<String> {
    let (name, score, why) = reached
        .iter()
        .filter(|r| !alerting.contains(r.project.root.as_str()))
        .filter_map(|r| {
            let s = cached(rt, &r.project)?;
            let score = s.score.filter(|n| *n < NOTICE_BELOW)?;
            let why: Vec<_> = s
                .reasons
                .into_iter()
                .filter(|w| w.component != Component::Links || alerting.is_empty())
                .map(|w| w.text)
                .collect();
            (!why.is_empty()).then(|| (r.project.display_name(), score, why))
        })
        .min_by_key(|(_, score, _)| *score)?;
    Some(format!(
        "notice: graph health {score} for {name}: {}; results may be incomplete\n",
        why.join("; ")
    ))
}

/// One project under [`NOTICE_BELOW`], as `rtok doctor` lists it.
#[derive(Clone, Debug, Serialize, JsonSchema)]
pub struct Weak {
    pub project: String,
    pub root: String,
    pub score: u8,
    pub reasons: Vec<Reason>,
}

/// Every registered project that scores under [`NOTICE_BELOW`], weakest first.
pub fn weak(rt: &Runtime) -> Vec<Weak> {
    let mut out: Vec<_> = rt
        .store
        .projects()
        .unwrap_or_default()
        .iter()
        .filter_map(|p| {
            let s = cached(rt, p)?;
            Some(Weak {
                project: p.display_name().to_string(),
                root: p.root.clone(),
                score: s.score.filter(|n| *n < NOTICE_BELOW)?,
                reasons: s.reasons,
            })
        })
        .collect();
    out.sort_by(|a, b| (a.score, &a.root).cmp(&(b.score, &b.root)));
    out
}

/// The lines `rtok doctor` prints.
pub fn texts(weak: &[Weak]) -> Vec<String> {
    weak.iter()
        .map(|w| {
            let why: Vec<_> = w
                .reasons
                .iter()
                .map(|r| format!("{} (fix: {})", r.text, r.fix))
                .collect();
            format!("{} {}/100: {}", w.project, w.score, why.join("; "))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::{Path, PathBuf};

    use super::super::Health;
    use super::*;
    use crate::plugins::graph::capability::{Absent, Probe};
    use crate::store::{LinkKind, Origin};

    const OK: &dyn Fn(&Path) -> Probe = &|_| Ok(());

    fn runtime(tag: &str, backend: &str) -> (Runtime, PathBuf) {
        let (mut c, dir) = crate::testutil::config(tag);
        c.plugins.graph.backend = backend.into();
        (Runtime::open(c, tag).unwrap(), dir)
    }

    /// A registered, indexed project of `files` source files.
    fn project(rt: &Runtime, dir: &Path, name: &str, files: usize) -> Project {
        let root = dir.join(name);
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("Cargo.toml"), "[package]\nname = \"x\"\n").unwrap();
        for i in 0..files {
            fs::write(
                root.join(format!("f{i}.rs")),
                format!("pub fn {name}_{i}() {{}}\n"),
            )
            .unwrap();
        }
        let p = rt.store.register_project(&root, Origin::Manual).unwrap();
        index::run(&Ctx::new(rt), Path::new(&p.root), false).unwrap();
        p
    }

    fn scored(rt: &Runtime, p: &Project) -> Score {
        let st = status::collect(&Ctx::new(rt), Path::new(&p.root)).unwrap();
        of(rt, p, &st)
    }

    /// One request that makes the project's capability record: `probe` decides what is found.
    fn ask(rt: &Runtime, p: &Project, probe: &dyn Fn(&Path) -> Probe) {
        let _ = crate::plugins::graph::door(
            &Ctx::new(rt),
            Path::new(&p.root),
            "symbol",
            &[],
            probe,
            || Ok("f0.rs:1 function".into()),
            || Ok("tags answer".into()),
        );
    }

    fn touch(p: &Project, n: usize) {
        for i in 0..n {
            fs::write(
                Path::new(&p.root).join(format!("f{i}.rs")),
                "pub fn changed_to_something_longer() {}\n",
            )
            .unwrap();
        }
    }

    #[test]
    fn an_indexed_project_with_a_working_server_and_intact_links_scores_100() {
        let (rt, dir) = runtime("t32919-full", "auto");
        let a = project(&rt, &dir, "a", 4);
        let b = project(&rt, &dir, "b", 2);
        rt.store
            .link_projects(a.id, b.id, LinkKind::Manual, None)
            .unwrap();
        ask(&rt, &a, OK);
        let s = scored(&rt, &a);
        assert_eq!((s.score, s.level), (Some(100), Level::Good), "{s:?}");
        assert!(s.reasons.is_empty());
    }

    #[test]
    fn thirty_percent_pending_drops_below_80_with_the_reason_and_fix() {
        let (rt, dir) = runtime("t32919-pending", "tags");
        let a = project(&rt, &dir, "a", 10);
        touch(&a, 3);
        let s = scored(&rt, &a);
        assert_eq!(s.components.freshness, 0.0);
        assert_eq!((s.score, s.level), (Some(60), Level::Warn), "{s:?}");
        assert_eq!(s.reasons[0].text, "3 files pending (30%)");
        assert!(s.reasons[0].fix.starts_with("run `rtok graph index "));
    }

    #[test]
    fn a_few_pending_files_cost_only_part_of_freshness() {
        let (rt, dir) = runtime("t32919-few", "tags");
        let a = project(&rt, &dir, "a", 20);
        touch(&a, 1);
        let s = scored(&rt, &a);
        // 5% of 20% is a quarter off: 0.75 of the 40 points.
        assert_eq!((s.components.freshness, s.score), (0.75, Some(90)), "{s:?}");
    }

    #[test]
    fn tree_sitter_fallback_under_auto_reads_0_6_and_default_tags_reads_1() {
        let (rt, dir) = runtime("t32919-auto", "auto");
        let a = project(&rt, &dir, "a", 3);
        let no_server: &dyn Fn(&Path) -> Probe = &|_| {
            Err(Absent {
                server: true,
                reason: "lsp: rust-analyzer not on PATH".into(),
            })
        };
        ask(&rt, &a, no_server);
        let s = scored(&rt, &a);
        assert_eq!((s.components.backend, s.score), (0.6, Some(88)), "{s:?}");
        assert_eq!(
            s.reasons[0].text,
            "rust-analyzer not on PATH, using tree-sitter"
        );
        assert_eq!(
            s.reasons[0].fix,
            "install the server; it is picked up within one health-check interval, or restart"
        );

        let (rt, dir) = runtime("t32919-tags", "tags");
        let a = project(&rt, &dir, "a", 3);
        assert_eq!(scored(&rt, &a).components.backend, 1.0);
    }

    #[test]
    fn a_server_that_broke_says_the_health_check_retries_it() {
        let (rt, dir) = runtime("t32919-down", "auto");
        let a = project(&rt, &dir, "a", 3);
        ask(&rt, &a, OK);
        let _ = crate::plugins::graph::door(
            &Ctx::new(&rt),
            Path::new(&a.root),
            "symbol",
            &[],
            OK,
            || Err(anyhow::anyhow!("lsp: eof")),
            || Ok("tags answer".into()),
        );
        let s = scored(&rt, &a);
        assert_eq!(s.components.backend, 0.6, "{s:?}");
        assert!(s.reasons[0].fix.contains("retries the server"), "{s:?}");
    }

    #[test]
    fn a_broken_link_lowers_the_links_component() {
        let (rt, dir) = runtime("t32919-links", "tags");
        let a = project(&rt, &dir, "a", 2);
        let b = project(&rt, &dir, "b", 2);
        let c = project(&rt, &dir, "c", 2);
        for to in [&b, &c] {
            rt.store
                .link_projects(a.id, to.id, LinkKind::Manual, None)
                .unwrap();
        }
        assert_eq!(scored(&rt, &a).components.links, 1.0);

        fs::rename(dir.join("b"), dir.join("b.moved")).unwrap();
        let s = scored(&rt, &a);
        assert_eq!((s.components.links, s.score), (0.5, Some(85)), "{s:?}");
        assert!(s.reasons[0].text.starts_with("link to b broken"), "{s:?}");
        assert!(s.reasons[0].fix.contains("projects unlink"), "{s:?}");

        fs::rename(dir.join("b.moved"), dir.join("b")).unwrap();
        let unindexed = dir.join("d");
        fs::create_dir_all(&unindexed).unwrap();
        let d = rt
            .store
            .register_project(&unindexed, Origin::Manual)
            .unwrap();
        rt.store
            .link_projects(a.id, d.id, LinkKind::Manual, None)
            .unwrap();
        let s = scored(&rt, &a);
        assert!(s.reasons[0].text.contains("link to d not indexed"), "{s:?}");
    }

    #[test]
    fn a_manifest_path_that_points_nowhere_lowers_links() {
        let (rt, dir) = runtime("t32919-manifest", "tags");
        let a = project(&rt, &dir, "a", 2);
        fs::write(
            dir.join("a/Cargo.toml"),
            "[package]\nname = \"a\"\n[dependencies]\nlost = { path = \"../nowhere\" }\n",
        )
        .unwrap();
        let mut h = Health::default();
        for t in 0..2 {
            h.tick(&rt, std::slice::from_ref(&a), OK, t * 60);
        }
        let s = scored(&rt, &a);
        assert_eq!((s.components.links, s.score), (0.0, Some(70)), "{s:?}");
        assert!(s.reasons[0].text.contains("../nowhere"), "{s:?}");
    }

    #[test]
    fn a_missing_project_scores_0_and_a_first_index_has_no_score_yet() {
        let (rt, dir) = runtime("t32919-edges", "tags");
        let a = project(&rt, &dir, "a", 2);
        fs::remove_dir_all(dir.join("a")).unwrap();
        let s = cached(&rt, &a).unwrap();
        assert_eq!((s.score, s.level), (Some(0), Level::Missing), "{s:?}");
        assert!(s.reasons[0].fix.contains("projects remove"), "{s:?}");

        let root = dir.join("fresh");
        fs::create_dir_all(&root).unwrap();
        let p = rt.store.register_project(&root, Origin::Manual).unwrap();
        let s = scored(&rt, &p);
        assert_eq!((s.score, s.level), (None, Level::Indexing), "{s:?}");
        assert!(s.reasons[0].text.starts_with("indexing"));
    }

    #[test]
    fn the_scope_shows_its_lowest_score_not_an_average() {
        let (rt, dir) = runtime("t32919-scope", "tags");
        let a = project(&rt, &dir, "a", 10);
        let b = project(&rt, &dir, "b", 10);
        rt.store
            .link_projects(a.id, b.id, LinkKind::Manual, None)
            .unwrap();
        touch(&b, 3);
        let rows =
            serde_json::to_value(crate::plugins::graph::projects::rows(&rt).unwrap()).unwrap();
        assert_eq!(rows[0]["health"]["score"], 100);
        assert_eq!(rows[0]["scope_health"], 60, "{rows}");
        assert_eq!(rows[1]["health"]["score"], 60);
        assert_eq!(rows[1]["health"]["components"]["freshness"], 0.0);
        assert_eq!(lowest([&scored(&rt, &a), &scored(&rt, &b)]), Some(60));
    }

    #[test]
    fn answers_and_doctor_name_a_weak_scope_once() {
        let (rt, dir) = runtime("t32919-notice", "tags");
        let a = project(&rt, &dir, "a", 10);
        touch(&a, 3);
        let note = super::super::notice(&rt, None, Path::new(&a.root)).unwrap();
        assert_eq!(
            note,
            "notice: graph health 60 for a: 3 files pending (30%); results may be incomplete\n"
        );
        let text = crate::doctor::page(&rt.config).unwrap().to_text();
        assert!(
            text.contains(
                "graph health\n  a 60/100: 3 files pending (30%) (fix: run `rtok graph index "
            ),
            "{text}"
        );
    }

    #[test]
    fn a_project_an_alert_already_names_is_not_said_twice() {
        let (rt, dir) = runtime("t32919-once", "tags");
        let a = project(&rt, &dir, "a", 2);
        let b = project(&rt, &dir, "b", 2);
        rt.store
            .link_projects(a.id, b.id, LinkKind::Manual, None)
            .unwrap();
        fs::rename(dir.join("b"), dir.join("b.moved")).unwrap();
        let mut h = Health::default();
        for t in 0..2 {
            h.tick(&rt, &[a.clone(), b.clone()], OK, t * 60);
        }
        let note = super::super::notice(&rt, None, Path::new(&a.root)).unwrap();
        assert_eq!(note.lines().count(), 1, "{note}");
        assert!(note.contains("b missing"), "{note}");
    }
}

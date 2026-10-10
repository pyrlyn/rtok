// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.17: alerts for the projects of a graph scope, and the health check that is the only code
//! that re-probes (requests never do, T337). A thread in each process that hosts graph checks the
//! scope every `health_check_interval_s`; a problem alerts once it has been seen by two checks in
//! a row and clears after two good ones, so a brief unmount does not flap. Alerts are mirrored
//! per project into `plugin_state`, which is how `rtok doctor`, `rtok graph projects` and the
//! notice in graph answers (all other processes) see them.

use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::Result;
use rtok_plugin_sdk::Ctx;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

pub mod score;

use super::capability::{self, Chosen, Probe};
use super::{backend_name, index, lsp};
use crate::plugin::Runtime;
use crate::store::{Project, Store};

/// Checks in a row that raise an alert, and that clear it.
const CONFIRM: u8 = 2;
/// First wait before a broken server is tried again; doubled up to [`BACKOFF_CAP_S`].
const RETRY_S: i64 = 60;
/// Why 15 minutes: a server that keeps failing is probably not coming back within the session,
/// and each retry costs the next request a failed start.
const BACKOFF_CAP_S: i64 = 900;
/// A share that does not answer a `stat` in this time is unreachable; the check goes on.
const STAT_TIMEOUT: Duration = Duration::from_secs(2);

#[derive(
    Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Missing,
    Unreachable,
    BackendDown,
    LinkBroken,
}

impl Kind {
    fn words(self) -> &'static str {
        match self {
            Kind::Missing => "missing",
            Kind::Unreachable => "unreachable",
            Kind::BackendDown => "backend down",
            Kind::LinkBroken => "link broken",
        }
    }

    /// What an answer that touches the project should say about its own completeness.
    fn effect(self) -> &'static str {
        match self {
            Kind::Missing | Kind::Unreachable => "results exclude",
            Kind::BackendDown => "answered from the tags index for",
            Kind::LinkBroken => "the broken link leaves out a project of",
        }
    }
}

/// One raised alert, as stored and as `rtok graph projects --json` and `rtok doctor --json`
/// print it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Alert {
    pub kind: Kind,
    pub project: String,
    pub root: String,
    /// Unix seconds of the check that raised it.
    pub since: i64,
    pub detail: String,
}

fn key(root: &str) -> String {
    format!("alerts:{root}")
}

/// The alerts some process raised for the project registered at `root`.
pub(crate) fn mirrored(cx: &Ctx, root: &str) -> Vec<Alert> {
    cx.plugin_state_get("graph", &key(root))
        .ok()
        .flatten()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

fn store_alerts(cx: &Ctx, root: &str, alerts: &[Alert]) {
    if let Ok(json) = serde_json::to_string(alerts) {
        // A lost write is rewritten by the next check that sees a difference.
        let _ = cx.plugin_state_set("graph", &key(root), &json);
    }
}

/// Every alert on the registry, for `rtok doctor`.
pub fn all(rt: &Runtime) -> Vec<Alert> {
    let cx = Ctx::new(rt);
    let mut out: Vec<_> = rt
        .store
        .projects()
        .unwrap_or_default()
        .iter()
        .flat_map(|p| mirrored(&cx, &p.root))
        .collect();
    out.sort_by(|a, b| (a.kind, &a.root).cmp(&(b.kind, &b.root)));
    out
}

fn hhmm(ts: i64) -> String {
    jiff::Timestamp::from_second(ts).map_or_else(
        |_| "?".into(),
        |t| {
            t.to_zoned(jiff::tz::TimeZone::system())
                .strftime("%H:%M")
                .to_string()
        },
    )
}

/// One alert per kind: projects hit by the same problem at once (a whole disk unmounted) read as
/// one line. `chains` names the links that lead to a transitively linked project.
fn describe(kind: Kind, group: &[(&Alert, &[String])], notice: bool) -> String {
    let since = hhmm(group.iter().map(|(a, _)| a.since).min().unwrap_or_default());
    let names = group
        .iter()
        .map(|(a, _)| a.project.as_str())
        .collect::<Vec<_>>()
        .join(", ");
    let mut line = match group {
        [(a, chain)] if chain.len() > 2 => {
            format!("{}: {} {}", chain.join(" to "), a.project, kind.words())
        }
        _ => format!("{names} {}", kind.words()),
    };
    line.push_str(&format!(" since {since}"));
    match group {
        [(a, _)] => line.push_str(&format!(" ({})", a.detail)),
        _ => line.push_str(&format!(" ({} projects)", group.len())),
    }
    if notice {
        line.push_str(&format!("; {} {names}", kind.effect()));
    }
    line
}

fn grouped<'a>(
    alerts: impl IntoIterator<Item = (&'a Alert, &'a [String])>,
    notice: bool,
) -> Vec<String> {
    let mut by: BTreeMap<Kind, Vec<(&Alert, &[String])>> = BTreeMap::new();
    for (a, chain) in alerts {
        by.entry(a.kind).or_default().push((a, chain));
    }
    by.into_iter()
        .map(|(kind, group)| describe(kind, &group, notice))
        .collect()
}

/// The lines `rtok doctor` prints.
pub fn texts(alerts: &[Alert]) -> Vec<String> {
    grouped(alerts.iter().map(|a| (a, &[][..])), false)
}

/// A project of a scope and the names from the scope's start to it.
pub(crate) struct Reached {
    pub project: Project,
    pub chain: Vec<String>,
}

/// `start` and what it links to, level by level. Unlike `Store::project_scope` a missing project
/// stays in: it is exactly the one an alert is about.
pub(crate) fn reach(store: &Store, start: &Project) -> Result<Vec<Reached>> {
    let projects: HashMap<_, _> = store.projects()?.into_iter().map(|p| (p.id, p)).collect();
    let links = store.project_links()?;
    let mut seen = HashSet::from([start.id]);
    let mut out = Vec::new();
    let mut queue = VecDeque::from([(start.clone(), vec![start.display_name().to_string()])]);
    while let Some((project, chain)) = queue.pop_front() {
        for l in links.iter().filter(|l| l.from == project.id) {
            if let Some(next) = projects.get(&l.to).filter(|_| seen.insert(l.to)) {
                let mut chain = chain.clone();
                chain.push(next.display_name().to_string());
                queue.push_back((next.clone(), chain));
            }
        }
        out.push(Reached { project, chain });
    }
    Ok(out)
}

/// The notice that heads a graph answer for the scope of `project` (else the project at `cwd`):
/// "B missing since 14:02 (...); results exclude B". Agents learn of a problem in the answer they
/// are already reading. A scope that scores under 80 (T329.19) adds one health line. `None` when
/// nothing alerts or alerts are off.
pub fn notice(rt: &Runtime, project: Option<&str>, cwd: &Path) -> Option<String> {
    if !rt.config.plugins.graph.alerts {
        return None;
    }
    let start = match project.filter(|p| !p.is_empty()) {
        Some(target) => super::projects::resolve(&rt.store, target).ok()?,
        None => rt.store.project_by_root(cwd).ok()??,
    };
    let cx = Ctx::new(rt);
    let reached = reach(&rt.store, &start).ok()?;
    let found: Vec<_> = reached
        .iter()
        .flat_map(|r| {
            mirrored(&cx, &r.project.root)
                .into_iter()
                .map(|a| (a, r.chain.as_slice()))
        })
        .collect();
    let lines = grouped(found.iter().map(|(a, c)| (a, *c)), true);
    let alerting: HashSet<_> = found.iter().map(|(a, _)| a.root.as_str()).collect();
    let mut text: String = lines.iter().map(|l| format!("notice: {l}\n")).collect();
    text.extend(score::note(rt, &reached, &alerting));
    (!text.is_empty()).then_some(text)
}

enum Root {
    Up,
    Gone(Kind, String),
}

/// Whether the project root answers. A missing root under a parent that exists was deleted or
/// moved; one whose parent is gone too (an unmounted disk), or that errors, or that does not
/// answer in time (a stalled share) is unreachable. The `stat` runs on its own thread, so a hung
/// mount costs a thread and never the check.
fn root_state(root: &str) -> Root {
    let (tx, rx) = std::sync::mpsc::channel();
    let path = PathBuf::from(root);
    std::thread::spawn(move || {
        let stat = std::fs::metadata(&path).map(|m| m.is_dir());
        let parent = path.parent().is_some_and(Path::exists);
        let _ = tx.send((stat, parent));
    });
    match rx.recv_timeout(STAT_TIMEOUT) {
        Ok((Ok(true), _)) => Root::Up,
        Ok((Ok(false), _)) => Root::Gone(Kind::Missing, "not a directory".into()),
        Ok((Err(e), true)) if e.kind() == std::io::ErrorKind::NotFound => {
            Root::Gone(Kind::Missing, "directory does not exist".into())
        }
        Ok((Err(e), _)) => Root::Gone(Kind::Unreachable, super::capability::clip(&e.to_string())),
        Err(_) => Root::Gone(Kind::Unreachable, "no answer to stat".into()),
    }
}

#[derive(Default)]
struct Streak {
    bad: u8,
    good: u8,
    alert: Option<Alert>,
}

/// What the check remembers between rounds; one per hosting process.
#[derive(Default)]
pub(crate) struct Health {
    streaks: HashMap<(String, Kind), Streak>,
    /// Per project root: when a broken server is tried again, and the wait after that.
    backoff: HashMap<String, (i64, i64)>,
    /// What this process last wrote per root, so an unchanged state costs no write.
    written: HashMap<String, Vec<Alert>>,
    loaded: HashSet<String>,
}

fn now_s() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

impl Health {
    /// The problems of one project right now, with the cheap tier's side effects: a server
    /// installed since the record was made is picked up at once; a broken one is tried again on
    /// the backoff. Only a record this process holds is replaced (see [`capability::held`]).
    fn findings(
        &mut self,
        cx: &Ctx,
        p: &Project,
        probe: &dyn Fn(&Path) -> Probe,
        now: i64,
    ) -> Vec<(Kind, String)> {
        let root = Path::new(&p.root);
        if let Root::Gone(kind, why) = root_state(&p.root) {
            return vec![(kind, why)];
        }
        let mut out = Vec::new();
        let cfg = cx.plugin_config::<crate::config::Graph>("graph");
        if cfg.auto_link_references {
            let broken: Vec<_> = crate::project::refs::discover(root)
                .warnings
                .into_iter()
                .filter(|w| w.ends_with(", not found"))
                .collect();
            if !broken.is_empty() {
                out.push((Kind::LinkBroken, capability::clip(&broken.join("; "))));
            }
        }
        if let Some(why) = self.backend(cx, p, probe, now) {
            out.push((Kind::BackendDown, why));
        }
        out
    }

    fn backend(
        &mut self,
        cx: &Ctx,
        p: &Project,
        probe: &dyn Fn(&Path) -> Probe,
        now: i64,
    ) -> Option<String> {
        let root = Path::new(&p.root);
        let config = backend_name(cx, root);
        let held = capability::held(root).filter(|r| r.config == config);
        let rec = held.clone().or_else(|| capability::mirrored(cx, root))?;
        let retry = || capability::reprobe(cx, root, &config, |r| probe(r));
        if held.is_some() && rec.backend == Chosen::Tags && rec.server && !rec.down {
            if probe(root).is_ok() {
                retry();
            }
            return None;
        }
        if !rec.down {
            self.backoff.remove(&p.root);
            return None;
        }
        let step = self
            .backoff
            .entry(p.root.clone())
            .or_insert((now + RETRY_S, RETRY_S));
        // The retry follows the report, so a break is seen by two checks before it can be undone.
        if held.is_some() && now >= step.0 {
            step.1 = (step.1 * 2).min(BACKOFF_CAP_S);
            step.0 = now + step.1;
            retry();
        }
        Some(
            rec.reason
                .unwrap_or_else(|| "language server stopped answering".into()),
        )
    }

    /// One round over `projects` at time `now`. `probe` is the capability check, a parameter so
    /// tests need no language server.
    pub(crate) fn tick(
        &mut self,
        rt: &Runtime,
        projects: &[Project],
        probe: &dyn Fn(&Path) -> Probe,
        now: i64,
    ) {
        let cx = Ctx::new(rt);
        self.load(&cx, projects);
        let mut found = HashMap::new();
        for p in projects {
            for (kind, detail) in self.findings(&cx, p, probe, now) {
                found.insert(
                    (p.root.clone(), kind),
                    (p.display_name().to_string(), detail),
                );
            }
        }
        for (k, (project, detail)) in &found {
            let s = self.streaks.entry(k.clone()).or_default();
            (s.good, s.bad) = (0, (s.bad + 1).min(CONFIRM));
            if s.bad >= CONFIRM {
                let fresh = s.alert.is_none();
                let since = s.alert.as_ref().map_or(now, |a| a.since);
                let alert = Alert {
                    kind: k.1,
                    project: project.clone(),
                    root: k.0.clone(),
                    since,
                    detail: detail.clone(),
                };
                if fresh {
                    cx.log(
                        "warn",
                        "graph",
                        "alert",
                        &texts(std::slice::from_ref(&alert))[0],
                    );
                }
                s.alert = Some(alert);
            }
        }
        let quiet: Vec<_> = self
            .streaks
            .keys()
            .filter(|k| !found.contains_key(*k))
            .cloned()
            .collect();
        for k in quiet {
            // A project that left the scope (unlinked, removed) stops alerting at once.
            let in_scope = projects.iter().any(|p| p.root == k.0);
            let Some(s) = self.streaks.get_mut(&k) else {
                continue;
            };
            (s.bad, s.good) = (0, s.good + 1);
            if s.alert.is_some() && in_scope && s.good < CONFIRM {
                continue;
            }
            if let Some(a) = self.streaks.remove(&k).and_then(|s| s.alert)
                && in_scope
            {
                self.recovered(&cx, &a);
            }
        }
        self.persist(&cx);
    }

    /// Alerts another process (or an earlier run) raised are taken over, so they clear after two
    /// good checks here instead of staying for ever.
    fn load(&mut self, cx: &Ctx, projects: &[Project]) {
        for p in projects
            .iter()
            .filter(|p| self.loaded.insert(p.root.clone()))
        {
            let mut stored = mirrored(cx, &p.root);
            stored.sort_by_key(|a| a.kind);
            for a in &stored {
                self.streaks.insert(
                    (p.root.clone(), a.kind),
                    Streak {
                        bad: CONFIRM,
                        good: 0,
                        alert: Some(a.clone()),
                    },
                );
            }
            self.written.insert(p.root.clone(), stored);
        }
    }

    fn recovered(&self, cx: &Ctx, a: &Alert) {
        cx.log(
            "info",
            "graph",
            "alert",
            &format!("recovered: {} {}", a.project, a.kind.words()),
        );
        // Files may have changed while the project was away; the watcher does not index on start.
        if matches!(a.kind, Kind::Missing | Kind::Unreachable)
            && let Err(e) = index::run(cx, Path::new(&a.root), false)
        {
            cx.log(
                "warn",
                "graph",
                "alert",
                &format!("re-index of {}: {e:#}", a.root),
            );
        }
    }

    fn persist(&mut self, cx: &Ctx) {
        let mut now: HashMap<String, Vec<Alert>> = HashMap::new();
        for (k, s) in &self.streaks {
            if let Some(a) = &s.alert {
                now.entry(k.0.clone()).or_default().push(a.clone());
            }
        }
        let roots: HashSet<_> = now.keys().chain(self.written.keys()).cloned().collect();
        for root in roots {
            let mut alerts = now.remove(&root).unwrap_or_default();
            alerts.sort_by_key(|a| a.kind);
            if self.written.get(&root).is_none_or(|w| *w != alerts) {
                store_alerts(cx, &root, &alerts);
            }
            self.written.insert(root, alerts);
        }
    }
}

/// The projects the check covers: the scope of the project at `start`, or every registered
/// project when there is none (`rtok web` serves the whole registry).
fn scope_of(rt: &Runtime, start: Option<&Path>) -> Vec<Project> {
    let Some(dir) = start else {
        return rt.store.projects().unwrap_or_default();
    };
    match rt.store.project_by_root(dir) {
        Ok(Some(p)) => reach(&rt.store, &p)
            .map(|r| r.into_iter().map(|r| r.project).collect())
            .unwrap_or_default(),
        _ => Vec::new(),
    }
}

/// The health check of a hosting process: `rtok mcp` passes its working directory, `rtok web`
/// nothing. Ends on `stop`. Fails open: a panic or a store error costs that round only.
pub fn run(rt: &Runtime, start: Option<&Path>, stop: &AtomicBool) {
    let cfg = &rt.config.plugins.graph;
    if cfg.alerts && cfg.health_check_interval_s > 0 {
        let every = Duration::from_secs(u64::from(cfg.health_check_interval_s));
        run_every(rt, start, every, &lsp::probe, stop);
    }
}

fn run_every(
    rt: &Runtime,
    start: Option<&Path>,
    every: Duration,
    probe: &dyn Fn(&Path) -> Probe,
    stop: &AtomicBool,
) {
    let mut health = Health::default();
    while !stop.load(Ordering::Relaxed) {
        let round = Instant::now();
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            health.tick(rt, &scope_of(rt, start), probe, now_s());
        }));
        while round.elapsed() < every && !stop.load(Ordering::Relaxed) {
            std::thread::sleep(Duration::from_millis(10).min(every));
        }
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::atomic::AtomicUsize;

    use super::*;
    use crate::store::{LinkKind, Origin};

    const OK: &dyn Fn(&Path) -> Probe = &|_| Ok(());

    /// Projects `a`, `b`, `c` under a temp dir, each with a source file; `a` links to `b`, `b`
    /// to `c`. The returned roots are the registered strings.
    fn world(tag: &str) -> (Runtime, PathBuf, Vec<Project>) {
        let (c, dir) = crate::testutil::config(tag);
        let rt = Runtime::open(c, tag).unwrap();
        let mut ps = Vec::new();
        for name in ["a", "b", "c"] {
            let root = dir.join(name);
            fs::create_dir_all(&root).unwrap();
            fs::write(root.join("lib.rs"), format!("pub fn {name}_one() {{}}\n")).unwrap();
            ps.push(rt.store.register_project(&root, Origin::Manual).unwrap());
        }
        for (from, to) in [(0, 1), (1, 2)] {
            rt.store
                .link_projects(ps[from].id, ps[to].id, LinkKind::Manual, None)
                .unwrap();
        }
        (rt, dir, ps)
    }

    fn scope(rt: &Runtime, a: &Project) -> Vec<Project> {
        scope_of(rt, Some(Path::new(&a.root)))
    }

    fn alerts_of(rt: &Runtime, p: &Project) -> Vec<Alert> {
        mirrored(&Ctx::new(rt), &p.root)
    }

    #[test]
    fn a_renamed_directory_alerts_after_two_checks_and_clears_after_two() {
        let (rt, dir, ps) = world("t32917-missing");
        let (a, b) = (&ps[0], &ps[1]);
        let mut h = Health::default();
        h.tick(&rt, &scope(&rt, a), OK, 0);
        fs::rename(dir.join("b"), dir.join("b.moved")).unwrap();
        h.tick(&rt, &scope(&rt, a), OK, 60);
        assert!(alerts_of(&rt, b).is_empty(), "one check is not an alert");
        h.tick(&rt, &scope(&rt, a), OK, 120);
        let raised = alerts_of(&rt, b);
        assert_eq!(raised.len(), 1);
        assert_eq!((raised[0].kind, raised[0].since), (Kind::Missing, 120));

        // The same alert in a second process: doctor's list, and the notice agents read.
        let text = texts(&all(&rt)).join("\n");
        assert!(text.starts_with("b missing since "), "{text}");
        let note = notice(&rt, None, Path::new(&a.root)).unwrap();
        assert!(note.starts_with("notice: b missing since "), "{note}");
        assert!(note.ends_with("; results exclude b\n"), "{note}");
        let report = crate::doctor::page(&rt.config).unwrap();
        assert!(
            report
                .to_text()
                .contains("graph alerts\n  b missing since "),
            "{}",
            report.to_text()
        );
        let json = serde_json::to_value(&report).unwrap();
        assert_eq!(json["graph_alerts"][0]["kind"], "missing");
        let rows = serde_json::to_value(super::super::projects::rows(&rt).unwrap()).unwrap();
        assert_eq!(rows[1]["alerts"][0]["kind"], "missing", "{rows}");
        assert!(rows[0].get("alerts").is_none());

        let before = Ctx::new(&rt)
            .symbol_count(&index::canon(Path::new(&b.root)))
            .unwrap();
        fs::rename(dir.join("b.moved"), dir.join("b")).unwrap();
        fs::write(dir.join("b/new.rs"), "pub fn added_while_away() {}\n").unwrap();
        h.tick(&rt, &scope(&rt, a), OK, 180);
        assert_eq!(alerts_of(&rt, b).len(), 1, "one good check does not clear");
        h.tick(&rt, &scope(&rt, a), OK, 240);
        assert!(alerts_of(&rt, b).is_empty());
        // The unindexed `c` still lowers the scope's health; the alert itself is gone.
        let note = notice(&rt, None, Path::new(&a.root)).unwrap_or_default();
        assert!(!note.contains("missing"), "{note}");
        let after = Ctx::new(&rt)
            .symbol_count(&index::canon(Path::new(&b.root)))
            .unwrap();
        assert!(after > before, "recovery re-indexes: {before} -> {after}");
    }

    #[test]
    fn a_blip_of_one_check_never_alerts() {
        let (rt, dir, ps) = world("t32917-blip");
        let mut h = Health::default();
        fs::rename(dir.join("b"), dir.join("b.moved")).unwrap();
        h.tick(&rt, &scope(&rt, &ps[0]), OK, 0);
        fs::rename(dir.join("b.moved"), dir.join("b")).unwrap();
        for t in 1..4 {
            h.tick(&rt, &scope(&rt, &ps[0]), OK, t * 60);
        }
        assert!(all(&rt).is_empty());
    }

    #[test]
    fn several_at_once_are_one_alert_and_a_far_project_names_the_chain() {
        let (rt, dir, ps) = world("t32917-group");
        let mut h = Health::default();
        fs::rename(dir.join("b"), dir.join("b.moved")).unwrap();
        fs::rename(dir.join("c"), dir.join("c.moved")).unwrap();
        // `a` is the only member of its scope once `b` is gone, so the check covers the registry.
        for t in 0..2 {
            h.tick(&rt, &rt.store.projects().unwrap(), OK, t * 60);
        }
        let shown = texts(&all(&rt));
        assert_eq!(shown.len(), 1, "{shown:?}");
        assert!(shown[0].starts_with("b, c missing since "), "{shown:?}");
        assert!(shown[0].ends_with("(2 projects)"), "{shown:?}");
        let note = notice(&rt, None, Path::new(&ps[0].root)).unwrap();
        assert_eq!(note.lines().count(), 1, "{note}");
        assert!(note.contains("; results exclude b, c"), "{note}");

        fs::rename(dir.join("b.moved"), dir.join("b")).unwrap();
        for t in 2..4 {
            h.tick(&rt, &rt.store.projects().unwrap(), OK, t * 60);
        }
        let note = notice(&rt, None, Path::new(&ps[0].root)).unwrap();
        assert!(
            note.starts_with("notice: a to b to c: c missing since "),
            "{note}"
        );
    }

    #[test]
    fn a_vanished_parent_is_unreachable_not_missing() {
        let (c, dir) = crate::testutil::config("t32917-unreachable");
        let rt = Runtime::open(c, "t32917-unreachable").unwrap();
        let root = dir.join("disk/proj");
        fs::create_dir_all(&root).unwrap();
        rt.store.register_project(&root, Origin::Manual).unwrap();
        fs::remove_dir_all(dir.join("disk")).unwrap();
        let mut h = Health::default();
        for t in 0..2 {
            h.tick(&rt, &rt.store.projects().unwrap(), OK, t * 60);
        }
        assert_eq!(all(&rt)[0].kind, Kind::Unreachable);
    }

    #[test]
    fn a_manifest_path_that_points_nowhere_is_a_broken_link() {
        let (rt, dir, ps) = world("t32917-link");
        let manifest = dir.join("a/Cargo.toml");
        fs::write(
            &manifest,
            "[package]\nname = \"a\"\n[dependencies]\nlost = { path = \"../nowhere\" }\n",
        )
        .unwrap();
        let mut h = Health::default();
        for t in 0..2 {
            h.tick(&rt, &scope(&rt, &ps[0]), OK, t * 60);
        }
        let raised = alerts_of(&rt, &ps[0]);
        assert_eq!(raised[0].kind, Kind::LinkBroken, "{raised:?}");
        assert!(raised[0].detail.contains("../nowhere"), "{raised:?}");
        fs::write(&manifest, "[package]\nname = \"a\"\n").unwrap();
        for t in 2..4 {
            h.tick(&rt, &scope(&rt, &ps[0]), OK, t * 60);
        }
        assert!(alerts_of(&rt, &ps[0]).is_empty());
    }

    fn rust_project(tag: &str) -> (Runtime, Project) {
        let (mut c, dir) = crate::testutil::config(tag);
        c.plugins.graph.backend = "auto".into();
        let rt = Runtime::open(c, tag).unwrap();
        let root = dir.join("proj");
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("Cargo.toml"), "[package]\nname = \"x\"\n").unwrap();
        let p = rt.store.register_project(&root, Origin::Manual).unwrap();
        (rt, p)
    }

    /// One request that finds the server and then loses it, as a crash does.
    fn break_server(rt: &Runtime, p: &Project) {
        let first = AtomicUsize::new(0);
        for _ in 0..2 {
            let _ = super::super::door(
                &Ctx::new(rt),
                Path::new(&p.root),
                "symbol",
                &[],
                OK,
                || match first.fetch_add(1, Ordering::SeqCst) {
                    0 => Ok("a.rs:1 function".into()),
                    _ => Err(anyhow::anyhow!("lsp: eof")),
                },
                || Ok("tags answer".into()),
            );
        }
    }

    #[test]
    fn backend_down_alerts_then_clears_on_a_retry_without_a_restart() {
        let (rt, p) = rust_project("t32917-backend");
        break_server(&rt, &p);
        let root = Path::new(&p.root);
        assert!(capability::held(root).unwrap().down);
        let mut h = Health::default();
        let at = |h: &mut Health, t| h.tick(&rt, std::slice::from_ref(&p), OK, t);
        at(&mut h, 0);
        assert!(alerts_of(&rt, &p).is_empty());
        at(&mut h, 60);
        let raised = alerts_of(&rt, &p);
        assert_eq!(raised[0].kind, Kind::BackendDown, "{raised:?}");
        assert!(raised[0].detail.contains("eof"), "{raised:?}");
        // The retry came due in that round; the record is the server's again.
        assert_eq!(capability::held(root).unwrap().backend, Chosen::Lsp);
        at(&mut h, 120);
        assert_eq!(alerts_of(&rt, &p).len(), 1);
        at(&mut h, 180);
        assert!(alerts_of(&rt, &p).is_empty());
    }

    #[test]
    fn the_retry_backs_off_and_a_server_that_breaks_again_is_not_retried_every_round() {
        let (rt, p) = rust_project("t32917-backoff");
        let mut h = Health::default();
        let mut retried = Vec::new();
        for t in 0..8 {
            break_server(&rt, &p);
            h.tick(&rt, std::slice::from_ref(&p), OK, t * 60);
            // A retry puts the server back; the next round breaks it again.
            retried.push(capability::held(Path::new(&p.root)).unwrap().backend == Chosen::Lsp);
        }
        // After 60 s, then 120 s, then 240 s: not every round.
        let want = [false, true, false, true, false, false, false, true];
        assert_eq!(retried, want);
        assert!(h.backoff[&p.root].1 <= BACKOFF_CAP_S);
    }

    #[test]
    fn a_server_installed_later_is_picked_up_at_once() {
        let (rt, p) = rust_project("t32917-installed");
        let no_server: &dyn Fn(&Path) -> Probe = &|_| {
            Err(capability::Absent {
                server: true,
                reason: "lsp: rust-analyzer not on PATH".into(),
            })
        };
        let _ = super::super::door(
            &Ctx::new(&rt),
            Path::new(&p.root),
            "symbol",
            &[],
            no_server,
            || panic!("no server"),
            || Ok("tags answer".into()),
        );
        let mut h = Health::default();
        h.tick(&rt, std::slice::from_ref(&p), no_server, 0);
        assert_eq!(
            capability::held(Path::new(&p.root)).unwrap().backend,
            Chosen::Tags
        );
        h.tick(&rt, std::slice::from_ref(&p), OK, 60);
        assert_eq!(
            capability::held(Path::new(&p.root)).unwrap().backend,
            Chosen::Lsp
        );
        assert!(all(&rt).is_empty(), "a missing server is no alert");
    }

    #[test]
    fn the_loop_runs_on_its_own_clock_and_stops() {
        let (rt, dir, ps) = world("t32917-loop");
        fs::rename(dir.join("b"), dir.join("b.moved")).unwrap();
        let stop = AtomicBool::new(false);
        std::thread::scope(|s| {
            s.spawn(|| {
                run_every(
                    &rt,
                    Some(Path::new(&ps[0].root)),
                    Duration::from_millis(20),
                    OK,
                    &stop,
                )
            });
            let until = Instant::now() + Duration::from_secs(10);
            while all(&rt).is_empty() && Instant::now() < until {
                std::thread::sleep(Duration::from_millis(20));
            }
            stop.store(true, Ordering::Relaxed);
        });
        assert_eq!(all(&rt)[0].kind, Kind::Missing);
    }

    #[test]
    fn alerts_off_means_no_notice_and_no_loop() {
        let (mut c, dir) = crate::testutil::config("t32917-off");
        c.plugins.graph.alerts = false;
        let rt = Runtime::open(c, "t32917-off").unwrap();
        let root = dir.join("a");
        fs::create_dir_all(&root).unwrap();
        rt.store.register_project(&root, Origin::Manual).unwrap();
        store_alerts(
            &Ctx::new(&rt),
            &root.to_string_lossy(),
            &[Alert {
                kind: Kind::Missing,
                project: "a".into(),
                root: root.to_string_lossy().into(),
                since: 1,
                detail: "x".into(),
            }],
        );
        assert!(notice(&rt, None, &root).is_none());
        let stop = AtomicBool::new(false);
        // Returns at once instead of looping until `stop`.
        run(&rt, None, &stop);
    }
}

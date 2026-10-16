// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T480 (D27): the live calls pane of the Graph tab, the terminal counterpart of the web graph
//! page's panel (T329.26). The rows are `graph_events`, read by the web's own reader
//! ([`live::Reader`], the poller of `src/web/live.rs` without the async) and folded by the web
//! module's [`CallsStore`], which the page's `calls` frame is built from, so the pane and the page show the same
//! totals for the same calls. A freeze holds the picture only: the latest store keeps taking
//! batches, so nothing is lost when it lifts.
//!
//! T500 (D27, counterpart of T329.32) adds what fits a terminal: sparklines of the store buckets
//! for the selected window, the `outside scope` mark on a feed row that crosses out of the scope,
//! and the folded-target counter of the one-project view.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::mpsc;

use crossterm::event::KeyCode;
use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::Style;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Paragraph, Row, Table};

use super::projects::Entry;
use super::theme::{self, ACCENT, ERR, WARN};
use super::view::time_of;
use crate::config::{Config, Graph};
use crate::web::calls_store::{
    CallsStore, FeedFilter, Finished, Running, Spark, Totals, WINDOWS, clock_ms, distinct,
    filter_feed,
};
use crate::web::live::{self, CallBatch, Reader};

/// Tool bars the pane draws at most; the rest of a tall tool list is cut, not scrolled.
const MAX_BARS: usize = 4;
const BAR_WIDTH: u64 = 16;

struct Held {
    store: CallsStore,
    now: i64,
}

/// A node the one-project picture draws (T500): the label the pane names and the path a folded
/// target may sit under.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct Drawn {
    pub label: String,
    pub path: String,
}

/// The one-project view's drawn nodes and how many the cap left out. Folded counters need both
/// this and `more` > 0; outside that view nothing is folded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct OneProject {
    pub id: i32,
    pub drawn: Vec<Drawn>,
    pub more: usize,
}

/// The names the scope holds (T329 §8b / T500): the selected project plus everything its links
/// reach. `None` with no selection, so nothing is outside a scope that was never chosen.
fn scope_names(rows: &[Entry]) -> Option<HashSet<String>> {
    let from = rows.iter().find(|p| p.selected)?;
    let by_id: std::collections::HashMap<i32, &Entry> = rows.iter().map(|p| (p.id, p)).collect();
    let mut seen = HashSet::new();
    let mut todo = vec![from.id];
    while let Some(id) = todo.pop() {
        if !seen.insert(id) {
            continue;
        }
        if let Some(p) = by_id.get(&id) {
            for (to, _) in &p.links {
                if by_id.contains_key(to) && !seen.contains(to) {
                    todo.push(*to);
                }
            }
        }
    }
    Some(
        rows.iter()
            .filter(|p| seen.contains(&p.id))
            .map(|p| p.name.clone())
            .collect(),
    )
}

/// A call names its project; one the scope lacks is marked but never folded away (T500).
fn outside_scope(scope: &Option<HashSet<String>>, project: Option<&str>) -> bool {
    match (scope, project) {
        (Some(s), Some(p)) => !s.contains(p),
        _ => false,
    }
}

/// `▁▂▃▄▅▆▇█` scaled to the series' own max, so an all-zero series is a flat baseline.
fn spark_blocks(values: &[i64]) -> String {
    const BARS: [char; 8] = ['▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'];
    let top = values.iter().copied().max().unwrap_or(0);
    if top <= 0 {
        return BARS[0].to_string().repeat(values.len());
    }
    values
        .iter()
        .map(|&v| {
            let scaled = (v.max(0) as u64 * 7).div_ceil(top as u64).min(7) as usize;
            BARS[scaled]
        })
        .collect()
}

/// The page's `minutes`: the sparkline's own span, which "since start" caps at 15 minutes.
fn spark_minutes(span_ms: i64) -> i64 {
    (span_ms / 60_000).max(1)
}

/// Folded-call counters (T500, the web's `foldedCounters`): a call of the last 5 minutes whose
/// target matches no drawn node counts on the drawn node whose path equals or is a directory
/// prefix of the target (longest first), else on the "+N more" group. Sorted busiest first.
fn folded_counters(
    drawn: &[Drawn],
    more: usize,
    feed: &[Finished],
    running: &[Running],
    scope: &Option<HashSet<String>>,
    now: i64,
) -> Vec<(String, u64)> {
    if more == 0 {
        return Vec::new();
    }
    const FOLD_MS: i64 = 300_000;
    let labels: HashSet<&str> = drawn.iter().map(|d| d.label.as_str()).collect();
    let mut holders: Vec<&Drawn> = drawn.iter().collect();
    holders.sort_by(|a, b| b.path.len().cmp(&a.path.len()));
    let mut counts: std::collections::BTreeMap<String, u64> = Default::default();
    let mut group = 0u64;
    let mut fold = |target: Option<&str>| {
        let Some(target) = target else { return };
        if labels.contains(target) {
            return;
        }
        if let Some(h) = holders
            .iter()
            .find(|d| target == d.path || target.starts_with(&format!("{}/", d.path)))
        {
            *counts.entry(h.label.clone()).or_default() += 1;
        } else {
            group += 1;
        }
    };
    for r in feed {
        if r.interrupted || now - r.at > FOLD_MS || outside_scope(scope, r.project.as_deref()) {
            continue;
        }
        fold(r.target.as_deref());
    }
    for r in running {
        if now - r.at > FOLD_MS || outside_scope(scope, r.project.as_deref()) {
            continue;
        }
        fold(r.target.as_deref());
    }
    let mut out: Vec<(String, u64)> = counts.into_iter().collect();
    if group > 0 {
        out.push((format!("+{more} more"), group));
    }
    out.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    out
}

/// The pane's state. The clock arrives through [`Self::poll`], like the rest of the TUI state.
pub(super) struct LiveCalls {
    db_path: PathBuf,
    graph: Graph,
    /// Kept for the one-project view's drill read (T500); the feed reader needs only the path.
    cfg: Config,
    /// The newest event id when the TUI started: the totals count the events above it, read from
    /// the store when the reader starts, so they do not depend on when the Graph tab was first
    /// opened.
    started: Option<i64>,
    /// Started on the first look at the Graph tab, as the web's poller starts on the first
    /// subscribed socket; `None` until then.
    feed: Option<mpsc::Receiver<(CallBatch, i64)>>,
    latest: CallsStore,
    held: Option<Held>,
    window: usize,
    filter: FeedFilter,
    now: i64,
    /// The drilled picture of the selected project (T500); folded counters ride on it.
    one_project: Option<OneProject>,
    /// In-flight drill read for a project that just became selected.
    one_project_rx: Option<mpsc::Receiver<OneProject>>,
}

/// One thread on the web's reader; it ends when the TUI drops the receiver. Batches come with
/// the time to fold them at: the replay of the calls since `started` carries their own times.
fn spawn_reader(
    path: PathBuf,
    graph: Graph,
    started: Option<i64>,
) -> Option<mpsc::Receiver<(CallBatch, i64)>> {
    let (tx, rx) = mpsc::channel();
    std::thread::Builder::new()
        .name("rtok-tui-calls".into())
        .spawn(move || {
            let mut reader = None;
            let mut since = started;
            loop {
                // Open before the first sleep: events written while it runs must not be skipped.
                if reader.is_none() {
                    reader = Reader::open(&path, &graph);
                    if let (Some(r), Some(from)) = (reader.as_mut(), since.take()) {
                        for item in r.replay(from) {
                            if tx.send(item).is_err() {
                                return;
                            }
                        }
                    }
                }
                std::thread::sleep(live::POLL);
                if let Some(batch) = reader.as_mut().and_then(Reader::poll)
                    && tx.send((batch, clock_ms())).is_err()
                {
                    return;
                }
            }
        })
        .ok()?;
    Some(rx)
}

/// The selected project's drawn nodes and `more`, as the web's `useDrill` gets them (T500).
/// Off the UI thread; a failure answers nothing, so the pane keeps the picture it had.
fn load_one_project(cfg: &Config, id: i32) -> Option<OneProject> {
    #[cfg(not(feature = "graph"))]
    {
        let _ = (cfg, id);
        None
    }
    #[cfg(feature = "graph")]
    {
        use crate::plugins::graph::drill::{DEFAULT_LIMIT, DrillRequest};
        use crate::plugin::Runtime;
        let rt = Runtime::open(cfg.clone(), "tui-live-calls").ok()?;
        let req = DrillRequest {
            project: id.to_string(),
            limit: Some(DEFAULT_LIMIT),
            ..DrillRequest::default()
        };
        let g = crate::plugins::graph::drill::run(&rt, &req).ok()?;
        if g.state != "ok" {
            return None;
        }
        Some(OneProject {
            id: g.project,
            drawn: g
                .nodes
                .iter()
                .map(|n| Drawn {
                    label: n.label.clone(),
                    path: n.path.clone(),
                })
                .collect(),
            more: g.more,
        })
    }
}

impl LiveCalls {
    pub(super) fn new(cfg: &Config) -> Self {
        Self {
            db_path: cfg.core.db_path.clone(),
            graph: cfg.plugins.graph.clone(),
            cfg: cfg.clone(),
            started: live::head_of(&cfg.core.db_path),
            feed: None,
            latest: CallsStore::with(&cfg.plugins.graph),
            held: None,
            window: 1,
            filter: FeedFilter::default(),
            now: clock_ms(),
            one_project: None,
            one_project_rx: None,
        }
    }

    /// Takes what the reader found and drops calls whose process is gone. `active` is whether the
    /// Graph tab is up; the reader is not started before that. `selected` is the registry id the
    /// scope starts at; a change reloads the one-project picture (T500).
    pub(super) fn poll(&mut self, active: bool, selected: Option<i32>) {
        if active && self.feed.is_none() {
            self.feed = spawn_reader(self.db_path.clone(), self.graph.clone(), self.started);
        }
        self.now = clock_ms();
        let batches: Vec<(CallBatch, i64)> =
            self.feed.iter().flat_map(|rx| rx.try_iter()).collect();
        for (batch, at) in &batches {
            self.latest.fold(batch, *at);
        }
        self.latest.sweep(self.now);
        self.poll_one_project(selected);
    }

    /// Keeps the one-project picture on the selected project, loading it off the UI thread when
    /// the selection changes. A project that cannot answer leaves the old picture standing until
    /// the next selection: the pane must not blank because a drill read failed (fail open).
    fn poll_one_project(&mut self, selected: Option<i32>) {
        while let Some(rx) = &self.one_project_rx {
            match rx.try_recv() {
                Ok(one) => {
                    self.one_project = Some(one);
                    self.one_project_rx = None;
                }
                Err(mpsc::TryRecvError::Empty) => break,
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.one_project_rx = None;
                }
            }
        }
        let want = selected.unwrap_or(0);
        let have = self.one_project.as_ref().map_or(i32::MIN, |o| o.id);
        if want == have || self.one_project_rx.is_some() {
            return;
        }
        let Some(id) = selected else {
            self.one_project = None;
            return;
        };
        self.spawn_one_project(id);
    }

    fn spawn_one_project(&mut self, id: i32) {
        let (tx, rx) = mpsc::channel();
        self.one_project_rx = Some(rx);
        let cfg = self.cfg.clone();
        std::thread::Builder::new()
            .name("rtok-tui-one-project".into())
            .spawn(move || {
                if let Some(one) = load_one_project(&cfg, id)
                    && tx.send(one).is_ok()
                {
                    return;
                }
            })
            .ok();
    }

    /// The one-project picture, set directly by tests and by [`Self::poll_one_project`].
    #[cfg(test)]
    pub(super) fn set_one_project(&mut self, one: Option<OneProject>) {
        self.one_project = one;
    }

    #[cfg(test)]
    pub(super) fn feed_batch(&mut self, batch: &CallBatch, now: i64) {
        self.now = now;
        self.latest.fold(batch, now);
    }

    fn shown(&self) -> &CallsStore {
        self.held.as_ref().map_or(&self.latest, |h| &h.store)
    }

    fn shown_now(&self) -> i64 {
        self.held.as_ref().map_or(self.now, |h| h.now)
    }

    /// Calls that arrived while the picture was held.
    fn pending(&self) -> u64 {
        self.held
            .as_ref()
            .map_or(0, |h| self.latest.all.calls - h.store.all.calls)
    }

    fn toggle_freeze(&mut self) {
        self.held = match self.held {
            Some(_) => None,
            None => Some(Held {
                store: self.latest.clone(),
                now: self.now,
            }),
        };
    }

    /// The Graph tab's calls keys; `true` when the key was consumed.
    pub(super) fn key(&mut self, code: KeyCode) -> bool {
        // The filter picks from the rows on screen, which a freeze holds.
        let feed = &self.held.as_ref().map_or(&self.latest, |h| &h.store).feed;
        match code {
            KeyCode::Char('f') => self.toggle_freeze(),
            KeyCode::Char('w') => {
                self.window = (self.window + 1) % WINDOWS.len();
            }
            KeyCode::Char('a') => cycle(&mut self.filter.caller, feed, |r| Some(&r.caller)),
            KeyCode::Char('t') => cycle(&mut self.filter.tool, feed, |r| Some(&r.tool)),
            KeyCode::Char('o') => cycle(&mut self.filter.project, feed, |r| r.project.as_deref()),
            _ => return false,
        }
        true
    }
}

/// Steps a filter through "all" and the values in the feed, wrapping back to "all".
fn cycle<'a>(
    slot: &mut String,
    feed: &'a [Finished],
    pick: impl Fn(&'a Finished) -> Option<&'a str>,
) {
    let values = distinct(feed, pick);
    let next = if slot.is_empty() {
        values.first()
    } else {
        values
            .iter()
            .position(|v| v == slot)
            .and_then(|i| values.get(i + 1))
    };
    *slot = next.cloned().unwrap_or_default();
}

/// `1.2k`, `34k`, `5.6M`: the web page's `compact`.
fn compact(n: i64) -> String {
    let (a, f) = (n.unsigned_abs() as f64, n as f64);
    if a >= 1e9 {
        format!("{:.*}B", usize::from(a < 1e10), f / 1e9)
    } else if a >= 1e6 {
        format!("{:.*}M", usize::from(a < 1e7), f / 1e6)
    } else if a >= 1e4 {
        format!("{:.0}k", f / 1e3)
    } else if a >= 1e3 {
        format!("{:.1}k", f / 1e3)
    } else {
        n.to_string()
    }
}

/// `30 ms`, `1.5 s`: the web page's `millis`.
fn millis(ms: f64) -> String {
    if ms < 1000.0 {
        format!("{} ms", ms.round())
    } else {
        format!("{:.1} s", ms / 1000.0)
    }
}

fn percent(part: i64, whole: i64) -> String {
    if whole == 0 {
        "-".into()
    } else {
        format!("{:.0}%", part as f64 * 100.0 / whole as f64)
    }
}

fn chip(label: &str, on: bool) -> Span<'static> {
    let style = if on {
        theme::selected()
    } else {
        theme::muted()
    };
    Span::styled(format!(" {label} "), style)
}

fn filter_span(name: &str, value: &str) -> Vec<Span<'static>> {
    let style = if value.is_empty() {
        theme::muted()
    } else {
        Style::new().fg(WARN)
    };
    let shown = if value.is_empty() { "all" } else { value };
    vec![
        Span::styled(format!("{name} "), theme::muted()),
        Span::styled(shown.to_owned(), style),
        Span::raw("  "),
    ]
}

fn result_cell(r: &Finished) -> String {
    if r.interrupted {
        "interrupted".into()
    } else if r.ok {
        format!(
            "{} · {} saved · {} ms",
            r.backend.as_deref().unwrap_or("-"),
            compact(r.before - r.after),
            r.ms.unwrap_or(0.0).round()
        )
    } else {
        r.error.clone().unwrap_or_else(|| "failed".into())
    }
}

impl LiveCalls {
    fn metric_lines(&self, t: &Totals, bars: usize) -> Vec<Line<'static>> {
        let store = self.shown();
        let saved = t.before - t.after;
        let mut windows: Vec<Span> = WINDOWS
            .iter()
            .enumerate()
            .map(|(i, (label, _))| chip(label, i == self.window))
            .collect();
        windows.push(Span::raw("  "));
        windows.push(if self.held.is_some() {
            Span::styled(
                format!("frozen · {} held", self.pending()),
                Style::new().fg(WARN),
            )
        } else {
            Span::styled("live", theme::muted())
        });
        let running = if store.running.is_empty() {
            "idle".to_owned()
        } else {
            store
                .running
                .iter()
                .map(|r| r.tool.as_str())
                .collect::<Vec<_>>()
                .join(", ")
        };
        let failed = if t.failed > 0 {
            Style::new().fg(WARN)
        } else {
            theme::muted()
        };
        let kpi = Line::from(vec![
            Span::styled("running ", theme::muted()),
            Span::raw(format!("{} ({running})  ", store.running.len())),
            Span::styled("calls ", theme::muted()),
            Span::raw(format!("{} ", t.calls)),
            Span::styled(format!("({} failed)  ", t.failed), failed),
            Span::styled("sent ", theme::muted()),
            Span::raw(format!("{}  ", compact(t.after))),
            Span::styled("without rtok ", theme::muted()),
            Span::raw(format!("{}  ", compact(t.before))),
            Span::styled("saved ", theme::muted()),
            Span::styled(
                format!("{} ({})", compact(saved), percent(saved, t.before)),
                Style::new().fg(ACCENT),
            ),
        ]);
        let latency = t.latency().map_or_else(
            || Span::raw("-  "),
            |(p50, p95)| Span::raw(format!("{} (p95 {})  ", millis(p50), millis(p95))),
        );
        let fallbacks = if t.fallbacks > 0 {
            Style::new().fg(WARN)
        } else {
            theme::muted()
        };
        let metrics = Line::from(vec![
            Span::styled("latency p50 ", theme::muted()),
            latency,
            Span::styled("symbols returned ", theme::muted()),
            Span::raw(format!(
                "{} of {} ({} across projects)  ",
                t.symbols_returned, t.symbols, t.crossed
            )),
            Span::styled("fallbacks ", theme::muted()),
            Span::styled(format!("{} ({} capped)", t.fallbacks, t.caps), fallbacks),
        ]);
        // T487: a line of its own, because the counts above already fill a 128-column pane.
        let answer = Line::from(vec![
            Span::styled("files touched ", theme::muted()),
            Span::raw(format!("{}  ", t.files_touched)),
            Span::styled("projects with hits ", theme::muted()),
            Span::raw(t.projects_hit.to_string()),
        ]);
        let mut lines = vec![Line::from(windows), kpi, metrics, answer];
        let mut tools: Vec<_> = t.tools.iter().collect();
        tools.sort_by(|a, b| b.1.calls.cmp(&a.1.calls).then(a.0.cmp(b.0)));
        let top = tools.first().map_or(1, |(_, v)| v.calls).max(1);
        for (tool, v) in tools.into_iter().take(bars) {
            let width = (v.calls * BAR_WIDTH).div_ceil(top) as usize;
            lines.push(Line::from(vec![
                Span::raw(format!("{tool:<10}")),
                Span::styled("█".repeat(width), Style::new().fg(ACCENT)),
                Span::styled(
                    format!(" {} · {} saved", v.calls, compact(v.saved)),
                    theme::muted(),
                ),
            ]));
        }
        let listed: u64 = t.backends.values().sum();
        if listed > 0 {
            let shares: Vec<String> = t
                .backends
                .iter()
                .map(|(b, n)| format!("{b} {}", percent(*n as i64, listed as i64)))
                .collect();
            lines.push(Line::styled(
                format!("backend {}", shares.join("  ")),
                theme::muted(),
            ));
        }
        lines
    }

    /// T500: the page's "calls over the last N min" / "tokens saved over the last N min" on the
    /// store's own buckets (`CallsStore::spark`), so the series matches the web cards.
    fn spark_lines(&self) -> Vec<Line<'static>> {
        let spark: Spark = self.shown().spark(self.window, self.shown_now());
        let mins = spark_minutes(spark.span_ms);
        let row = |what: &str, values: Vec<i64>| {
            Line::from(vec![
                Span::styled(
                    format!("{what} over the last {mins} min  "),
                    theme::muted(),
                ),
                Span::styled(spark_blocks(&values), Style::new().fg(ACCENT)),
            ])
        };
        vec![
            row("calls", spark.calls.iter().map(|&c| c as i64).collect()),
            row("tokens saved", spark.saved.clone()),
        ]
    }

    /// T500: `{label} · {n} folded` under "folded calls", only in the one-project view when the
    /// cap left nodes out. One line, busiest first, as the page lists them.
    fn folded_line(&self, scope: &Option<HashSet<String>>) -> Option<Line<'static>> {
        let one = self.one_project.as_ref()?;
        if one.more == 0 {
            return None;
        }
        let now = self.shown_now();
        let store = self.shown();
        let counters = folded_counters(
            &one.drawn,
            one.more,
            &store.feed,
            &store.running,
            scope,
            now,
        );
        if counters.is_empty() {
            return None;
        }
        let listed = counters
            .iter()
            .map(|(label, n)| format!("{label} · {n} folded"))
            .collect::<Vec<_>>()
            .join("  ");
        Some(Line::from(vec![
            Span::styled("folded calls  ", theme::muted()),
            Span::raw(listed),
        ]))
    }

    pub(super) fn render(&self, frame: &mut Frame, area: Rect, rows: &[Entry]) {
        let t = self.shown().window_totals(self.window, self.shown_now());
        let scope = scope_names(rows);
        let spark = self.spark_lines();
        let folded = self.folded_line(&scope);
        // Four metric rows, the sparklines, the optional folded line and the filter row; the bars
        // yield so the feed keeps its rows.
        let extra = spark.len() + usize::from(folded.is_some());
        let bars = MAX_BARS.min(usize::from(area.height).saturating_sub(9 + extra));
        let mut lines = self.metric_lines(&t, bars);
        lines.extend(spark);
        lines.extend(folded);
        let mut filters = Vec::new();
        for (name, value) in [
            ("caller [a]", &self.filter.caller),
            ("tool [t]", &self.filter.tool),
            ("project [o]", &self.filter.project),
        ] {
            filters.extend(filter_span(
                name,
                &value.chars().take(12).collect::<String>(),
            ));
        }
        lines.push(Line::from(filters));
        let [top, feed] =
            Layout::vertical([Constraint::Length(lines.len() as u16), Constraint::Min(0)])
                .areas(area);
        frame.render_widget(Paragraph::new(lines), top);
        let rows = filter_feed(&self.shown().feed, &self.filter);
        let title = format!(
            "graph calls ({} of {})",
            rows.len(),
            self.shown().feed.len()
        );
        let table_rows = rows.iter().map(|r| {
            let project = match &r.project {
                Some(p) if outside_scope(&scope, Some(p)) => format!("{p} · outside scope"),
                Some(p) => p.clone(),
                None => "-".into(),
            };
            let row = Row::new([
                time_of(r.at / 1000),
                r.tool.clone(),
                r.target.clone().unwrap_or_else(|| "-".into()),
                r.caller.clone(),
                project,
                result_cell(r),
            ]);
            if r.ok {
                row
            } else {
                row.style(Style::new().fg(ERR))
            }
        });
        let table = Table::new(
            table_rows,
            [
                Constraint::Length(8),
                Constraint::Length(9),
                Constraint::Min(10),
                Constraint::Length(16),
                Constraint::Min(8),
                Constraint::Min(20),
            ],
        )
        .header(theme::header([
            "time", "tool", "symbol", "caller", "project", "result",
        ]))
        .block(theme::section(title));
        frame.render_widget(table, feed);
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use crossterm::event::KeyModifiers;
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::store::MeasurementSample;
    use crate::tui::app::{App, KEYS, tests::config};
    use crate::tui::view;
    use crate::web::calls_store::fixtures::{T, batch, end, event};

    fn graph_app() -> App {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        let app = App::new(&cfg);
        assert_eq!(app.page(), "graph");
        app
    }

    fn screen(app: &App) -> String {
        screen_at(app, 130, 40)
    }

    fn screen_at(app: &App, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal.draw(|f| view::draw(f, app)).unwrap();
        let buf = terminal.backend().buffer().clone();
        (0..buf.area.height)
            .map(|y| {
                (0..buf.area.width)
                    .map(|x| buf[(x, y)].symbol())
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    fn press(app: &mut App, c: char) {
        assert!(!app.key(KeyCode::Char(c), KeyModifiers::NONE));
    }

    fn first_batch() -> CallBatch {
        let mut failed = end("b", 0, 0);
        failed.ok = false;
        failed.error = Some("no backend".into());
        failed.tool = "impact".into();
        batch(vec![end("a", 100, 30), failed, event("c")], 0)
    }

    /// The numbers the store computes for this batch, which is what the page's frame carries.
    #[test]
    fn the_pane_shows_the_totals_the_web_store_computes() {
        let mut app = graph_app();
        app.live_mut().feed_batch(&first_batch(), T);
        let s = screen(&app);
        for want in [
            "running 1 (callers)",
            "calls 2 (1 failed)",
            "sent 30",
            "without rtok 100",
            "saved 70 (70%)",
            "callers",
            "backend tags 100%",
            "graph calls (2 of 2)",
            "no backend",
            "tags · 70 saved · 12 ms",
        ] {
            assert!(s.contains(want), "missing `{want}` in\n{s}");
        }
        let t = app.live().latest.window_totals(3, T);
        assert_eq!((t.calls, t.failed, t.before, t.after), (2, 1, 100, 30));
    }

    /// The events and figures of the `calls_store.rs` metrics test, read off the pane.
    #[test]
    fn the_pane_shows_the_call_metrics_with_the_web_wording() {
        let sample = |kind: &str, ref_id: Option<&str>| MeasurementSample {
            id: 1,
            kind: kind.into(),
            before_bytes: 0,
            after_bytes: 0,
            est_before: 0,
            est_after: 0,
            ref_id: ref_id.map(Into::into),
        };
        let timed = |call: &str, ms: f64| {
            let mut e = end(call, 0, 0);
            e.ms = Some(ms);
            e.samples.clear();
            e
        };
        let mut a = timed("a", 10.0);
        a.symbols = Some(2);
        a.total = Some(3);
        a.samples = vec![sample("lsp_fallback", None)];
        (a.symbols_returned, a.files_touched, a.projects_hit) = (Some(1), Some(4), Some(2));
        let mut b = timed("b", 20.0);
        b.symbols = Some(1);
        b.total = Some(1);
        (b.symbols_returned, b.files_touched, b.projects_hit) = (Some(1), Some(2), Some(1));
        b.samples = vec![sample("cap", Some("ab")), sample("explore", None)];
        let events = vec![a, b, timed("c", 30.0), timed("d", 40.0), timed("e", 100.0)];
        let mut app = graph_app();
        app.live_mut().feed_batch(&batch(events, 0), T);
        let s = screen(&app);
        for want in [
            "latency p50 30 ms (p95 100 ms)",
            "symbols returned 2 of 3 (1 across projects)",
            "fallbacks 1 (1 capped)",
            "files touched 6  projects with hits 3",
        ] {
            assert!(s.contains(want), "missing `{want}` in\n{s}");
        }
    }

    /// The Graph page is cramped at 24 rows: the metrics line must not push the feed out.
    #[test]
    fn the_metrics_line_leaves_the_feed_its_rows_at_24_rows() {
        let mut app = graph_app();
        app.live_mut().feed_batch(&first_batch(), T);
        let s = screen_at(&app, 130, 24);
        assert!(s.contains("latency p50 12 ms (p95 12 ms)"), "{s}");
        assert!(s.contains("no backend"), "{s}");
        assert!(s.contains("tags · 70 saved · 12 ms"), "{s}");
    }

    #[test]
    fn a_slow_call_is_shown_in_seconds_and_no_latency_as_a_dash() {
        assert_eq!(millis(29.6), "30 ms");
        assert_eq!(millis(1500.0), "1.5 s");
        let app = graph_app();
        let t = app.live().latest.window_totals(1, T);
        let line = app.live().metric_lines(&t, 0)[2].to_string();
        assert!(
            line.starts_with("latency p50 -  symbols returned 0 of 0"),
            "{line}"
        );
    }

    #[test]
    fn a_freeze_holds_the_picture_and_the_unfreeze_shows_every_held_call() {
        let mut app = graph_app();
        app.live_mut().feed_batch(&first_batch(), T);
        press(&mut app, 'f');
        app.live_mut().feed_batch(
            &batch(vec![end("d", 40, 10), end("e", 40, 10)], 0),
            T + 1000,
        );
        let held = screen(&app);
        assert!(held.contains("frozen · 2 held"), "{held}");
        assert!(held.contains("calls 2 (1 failed)"), "{held}");
        assert!(!held.contains("sent 50"), "{held}");
        press(&mut app, 'f');
        let live = screen(&app);
        assert!(live.contains("calls 4 (1 failed)"), "{live}");
        assert!(live.contains("sent 50"), "{live}");
        assert!(live.contains("graph calls (4 of 4)"), "{live}");
        assert!(!live.contains("frozen"), "{live}");
    }

    #[test]
    fn the_windows_chips_and_filters_narrow_the_pane() {
        let mut app = graph_app();
        app.live_mut().feed_batch(&first_batch(), T);
        press(&mut app, 't');
        let s = screen(&app);
        assert!(s.contains("graph calls (1 of 2)"), "{s}");
        assert!(s.contains("tool [t] callers"), "{s}");
        press(&mut app, 't');
        press(&mut app, 't');
        assert!(
            screen(&app).contains("graph calls (2 of 2)"),
            "t wraps to all"
        );
        // The default window is 5 minutes; a batch older than that falls out of it.
        app.live_mut()
            .feed_batch(&batch(vec![end("z", 8, 4)], 0), T + 6 * 60_000);
        assert!(screen(&app).contains("calls 1 (0 failed)"));
        press(&mut app, 'w');
        press(&mut app, 'w');
        assert!(
            screen(&app).contains("calls 3 (1 failed)"),
            "since start sums all"
        );
    }

    #[test]
    fn every_graph_key_in_the_table_is_handled() {
        let mut live = LiveCalls::new(&config());
        // The Graph page also lists the project keys (T476); the pane's rows all name the calls.
        let pane = KEYS
            .iter()
            .filter(|(p, _, d)| *p == "graph" && d.contains("calls"));
        for (_, key, _) in pane {
            let c = key.chars().next().unwrap();
            assert!(
                live.key(KeyCode::Char(c)),
                "`{key}` is listed but not handled"
            );
        }
        assert!(!live.key(KeyCode::Char('x')));
    }

    #[test]
    fn rows_the_poller_finds_in_the_store_reach_the_pane() {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        let mut live = LiveCalls::new(&cfg);
        live.poll(true, None);
        // The reader arms itself at the newest event and says nothing when it has, so calls are
        // written until one is seen: whichever lands after the arming reaches the pane.
        let store = crate::store::Store::open(&cfg.core.db_path).unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        let mut n = 0;
        while live.latest.all.calls == 0 && std::time::Instant::now() < deadline {
            n += 1;
            for e in [event(&format!("c{n}")), end(&format!("c{n}"), 100, 30)] {
                store.insert_graph_event(&e).unwrap();
            }
            std::thread::sleep(Duration::from_millis(100));
            live.poll(true, None);
        }
        let all = &live.latest.all;
        assert!(all.calls >= 1, "no call reached the pane");
        assert_eq!(
            (all.before, all.after),
            (100 * all.calls as i64, 30 * all.calls as i64)
        );
    }

    #[test]
    fn the_caller_column_shows_the_name_the_store_gave_else_the_session() {
        let mut app = graph_app();
        let mut b = first_batch();
        b.callers
            .insert("session-abcdef".into(), "claude 3f9a1c2e".into());
        app.live_mut().feed_batch(&b, T);
        let s = screen(&app);
        assert!(s.contains("claude 3f9a1c2e"), "{s}");
        let mut other = end("z", 8, 4);
        other.session = "mcp-1234567".into();
        app.live_mut().feed_batch(&batch(vec![other], 0), T + 1);
        assert!(screen(&app).contains("mcp-1234"));
    }

    /// The pane counts the calls made since the TUI started, even when the Graph tab was opened
    /// after them.
    #[test]
    fn calls_made_before_the_graph_tab_opened_are_counted() {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        let mut live = LiveCalls::new(&cfg);
        let store = crate::store::Store::open(&cfg.core.db_path).unwrap();
        for e in [event("early"), end("early", 100, 30)] {
            store.insert_graph_event(&e).unwrap();
        }
        live.poll(true, None);
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        while live.latest.all.calls == 0 && std::time::Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(100));
            live.poll(true, None);
        }
        assert_eq!(live.latest.all.calls, 1);
        assert_eq!(live.latest.feed[0].target.as_deref(), Some("open_index"));
    }

    fn entry(id: i32, name: &str, selected: bool, links: &[(i32, &str)]) -> Entry {
        use crate::plugins::graph::health::score::{Components, Level, Score};
        Entry {
            id,
            name: name.into(),
            selected,
            missing: false,
            state: "indexed",
            links: links.iter().map(|(to, n)| (*to, (*n).to_string())).collect(),
            health: Score {
                score: Some(100),
                level: Level::Good,
                components: Components {
                    freshness: 1.0,
                    backend: 1.0,
                    links: 1.0,
                },
                reasons: vec![],
            },
            scope_health: Some(100),
        }
    }

    fn drawn(label: &str, path: &str) -> Drawn {
        Drawn {
            label: label.into(),
            path: path.into(),
        }
    }

    /// T500: the scope is the selected project plus everything its links reach; with no
    /// selection nothing is outside.
    #[test]
    fn the_scope_follows_the_selected_projects_links() {
        let rows = vec![
            entry(1, "rtok", true, &[(2, "ketch")]),
            entry(2, "ketch", false, &[]),
            entry(3, "other", false, &[]),
        ];
        let scope = scope_names(&rows).unwrap();
        assert_eq!(
            scope.iter().map(String::as_str).collect::<HashSet<_>>(),
            HashSet::from(["rtok", "ketch"])
        );
        assert!(outside_scope(&scope_names(&rows), Some("other")));
        assert!(!outside_scope(&scope_names(&rows), Some("ketch")));
        // No project on a call is never outside; no selection means no scope at all.
        assert!(!outside_scope(&scope_names(&rows), None));
        let none = vec![entry(1, "rtok", false, &[])];
        assert!(scope_names(&none).is_none());
        assert!(!outside_scope(&scope_names(&none), Some("other")));
    }

    /// T500: the pane names the series "calls over the last N min" and "tokens saved over the
    /// last N min", drawing the store's own buckets.
    #[test]
    fn the_sparklines_match_the_store_buckets() {
        let mut app = graph_app();
        app.live_mut().feed_batch(&batch(vec![end("a", 100, 30), end("b", 40, 10)], 0), T);
        let s = screen(&app);
        assert!(s.contains("calls over the last 5 min"), "{s}");
        assert!(s.contains("tokens saved over the last 5 min"), "{s}");
        let store = app.live().shown();
        let spark = store.spark(1, T);
        assert_eq!(spark.calls.iter().sum::<u64>(), 2);
        assert_eq!(spark.saved.iter().sum::<i64>(), 100);
        assert_eq!((spark.span_ms, spark.calls.len()), (300_000, 30));
    }

    /// T500: `▁▂▃▄▅▆▇█` scaled to the series' own max, and a flat baseline when nothing happened.
    #[test]
    fn spark_blocks_scale_to_the_series_max() {
        assert_eq!(spark_blocks(&[]), "");
        assert_eq!(spark_blocks(&[0, 0]), "▁▁");
        assert_eq!(spark_blocks(&[0, 1, 2]), "▁▄█");
        assert_eq!(spark_blocks(&[5, 10]), "▄█");
    }

    /// T500: a feed row whose project is not in the scope carries the mark beside the project.
    #[test]
    fn an_outside_scope_call_is_marked_beside_the_project() {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        let mut app = App::new(&cfg);
        use crate::model::Snapshot;
        use crate::plugins::graph::health::score::{Components, Level, Score};
        use crate::plugins::graph::projects::ProjectRow;
        let score = Score {
            score: Some(100),
            level: Level::Good,
            components: Components {
                freshness: 1.0,
                backend: 1.0,
                links: 1.0,
            },
            reasons: vec![],
        };
        let mut sel = ProjectRow::fixture(1, "rtok", score.clone(), Some(100));
        sel.selected = true;
        let other = ProjectRow::fixture(2, "ketch", score.clone(), Some(100));
        let outside = ProjectRow::fixture(3, "elsewhere", score, Some(100));
        app.refresh(Snapshot {
            projects: Some(vec![sel, other, outside]),
            ..Snapshot::default()
        });
        app.live_mut().feed_batch(&first_batch(), T);
        let s = screen(&app);
        // The fixtures name their project `rtok`, which the selected scope holds: no mark.
        assert!(!s.contains("outside scope"), "{s}");
        let mut far = end("far", 8, 4);
        far.project = Some("elsewhere".into());
        app.live_mut().feed_batch(&batch(vec![far], 0), T + 1);
        let s = screen(&app);
        assert!(s.contains("elsewhere · outside scope"), "{s}");
    }

    /// T500: a folded target counts on the drawn node whose path holds it, longest first, else
    /// on the "+N more" group.
    #[test]
    fn folded_targets_count_on_their_nearest_visible_ancestor() {
        let scope = Some(HashSet::from(["rtok".to_string()]));
        let nodes = vec![
            drawn("main.rs", "src/main.rs"),
            drawn("store.rs", "src/store.rs"),
        ];
        let mut hidden = end("hidden_fn", 1, 1);
        hidden.target = Some("hidden_fn".into());
        let mut inside = end("inside", 1, 1);
        inside.target = Some("src/store.rs/open".into());
        let mut on_file = end("on_file", 1, 1);
        on_file.target = Some("src/main.rs".into());
        let mut store = CallsStore::with(&Graph::default());
        store.fold(&batch(vec![hidden, inside, on_file], 0), T);
        let counters = folded_counters(&nodes, 7, &store.feed, &store.running, &scope, T);
        // `src/main.rs` matches its node's label and is not folded; the other two are.
        assert_eq!(
            counters,
            vec![("+7 more".to_string(), 1), ("store.rs".to_string(), 1)]
        );
        // Without folded nodes nothing is counted; an outside-scope call is not folded.
        assert!(folded_counters(&nodes, 0, &store.feed, &store.running, &scope, T).is_empty());
        let mut far = end("far", 1, 1);
        far.project = Some("elsewhere".into());
        far.target = Some("hidden_fn".into());
        let mut outside = CallsStore::with(&Graph::default());
        outside.fold(&batch(vec![far], 0), T);
        assert_eq!(
            folded_counters(&nodes, 7, &outside.feed, &outside.running, &scope, T),
            Vec::<(String, u64)>::new()
        );
    }

    /// T500: the pane lists `{label} · {n} folded` under "folded calls" in the one-project view.
    #[test]
    fn the_pane_lists_folded_calls_in_the_one_project_view() {
        let mut app = graph_app();
        let mut hidden = end("hidden_fn", 1, 1);
        hidden.target = Some("hidden_fn".into());
        let mut inside = end("inside", 1, 1);
        inside.target = Some("src/store.rs/open".into());
        app.live_mut().feed_batch(&batch(vec![hidden, inside], 0), T);
        app.live_mut().set_one_project(Some(OneProject {
            id: 1,
            drawn: vec![drawn("main.rs", "src/main.rs"), drawn("store.rs", "src/store.rs")],
            more: 7,
        }));
        let s = screen(&app);
        assert!(s.contains("folded calls"), "{s}");
        assert!(s.contains("store.rs · 1 folded"), "{s}");
        assert!(s.contains("+7 more · 1 folded"), "{s}");
        // The cap left nothing out: no folded line at all.
        app.live_mut().set_one_project(Some(OneProject {
            id: 1,
            drawn: vec![drawn("main.rs", "src/main.rs")],
            more: 0,
        }));
        assert!(!screen(&app).contains("folded calls"));
    }
}

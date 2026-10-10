// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T480 (D27): the live calls pane of the Graph tab, the terminal counterpart of the web graph
//! page's panel (T329.26). The rows are `graph_events`, read by the web's own reader
//! ([`live::Reader`], the poller of `src/web/live.rs` without the async) and folded by the web
//! module's port of the page's store ([`CallsStore`]), so the pane and the page show the same
//! totals for the same calls. A freeze holds the picture only: the latest store keeps taking
//! batches, so nothing is lost when it lifts.

use std::path::PathBuf;
use std::sync::mpsc;
use std::time::{SystemTime, UNIX_EPOCH};

use crossterm::event::KeyCode;
use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::Style;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Paragraph, Row, Table};

use super::theme::{self, ACCENT, ERR, WARN};
use super::view::time_of;
use crate::web::calls_store::{
    CallsStore, FeedFilter, Finished, Totals, WINDOWS, distinct, filter_feed,
};
use crate::web::live::{self, CallBatch, Reader};

/// Tool bars the pane draws at most; the rest of a tall tool list is cut, not scrolled.
const MAX_BARS: usize = 4;
const BAR_WIDTH: u64 = 16;

struct Held {
    store: CallsStore,
    now: i64,
}

/// The pane's state. The clock arrives through [`Self::poll`], like the rest of the TUI state.
pub(super) struct LiveCalls {
    db_path: PathBuf,
    /// Started on the first look at the Graph tab, as the web's poller starts on the first
    /// subscribed socket; `None` until then.
    feed: Option<mpsc::Receiver<CallBatch>>,
    latest: CallsStore,
    held: Option<Held>,
    window: usize,
    filter: FeedFilter,
    now: i64,
}

fn clock_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// One thread on the web's reader; it ends when the TUI drops the receiver.
fn spawn_reader(path: PathBuf) -> Option<mpsc::Receiver<CallBatch>> {
    let (tx, rx) = mpsc::channel();
    std::thread::Builder::new()
        .name("rtok-tui-calls".into())
        .spawn(move || {
            let mut reader = None;
            loop {
                // Open before the first sleep: events written while it runs must not be skipped.
                if reader.is_none() {
                    reader = Reader::open(&path);
                }
                std::thread::sleep(live::POLL);
                if let Some(batch) = reader.as_mut().and_then(Reader::poll)
                    && tx.send(batch).is_err()
                {
                    return;
                }
            }
        })
        .ok()?;
    Some(rx)
}

impl LiveCalls {
    pub(super) fn new(db_path: PathBuf) -> Self {
        Self {
            db_path,
            feed: None,
            latest: CallsStore::default(),
            held: None,
            window: 1,
            filter: FeedFilter::default(),
            now: clock_ms(),
        }
    }

    /// Takes what the reader found and drops calls whose process is gone. `active` is whether the
    /// Graph tab is up; the reader is not started before that.
    pub(super) fn poll(&mut self, active: bool) {
        if active && self.feed.is_none() {
            self.feed = spawn_reader(self.db_path.clone());
        }
        self.now = clock_ms();
        let batches: Vec<CallBatch> = self.feed.iter().flat_map(|rx| rx.try_iter()).collect();
        for batch in &batches {
            self.latest.fold(batch, self.now);
        }
        self.latest.sweep(self.now);
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
            KeyCode::Char('a') => cycle(&mut self.filter.session, feed, |r| Some(&r.session)),
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
            Span::styled("symbols asked ", theme::muted()),
            Span::raw(format!("{} ({} across projects)  ", t.symbols, t.crossed)),
            Span::styled("fallbacks ", theme::muted()),
            Span::styled(format!("{} ({} capped)", t.fallbacks, t.caps), fallbacks),
        ]);
        let mut lines = vec![Line::from(windows), kpi, metrics];
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

    pub(super) fn render(&self, frame: &mut Frame, area: Rect) {
        let t = self.shown().window_totals(self.window, self.shown_now());
        // The Graph page gives the pane 13 rows at 24; the bars yield so the feed keeps its rows.
        let bars = MAX_BARS.min(usize::from(area.height).saturating_sub(10));
        let mut lines = self.metric_lines(&t, bars);
        let mut filters = Vec::new();
        for (name, value) in [
            ("caller [a]", &self.filter.session),
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
            let row = Row::new([
                time_of(r.at / 1000),
                r.tool.clone(),
                r.target.clone().unwrap_or_else(|| "-".into()),
                r.session.chars().take(8).collect(),
                r.project.clone().unwrap_or_else(|| "-".into()),
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
                Constraint::Length(8),
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

    /// The numbers are those `callsStore.test.ts` expects for the same events, and the same
    /// ones the store computes for this batch.
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
        let mut b = timed("b", 20.0);
        b.symbols = Some(1);
        b.total = Some(1);
        b.samples = vec![sample("cap", Some("ab")), sample("explore", None)];
        let events = vec![a, b, timed("c", 30.0), timed("d", 40.0), timed("e", 100.0)];
        let mut app = graph_app();
        app.live_mut().feed_batch(&batch(events, 0), T);
        let s = screen(&app);
        for want in [
            "latency p50 30 ms (p95 100 ms)",
            "symbols asked 3 (1 across projects)",
            "fallbacks 1 (1 capped)",
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
        assert!(line.starts_with("latency p50 -  symbols asked 0"), "{line}");
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
            "since open sums all"
        );
    }

    #[test]
    fn every_graph_key_in_the_table_is_handled() {
        let mut live = LiveCalls::new(std::path::PathBuf::new());
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
        let mut live = LiveCalls::new(cfg.core.db_path.clone());
        live.poll(true);
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
            live.poll(true);
        }
        let all = &live.latest.all;
        assert!(all.calls >= 1, "no call reached the pane");
        assert_eq!(
            (all.before, all.after),
            (100 * all.calls as i64, 30 * all.calls as i64)
        );
    }
}

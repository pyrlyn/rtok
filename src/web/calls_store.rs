// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T480: the live calls panel's aggregation in Rust, for `rtok tui`. It is a port of
//! `web/src/pages/graph3d/live/callsStore.ts` (T329.26): same fold, same windows, same
//! interrupt sweep, same filters. The web computes in the browser and the server does not
//! send totals, so the port is held to that file by its tests, which repeat the cases of
//! `callsStore.test.ts` with the same numbers. Totals come from the batch `summary`, which
//! counts every event of a poll, so they equal what `rtok stats` sums over the same calls.

use std::collections::{BTreeMap, HashSet};

use super::live::CallBatch;
use crate::store::{EventPhase, GraphEvent};

pub const FEED_ROWS: usize = 200;
/// A call with no end this long after its start is shown as interrupted: its process is gone.
pub const INTERRUPT_MS: i64 = 120_000;
const LONGEST_BUCKET_MS: i64 = 900_000;

/// The window chips of the page; the last one is "since open" and reads the running total.
pub const WINDOWS: [(&str, i64); 4] = [
    ("1 min", 60_000),
    ("5 min", 300_000),
    ("15 min", LONGEST_BUCKET_MS),
    ("since open", i64::MAX),
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Running {
    pub call: String,
    pub tool: String,
    pub target: Option<String>,
    pub project: Option<String>,
    pub session: String,
    /// The reader's clock: the interrupt timeout must not depend on the writer's.
    pub at: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Finished {
    pub call: String,
    pub tool: String,
    pub target: Option<String>,
    pub project: Option<String>,
    pub session: String,
    pub ok: bool,
    pub error: Option<String>,
    pub backend: Option<String>,
    pub ms: Option<f64>,
    pub before: i64,
    pub after: i64,
    pub at: i64,
    pub interrupted: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ToolTotals {
    pub calls: u64,
    pub saved: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Totals {
    pub calls: u64,
    pub failed: u64,
    pub before: i64,
    pub after: i64,
    /// Counted from the events a batch lists, so under a burst the bars cover the listed
    /// calls; `calls` is exact.
    pub tools: BTreeMap<String, ToolTotals>,
    pub backends: BTreeMap<String, u64>,
}

impl Totals {
    fn add(&mut self, from: &Totals) {
        self.calls += from.calls;
        self.failed += from.failed;
        self.before += from.before;
        self.after += from.after;
        for (tool, t) in &from.tools {
            let own = self.tools.entry(tool.clone()).or_default();
            own.calls += t.calls;
            own.saved += t.saved;
        }
        for (b, n) in &from.backends {
            *self.backends.entry(b.clone()).or_default() += n;
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct CallsStore {
    pub running: Vec<Running>,
    /// Newest first.
    pub feed: Vec<Finished>,
    /// One per batch, kept for the longest window.
    buckets: Vec<(i64, Totals)>,
    pub all: Totals,
}

fn ended(e: &GraphEvent, at: i64) -> Finished {
    Finished {
        call: e.call.clone(),
        tool: e.tool.clone(),
        target: e.target.clone(),
        project: e.project.clone(),
        session: e.session.clone(),
        ok: e.ok,
        error: e.error.clone(),
        backend: e.backend.clone(),
        ms: e.ms,
        before: e.samples.iter().map(|s| i64::from(s.est_before)).sum(),
        after: e.samples.iter().map(|s| i64::from(s.est_after)).sum(),
        at,
        interrupted: false,
    }
}

impl CallsStore {
    /// Folds one batch in. Totals come from the batch `summary`, which counts every event, so a
    /// burst cut down to the listed events still adds up to what `rtok stats` sums.
    pub fn fold(&mut self, batch: &CallBatch, now: i64) {
        let ends: Vec<&GraphEvent> = batch
            .events
            .iter()
            .filter(|e| e.phase == EventPhase::End)
            .collect();
        let done: HashSet<&str> = ends.iter().map(|e| e.call.as_str()).collect();
        for e in &batch.events {
            if e.phase == EventPhase::End {
                self.running.retain(|r| r.call != e.call);
            } else if !done.contains(e.call.as_str()) {
                // A progress event refreshes the call but keeps its start time and its place in the list.
                let at = self
                    .running
                    .iter()
                    .find(|r| r.call == e.call)
                    .map_or(now, |r| r.at);
                let r = Running {
                    call: e.call.clone(),
                    tool: e.tool.clone(),
                    target: e.target.clone(),
                    project: e.project.clone(),
                    session: e.session.clone(),
                    at,
                };
                match self.running.iter_mut().find(|x| x.call == e.call) {
                    Some(slot) => *slot = r,
                    None => self.running.push(r),
                }
            }
        }
        let mut bucket = Totals {
            calls: u64::from(batch.summary.ends),
            failed: u64::from(batch.summary.failed),
            before: batch.summary.est_before,
            after: batch.summary.est_after,
            ..Totals::default()
        };
        let rows: Vec<Finished> = ends.iter().map(|e| ended(e, now)).collect();
        for row in &rows {
            let t = bucket.tools.entry(row.tool.clone()).or_default();
            t.calls += 1;
            t.saved += row.before - row.after;
            if let Some(b) = &row.backend {
                *bucket.backends.entry(b.clone()).or_default() += 1;
            }
        }
        self.all.add(&bucket);
        // A late end for a call already marked interrupted replaces the mark.
        self.feed.retain(|r| !done.contains(r.call.as_str()));
        self.feed.splice(0..0, rows.into_iter().rev());
        self.feed.truncate(FEED_ROWS);
        self.buckets.push((now, bucket));
        self.buckets.retain(|(at, _)| now - at <= LONGEST_BUCKET_MS);
    }

    /// Moves a call that never ended into the feed, marked, so it stops counting as running.
    pub fn sweep(&mut self, now: i64) {
        let (stale, live): (Vec<Running>, Vec<Running>) = std::mem::take(&mut self.running)
            .into_iter()
            .partition(|r| now - r.at > INTERRUPT_MS);
        self.running = live;
        let rows = stale.into_iter().rev().map(|r| Finished {
            call: r.call,
            tool: r.tool,
            target: r.target,
            project: r.project,
            session: r.session,
            ok: false,
            error: None,
            backend: None,
            ms: None,
            before: 0,
            after: 0,
            at: r.at,
            interrupted: true,
        });
        self.feed.splice(0..0, rows);
        self.feed.truncate(FEED_ROWS);
    }

    /// Summed from the buckets on every call, never kept as a second counter.
    pub fn window_totals(&self, window: usize, now: i64) -> Totals {
        let ms = WINDOWS[window].1;
        if ms == i64::MAX {
            return self.all.clone();
        }
        let mut sum = Totals::default();
        for (at, b) in &self.buckets {
            if now - at <= ms {
                sum.add(b);
            }
        }
        sum
    }
}

/// Empty strings mean "all".
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FeedFilter {
    pub session: String,
    pub tool: String,
    pub project: String,
}

impl FeedFilter {
    fn keeps(&self, r: &Finished) -> bool {
        (self.session.is_empty() || r.session == self.session)
            && (self.tool.is_empty() || r.tool == self.tool)
            && (self.project.is_empty() || Some(&self.project) == r.project.as_ref())
    }
}

pub fn filter_feed<'a>(feed: &'a [Finished], f: &FeedFilter) -> Vec<&'a Finished> {
    feed.iter().filter(|r| f.keeps(r)).collect()
}

/// The values a filter can pick, sorted.
pub fn distinct<'a>(
    feed: &'a [Finished],
    pick: impl Fn(&'a Finished) -> Option<&'a str>,
) -> Vec<String> {
    let mut v: Vec<String> = feed
        .iter()
        .filter_map(pick)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect();
    v.sort();
    v.dedup();
    v
}

/// Fixtures shared with the TUI pane's tests: the cases of `callsFixtures.ts`.
#[cfg(test)]
pub(crate) mod fixtures {
    use super::*;
    use crate::store::MeasurementSample;
    use crate::web::live::CallSummary;

    pub const T: i64 = 1_000_000;

    pub fn event(call: &str) -> GraphEvent {
        let mut e = GraphEvent::new(call, EventPhase::Start, "session-abcdef", "callers");
        e.project = Some("rtok".into());
        e.target = Some("open_index".into());
        e
    }

    pub fn end(call: &str, before: i32, after: i32) -> GraphEvent {
        let mut e = event(call);
        e.phase = EventPhase::End;
        e.backend = Some("tags".into());
        e.ms = Some(12.0);
        e.samples = vec![MeasurementSample {
            id: 1,
            kind: "graph.callers".into(),
            before_bytes: 0,
            after_bytes: 0,
            est_before: before,
            est_after: after,
        }];
        e
    }

    /// A batch whose summary counts exactly the events it lists, plus `omitted` ends of 10 to 4.
    pub fn batch(events: Vec<GraphEvent>, omitted: u32) -> CallBatch {
        let ends: Vec<&GraphEvent> = events
            .iter()
            .filter(|e| e.phase == EventPhase::End)
            .collect();
        let sum = |f: fn(&MeasurementSample) -> i32| -> i64 {
            ends.iter()
                .flat_map(|e| &e.samples)
                .map(|s| i64::from(f(s)))
                .sum()
        };
        CallBatch {
            head: events.len() as i64,
            omitted,
            summary: CallSummary {
                starts: events
                    .iter()
                    .filter(|e| e.phase == EventPhase::Start)
                    .count() as u32,
                ends: ends.len() as u32 + omitted,
                failed: ends.iter().filter(|e| !e.ok).count() as u32,
                est_before: sum(|s| s.est_before) + i64::from(omitted) * 10,
                est_after: sum(|s| s.est_after) + i64::from(omitted) * 4,
            },
            events,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fixtures::{T, batch, end, event};
    use super::*;

    fn folded(store: &mut CallsStore, events: Vec<GraphEvent>, now: i64) {
        store.fold(&batch(events, 0), now);
    }

    #[test]
    fn a_start_runs_and_its_end_moves_it_to_the_feed_with_the_measured_tokens() {
        let mut s = CallsStore::default();
        folded(&mut s, vec![event("a")], T);
        assert_eq!(
            s.running.iter().map(|r| &r.call[..]).collect::<Vec<_>>(),
            ["a"]
        );
        folded(&mut s, vec![end("a", 100, 30)], T + 50);
        assert!(s.running.is_empty());
        assert_eq!((s.feed[0].before, s.feed[0].after), (100, 30));
        assert_eq!(s.feed[0].backend.as_deref(), Some("tags"));
        assert_eq!((s.all.calls, s.all.before, s.all.after), (1, 100, 30));
        assert_eq!(
            s.all.tools["callers"],
            ToolTotals {
                calls: 1,
                saved: 70
            }
        );
    }

    #[test]
    fn a_failed_end_counts_and_keeps_its_error() {
        let mut e = end("a", 0, 0);
        e.ok = false;
        e.error = Some("no backend".into());
        let mut s = CallsStore::default();
        folded(&mut s, vec![e], T);
        assert_eq!(s.all.failed, 1);
        assert_eq!(s.feed[0].error.as_deref(), Some("no backend"));
    }

    #[test]
    fn totals_count_the_events_a_burst_batch_left_out() {
        let mut s = CallsStore::default();
        s.fold(&batch(vec![end("a", 100, 30)], 400), T);
        assert_eq!(s.all.calls, 401);
        assert_eq!(s.all.before, 100 + 4000);
        assert_eq!(s.all.after, 30 + 1600);
    }

    #[test]
    fn the_feed_keeps_the_newest_rows_first_up_to_the_cap() {
        let mut s = CallsStore::default();
        for i in 0..FEED_ROWS + 5 {
            folded(&mut s, vec![end(&format!("c{i}"), 2, 1)], T + i as i64);
        }
        assert_eq!(s.feed.len(), FEED_ROWS);
        assert_eq!(s.feed[0].call, format!("c{}", FEED_ROWS + 4));
        assert_eq!(s.all.calls, FEED_ROWS as u64 + 5);
    }

    #[test]
    fn each_window_is_summed_from_the_stores_batches() {
        let mut s = CallsStore::default();
        folded(&mut s, vec![end("old", 10, 5)], T);
        folded(&mut s, vec![end("mid", 20, 5)], T + 6 * 60_000);
        folded(&mut s, vec![end("new", 40, 5)], T + 9 * 60_000);
        let now = T + 9 * 60_000 + 1000;
        let before = |w| s.window_totals(w, now).before;
        assert_eq!([0, 1, 2, 3].map(before), [40, 60, 70, 70]);
    }

    #[test]
    fn a_batch_older_than_the_longest_window_leaves_the_buckets_but_not_the_total() {
        let mut s = CallsStore::default();
        folded(&mut s, vec![end("old", 10, 5)], T);
        folded(&mut s, vec![end("new", 40, 5)], T + 16 * 60_000);
        assert_eq!(s.buckets.len(), 1);
        assert_eq!(s.window_totals(3, T + 16 * 60_000).calls, 2);
    }

    #[test]
    fn a_call_with_no_end_after_the_timeout_is_marked_and_stops_running() {
        let mut s = CallsStore::default();
        folded(&mut s, vec![event("a")], T);
        s.sweep(T + INTERRUPT_MS);
        assert_eq!(s.running.len(), 1, "the timeout is exclusive");
        s.sweep(T + INTERRUPT_MS + 1);
        assert!(s.running.is_empty());
        assert!(s.feed[0].interrupted && s.feed[0].call == "a");
        assert_eq!(s.all.calls, 0);
        folded(&mut s, vec![end("a", 10, 5)], T + INTERRUPT_MS + 2);
        let marks: Vec<_> = s
            .feed
            .iter()
            .map(|r| (&r.call[..], r.interrupted))
            .collect();
        assert_eq!(marks, [("a", false)], "a late end replaces the mark");
    }

    #[test]
    fn caller_tool_and_project_narrow_the_rows_and_blank_means_all() {
        let row = |call: &str, session: &str, tool: &str, project: &str| {
            let mut e = end(call, 2, 1);
            e.session = session.into();
            e.tool = tool.into();
            e.project = Some(project.into());
            e
        };
        let mut s = CallsStore::default();
        folded(
            &mut s,
            vec![
                row("a", "x", "callers", "p"),
                row("b", "y", "impact", "p"),
                row("c", "y", "callers", "q"),
            ],
            T,
        );
        let f = |session: &str, tool: &str| FeedFilter {
            session: session.into(),
            tool: tool.into(),
            project: String::new(),
        };
        let calls = |f: &FeedFilter| -> Vec<&str> {
            filter_feed(&s.feed, f)
                .iter()
                .map(|r| &r.call[..])
                .collect()
        };
        assert_eq!(calls(&f("", "")).len(), 3);
        assert_eq!(calls(&f("y", "")), ["c", "b"]);
        assert_eq!(calls(&f("y", "callers")), ["c"]);
        assert_eq!(distinct(&s.feed, |r| r.project.as_deref()), ["p", "q"]);
    }
}

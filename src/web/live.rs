// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.15: graph call events on `/ws`. Any rtok process appends events to the store
//! (`plugins::graph::events`); this module is the one reader in `rtok web`. A poller runs only
//! while a socket is subscribed, reads `id > cursor` every [`POLL`], folds what it found into
//! one [`CallBatch`] and broadcasts it, so the work and the traffic per second are bounded
//! however many calls arrive.
//!
//! [`Reader`] is that one reader without the async: `rtok tui` drives it from a thread, so both
//! surfaces fold the same rows through the same cursor and [`coalesce`].
//!
//! No replay: a socket sees the events written after it subscribed. A page that wants the
//! past (a window total, a reconnect) reads the store through the snapshot, not this stream.

use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use schemars::JsonSchema;
use serde::Serialize;
use tokio::sync::broadcast;

use super::protocol::ServerFrame;
use crate::config::Config;
use crate::store::{EventPhase, GraphEvent, Store};

/// How often the store is asked for new events; with the write and the render this keeps a
/// call in another process well inside the second the page promises.
pub const POLL: Duration = Duration::from_millis(250);
/// Events read per poll. A burst larger than this drains over the next polls.
const FETCH: i64 = 2000;
/// Events one frame lists; the rest of a burst is counted in `omitted` and in `summary`.
pub const MAX_EVENTS: usize = 100;
/// Frames a slow socket may fall behind before it skips the oldest.
const BACKLOG: usize = 16;

/// What one poll found, as one frame. `summary` counts every event of the poll (including
/// the ones left out of `events`), so a page that adds summaries up never undercounts a burst.
#[derive(Debug, Clone, Default, Serialize, JsonSchema)]
pub struct CallBatch {
    /// Newest last. A call's start and progress events are left out when its end is in the
    /// batch, since the end carries the same call and tool.
    pub events: Vec<GraphEvent>,
    /// Events cut because the batch held more than [`MAX_EVENTS`] (the oldest go first).
    pub omitted: u32,
    pub summary: CallSummary,
    /// The newest event id the batch covers.
    pub head: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, JsonSchema)]
pub struct CallSummary {
    pub starts: u32,
    pub ends: u32,
    pub failed: u32,
    /// Sums of the `samples` of the end events: the `Measurement` columns `rtok stats` adds up.
    pub est_before: i64,
    pub est_after: i64,
}

/// Folds `events` (oldest first) into a frame, or `None` when there are none.
pub fn coalesce(events: Vec<GraphEvent>) -> Option<CallBatch> {
    let head = events.last()?.id;
    let mut summary = CallSummary::default();
    let ended: HashSet<String> = events
        .iter()
        .filter(|e| e.phase == EventPhase::End)
        .map(|e| e.call.clone())
        .collect();
    for e in &events {
        match e.phase {
            EventPhase::Start => summary.starts += 1,
            EventPhase::Progress => {}
            EventPhase::End => {
                summary.ends += 1;
                summary.failed += u32::from(!e.ok);
                for s in &e.samples {
                    summary.est_before += i64::from(s.est_before);
                    summary.est_after += i64::from(s.est_after);
                }
            }
        }
    }
    let mut kept: Vec<GraphEvent> = events
        .into_iter()
        .filter(|e| e.phase == EventPhase::End || !ended.contains(&e.call))
        .collect();
    let omitted = kept.len().saturating_sub(MAX_EVENTS);
    kept.drain(..omitted);
    Some(CallBatch {
        events: kept,
        omitted: u32::try_from(omitted).unwrap_or(u32::MAX),
        summary,
        head,
    })
}

/// The poller and its fan-out. One per [`super::DashState`].
pub struct LiveCalls {
    tx: broadcast::Sender<Arc<str>>,
    running: AtomicBool,
}

impl LiveCalls {
    pub(super) fn new() -> Self {
        Self {
            tx: broadcast::channel(BACKLOG).0,
            running: AtomicBool::new(false),
        }
    }

    /// Frames (`ServerFrame::Calls` as JSON) from now on, and the first one: an empty batch
    /// whose `head` is the newest event id at subscription, which tells the page the stream
    /// is armed. The first subscriber starts the poller; it stops when the last receiver is
    /// dropped. Must run inside the tokio runtime.
    pub async fn subscribe(
        self: &Arc<Self>,
        cfg: &Config,
    ) -> (broadcast::Receiver<Arc<str>>, Arc<str>) {
        // Receiver first: a frame the poller sends while the store opens is not lost.
        let rx = self.tx.subscribe();
        let db_path = cfg.core.db_path.clone();
        let reader = open_reader(db_path.clone()).await;
        let head = reader.as_ref().map_or(0, Reader::head);
        if !self.running.swap(true, Ordering::AcqRel) {
            tokio::spawn(poll(self.clone(), db_path, reader));
        }
        let armed = CallBatch {
            head,
            ..CallBatch::default()
        };
        (rx, Arc::from(ServerFrame::Calls { batch: armed }.to_json()))
    }
}

/// A store handle and a cursor: where a reader that wants only new events starts.
pub struct Reader {
    store: Store,
    cursor: i64,
}

impl Reader {
    /// `None` when the file will not open; callers try again on their next pass, so a missing
    /// or locked store costs a retry and never a failed socket.
    pub fn open(path: &std::path::Path) -> Option<Self> {
        let store = Store::open(path).ok()?;
        let cursor = store.graph_event_head().ok()?;
        Some(Self { store, cursor })
    }

    /// The newest event id read so far.
    pub fn head(&self) -> i64 {
        self.cursor
    }

    /// What was written since the last call, folded; `None` when nothing was, or the read
    /// failed (the cursor stays, so the events come on the next try).
    pub fn poll(&mut self) -> Option<CallBatch> {
        let events = self.store.graph_events_after(self.cursor, FETCH).ok()?;
        let batch = coalesce(events)?;
        self.cursor = batch.head;
        Some(batch)
    }
}

async fn open_reader(path: std::path::PathBuf) -> Option<Reader> {
    tokio::task::spawn_blocking(move || Reader::open(&path))
        .await
        .ok()
        .flatten()
}

async fn poll(live: Arc<LiveCalls>, db_path: std::path::PathBuf, mut reader: Option<Reader>) {
    loop {
        tokio::time::sleep(POLL).await;
        if live.tx.receiver_count() == 0 {
            live.running.store(false, Ordering::Release);
            // A socket that subscribed between the check and the store would find the flag
            // still set and start no poller; take the role back if one did.
            if live.tx.receiver_count() == 0 || live.running.swap(true, Ordering::AcqRel) {
                return;
            }
        }
        let mut r = match reader.take() {
            Some(r) => r,
            None => match open_reader(db_path.clone()).await {
                Some(r) => r,
                None => continue,
            },
        };
        // A failed join drops the reader; the next pass opens a fresh one.
        let Ok((r, batch)) = tokio::task::spawn_blocking(move || {
            let batch = r.poll();
            (r, batch)
        })
        .await
        else {
            continue;
        };
        if let Some(batch) = batch {
            // No receiver is not an error: the last socket just left.
            let _ = live
                .tx
                .send(Arc::from(ServerFrame::Calls { batch }.to_json()));
        }
        reader = Some(r);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::MeasurementSample;

    fn ev(id: i64, call: &str, phase: EventPhase) -> GraphEvent {
        let mut e = GraphEvent::new(call, phase, "mcp-1", "callers");
        e.id = id;
        e
    }

    #[test]
    fn nothing_found_sends_nothing() {
        assert!(coalesce(Vec::new()).is_none());
    }

    #[test]
    fn an_ended_call_is_listed_once_and_still_counted() {
        let mut end = ev(3, "a", EventPhase::End);
        end.samples = vec![MeasurementSample {
            id: 1,
            kind: "cap".into(),
            before_bytes: 9,
            after_bytes: 3,
            est_before: 10,
            est_after: 4,
        }];
        let b = coalesce(vec![
            ev(1, "a", EventPhase::Start),
            ev(2, "a", EventPhase::Progress),
            end,
            ev(4, "b", EventPhase::Start),
        ])
        .unwrap();
        assert_eq!(
            b.events.iter().map(|e| e.id).collect::<Vec<_>>(),
            [3, 4],
            "a's start and progress are folded into its end; b still runs"
        );
        assert_eq!(b.head, 4);
        assert_eq!(b.omitted, 0);
        assert_eq!(
            b.summary,
            CallSummary {
                starts: 2,
                ends: 1,
                failed: 0,
                est_before: 10,
                est_after: 4,
            }
        );
    }

    #[test]
    fn a_burst_is_capped_but_its_summary_counts_every_call() {
        let mut events = Vec::new();
        let mut id = 0;
        for n in 0..500 {
            let call = format!("c{n}");
            for phase in [EventPhase::Start, EventPhase::Progress, EventPhase::End] {
                id += 1;
                let mut e = ev(id, &call, phase);
                if phase == EventPhase::End {
                    e.ok = n % 10 != 0;
                }
                events.push(e);
            }
        }
        let b = coalesce(events).unwrap();
        assert_eq!(b.events.len(), MAX_EVENTS);
        assert_eq!(b.omitted, 400);
        assert_eq!(b.events.last().unwrap().call, "c499", "the newest survive");
        assert_eq!(
            (b.summary.starts, b.summary.ends, b.summary.failed),
            (500, 500, 50)
        );
        assert!(ServerFrame::Calls { batch: b }.to_json().len() < 100_000);
    }
}

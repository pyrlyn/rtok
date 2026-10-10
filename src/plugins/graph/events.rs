// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.15: the start, progress and end events of one graph tool call, appended to the store
//! so `rtok web` can stream them from any process. Runs in the MCP server and `rtok mcp --call`,
//! never in a hook. Every write is fail open: a lost event is a missing row on the page, and
//! must not turn a good answer into an error.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

use anyhow::Result;
use serde_json::Value;

use crate::plugin::Runtime;
use crate::store::{EventPhase, GraphEvent};
use crate::tokens::Class;

static CALLS: AtomicU64 = AtomicU64::new(0);

/// One call being tracked. [`Call::start`] writes the start event; [`Call::end`] writes the
/// end event with the measurement rows the call left behind.
pub struct Call<'a> {
    cx: &'a Runtime,
    key: String,
    tool: String,
    started: Instant,
    /// Newest `measurements` id before the call: what is above it at the end belongs to it.
    mark: i32,
    total: Option<u32>,
    /// Repeated on every event: a page that folds a call into its end still names the call.
    target: Option<String>,
    project: Option<String>,
}

impl<'a> Call<'a> {
    pub fn start(cx: &'a Runtime, tool: &str, args: &Value) -> Self {
        let key = format!(
            "{}:{}",
            cx.session,
            CALLS.fetch_add(1, Ordering::Relaxed) + 1
        );
        let call = Self {
            cx,
            key,
            tool: tool.to_string(),
            started: Instant::now(),
            mark: cx.store.measurement_head().unwrap_or(0),
            total: None,
            target: target(args),
            project: project(args),
        };
        call.write(&call.event(EventPhase::Start));
        call
    }

    /// The scope is resolved: `total` projects will be asked. The graph plugin answers a
    /// scope as one block, so this is the one point at which the call reports how far along
    /// it is.
    pub fn progress(&mut self, total: usize) {
        let total = u32::try_from(total).unwrap_or(u32::MAX);
        self.total = Some(total);
        let mut e = self.event(EventPhase::Progress);
        e.done = Some(0);
        e.total = Some(total);
        self.write(&e);
    }

    pub fn end(self, result: &Result<String>) {
        let mut e = self.event(EventPhase::End);
        e.ms = Some(self.started.elapsed().as_secs_f64() * 1000.0);
        e.total = self.total;
        match result {
            Ok(text) => {
                e.done = self.total;
                e.backend = backend(text);
                e.answer_tokens = Some(self.cx.estimate(text, Class::Json));
            }
            Err(err) => {
                e.ok = false;
                e.error = Some(err.to_string());
            }
        }
        e.samples = self
            .cx
            .store
            .graph_measurements_after(&self.cx.session, self.mark)
            .unwrap_or_default();
        self.write(&e);
    }

    fn event(&self, phase: EventPhase) -> GraphEvent {
        let mut e = GraphEvent::new(&self.key, phase, &self.cx.session, &self.tool);
        e.target.clone_from(&self.target);
        e.project.clone_from(&self.project);
        e
    }

    fn write(&self, e: &GraphEvent) {
        let _ = self.cx.store.insert_graph_event(e);
    }
}

/// What the call is about: the symbol, the first of several, the query, the path or the id.
fn target(args: &Value) -> Option<String> {
    let first_name = args["names"]
        .as_array()
        .and_then(|l| l.first())
        .and_then(Value::as_str);
    [
        args["name"].as_str(),
        first_name,
        args["query"].as_str(),
        args["path"].as_str(),
        args["id"].as_str(),
    ]
    .into_iter()
    .flatten()
    .find(|s| !s.is_empty())
    .map(str::to_string)
}

fn project(args: &Value) -> Option<String> {
    match args["project"].as_str().filter(|p| !p.is_empty()) {
        Some(p) => Some(p.to_string()),
        None => crate::project::project_name(&std::env::current_dir().ok()?),
    }
}

/// The header `lsp_or_tags` puts on an answer under `auto`: `(lsp)`, `(tags)` or
/// `(tags; lsp: <reason>)`. An answer without one comes from a fixed mode, so it is unnamed.
fn backend(text: &str) -> Option<String> {
    let head = text.lines().next()?;
    ["lsp", "tags", "text"]
        .into_iter()
        .find(|b| head.strip_prefix('(').is_some_and(|h| h.starts_with(b)))
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn target_prefers_the_symbol_then_the_query() {
        assert_eq!(
            target(&json!({"name":"f","query":"q"})).as_deref(),
            Some("f")
        );
        assert_eq!(target(&json!({"names":["a","b"]})).as_deref(), Some("a"));
        assert_eq!(target(&json!({"query":"q"})).as_deref(), Some("q"));
        assert_eq!(target(&json!({"name":""})), None);
    }

    #[test]
    fn backend_reads_the_answer_header() {
        assert_eq!(backend("(lsp)\nx").as_deref(), Some("lsp"));
        assert_eq!(backend("(tags; lsp: down)\nx").as_deref(), Some("tags"));
        assert_eq!(backend("a.rs:1 f").as_deref(), None);
        assert_eq!(backend(""), None);
    }

    /// The events a second process writes: start, progress and end, the end carrying the
    /// `graph` rows the call wrote and not another session's.
    #[test]
    fn a_call_writes_three_events_with_its_own_rows() {
        let cx = Runtime::in_memory("mcp-1").unwrap();
        let mut call = Call::start(&cx, "callers", &json!({"name":"f"}));
        call.progress(2);
        let row = |kind| rtok_plugin_sdk::Measurement {
            plugin: "graph",
            kind,
            before_bytes: 40,
            after_bytes: 10,
            est_before: 12,
            est_after: 3,
            ref_id: None,
            call_id: None,
        };
        cx.record(&row("cap")).unwrap();
        cx.store.insert_measurement("mcp-2", &row("cap")).unwrap();
        call.end(&Ok("(lsp)\na.rs:1 f".to_string()));
        let ev = cx.store.graph_events_after(0, 10).unwrap();
        let phases: Vec<_> = ev.iter().map(|e| e.phase).collect();
        assert_eq!(
            phases,
            [EventPhase::Start, EventPhase::Progress, EventPhase::End]
        );
        let end = &ev[2];
        assert_eq!(end.call, ev[0].call);
        assert_eq!(end.backend.as_deref(), Some("lsp"));
        assert_eq!((end.done, end.total), (Some(2), Some(2)));
        assert_eq!(end.samples.len(), 1);
        assert_eq!(end.samples[0].est_before, 12);
        assert_eq!(ev[0].target.as_deref(), Some("f"));
        assert_eq!(
            end.target, ev[0].target,
            "a folded call still names its symbol"
        );
    }

    #[test]
    fn a_failed_call_ends_with_its_error() {
        let cx = Runtime::in_memory("mcp-1").unwrap();
        Call::start(&cx, "impact", &json!({})).end(&Err(anyhow::anyhow!("no backend")));
        let end = cx.store.graph_events_after(0, 10).unwrap().pop().unwrap();
        assert!(!end.ok);
        assert_eq!(end.error.as_deref(), Some("no backend"));
        assert!(end.samples.is_empty());
    }
}

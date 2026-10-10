// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.15: graph call events. Any rtok process appends a start, a progress and an end event
//! per graph tool call; `rtok web` reads them with an id cursor. The table is the channel
//! between processes, so nothing here depends on who wrote a row or on a daemon being up.

use crate::Result;
use diesel::prelude::*;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use super::Store;
use super::schema::{graph_events, measurements};

/// Rows kept. A page reads `id > cursor` every few hundred milliseconds, so only a burst
/// larger than this between two reads could lose rows; the cap keeps the table from growing
/// without a retention job.
const KEEP: i64 = 5000;
/// Free text (a symbol, an error) is clipped so a pathological argument cannot bloat a row.
const TEXT_MAX: usize = 200;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum EventPhase {
    #[default]
    Start,
    Progress,
    End,
}

impl EventPhase {
    fn as_str(self) -> &'static str {
        match self {
            Self::Start => "start",
            Self::Progress => "progress",
            Self::End => "end",
        }
    }

    fn parse(s: &str) -> Option<Self> {
        [Self::Start, Self::Progress, Self::End]
            .into_iter()
            .find(|p| p.as_str() == s)
    }
}

/// One `measurements` row of the call, with the columns `rtok stats` sums. Copied from the
/// table at the call's end, so a page that adds these up gets the report's numbers.
#[derive(
    Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema, Queryable, Selectable,
)]
#[diesel(table_name = measurements)]
pub struct MeasurementSample {
    pub id: i32,
    pub kind: String,
    pub before_bytes: i64,
    pub after_bytes: i64,
    pub est_before: i32,
    pub est_after: i32,
    /// Set when the row's answer was cut and archived: how a page tells a cap hit from an
    /// `explore` row that only stands for several smaller calls. Absent in rows written before T329.33.
    #[serde(default)]
    pub ref_id: Option<String>,
}

/// One event of a graph call. `call` ties a call's events together; `ts_ms`, `id` and the
/// clipping of free text are the store's, so a writer leaves `id` and `ts_ms` at zero.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct GraphEvent {
    pub id: i64,
    pub ts_ms: i64,
    pub call: String,
    pub phase: EventPhase,
    pub session: String,
    pub tool: String,
    pub target: Option<String>,
    pub project: Option<String>,
    /// `lsp`, `tags` or `text`: the backend that answered (end events).
    pub backend: Option<String>,
    pub ok: bool,
    pub error: Option<String>,
    /// Elapsed milliseconds (end events).
    pub ms: Option<f64>,
    /// Scope members done and in total (progress and end events).
    pub done: Option<u32>,
    pub total: Option<u32>,
    /// Estimated tokens of the answer the caller received (end events).
    pub answer_tokens: Option<u32>,
    /// The call's `graph` measurement rows (end events); empty when the answer was not shortened.
    pub samples: Vec<MeasurementSample>,
    /// Symbols the call asked for (every event); `None` for a tool that takes none.
    pub symbols: Option<u32>,
    /// What the answer returned, counted by the backends (end events of `symbol`, `callers` and
    /// `impact` by name): asked symbols it lists, distinct files of its rows, projects with a row.
    pub symbols_returned: Option<u32>,
    pub files_touched: Option<u32>,
    pub projects_hit: Option<u32>,
}

impl GraphEvent {
    pub fn new(call: &str, phase: EventPhase, session: &str, tool: &str) -> Self {
        Self {
            call: call.to_string(),
            phase,
            session: session.to_string(),
            tool: tool.to_string(),
            ok: true,
            ..Self::default()
        }
    }
}

#[derive(Queryable, Selectable)]
#[diesel(table_name = graph_events)]
struct Row {
    id: i64,
    ts_ms: i64,
    call: String,
    phase: String,
    session: String,
    tool: String,
    target: Option<String>,
    project: Option<String>,
    backend: Option<String>,
    ok: i32,
    error: Option<String>,
    ms: Option<f64>,
    done: Option<i32>,
    total: Option<i32>,
    answer_tokens: Option<i32>,
    rows_json: Option<String>,
    symbols: Option<i32>,
    symbols_returned: Option<i32>,
    files_touched: Option<i32>,
    projects_hit: Option<i32>,
}

impl Row {
    /// `None` for a phase this build does not know, so a newer writer cannot break an older reader.
    fn into_event(self) -> Option<GraphEvent> {
        let unsigned = |v: Option<i32>| v.and_then(|n| u32::try_from(n).ok());
        Some(GraphEvent {
            id: self.id,
            ts_ms: self.ts_ms,
            call: self.call,
            phase: EventPhase::parse(&self.phase)?,
            session: self.session,
            tool: self.tool,
            target: self.target,
            project: self.project,
            backend: self.backend,
            ok: self.ok != 0,
            error: self.error,
            ms: self.ms,
            done: unsigned(self.done),
            total: unsigned(self.total),
            answer_tokens: unsigned(self.answer_tokens),
            samples: self
                .rows_json
                .and_then(|j| serde_json::from_str(&j).ok())
                .unwrap_or_default(),
            symbols: unsigned(self.symbols),
            symbols_returned: unsigned(self.symbols_returned),
            files_touched: unsigned(self.files_touched),
            projects_hit: unsigned(self.projects_hit),
        })
    }
}

fn clip(s: &str) -> String {
    s.chars().take(TEXT_MAX).collect()
}

fn unix_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

impl Store {
    /// Append `e`, stamped with the time, and trim the table to the newest [`KEEP`] rows.
    /// Returns the new id.
    pub fn insert_graph_event(&self, e: &GraphEvent) -> Result<i64> {
        let rows_json = match e.samples.is_empty() {
            true => None,
            false => Some(serde_json::to_string(&e.samples).map_err(anyhow::Error::from)?),
        };
        let int = |v: Option<u32>| v.and_then(|n| i32::try_from(n).ok());
        let mut conn = self.lock()?;
        let id: i64 = diesel::insert_into(graph_events::table)
            .values((
                graph_events::ts_ms.eq(unix_ms()),
                graph_events::call.eq(&e.call),
                graph_events::phase.eq(e.phase.as_str()),
                graph_events::session.eq(&e.session),
                graph_events::tool.eq(&e.tool),
                graph_events::target.eq(e.target.as_deref().map(clip)),
                graph_events::project.eq(e.project.as_deref().map(clip)),
                graph_events::backend.eq(e.backend.as_deref().map(clip)),
                graph_events::ok.eq(i32::from(e.ok)),
                graph_events::error.eq(e.error.as_deref().map(clip)),
                graph_events::ms.eq(e.ms),
                graph_events::done.eq(int(e.done)),
                graph_events::total.eq(int(e.total)),
                graph_events::answer_tokens.eq(int(e.answer_tokens)),
                graph_events::rows_json.eq(rows_json),
                graph_events::symbols.eq(int(e.symbols)),
                graph_events::symbols_returned.eq(int(e.symbols_returned)),
                graph_events::files_touched.eq(int(e.files_touched)),
                graph_events::projects_hit.eq(int(e.projects_hit)),
            ))
            .returning(graph_events::id)
            .get_result(&mut *conn)?;
        diesel::delete(graph_events::table.filter(graph_events::id.le(id - KEEP)))
            .execute(&mut *conn)?;
        Ok(id)
    }

    /// Up to `limit` events with an id above `cursor`, oldest first: one range scan on the
    /// primary key, so polling it costs the same however long the table has grown.
    pub fn graph_events_after(&self, cursor: i64, limit: i64) -> Result<Vec<GraphEvent>> {
        let mut conn = self.lock()?;
        let rows: Vec<Row> = graph_events::table
            .filter(graph_events::id.gt(cursor))
            .order(graph_events::id.asc())
            .limit(limit)
            .select(Row::as_select())
            .load(&mut *conn)?;
        Ok(rows.into_iter().filter_map(Row::into_event).collect())
    }

    /// The newest event id, `0` for an empty table: where a reader that wants only new events
    /// starts.
    pub fn graph_event_head(&self) -> Result<i64> {
        let mut conn = self.lock()?;
        Ok(graph_events::table
            .select(diesel::dsl::max(graph_events::id))
            .first::<Option<i64>>(&mut *conn)?
            .unwrap_or(0))
    }

    /// The newest `measurements` id, `0` for an empty ledger: a call notes it at its start and
    /// asks [`Store::graph_measurements_after`] for what it wrote at its end.
    pub fn measurement_head(&self) -> Result<i32> {
        let mut conn = self.lock()?;
        Ok(measurements::table
            .select(diesel::dsl::max(measurements::id))
            .first::<Option<i32>>(&mut *conn)?
            .unwrap_or(0))
    }

    /// The `graph` rows `session` wrote after measurement `after`, in write order.
    pub fn graph_measurements_after(
        &self,
        session: &str,
        after: i32,
    ) -> Result<Vec<MeasurementSample>> {
        let mut conn = self.lock()?;
        Ok(measurements::table
            .filter(measurements::id.gt(after))
            .filter(measurements::session.eq(session))
            .filter(measurements::plugin.eq("graph"))
            .order(measurements::id.asc())
            .select(MeasurementSample::as_select())
            .load(&mut *conn)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event(call: &str, phase: EventPhase) -> GraphEvent {
        GraphEvent::new(call, phase, "mcp-1", "callers")
    }

    #[test]
    fn events_come_back_after_the_cursor_in_order() {
        let s = Store::open_in_memory().unwrap();
        assert_eq!(s.graph_event_head().unwrap(), 0);
        let a = s
            .insert_graph_event(&event("c1", EventPhase::Start))
            .unwrap();
        let mut end = event("c1", EventPhase::End);
        end.ms = Some(1.5);
        end.answer_tokens = Some(7);
        end.samples = vec![MeasurementSample {
            id: 3,
            kind: "cap".into(),
            before_bytes: 10,
            after_bytes: 4,
            est_before: 5,
            est_after: 2,
            ref_id: Some("ab".into()),
        }];
        end.symbols = Some(3);
        (end.symbols_returned, end.files_touched, end.projects_hit) = (Some(2), Some(5), Some(1));
        let b = s.insert_graph_event(&end).unwrap();
        assert_eq!(s.graph_event_head().unwrap(), b);
        let all = s.graph_events_after(0, 10).unwrap();
        assert_eq!(all.iter().map(|e| e.id).collect::<Vec<_>>(), [a, b]);
        assert_eq!(all[1].samples, end.samples);
        assert_eq!(all[1].answer_tokens, Some(7));
        assert_eq!((all[0].symbols, all[1].symbols), (None, Some(3)));
        assert_eq!(
            (
                all[0].files_touched,
                all[1].symbols_returned,
                all[1].files_touched,
                all[1].projects_hit
            ),
            (None, Some(2), Some(5), Some(1))
        );
        assert!(s.graph_events_after(b, 10).unwrap().is_empty());
        assert_eq!(s.graph_events_after(0, 1).unwrap().len(), 1);
    }

    #[test]
    fn the_table_keeps_only_the_newest_rows_and_clips_text() {
        let s = Store::open_in_memory().unwrap();
        let mut e = event("c", EventPhase::Start);
        e.target = Some("x".repeat(1000));
        for _ in 0..KEEP + 20 {
            s.insert_graph_event(&e).unwrap();
        }
        let all = s.graph_events_after(0, KEEP * 2).unwrap();
        assert_eq!(all.len(), usize::try_from(KEEP).unwrap());
        assert_eq!(all[0].target.as_deref().map(str::len), Some(TEXT_MAX));
    }

    #[test]
    fn measurements_after_filters_by_session_plugin_and_id() {
        use rtok_plugin_sdk::Measurement;
        let s = Store::open_in_memory().unwrap();
        let row = |plugin, kind| Measurement {
            plugin,
            kind,
            before_bytes: 9,
            after_bytes: 3,
            est_before: 4,
            est_after: 1,
            ref_id: None,
            call_id: None,
        };
        s.insert_measurement("mcp-1", &row("graph", "cap")).unwrap();
        let head = s.measurement_head().unwrap();
        s.insert_measurement("mcp-1", &row("graph", "tags.callers"))
            .unwrap();
        s.insert_measurement("mcp-2", &row("graph", "cap")).unwrap();
        s.insert_measurement("mcp-1", &row("read", "cap")).unwrap();
        let got = s.graph_measurements_after("mcp-1", head).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].kind, "tags.callers");
        assert_eq!((got[0].est_before, got[0].est_after), (4, 1));
    }
}

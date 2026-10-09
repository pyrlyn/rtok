// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Report pages of the operator model (T22.1, D24).

use std::collections::BTreeMap;

use anyhow::Result;
use serde::Serialize;

use crate::config::Config;
use crate::measure::{cache, stats};
use crate::store::Store;

// ── the report pages (T22.1, D24) ────────────────────────────────────────────
//
// `rtok report` renders; it never computes a number of its own (D24). Every figure it
// prints comes from [`ReportLedgers`] — one store open serves every ledger section, and
// each struct carries the rows its numbers were summed from. The Config and Doctor
// sections come from their own pages above ([`config_entries`], [`doctor`]).

/// The report's window and row counts per ledger.
#[derive(Debug, Serialize)]
pub struct ReportWindow {
    /// The configured window, e.g. `"30d"` — the report's own, not `[stats] since`.
    pub since: String,
    pub from_unix: i64,
    pub to_unix: i64,
    /// `YYYY-MM-DD` UTC, through `log::stamp`'s calendar — one date implementation.
    pub from_date: String,
    pub to_date: String,
    pub db_path: String,
    /// `calls` rows with `ts` in the window — the one ledger whose reader returns row
    /// times, so the only one the window can filter.
    pub calls_in_window: u64,
    pub calls_total: u64,
    /// Whole-ledger counts: the measurements and usage readers return no row time to
    /// filter on, and the report says so beside the numbers.
    pub measurements: u64,
    pub usage: u64,
}

/// One plugin's savings, from `Measurement` rows only (D3: a saving that is not a
/// `Measurement` row does not exist).
#[derive(Debug, Serialize)]
pub struct ReportSavings {
    pub plugin: String,
    pub rows: u64,
    pub est_before: i64,
    pub est_after: i64,
    /// est_before − est_after, net: an `expand` row counts negative, because retrieval
    /// costs tokens.
    pub saved: i64,
}

#[derive(Debug, Serialize)]
pub struct ReportSavingsSection {
    /// One row per plugin with at least one `Measurement` row — every plugin the
    /// ledger has seen, not just the catalogue (T207).
    pub rows: Vec<ReportSavings>,
    pub total_rows: u64,
    pub total_saved: i64,
    /// Every distinct `Measurement` kind present, sorted — T22.5 matches hook events
    /// against these instead of growing a per-event query.
    pub kinds: Vec<String>,
}

/// Latency of one surface; p50/p95 are nearest-rank over the calls that recorded an `ms`.
#[derive(Debug, Serialize)]
pub struct ReportCalls {
    /// `hook` | `mcp` | `proxy` — the section set's three surfaces.
    pub surface: String,
    pub calls: u64,
    /// Rows with a recorded latency.
    pub timed: u64,
    pub p50_ms: Option<f64>,
    pub p95_ms: Option<f64>,
}

#[derive(Debug, Serialize)]
pub struct ReportCallsSection {
    pub rows: Vec<ReportCalls>,
    pub in_window: u64,
    pub total: u64,
    /// Hook-surface calls in window by event, busiest first (T22.5 rule 4).
    pub hooks: Vec<ReportHook>,
}

/// One hook event's in-window `calls` rows; `"unknown"` when the row names no event.
#[derive(Debug, Serialize)]
pub struct ReportHook {
    pub name: String,
    pub calls: u64,
}

/// Cache busts and their cause, aggregated from the `stats --cache` page.
#[derive(Debug, Serialize)]
pub struct ReportCache {
    pub sessions: u64,
    pub turns: u64,
    pub busts: u64,
    /// Busts per cause (`tools` | `system` | `unknown`), cause order.
    pub by_cause: Vec<(String, u64)>,
    /// Every bust with its turn named, in session/turn order (T22.5 rule 3 keeps
    /// `tools` / `system`). `turn` is 1-based in request order, the numbering
    /// `stats --cache` prints.
    pub detail: Vec<ReportBust>,
}

/// One prompt-cache bust: which session, which turn, what it re-wrote.
#[derive(Debug, Serialize)]
pub struct ReportBust {
    pub session: String,
    pub turn: u64,
    pub cause: String,
    pub cache_create: i64,
    pub cache_read: i64,
}

/// The archive plugin's honesty metric (T5.4): how often a live-zone pointer had to be
/// expanded, and which ids.
#[derive(Debug, Serialize)]
pub struct ReportExpand {
    pub decisions: i64,
    pub expanded: i64,
    /// expanded / decisions; `0.0` with no decisions (the report prints the empty line,
    /// not this zero).
    pub rate: f64,
    /// Archive ids a `rtok expand` froze — `Measurement` rows, kind `expand`, `ref_id`.
    pub expanded_ids: Vec<String>,
    /// Σ est_after over those rows — what the re-reads cost (T22.5 rules 1 and 6).
    pub cost: i64,
    /// How many `expand` rows that sum came from.
    pub cost_rows: u64,
}

/// One token sink ranked by raw bytes in `Measurement` rows (T59.8).
#[derive(Clone, Debug, Serialize)]
pub struct ReportSink {
    /// `read` | `cmd` | `mcp`
    pub class: String,
    /// File path, command stem, or `server/tool`.
    pub sink: String,
    pub before_bytes: i64,
    pub rows: u64,
    /// Which rtok switch would shorten this sink.
    pub switch: String,
}

#[derive(Debug, Serialize)]
pub struct ReportSinksSection {
    pub rows: Vec<ReportSink>,
}

/// Every ledger section of the report, from one store open.
#[derive(Debug, Serialize)]
pub struct ReportLedgers {
    pub window: ReportWindow,
    pub savings: ReportSavingsSection,
    pub sinks: ReportSinksSection,
    pub calls: ReportCallsSection,
    pub cache: ReportCache,
    pub expand: ReportExpand,
}

/// The report's ledger read (T22.1). A store that will not open is an error, as in
/// `cache_health`: a report over an unreadable store is not a report.
pub fn report_ledgers(cfg: &Config) -> Result<ReportLedgers> {
    let store = Store::open(&cfg.core.db_path)?;
    // One scan of the whole `calls` table, shared: each section used to run its own.
    let calls = store.calls_after(0, i64::MAX)?;
    let window = report_window(cfg, &store, &calls)?;
    Ok(ReportLedgers {
        calls: report_calls(&calls, window.from_unix),
        window,
        savings: report_savings(&store)?,
        sinks: report_sinks(&store, cfg)?,
        cache: report_cache(&store)?,
        expand: report_expand(&store)?,
    })
}

fn report_window(
    cfg: &Config,
    store: &Store,
    calls: &[crate::store::models::Call],
) -> Result<ReportWindow> {
    let since = cfg.report.since.clone();
    let to_unix = crate::log::now() as i64;
    let span = i64::try_from(stats::parse_since_from(&since, "report.since")?.as_secs())
        .unwrap_or(i64::MAX);
    let from_unix = to_unix.saturating_sub(span);
    let date = |secs: i64| crate::log::stamp(secs.max(0) as u64)[..10].to_string();
    Ok(ReportWindow {
        calls_in_window: calls.iter().filter(|c| c.ts >= from_unix).count() as u64,
        calls_total: calls.len() as u64,
        // T207: one `COUNT(*)` each, the whole ledger — the old per-catalogue-plugin
        // and per-session loops undercounted (out-of-tree plugins never showed up
        // despite the "Whole-ledger counts" label below) and cost an N+1 per report.
        measurements: store.count_measurements()? as u64,
        usage: store.count_usage()? as u64,
        since,
        from_unix,
        to_unix,
        from_date: date(from_unix),
        to_date: date(to_unix),
        db_path: cfg.core.db_path.display().to_string(),
    })
}

fn report_savings(store: &Store) -> Result<ReportSavingsSection> {
    // T207: one grouped read for every plugin the ledger has ever recorded a
    // `Measurement` for — not just the catalogue, so out-of-tree/WASM plugins count —
    // folded from (plugin, kind) down to (plugin). `saved` keeps an `expand` group
    // negative (`ReportSavings::saved`'s contract): it is a cost, not a saving, so it
    // nets out of the total instead of being dropped from it.
    let mut by_plugin: BTreeMap<String, (i64, i64, i64)> = BTreeMap::new();
    let mut kinds = std::collections::BTreeSet::new();
    for t in store.measurement_totals()? {
        kinds.insert(t.kind);
        let e = by_plugin.entry(t.plugin).or_insert((0, 0, 0));
        e.0 += t.rows;
        e.1 += t.est_before;
        e.2 += t.est_after;
    }
    let (mut total_rows, mut total_saved) = (0u64, 0i64);
    let rows = by_plugin
        .into_iter()
        .map(|(plugin, (n, before, after))| {
            total_rows += n as u64;
            total_saved += before - after;
            ReportSavings {
                plugin,
                rows: n as u64,
                est_before: before,
                est_after: after,
                saved: before - after,
            }
        })
        .collect();
    Ok(ReportSavingsSection {
        rows,
        total_rows,
        total_saved,
        kinds: kinds.into_iter().collect(),
    })
}

fn report_sinks(store: &Store, cfg: &Config) -> Result<ReportSinksSection> {
    let mut by: BTreeMap<(String, String), (i64, u64)> = BTreeMap::new();
    for (plugin, _) in crate::config::CATALOGUE {
        for m in store.list_measurements(plugin)? {
            if m.before_bytes <= 0 {
                continue;
            }
            let (class, sink) = sink_label(plugin, &m.kind, m.ref_id.as_deref());
            let e = by.entry((class, sink)).or_insert((0, 0));
            e.0 += m.before_bytes;
            e.1 += 1;
        }
    }
    let mut rows: Vec<ReportSink> = by
        .into_iter()
        .map(|((class, sink), (before_bytes, rows))| {
            let switch = sink_switch(cfg, &class, &sink);
            ReportSink {
                class,
                sink,
                before_bytes,
                rows,
                switch,
            }
        })
        .collect();
    rows.sort_by(|a, b| {
        b.before_bytes
            .cmp(&a.before_bytes)
            .then_with(|| a.sink.cmp(&b.sink))
    });
    rows.truncate(10);
    Ok(ReportSinksSection { rows })
}

fn sink_label(plugin: &str, kind: &str, ref_id: Option<&str>) -> (String, String) {
    match plugin {
        "cmd" if kind == "wrap" => {
            let sink = ref_id
                .and_then(|r| r.rsplit_once(':').map(|(s, _)| s.to_string()))
                .unwrap_or_else(|| "mcp".into());
            ("mcp".into(), sink)
        }
        "cmd" => {
            let stem = ref_id
                .and_then(|r| r.split_once(':').map(|(s, _)| s.to_string()))
                .unwrap_or_else(|| kind.to_string());
            ("cmd".into(), stem)
        }
        "read" => ("read".into(), ref_id.unwrap_or("unknown").to_string()),
        _ => (plugin.to_string(), ref_id.unwrap_or(kind).to_string()),
    }
}

fn sink_switch(cfg: &Config, class: &str, sink: &str) -> String {
    match class {
        "read" => format!(
            "[plugins.read] default_mode = {}",
            cfg.plugins.read.default_mode
        ),
        "cmd" => {
            // Without the `cmd` plugin `no rule` reads as "no rule yet" (T0.4: one
            // plugin feature must build alone).
            #[cfg(feature = "cmd")]
            let has = crate::plugins::cmd::rules::defaults()
                .iter()
                .any(|r| r.match_cmd == sink);
            #[cfg(not(feature = "cmd"))]
            let has = false;
            if has {
                format!("[{sink}] rule")
            } else {
                format!("[{sink}] rule (default head/tail)")
            }
        }
        "mcp" => "rtok mcp -- <server> (wrap)".into(),
        _ => "none: already shortened".into(),
    }
}

fn report_calls(all: &[crate::store::models::Call], from: i64) -> ReportCallsSection {
    let mut rows = Vec::new();
    for surface in ["hook", "mcp", "proxy"] {
        let group: Vec<_> = all
            .iter()
            .filter(|c| c.surface == surface && c.ts >= from)
            .collect();
        let mut ms: Vec<f64> = group.iter().filter_map(|c| c.ms).collect();
        ms.sort_by(f64::total_cmp);
        rows.push(ReportCalls {
            surface: surface.to_string(),
            calls: group.len() as u64,
            timed: ms.len() as u64,
            p50_ms: pct(&ms, 0.5),
            p95_ms: pct(&ms, 0.95),
        });
    }
    ReportCallsSection {
        in_window: all.iter().filter(|c| c.ts >= from).count() as u64,
        total: all.len() as u64,
        rows,
        hooks: report_hooks(all, from),
    }
}

/// Hook-surface `calls` rows in window grouped by event name, busiest first.
fn report_hooks(all: &[crate::store::models::Call], from: i64) -> Vec<ReportHook> {
    let mut by_name: std::collections::BTreeMap<String, u64> = Default::default();
    for c in all.iter().filter(|c| c.surface == "hook" && c.ts >= from) {
        *by_name
            .entry(c.name.clone().unwrap_or_else(|| "unknown".into()))
            .or_default() += 1;
    }
    let mut hooks: Vec<ReportHook> = by_name
        .into_iter()
        .map(|(name, calls)| ReportHook { name, calls })
        .collect();
    hooks.sort_by(|a, b| b.calls.cmp(&a.calls).then(a.name.cmp(&b.name)));
    hooks
}

fn report_cache(store: &Store) -> Result<ReportCache> {
    let health = cache::report(store)?;
    let mut by_cause: std::collections::BTreeMap<String, u64> = Default::default();
    let mut detail = Vec::new();
    let (mut turns, mut busts) = (0u64, 0u64);
    for h in &health {
        turns += h.turns.len() as u64;
        busts += h.busts as u64;
        for (i, t) in h.turns.iter().enumerate() {
            if let Some(cause) = &t.bust {
                *by_cause.entry(cause.clone()).or_default() += 1;
                detail.push(ReportBust {
                    session: h.session.clone(),
                    turn: i as u64 + 1,
                    cause: cause.clone(),
                    cache_create: t.cache_create,
                    cache_read: t.cache_read,
                });
            }
        }
    }
    Ok(ReportCache {
        sessions: health.len() as u64,
        turns,
        busts,
        by_cause: by_cause.into_iter().collect(),
        detail,
    })
}

fn report_expand(store: &Store) -> Result<ReportExpand> {
    let (decisions, expanded) = store.archive_decision_counts()?;
    let mut expanded_ids = Vec::new();
    let (mut cost, mut cost_rows) = (0i64, 0u64);
    for m in store.list_measurements("archive")? {
        if m.kind == "expand" {
            cost += i64::from(m.est_after);
            cost_rows += 1;
            if let Some(id) = m.ref_id {
                expanded_ids.push(id);
            }
        }
    }
    Ok(ReportExpand {
        rate: if decisions > 0 {
            expanded as f64 / decisions as f64
        } else {
            0.0
        },
        decisions,
        expanded,
        expanded_ids,
        cost,
        cost_rows,
    })
}

/// Nearest-rank percentile: the `ceil(p·n)`-th smallest value; one sample gives p50 = p95
/// = it. `None` when nothing was timed — the report prints a dash, not a zero.
pub(crate) fn pct(sorted: &[f64], p: f64) -> Option<f64> {
    let idx = ((p * sorted.len() as f64).ceil() as usize)
        .saturating_sub(1)
        .min(sorted.len().saturating_sub(1));
    sorted.get(idx).copied()
}

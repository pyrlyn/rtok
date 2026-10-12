// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! GitHub Copilot CLI (T358.3). `session-state/<session>/events.jsonl` is the host's event
//! log. The per-request `assistant.usage` event is ephemeral (the SDK schema marks it as
//! never persisted), so the only token counts on disk are the `modelMetrics` of the
//! `session.shutdown` event: one total per model for the whole session (github/copilot-sdk
//! `nodejs/src/generated/session-events.ts` at 5b2d7cd, see `research.md`).
//!
//! That makes this reader coarser than the others: a session lands on the day it ended,
//! and a session that never shut down cleanly (still open, killed) has no totals and counts
//! nowhere rather than being estimated from the `outputTokens` of its messages.

use crate::measure::{codex, jsonl};
use crate::store::UsageSlice;
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

/// The last `session.shutdown` model totals of each session under `dirs`, and the first log
/// that could not be read.
pub(super) fn slices(
    dirs: &[PathBuf],
    since: i64,
    cutoff: SystemTime,
) -> (Vec<UsageSlice>, Option<PathBuf>) {
    let mut paths: Vec<PathBuf> = dirs
        .iter()
        .flat_map(|d| codex::jsonl_paths(d, cutoff))
        .filter(|p| p.file_name().is_some_and(|n| n == "events.jsonl"))
        .collect();
    paths.sort();
    let mut out = Vec::new();
    let mut unreadable = None;
    for p in paths {
        match std::fs::read_to_string(&p) {
            Ok(text) => out.extend(session(&p, &text, since)),
            Err(_) => {
                unreadable.get_or_insert(p);
            }
        }
    }
    (out, unreadable)
}

fn session(path: &Path, text: &str, since: i64) -> Vec<UsageSlice> {
    let id = path
        .parent()
        .and_then(Path::file_name)
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    // Most of a log is tool and hook events: only the shutdown line is parsed. A resumed
    // session appends another shutdown whose totals are session-wide (the SDK documents them
    // as "across all requests"), so only the last one counts: summing would count the earlier
    // runs again on every resume.
    let mut last = None;
    for line in text.lines().filter(|l| l.contains("\"session.shutdown\"")) {
        let Ok(v) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        if v.get("type").and_then(Value::as_str) != Some("session.shutdown") {
            continue;
        }
        if let Some(models) = v.pointer("/data/modelMetrics").and_then(Value::as_object) {
            last = Some((jsonl::line_ts(&v), models.clone()));
        }
    }
    let Some((ts, models)) = last else {
        return Vec::new();
    };
    models
        .iter()
        .filter_map(|(model, metric)| {
            super::slice(
                "copilot",
                "github",
                &id,
                Some(model.clone()),
                ts,
                legs(metric),
                since,
            )
        })
        .collect()
}

/// `[input, cache write, cache read, output]` of one model's metric. `tokenDetails` is the
/// whole spend: it is `usage` plus the context-compaction requests, which `usage` leaves out
/// (the sums match on the one real log, `research.md` 30.2). Its `input` is uncached already.
/// Without both `input` and `output` there it falls back to `usage`, whose `inputTokens`
/// includes the cache legs.
fn legs(metric: &Value) -> [i64; 4] {
    let count = |p: String| metric.pointer(&p).and_then(Value::as_i64).map(|c| c.max(0));
    let detail = |k: &str| count(format!("/tokenDetails/{k}/tokenCount"));
    let usage = |k: &str| count(format!("/usage/{k}")).unwrap_or(0);
    if let (Some(input), Some(output)) = (detail("input"), detail("output")) {
        return [
            input,
            detail("cache_write").unwrap_or(0),
            detail("cache_read").unwrap_or(0),
            output,
        ];
    }
    let (cache_read, cache_write) = (usage("cacheReadTokens"), usage("cacheWriteTokens"));
    [
        (usage("inputTokens") - cache_read - cache_write).max(0),
        cache_write,
        cache_read,
        usage("outputTokens"),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn shutdown(ts: &str, metrics: Value) -> String {
        json!({"type":"session.shutdown","timestamp":ts,"data":{"modelMetrics":metrics}})
            .to_string()
    }

    fn metric(i: i64, cr: i64, cw: i64, o: i64) -> Value {
        json!({"requests":{"count":3},"usage":{"inputTokens":i,"cacheReadTokens":cr,
            "cacheWriteTokens":cw,"outputTokens":o,"reasoningTokens":4}})
    }

    #[test]
    fn each_model_of_a_shutdown_is_one_slice_dated_when_the_session_ended() {
        let d = std::env::temp_dir().join(format!("rtok-usage-cop-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(d.join("sess-1")).unwrap();
        std::fs::create_dir_all(d.join("sess-2")).unwrap();
        let log = [
            json!({"type":"assistant.message","data":{"outputTokens":99}}).to_string(),
            shutdown(
                "2026-09-02T10:58:10.672Z",
                json!({"gpt-x": metric(100, 60, 0, 7), "claude-y": metric(50, 10, 20, 5)}),
            ),
            "not json \"session.shutdown\"".into(),
        ]
        .join("\n");
        std::fs::write(d.join("sess-1/events.jsonl"), log).unwrap();
        // No shutdown (still open, or killed): no totals, so nothing is counted.
        std::fs::write(
            d.join("sess-2/events.jsonl"),
            "{\"type\":\"session.start\"}\n",
        )
        .unwrap();
        std::fs::write(
            d.join("sess-1/other.jsonl"),
            shutdown("2026-09-02T10:58:10Z", json!({})),
        )
        .unwrap();

        let (mut rows, bad) = slices(std::slice::from_ref(&d), 0, SystemTime::UNIX_EPOCH);
        assert!(bad.is_none());
        rows.sort_by(|a, b| a.model.cmp(&b.model));
        assert_eq!(rows.len(), 2);
        assert_eq!(
            (
                rows[0].model.as_deref(),
                rows[0].session.as_str(),
                rows[0].ts
            ),
            (Some("claude-y"), "sess-1", 1_788_346_690)
        );
        // `inputTokens` includes both cache legs; reasoning is inside `outputTokens`.
        assert_eq!(
            (
                rows[0].input,
                rows[0].cache_create,
                rows[0].cache_read,
                rows[0].output
            ),
            (20, 20, 10, 5)
        );
        assert_eq!(
            (rows[1].input, rows[1].cache_read, rows[1].output),
            (40, 60, 7)
        );
        assert!(
            slices(
                std::slice::from_ref(&d),
                1_788_346_691,
                SystemTime::UNIX_EPOCH
            )
            .0
            .is_empty()
        );
        std::fs::remove_dir_all(&d).ok();
    }

    /// A real 1.0.80 log: `tokenDetails` is `usage` plus the compaction requests (the
    /// `compactionTokensUsed` of each `session.compaction_complete`), so it is the whole spend.
    #[test]
    fn token_details_carry_the_compaction_requests_that_usage_leaves_out() {
        let d = std::env::temp_dir().join(format!("rtok-usage-cop-td-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(d.join("sess-td")).unwrap();
        let mut m = metric(1000, 600, 0, 50);
        m["tokenDetails"] = json!({"input":{"tokenCount":700},"cache_read":{"tokenCount":600},
            "cache_write":{"tokenCount":0},"output":{"tokenCount":80}});
        // A block that lacks a leg is not trusted over `usage`.
        let mut partial = metric(200, 50, 0, 9);
        partial["tokenDetails"] = json!({"input":{"tokenCount":999}});
        std::fs::write(
            d.join("sess-td/events.jsonl"),
            shutdown(
                "2026-09-02T10:58:10Z",
                json!({"full": m, "partial": partial}),
            ),
        )
        .unwrap();

        let (mut rows, _) = slices(std::slice::from_ref(&d), 0, SystemTime::UNIX_EPOCH);
        rows.sort_by(|a, b| a.model.cmp(&b.model));
        let legs = |r: &UsageSlice| (r.input, r.cache_create, r.cache_read, r.output);
        assert_eq!(legs(&rows[0]), (700, 0, 600, 80));
        assert_eq!(legs(&rows[1]), (150, 0, 50, 9));
        std::fs::remove_dir_all(&d).ok();
    }

    /// SDK 6be623b: shutdown `modelMetrics` are totals "across all requests" and the block is
    /// session-wide, so a resumed session's second shutdown already holds the first run.
    #[test]
    fn a_resumed_session_counts_its_last_shutdown_not_the_sum_of_all() {
        let d = std::env::temp_dir().join(format!("rtok-usage-cop-rs-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        for s in ["sess-a", "sess-b", "sess-c"] {
            std::fs::create_dir_all(d.join(s)).unwrap();
        }
        let line = |ts: &str, m: Value| shutdown(ts, json!({"gpt-x": m}));
        // The second shutdown includes the first run (100 + 40 in, 7 + 3 out), plus a
        // checkpoint and resume event between them that carry no totals.
        let resumed = [
            line("2026-09-02T10:00:00Z", metric(100, 0, 0, 7)),
            json!({"type":"session.resume","data":{"eventCount":9}}).to_string(),
            json!({"type":"session.usage_checkpoint","data":{"totalNanoAiu":5}}).to_string(),
            line("2026-09-03T10:00:00Z", metric(140, 0, 0, 10)),
        ]
        .join("\n");
        std::fs::write(d.join("sess-a/events.jsonl"), resumed).unwrap();
        // One shutdown is unchanged; a different session is a separate total.
        std::fs::write(
            d.join("sess-b/events.jsonl"),
            line("2026-09-02T10:00:00Z", metric(50, 0, 0, 5)),
        )
        .unwrap();
        // A later unparsable or model-less shutdown must not hide the earlier totals.
        std::fs::write(
            d.join("sess-c/events.jsonl"),
            [
                line("2026-09-02T10:00:00Z", metric(30, 0, 0, 2)),
                "{\"type\":\"session.shutdown\",\"data\":{}}".into(),
            ]
            .join("\n"),
        )
        .unwrap();

        let (mut rows, _) = slices(std::slice::from_ref(&d), 0, SystemTime::UNIX_EPOCH);
        rows.sort_by(|a, b| a.session.cmp(&b.session));
        let got: Vec<_> = rows
            .iter()
            .map(|r| (r.session.as_str(), r.input, r.output, r.ts))
            .collect();
        assert_eq!(
            got,
            [
                ("sess-a", 140, 10, 1_788_429_600),
                ("sess-b", 50, 5, 1_788_343_200),
                ("sess-c", 30, 2, 1_788_343_200),
            ]
        );
        std::fs::remove_dir_all(&d).ok();
    }
}

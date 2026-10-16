// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T16.2 (D19): what the OpenTelemetry exporter reads — per-stream watermarks and the rows
//! past them. A second `impl Store`; `src/otel/` never sees SQL.

use crate::Result;
use diesel::dsl::{count_star, max, min};
use diesel::prelude::*;
use diesel::sqlite::SqliteConnection;

use super::sum_bigint;

use super::Store;
use super::models::{Call, CallIo, LogRow, Session, TokenRow};
use super::schema::{
    call_io, calls, hook_sessions, hosts, logs, measurements, models, otel_export, providers,
    sessions, tokens, usage,
};

/// The `measurements.kind` the proxy writes when the semantic cache answers a request.
/// Such a call keeps its `usage` row (the cached body's counters) but no provider was
/// billed for it, so the provider-token totals skip it.
pub const SEMANTIC_CACHE_HIT_KIND: &str = "semantic_cache_hit";

/// Everything a span needs beside its `calls` row.
#[derive(Debug, Default)]
pub struct CallDetail {
    pub io: Option<CallIo>,
    pub usage: Option<CallUsage>,
    pub tokens: Vec<TokenRow>,
    pub measurements: Vec<CallMeasurement>,
    pub host: Option<String>,
    pub provider: Option<String>,
    pub model: Option<String>,
}

/// The `usage` row of one proxied request.
#[derive(Debug, Queryable)]
pub struct CallUsage {
    pub api: String,
    pub input: i64,
    pub cache_create: i64,
    pub cache_read: i64,
    pub output: i64,
}

/// One `measurements` row attributed to a call.
#[derive(Debug, Queryable)]
pub struct CallMeasurement {
    pub plugin: String,
    pub kind: String,
    pub before_bytes: i64,
    pub after_bytes: i64,
    pub est_before: i32,
    pub est_after: i32,
    pub ref_id: Option<String>,
}

/// `usage` summed per model and wire (T16.7).
#[derive(Debug)]
pub struct TokenTotal {
    pub model: Option<String>,
    pub api: String,
    pub input: i64,
    pub cache_create: i64,
    pub cache_read: i64,
    pub output: i64,
}

/// `measurements` saved tokens per plugin and kind (T16.7).
#[derive(Debug)]
pub struct SavedTotal {
    pub plugin: String,
    pub kind: String,
    pub saved: i64,
}

/// `calls` counted per surface, kind and outcome (T16.7).
#[derive(Debug)]
pub struct CallTotal {
    pub surface: String,
    pub kind: String,
    pub ok: i32,
    pub n: i64,
}

fn clamp(id: i64) -> i32 {
    i32::try_from(id).unwrap_or(i32::MAX)
}

fn host_slug(conn: &mut SqliteConnection, id: i32) -> Result<Option<String>> {
    Ok(hosts::table
        .find(id)
        .select(hosts::slug)
        .first::<String>(conn)
        .optional()?)
}

impl Store {
    pub fn host_slug(&self, id: i32) -> Result<Option<String>> {
        let mut conn = self.lock()?;
        host_slug(&mut conn, id)
    }

    /// Rows past the `calls` and `logs` marks — what the next flushes will post.
    pub fn otel_pending(&self) -> Result<(i64, i64)> {
        let (c, l) = (self.otel_mark("calls")?, self.otel_mark("logs")?);
        let mut conn = self.lock()?;
        let calls = calls::table
            .filter(calls::id.gt(clamp(c)))
            .count()
            .get_result::<i64>(&mut *conn)?;
        let logs = logs::table
            .filter(logs::id.gt(clamp(l)))
            .count()
            .get_result::<i64>(&mut *conn)?;
        Ok((calls, logs))
    }

    /// Newest `logs` row from `source`, e.g. the exporter's last failure.
    pub fn last_log(&self, source: &str) -> Result<Option<LogRow>> {
        let mut conn = self.lock()?;
        Ok(logs::table
            .filter(logs::source.eq(source))
            .order(logs::id.desc())
            .select(LogRow::as_select())
            .first(&mut *conn)
            .optional()?)
    }

    /// Provider-billed totals: a call the semantic cache answered kept its `usage` row
    /// but no provider request happened, so counting it would double the bill.
    pub fn otel_token_totals(&self) -> Result<Vec<TokenTotal>> {
        let mut conn = self.lock()?;
        let cache_hit = measurements::table
            .filter(measurements::call_id.eq(usage::call_id))
            .filter(measurements::kind.eq(SEMANTIC_CACHE_HIT_KIND));
        let rows = usage::table
            .filter(diesel::dsl::not(diesel::dsl::exists(cache_hit)))
            .group_by((usage::model, usage::api))
            .select((
                usage::model,
                usage::api,
                sum_bigint(usage::input),
                sum_bigint(usage::cache_create),
                sum_bigint(usage::cache_read),
                sum_bigint(usage::output),
            ))
            .order((usage::model.asc(), usage::api.asc()))
            .load::<(
                Option<String>,
                String,
                Option<i64>,
                Option<i64>,
                Option<i64>,
                Option<i64>,
            )>(&mut *conn)?;
        Ok(rows
            .into_iter()
            .map(
                |(model, api, input, cache_create, cache_read, output)| TokenTotal {
                    model,
                    api,
                    input: input.unwrap_or(0),
                    cache_create: cache_create.unwrap_or(0),
                    cache_read: cache_read.unwrap_or(0),
                    output: output.unwrap_or(0),
                },
            )
            .collect())
    }

    /// T207: reads [`Store::measurement_totals`] instead of its own raw `GROUP BY` — the
    /// Diesel DSL expresses this aggregate fine, so the raw SQL was the one thing here
    /// the crate rule (no raw SQL where the DSL can say it) didn't actually need. An
    /// `expand` group's `saved` comes out negative (retrieval costs tokens, same as
    /// `ReportSavings::saved`), not dropped — the exporter reports the cost, not just
    /// the win.
    pub fn otel_saved_totals(&self) -> Result<Vec<SavedTotal>> {
        Ok(self
            .measurement_totals()?
            .into_iter()
            .map(|t| SavedTotal {
                plugin: t.plugin,
                kind: t.kind,
                saved: t.est_before - t.est_after,
            })
            .collect())
    }

    pub fn otel_call_totals(&self) -> Result<Vec<CallTotal>> {
        let mut conn = self.lock()?;
        let rows = calls::table
            .group_by((calls::surface, calls::kind, calls::ok))
            .select((calls::surface, calls::kind, calls::ok, count_star()))
            .order((calls::surface.asc(), calls::kind.asc(), calls::ok.asc()))
            .load::<(String, String, i32, i64)>(&mut *conn)?;
        Ok(rows
            .into_iter()
            .map(|(surface, kind, ok, n)| CallTotal {
                surface,
                kind,
                ok,
                n,
            })
            .collect())
    }

    /// Earliest `calls.ts`, the sums' start time; 0 on an empty ledger.
    pub fn otel_first_ts(&self) -> Result<i64> {
        let mut conn = self.lock()?;
        Ok(calls::table
            .select(min(calls::ts))
            .first::<Option<i64>>(&mut *conn)?
            .unwrap_or(0))
    }

    /// Last value posted for `stream`; 0 before the first successful flush.
    pub fn otel_mark(&self, stream: &str) -> Result<i64> {
        let mut conn = self.lock()?;
        Ok(otel_export::table
            .find(stream)
            .select(otel_export::mark)
            .first::<i64>(&mut *conn)
            .optional()?
            .unwrap_or(0))
    }

    pub fn otel_advance(&self, stream: &str, mark: i64) -> Result<()> {
        let mut conn = self.lock()?;
        diesel::insert_into(otel_export::table)
            .values((otel_export::stream.eq(stream), otel_export::mark.eq(mark)))
            .on_conflict(otel_export::stream)
            .do_update()
            .set(otel_export::mark.eq(mark))
            .execute(&mut *conn)?;
        Ok(())
    }

    /// `calls` rows with `id > after`, ascending, at most `limit`.
    pub fn calls_after(&self, after: i64, limit: i64) -> Result<Vec<Call>> {
        let mut conn = self.lock()?;
        Ok(calls::table
            .filter(calls::id.gt(clamp(after)))
            .order(calls::id.asc())
            .limit(limit)
            .select(Call::as_select())
            .load(&mut *conn)?)
    }

    pub fn logs_after(&self, after: i64, limit: i64) -> Result<Vec<LogRow>> {
        let mut conn = self.lock()?;
        Ok(logs::table
            .filter(logs::id.gt(clamp(after)))
            .order(logs::id.asc())
            .limit(limit)
            .select(LogRow::as_select())
            .load(&mut *conn)?)
    }

    /// Sessions with `ended_at >= ts`: a tie is re-sent (same span id, same bytes), never lost.
    pub fn sessions_ended_after(&self, ts: i64) -> Result<Vec<Session>> {
        let mut conn = self.lock()?;
        Ok(sessions::table
            .filter(sessions::ended_at.ge(ts))
            .order(sessions::ended_at.asc())
            .select(Session::as_select())
            .load(&mut *conn)?)
    }

    /// Ended sessions past `after`, the `end_seq` of the last one posted, each with its own
    /// `end_seq`. The order sessions ended in never moves, so a session that ends later is
    /// always past the mark — whatever its id or `ended_at` second (T592).
    ///
    /// At most `limit` rows, like `calls_after` / `logs_after`: unbounded, an endpoint that was
    /// down for a while got every pending session in one `/v1/traces` body.
    pub fn sessions_pending_export(&self, after: i64, limit: i64) -> Result<Vec<(i64, Session)>> {
        let mut conn = self.lock()?;
        Ok(sessions::table
            .filter(sessions::end_seq.gt(after))
            .order(sessions::end_seq.asc())
            .limit(limit)
            .select((sessions::end_seq, Session::as_select()))
            .load(&mut *conn)?)
    }

    /// Stamp `ended_at` and take the next `end_seq`. Ending a session at the second it already
    /// ended at changes nothing, so a repeated `SessionEnd` does not queue a second export.
    pub fn end_session(&self, id: &str, ended_at: i64) -> Result<()> {
        let mut conn = self.lock()?;
        // The highest `end_seq` is read and the next one written under one write lock, so two
        // processes ending sessions at once cannot be given the same place.
        conn.immediate_transaction(|conn| {
            let seq = sessions::table
                .select(max(sessions::end_seq))
                .first::<Option<i64>>(conn)?
                .unwrap_or(0)
                .saturating_add(1);
            diesel::update(
                sessions::table.find(id).filter(
                    sessions::ended_at
                        .is_null()
                        .or(sessions::ended_at.ne(ended_at)),
                ),
            )
            .set((sessions::ended_at.eq(ended_at), sessions::end_seq.eq(seq)))
            .execute(conn)?;
            Ok(())
        })
    }

    /// The one read a span needs: io, usage, plugin token rows, measurements and the three slugs.
    pub fn call_detail(&self, call: &Call) -> Result<CallDetail> {
        let mut conn = self.lock()?;
        // T433: a hook body saved without its session fields gets them back before mapping.
        let io = call_io::table
            .left_join(hook_sessions::table)
            .filter(call_io::call_id.eq(call.id))
            .select((CallIo::as_select(), hook_sessions::fields.nullable()))
            .first::<(CallIo, Option<String>)>(&mut *conn)
            .optional()?
            .map(|(mut io, fields)| {
                io.request_json = io
                    .request_json
                    .map(|j| super::hook_fields::rebuild(j, fields.as_deref()));
                io
            });
        let usage = usage::table
            .filter(usage::call_id.eq(call.id))
            .order(usage::id.desc())
            .select((
                usage::api,
                usage::input,
                usage::cache_create,
                usage::cache_read,
                usage::output,
            ))
            .first::<CallUsage>(&mut *conn)
            .optional()?;
        let tokens = tokens::table
            .filter(tokens::call_id.eq(call.id))
            .order(tokens::id.asc())
            .select(TokenRow::as_select())
            .load(&mut *conn)?;
        let measurements = measurements::table
            .filter(measurements::call_id.eq(call.id))
            .order(measurements::id.asc())
            .select((
                measurements::plugin,
                measurements::kind,
                measurements::before_bytes,
                measurements::after_bytes,
                measurements::est_before,
                measurements::est_after,
                measurements::ref_id,
            ))
            .load::<CallMeasurement>(&mut *conn)?;
        let host = match call.host_id {
            Some(id) => host_slug(&mut conn, id)?,
            None => None,
        };
        let provider = match call.provider_id {
            Some(id) => providers::table
                .find(id)
                .select(providers::slug)
                .first::<String>(&mut *conn)
                .optional()?,
            None => None,
        };
        let model = match call.model_id {
            Some(id) => models::table
                .find(id)
                .select(models::slug)
                .first::<String>(&mut *conn)
                .optional()?,
            None => None,
        };
        Ok(CallDetail {
            io,
            usage,
            tokens,
            measurements,
            host,
            provider,
            model,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rtok_plugin_sdk::Measurement;

    #[test]
    fn marks_start_at_zero_and_upsert() {
        let s = Store::open_in_memory().unwrap();
        assert_eq!(s.otel_mark("calls").unwrap(), 0);
        s.otel_advance("calls", 5).unwrap();
        s.otel_advance("calls", 7).unwrap();
        assert_eq!(s.otel_mark("calls").unwrap(), 7);
        assert_eq!(s.otel_mark("logs").unwrap(), 0);
    }

    #[test]
    fn rows_after_a_mark_ascend_and_honour_the_limit() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, None, None, None).unwrap();
        for _ in 0..3 {
            s.insert_call(
                "s1",
                "hook",
                "hook",
                None,
                None,
                None,
                None,
                Some("PostToolUse"),
            )
            .unwrap();
        }
        let ids: Vec<i32> = s.calls_after(1, 10).unwrap().iter().map(|c| c.id).collect();
        assert_eq!(ids, vec![2, 3]);
        assert_eq!(s.calls_after(0, 1).unwrap().len(), 1);
        assert!(s.calls_after(3, 10).unwrap().is_empty());
        s.insert_log("info", "otel", "flush", "m", Some("s1"), Some(1), None)
            .unwrap();
        assert_eq!(s.logs_after(0, 10).unwrap().len(), 1);
        assert!(s.logs_after(1, 10).unwrap().is_empty());
    }

    #[test]
    fn ended_sessions_include_the_tie() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, Some("p"), Some("/w"), Some("startup"))
            .unwrap();
        assert!(s.sessions_ended_after(0).unwrap().is_empty());
        s.end_session("s1", 1_700_000_000).unwrap();
        let rows = s.sessions_ended_after(1_700_000_000).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].ended_at, Some(1_700_000_000));
        assert!(s.sessions_ended_after(1_700_000_001).unwrap().is_empty());
    }

    fn ids(rows: &[(i64, Session)]) -> Vec<&str> {
        rows.iter().map(|(_, s)| s.id.as_str()).collect()
    }

    /// T592: the order sessions ended in decides what is pending, not the id and not the
    /// second — a late session with a lower id used to hide behind the posted one.
    #[test]
    fn sessions_pending_export_follows_the_order_sessions_ended_in() {
        let s = Store::open_in_memory().unwrap();
        for id in ["z-first", "a-late", "open"] {
            s.upsert_session(id, None, None, None, None).unwrap();
        }
        s.end_session("z-first", 1_700_000_000).unwrap();
        let first = s.sessions_pending_export(0, 1000).unwrap();
        assert_eq!(ids(&first), ["z-first"]);
        s.otel_advance("sessions_seq", first[0].0).unwrap();
        s.end_session("a-late", 1_700_000_000).unwrap();
        let rest = s
            .sessions_pending_export(s.otel_mark("sessions_seq").unwrap(), 1000)
            .unwrap();
        assert_eq!(ids(&rest), ["a-late"], "open sessions never appear");
        assert!(rest[0].0 > first[0].0);
    }

    /// A batch cut inside one second resumes at the next session, never repeats or skips one.
    #[test]
    fn sessions_pending_export_is_batched_and_resumes_mid_second() {
        let s = Store::open_in_memory().unwrap();
        for id in ["s3", "s1", "s2"] {
            s.upsert_session(id, None, None, None, None).unwrap();
            s.end_session(id, 1_700_000_000).unwrap();
        }
        let first = s.sessions_pending_export(0, 2).unwrap();
        assert_eq!(ids(&first), ["s3", "s1"]);
        let rest = s.sessions_pending_export(first[1].0, 2).unwrap();
        assert_eq!(ids(&rest), ["s2"]);
    }

    #[test]
    fn ending_a_session_at_the_same_second_again_queues_nothing_new() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, None, None, None).unwrap();
        s.end_session("s1", 1_700_000_000).unwrap();
        let seq = s.sessions_pending_export(0, 10).unwrap()[0].0;
        s.end_session("s1", 1_700_000_000).unwrap();
        assert_eq!(s.sessions_pending_export(seq, 10).unwrap().len(), 0);
        s.end_session("s1", 1_700_000_005).unwrap();
        assert_eq!(s.sessions_pending_export(seq, 10).unwrap().len(), 1);
    }

    #[test]
    fn call_detail_joins_io_usage_tokens_measurements_and_slugs() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, None, None, None).unwrap();
        let (pid, mid) = s.upsert_model("anthropic", "claude-x").unwrap();
        let id = s
            .insert_call(
                "s1",
                "proxy",
                "api_request",
                None,
                Some(pid),
                Some(mid),
                None,
                Some("/v1/messages"),
            )
            .unwrap();
        s.insert_call_io(id, Some(br#"{"a":1}"#), Some(br#"{"b":2}"#), 1024, None)
            .unwrap();
        s.insert_usage("s1", Some("claude-x"), "anthropic", 100, 10, 20, 30, id)
            .unwrap();
        s.insert_tokens(id, Some("proxy"), "before", "est", 3)
            .unwrap();
        s.insert_measurement(
            "s1",
            &Measurement {
                plugin: "proxy",
                kind: "raw",
                before_bytes: 10,
                after_bytes: 5,
                est_before: 3,
                est_after: 1,
                ref_id: None,
                call_id: Some(id),
            },
        )
        .unwrap();
        let call = s.calls_after(0, 1).unwrap().remove(0);
        let d = s.call_detail(&call).unwrap();
        assert_eq!(d.io.unwrap().request_json.as_deref(), Some(r#"{"a":1}"#));
        let u = d.usage.unwrap();
        assert_eq!(
            (u.input, u.cache_create, u.cache_read, u.output),
            (100, 10, 20, 30)
        );
        assert_eq!(d.tokens[0].n_tokens, 3);
        assert_eq!(
            d.measurements[0].est_before - d.measurements[0].est_after,
            2
        );
        assert_eq!(d.provider.as_deref(), Some("anthropic"));
        assert_eq!(d.model.as_deref(), Some("claude-x"));
        assert_eq!(d.host, None);
    }

    fn proxied_call(s: &Store, kind: &str) -> i32 {
        s.insert_call(
            "s1",
            "proxy",
            kind,
            None,
            None,
            None,
            None,
            Some("/v1/messages"),
        )
        .unwrap()
    }

    // The proxy writes a cache hit's usage row like a live one, so the marker that tells
    // them apart is the hit's `semantic_cache_hit` measurement on the same call.
    #[test]
    fn token_totals_count_the_upstream_request_not_the_cache_hit_served_after_it() {
        let s = Store::open_in_memory().unwrap();
        s.upsert_session("s1", None, None, None, None).unwrap();
        let live = proxied_call(&s, "api_request");
        let hit = proxied_call(&s, "api_request");
        for id in [live, hit] {
            s.insert_usage("s1", Some("claude-x"), "anthropic", 100, 0, 0, 25, id)
                .unwrap();
        }
        s.insert_measurement(
            "s1",
            &Measurement {
                plugin: "proxy",
                kind: SEMANTIC_CACHE_HIT_KIND,
                before_bytes: 40,
                after_bytes: 40,
                est_before: 10,
                est_after: 10,
                ref_id: None,
                call_id: Some(hit),
            },
        )
        .unwrap();

        let totals = s.otel_token_totals().unwrap();
        assert_eq!(totals.len(), 1);
        assert_eq!((totals[0].input, totals[0].output), (100, 25));
        // The hit's own usage stays visible for per-call analytics.
        let hit_call = s.calls_after(live as i64, 1).unwrap().remove(0);
        let usage = s.call_detail(&hit_call).unwrap().usage.unwrap();
        assert_eq!((usage.input, usage.output), (100, 25));
    }
}

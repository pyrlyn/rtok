// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::*;
use super::report::pct;
use crate::plugin::{Measurement, Runtime};
use rstest::rstest;

fn fixture() -> Runtime {
    let cx = Runtime::in_memory("dash").unwrap();
    cx.record(&Measurement {
        plugin: "cmd",
        kind: "filter",
        before_bytes: 100,
        after_bytes: 40,
        est_before: 25,
        est_after: 10,
        ref_id: None,
        call_id: None,
    })
    .unwrap();
    cx
}

/// T358.5: the Usage page is `rtok agents usage`'s report and its text, read once.
#[test]
fn usage_page_carries_the_cli_report_and_its_text() {
    let cfg = crate::testutil::config_in(&crate::testutil::tmp_dir("usage-page"));
    let page = read_usage_page(&cfg);
    let report = page.report.as_ref().expect("an empty home still reads");
    assert_eq!(page.text, report.to_text());
    assert!(
        page.text.starts_with("rtok agents usage: "),
        "{}",
        page.text
    );
    let wire = serde_json::to_value(&page).unwrap();
    assert!(wire["report"]["totals"]["tokens"].is_number(), "{wire}");
}

/// A bad `[agents.usage]` value is the page's text, never a failed snapshot.
#[test]
fn usage_page_names_a_failed_read() {
    let mut cfg = crate::testutil::config_in(&crate::testutil::tmp_dir("usage-bad"));
    cfg.agents.usage.tz = "Nowhere/Land".into();
    let page = read_usage_page(&cfg);
    assert!(page.report.is_none());
    assert!(
        page.text.starts_with("usage did not answer"),
        "{}",
        page.text
    );
}

#[test]
fn snapshot_lists_catalogue_and_hides_stats_on_measure() {
    let cx = fixture();
    let snap = Model::new(&cx.config, Some(&cx.store)).snapshot();
    assert!(snap.plugins.iter().any(|p| p.id == "cmd"));
    let measure = snap.plugins.iter().find(|p| p.id == "measure").unwrap();
    assert!(!measure.saves_tokens);
    assert!(measure.stats.is_none());
    let cmd = snap.plugins.iter().find(|p| p.id == "cmd").unwrap();
    let stats = cmd.stats.as_ref().unwrap();
    assert_eq!(stats.est_before, 25);
    assert_eq!(stats.est_after, 10);
}

/// T207's Check: `Store::measurement_totals`'s `GROUP BY` against the per-row loop it
/// replaced, at a size the old per-plugin `list_measurements` N+1 would make slow —
/// several plugins (one outside the catalogue), several kinds, `expand` included.
#[test]
fn plugin_stats_matches_sql_aggregates_on_10k_rows() {
    let cx = Runtime::in_memory("dash-10k").unwrap();
    let plugins = ["cmd", "archive", "wasm_widget"];
    let kinds = ["filter", "expand", "rule"];
    for i in 0..10_000i64 {
        cx.record(&Measurement {
            plugin: plugins[(i % plugins.len() as i64) as usize],
            kind: kinds[(i % kinds.len() as i64) as usize],
            before_bytes: 100,
            after_bytes: 40,
            est_before: (i % 50) as u32 + 1,
            est_after: (i % 20) as u32,
            ref_id: None,
            call_id: None,
        })
        .unwrap();
    }

    // The naive per-row aggregate every one of the three callers used to hand-roll.
    let mut want: BTreeMap<(String, String), (i64, i64, i64)> = BTreeMap::new();
    for plugin in plugins {
        for m in cx.store.list_measurements(plugin).unwrap() {
            let e = want
                .entry((plugin.to_string(), m.kind.clone()))
                .or_insert((0, 0, 0));
            e.0 += 1;
            e.1 += i64::from(m.est_before);
            e.2 += i64::from(m.est_after);
        }
    }
    let got: BTreeMap<(String, String), (i64, i64, i64)> = cx
        .store
        .measurement_totals()
        .unwrap()
        .into_iter()
        .map(|t| ((t.plugin, t.kind), (t.rows, t.est_before, t.est_after)))
        .collect();
    assert_eq!(got, want);

    // The Plugins page sums the same read across kinds, per plugin (T207).
    let totals = Model::new(&cx.config, Some(&cx.store)).plugin_stat_totals();
    for plugin in ["cmd", "archive"] {
        let want_plugin = want.iter().filter(|((p, _), _)| p == plugin).fold(
            (0u64, 0i64, 0i64),
            |(rows, before, after), (_, (n, b, a))| (rows + *n as u64, before + b, after + a),
        );
        let s = totals.get(plugin).unwrap();
        assert_eq!((s.rows, s.est_before, s.est_after), want_plugin, "{plugin}");
    }
}

/// T414.13's Check: the Δtok trend buckets fixture rows by day in the zone, per plugin and
/// in total, nets an `expand` row out, and leaves a day without rows empty, never zero.
#[test]
fn savings_trend_buckets_rows_by_day_and_plugin() {
    let cx = Runtime::in_memory("trend").unwrap();
    let at = |utc: &str| utc.parse::<Timestamp>().unwrap().as_second();
    for (session, plugin, kind, before, after, utc) in [
        ("old", "cmd", "filter", 999, 0, "2026-09-24T23:59:59Z"),
        ("edge", "cmd", "filter", 7, 0, "2026-09-25T00:00:00Z"),
        ("mid", "cmd", "filter", 30, 10, "2026-10-06T22:30:00Z"),
        ("today", "cmd", "filter", 100, 40, "2026-10-08T01:00:00Z"),
        ("late", "archive", "filter", 50, 10, "2026-10-08T23:30:00Z"),
        ("late", "archive", "expand", 0, 5, "2026-10-08T23:30:00Z"),
        ("future", "cmd", "filter", 5, 0, "2026-10-09T00:00:00Z"),
    ] {
        let m = Measurement {
            plugin,
            kind,
            before_bytes: 0,
            after_bytes: 0,
            est_before: before,
            est_after: after,
            ref_id: None,
            call_id: None,
        };
        cx.store.insert_measurement(session, &m).unwrap();
        cx.store.set_measurement_ts(session, at(utc)).unwrap();
    }
    let now = at("2026-10-08T12:00:00Z");
    let days = savings_trend(&cx.store, now, &TimeZone::UTC).unwrap();
    assert_eq!(days.len(), SAVINGS_DAYS);
    assert_eq!(days[0].day, "2026-09-25");
    assert_eq!(days[13].day, "2026-10-08");
    let day = |rows, saved, plugins: &[(&str, i64)]| SavingsDay {
        day: String::new(),
        rows,
        saved,
        plugins: plugins.iter().map(|(p, s)| (p.to_string(), *s)).collect(),
    };
    let got: Vec<SavingsDay> = days
        .iter()
        .map(|d| SavingsDay {
            day: String::new(),
            ..d.clone()
        })
        .collect();
    let mut want = vec![day(0, None, &[]); SAVINGS_DAYS];
    want[0] = day(1, Some(7), &[("cmd", 7)]);
    want[11] = day(1, Some(20), &[("cmd", 20)]);
    want[13] = day(3, Some(95), &[("archive", 35), ("cmd", 60)]);
    assert_eq!(got, want);
    // An empty day goes out as `null`, which the chart draws as a gap.
    assert_eq!(
        serde_json::to_value(&days[1]).unwrap()["saved"],
        Value::Null
    );

    // Kyiv is UTC+3 in October: 22:30Z is the next day there, and 23:30Z on the 8th is
    // already the 9th, after today.
    let kyiv = TimeZone::get("Europe/Kyiv").unwrap();
    let days = savings_trend(&cx.store, now, &kyiv).unwrap();
    assert_eq!(days[12].day, "2026-10-07");
    assert_eq!(days[12].saved, Some(20));
    assert_eq!(days[13].plugins, BTreeMap::from([("cmd".to_string(), 60)]));
}

/// T15.3's Check: the Overview page carries the store's own sums — totals, CTT and
/// the per-turn series — so the tab, `rtok stats --json`'s `api` table and the web
/// Overview agree on one store. Two sessions: `a` with two turns, `b` with one.
#[test]
fn overview_carries_totals_ctt_and_turns() {
    let cx = Runtime::in_memory("dash").unwrap();
    cx.store.insert_proxy_turn("a", 10, 0, 0, 1).unwrap();
    cx.store.insert_proxy_turn("a", 4, 0, 12, 2).unwrap();
    cx.store.insert_proxy_turn("b", 100, 20, 0, 5).unwrap();
    let over = Model::new(&cx.config, Some(&cx.store)).overview();
    assert_eq!(
        (
            over.totals.input,
            over.totals.cache_create,
            over.totals.cache_read,
            over.totals.output
        ),
        (114, 20, 12, 8)
    );
    // `a`: turn ctx 10 × 1 turn after, then ctx 16 × 0; `b`: one turn × 0.
    assert_eq!(over.ctt, 10);
    assert_eq!(over.turns, vec![10, 16, 120]);
    // Gate P15: the same sums `rtok stats --json` prints in its `api` table —
    // `attach_api` reads the same `usage_by_api` rows the Overview sums.
    let mut report = stats::Report::default();
    stats::attach_api(&mut report, &cx.store).unwrap();
    let api = report.api.get("anthropic").expect("one api on record");
    assert_eq!(
        (api.input, api.cache_create, api.cache_read, api.output),
        (
            over.totals.input,
            over.totals.cache_create,
            over.totals.cache_read,
            over.totals.output
        )
    );
    // The wire keeps the P19 shape: totals flat under `usage`, CTT beside them.
    let v = serde_json::to_value(Model::new(&cx.config, Some(&cx.store)).snapshot()).unwrap();
    assert_eq!(v["usage"]["input"], 114);
    assert_eq!(v["usage"]["ctt"], 10);
    assert_eq!(v["usage"]["turns"], serde_json::json!([10, 16, 120]));
}

/// `usage_ctt` against the per-session loop it replaced, past the sparkline cap and with
/// sessions interleaved.
#[test]
fn overview_matches_the_per_session_loop() {
    let cx = Runtime::in_memory("dash-ref").unwrap();
    for i in 0..150i64 {
        let s = ["a", "b", "c"][(i % 3) as usize];
        cx.store.insert_proxy_turn(s, i, i % 7, i % 5, 1).unwrap();
    }
    let (mut ctt, mut turns) = (0i64, Vec::new());
    for s in cx.store.usage_sessions().unwrap() {
        let mut rows = cx.store.usage_rows(&s).unwrap();
        rows.reverse();
        let n = rows.len() as i64;
        for (j, r) in rows.iter().enumerate() {
            let c = r.input + r.cache_create + r.cache_read;
            ctt += c * (n - j as i64 - 1);
            turns.push(c);
        }
    }
    let over = Model::new(&cx.config, Some(&cx.store)).overview();
    assert_eq!(over.ctt, ctt);
    assert_eq!(over.turns, turns[turns.len() - OVERVIEW_TURNS..]);
}

/// The wire the P19 UI reads: keys and values as `json!` produced them.
#[test]
fn json_shape_is_what_p19_pinned() {
    let cx = fixture();
    let v = serde_json::to_value(Model::new(&cx.config, Some(&cx.store)).snapshot()).unwrap();
    assert_eq!(v["type"], "snapshot");
    assert!(v["usage"]["cache_read"].is_i64());
    let cmd = v["plugins"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["id"] == "cmd")
        .unwrap();
    assert_eq!(cmd["stats"]["est_before"], 25);
    assert_eq!(cmd["saves_tokens"], true);
    assert!(cmd["fields"].is_array());
    assert!(cmd["surfaces"].is_array());
    assert_eq!(cmd["title"], "Bash / cmd");
}

/// T25.1's Check: the Sessions page carries exactly the store call's numbers (one
/// reader, D27) and rides the snapshot the `/ws` frame sends — three sessions
/// across the two hosts the migrations seed, one ended, one zeroed.
#[test]
fn sessions_page_matches_the_store_and_rides_the_snapshot() {
    let cx = Runtime::in_memory("dash").unwrap();
    let claude = cx
        .store
        .host_id("claude")
        .unwrap()
        .expect("0002 seeds claude");
    let pi = cx.store.host_id("pi").unwrap().expect("0010 seeds pi");
    cx.store
        .upsert_session("a", Some(claude), Some("rtok"), None, Some("proxy"))
        .unwrap();
    cx.store
        .upsert_session("b", Some(pi), Some("rtok"), None, None)
        .unwrap();
    cx.store
        .upsert_session("c", Some(pi), None, None, None)
        .unwrap();
    let (pid, mid) = cx.store.upsert_model("anthropic", "claude-x").unwrap();
    let call_a = cx
        .store
        .insert_call(
            "a",
            "proxy",
            "api_request",
            Some(claude),
            Some(pid),
            Some(mid),
            None,
            Some("/v1/messages"),
        )
        .unwrap();
    let call_b = cx
        .store
        .insert_call(
            "b",
            "proxy",
            "api_request",
            Some(pi),
            None,
            None,
            None,
            Some("/v1/chat/completions"),
        )
        .unwrap();
    cx.store
        .insert_usage("a", Some("claude-x"), "anthropic", 10, 1, 2, 3, call_a)
        .unwrap();
    cx.store
        .insert_usage("a", Some("claude-x"), "anthropic", 20, 0, 5, 4, call_a)
        .unwrap();
    cx.store
        .insert_usage("b", Some("gpt-x"), "openai_chat", 7, 2, 0, 1, call_b)
        .unwrap();
    cx.store.end_session("b", 2_500).unwrap();

    let model = Model::new(&cx.config, Some(&cx.store));
    assert_eq!(
        model.sessions(0),
        cx.store.session_totals(0).unwrap(),
        "the page is the store call, not a second query"
    );
    let snap = model.snapshot();
    assert_eq!(snap.sessions.len(), 3);
    let a = snap.sessions.iter().find(|r| r.id == "a").unwrap();
    assert_eq!(
        (a.input, a.cache_create, a.cache_read, a.output),
        (30, 1, 7, 7)
    );
    assert_eq!(a.host.as_deref(), Some("claude"));
    assert_eq!(a.provider.as_deref(), Some("anthropic"));
    assert!(a.ended_at.is_none(), "a never ended");
    let b = snap.sessions.iter().find(|r| r.id == "b").unwrap();
    assert_eq!(
        (b.input, b.cache_create, b.cache_read, b.output),
        (7, 2, 0, 1)
    );
    assert_eq!(b.ended_at, Some(2_500));
    let c = snap.sessions.iter().find(|r| r.id == "c").unwrap();
    assert_eq!((c.input, c.output), (0, 0));
    assert_eq!(
        c.last_activity, c.started_at,
        "no rows yet: started is all there is"
    );
    // The wire frame gains the page; `tests/surface_parity.rs` pins the key set.
    let v = serde_json::to_value(&snap).unwrap();
    let rows = v["sessions"].as_array().expect("sessions rides the frame");
    assert_eq!(rows.len(), 3);
    assert!(
        rows.iter()
            .any(|r| r["id"] == "a" && r["input"] == 30 && r["cache_read"] == 7)
    );
}

/// T15.6's Check: the Doctor page rides the snapshot — the same `Report` the `doctor`
/// page function returns — so the tab, `rtok doctor` and the web frame agree (D23).
#[test]
fn doctor_page_rides_the_snapshot() {
    let cx = Runtime::in_memory("dash").unwrap();
    let cfg = cx.config.clone();
    let snap = Model::new(&cfg, Some(&cx.store)).snapshot();
    let direct = doctor(&cfg).expect("doctor page");
    let carried = snap
        .doctor
        .as_ref()
        .expect("snapshot carries the doctor page");
    assert_eq!(carried.to_text(), direct.to_text());
    // The wire frame gains the page; `tests/surface_parity.rs` pins the key set.
    let v = serde_json::to_value(&snap).unwrap();
    assert!(
        v["doctor"]["hooks_total"].is_number(),
        "doctor rides the frame"
    );
}

/// T15.5's Check: the Calls page is the store's one `recent_calls` read verbatim
/// (D27), newest first, and rides the snapshot's `calls` key — so the tab and the
/// `/ws` frame carry the same rows. One proxy call with its usage row and host,
/// provider and model slugs; one hook call with none of those.
#[test]
fn calls_page_is_the_store_read_and_rides_the_snapshot() {
    let cx = Runtime::in_memory("dash").unwrap();
    let claude = cx
        .store
        .host_id("claude")
        .unwrap()
        .expect("0002 seeds claude");
    cx.store
        .upsert_session("s", Some(claude), None, None, Some("proxy"))
        .unwrap();
    let (pid, mid) = cx.store.upsert_model("anthropic", "claude-x").unwrap();
    let hook = cx
        .store
        .insert_call("s", "hook", "hook", None, None, None, None, Some("Stop"))
        .unwrap();
    let api = cx
        .store
        .insert_call(
            "s",
            "proxy",
            "api_request",
            Some(claude),
            Some(pid),
            Some(mid),
            None,
            Some("/v1/messages"),
        )
        .unwrap();
    cx.store.set_call_ms(api, 12.5).unwrap();
    cx.store
        .insert_usage("s", Some("claude-x"), "anthropic", 10, 1, 2, 3, api)
        .unwrap();

    let model = Model::new(&cx.config, Some(&cx.store));
    assert_eq!(
        model.calls(),
        cx.store.recent_calls(CALLS_ROWS as i64).unwrap(),
        "the page is the store read, not a second query"
    );
    let rows = model.calls();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].id, api, "newest first");
    assert_eq!(rows[0].api.as_deref(), Some("anthropic"));
    assert_eq!(
        (
            rows[0].input,
            rows[0].cache_create,
            rows[0].cache_read,
            rows[0].output
        ),
        (Some(10), Some(1), Some(2), Some(3))
    );
    assert_eq!(rows[0].ms, Some(12.5));
    assert_eq!(rows[0].host.as_deref(), Some("claude"));
    assert_eq!(rows[1].id, hook);
    assert_eq!(rows[1].api, None, "a hook call carries no usage linkage");
    // The wire frame gains the page; `tests/surface_parity.rs` pins the key set.
    let v = serde_json::to_value(model.snapshot()).unwrap();
    let wire = v["calls"].as_array().expect("calls rides the frame");
    assert_eq!(wire.len(), 2);
    assert_eq!(wire[0]["api"], "anthropic");
    assert_eq!(wire[0]["ms"], 12.5);
    assert_eq!(wire[1]["api"], serde_json::json!(null));
}

#[test]
fn live_calls_prefer_in_process_ring_over_empty_http() {
    // Regression: a listener on the default proxy port that answers `/live`
    // with `[]` used to hide rows pushed into this process's ring.
    let _ring = crate::proxy::live::test_lock();
    crate::proxy::live::clear();
    crate::proxy::live::push(crate::proxy::LiveCall {
        ts: 1,
        method: "POST".into(),
        path: "/v1/messages".into(),
        provider: None,
        model: None,
        status: 200,
        request_bytes: 100,
        response_bytes: 50,
        ms: 5.0,
    });
    let mut cfg = crate::testutil::config("web-live-calls").0;
    cfg.proxy.enabled = false;
    cfg.core.enabled = true;
    let rows = Model::new(&cfg, None).calls();
    crate::proxy::live::clear();
    assert_eq!(rows.len(), 1, "{rows:?}");
    assert_eq!(rows[0].kind, "live_passthrough");
    assert_eq!(call_size_label(&rows[0]), "150 B");
}

#[rstest]
#[case("live_passthrough", None, None, None, None, None, "-")]
#[case("live_passthrough", Some(100), None, None, Some(50), None, "150 B")]
#[case(
    "api_request",
    Some(10),
    Some(1),
    Some(2),
    Some(3),
    Some("anthropic"),
    "16 tok"
)]
#[case("hook", None, None, None, None, None, "-")]
fn call_size_label_distinguishes_bytes_from_tokens(
    #[case] kind: &str,
    #[case] input: Option<i64>,
    #[case] cache_create: Option<i64>,
    #[case] cache_read: Option<i64>,
    #[case] output: Option<i64>,
    #[case] api: Option<&str>,
    #[case] want: &str,
) {
    let row = CallRow {
        id: 1,
        ts: 0,
        session: "s".into(),
        surface: "proxy".into(),
        kind: kind.into(),
        plugin: None,
        name: None,
        parent_id: None,
        ms: None,
        ok: 1,
        error: None,
        host: None,
        provider: None,
        model: None,
        api: api.map(str::to_string),
        input,
        cache_create,
        cache_read,
        output,
    };
    assert_eq!(call_size_label(&row), want);
}

#[test]
fn session_detail_filters_snapshot_calls_by_id() {
    let cfg = crate::testutil::config("session-detail").0;
    let mut snap = Model::new(&cfg, None).snapshot();
    snap.sessions = vec![SessionTotals {
        id: "a".into(),
        host: None,
        project: Some("rtok".into()),
        provider: None,
        api: Some("anthropic".into()),
        model: None,
        input: 30,
        cache_create: 1,
        cache_read: 7,
        output: 7,
        started_at: 1,
        last_activity: 2,
        ended_at: None,
    }];
    snap.calls = vec![
        CallRow {
            id: 1,
            ts: 1,
            session: "a".into(),
            surface: "proxy".into(),
            kind: "api_request".into(),
            plugin: None,
            name: Some("/v1/messages".into()),
            parent_id: None,
            ms: None,
            ok: 1,
            error: None,
            host: None,
            provider: None,
            model: None,
            api: None,
            input: None,
            cache_create: None,
            cache_read: None,
            output: None,
        },
        CallRow {
            id: 2,
            ts: 2,
            session: "other".into(),
            surface: "hook".into(),
            kind: "hook".into(),
            plugin: None,
            name: Some("Skip".into()),
            parent_id: None,
            ms: None,
            ok: 1,
            error: None,
            host: None,
            provider: None,
            model: None,
            api: None,
            input: None,
            cache_create: None,
            cache_read: None,
            output: None,
        },
    ];
    let (session, calls) = session_detail(&snap, "a").expect("session a");
    assert_eq!(session.project.as_deref(), Some("rtok"));
    assert_eq!(session.api.as_deref(), Some("anthropic"));
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].name.as_deref(), Some("/v1/messages"));
    assert!(session_detail(&snap, "missing").is_none());
}

/// T60.4: `expand_payload` is `expand::fetch` + `[expand] max_lines`; a live-zone
/// pointer freezes as the CLI does; the snapshot carries the call's archive id.
#[test]
fn expand_payload_caps_greps_and_freezes_like_cli() {
    let mut cfg = crate::testutil::config("expand-model").0;
    cfg.expand.max_lines = 2;
    let cx = crate::plugin::Runtime::open(cfg.clone(), "proxy-sess").unwrap();
    let id = cx
        .store
        .put_archive(
            "proxy-sess",
            b"alpha\nbeta\ngamma\nHIT\n",
            &cfg.core.archive_dir,
        )
        .unwrap();
    cx.store
        .put_archive_decision("tu-1", &id, "proxy-sess", &format!("[archived {id}]"))
        .unwrap();
    cx.store
        .upsert_session("s", None, None, None, None)
        .unwrap();
    let call = cx
        .store
        .insert_call(
            "s",
            "hook",
            "plugin_run",
            None,
            None,
            None,
            Some("cmd"),
            None,
        )
        .unwrap();
    cx.record(&Measurement {
        plugin: "cmd",
        kind: "filter",
        before_bytes: 10,
        after_bytes: 4,
        est_before: 3,
        est_after: 1,
        ref_id: Some(id.clone()),
        call_id: Some(call),
    })
    .unwrap();
    drop(cx);

    let text = expand_payload(&cfg, &id, None).expect("payload");
    assert!(text.contains("alpha"), "{text}");
    assert!(text.contains("omitted"), "{text}");
    let hit = expand_payload(&cfg, &id, Some("HIT")).expect("grep");
    assert!(hit.contains("HIT"), "{hit}");
    let snap = snapshot(&cfg);
    assert_eq!(snap.ref_ids.get(&call), Some(&id));
    let cx = crate::plugin::Runtime::open(cfg, "check").unwrap();
    assert!(
        cx.store
            .archive_decision("proxy-sess", "tu-1")
            .unwrap()
            .unwrap()
            .expanded
    );
}

/// The report's percentile, pinned where it is defined: nearest rank, so
/// `tests/report.rs` can assert the p50/p95 the fixture's ms values must produce.
#[test]
fn report_pct_is_nearest_rank() {
    assert_eq!(pct(&[], 0.95), None);
    assert_eq!(pct(&[2.0, 4.0], 0.5), Some(2.0));
    assert_eq!(pct(&[2.0, 4.0], 0.95), Some(4.0));
    assert_eq!(pct(&[10.0], 0.5), Some(10.0));
    assert_eq!(pct(&[10.0], 0.95), Some(10.0));
}

fn listed(
    name: &str,
    source: &str,
    desc: usize,
    body: u64,
    calls: Option<u64>,
) -> doctor::SkillRow {
    doctor::SkillRow {
        name: name.into(),
        source: source.into(),
        desc_chars: desc,
        body_bytes: body,
        invocations: calls,
        warn_desc: false,
        warn_body: false,
        warn_never: calls == Some(0),
    }
}

#[test]
fn skills_from_joins_listing_and_resident_without_stats_rows() {
    let listing = doctor::SkillsAudit {
        rows: vec![
            listed("hot", "user", 40, 100, Some(3)),
            listed("cold", "project", 10, 20, Some(0)),
            listed("plug", "plugin:x", 8, 50, Some(1)),
        ],
        desc_bytes: 58,
        ..doctor::SkillsAudit::default()
    };
    let mut stats = BTreeMap::new();
    stats.insert(
        "hot".into(),
        stats::SkillRow {
            count: 3,
            bytes: 100,
            mean: 33,
            p95: 100,
            max: 100,
            est_tokens: 25,
            resident: 800,
        },
    );
    stats.insert(
        "plug".into(),
        stats::SkillRow {
            count: 1,
            bytes: 50,
            mean: 50,
            p95: 50,
            max: 50,
            est_tokens: 12,
            resident: 200,
        },
    );
    let page = skills_from(Some(&listing), Some(&stats), 10_000, true);
    assert_eq!(
        page.rows
            .iter()
            .map(|r| r.name.as_str())
            .collect::<Vec<_>>(),
        ["hot", "plug", "cold"]
    );
    assert!(page.rows[2].never && page.rows[2].last_invoked == "never");
    assert_eq!(page.rows[0].resident, 800);
    assert!(page.header.contains("3 skills"));
    assert!(page.header.contains("58 desc bytes ≈ 14 tok/req"));
    let empty = skills_from(None, None, 0, false);
    assert!(empty.rows.is_empty());
    assert!(empty.header.starts_with("0 skills"));
}

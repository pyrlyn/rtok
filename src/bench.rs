// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok bench` A/B harness (plan T9.1, T68.9).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use figment::Figment;
use figment::providers::{Format, Toml};
use rtok_sys::proc::{Output, ProgressRunner, Unit};
use serde::Deserialize;
use serde_json::Value;

use crate::config::Config;

#[derive(Deserialize)]
struct TaskFile {
    tasks: Vec<Task>,
}

#[derive(Deserialize)]
struct Task {
    id: String,
    prompt: String,
    check: String,
}

#[derive(Default)]
struct Acc {
    input: u64,
    cache: u64,
    output: u64,
    cost: f64,
    pass: u32,
    n: u32,
}

pub fn run(cfg: &Config) -> Result<String> {
    if cfg.bench.suite == "graph" {
        return run_graph(cfg);
    }
    if !cfg.bench.suite.is_empty() {
        bail!("unknown bench suite {:?}", cfg.bench.suite);
    }
    let tasks = load_tasks(&cfg.bench.tasks)?;
    if tasks.len() != 6 {
        bail!("expected 6 tasks, got {}", tasks.len());
    }
    if cfg.bench.dry_run {
        return Ok(dry_list(&tasks, cfg));
    }
    Ok(table(&tasks, cfg))
}

fn load_tasks(path: &Path) -> Result<Vec<Task>> {
    Figment::new()
        .merge(Toml::file(path))
        .extract::<TaskFile>()
        .with_context(|| path.display().to_string())
        .map(|f| f.tasks)
}

fn dry_list(tasks: &[Task], cfg: &Config) -> String {
    let mut out = String::new();
    for t in tasks {
        for name in cfg.bench.configs.keys() {
            for n in 1..=cfg.bench.runs {
                out.push_str(&format!("{} {name} {n}\n", t.id));
            }
        }
    }
    out
}

fn table(tasks: &[Task], cfg: &Config) -> String {
    let mut by: BTreeMap<String, Acc> = BTreeMap::new();
    let runs = u64::from(cfg.bench.runs);
    let mut bar = ProgressRunner::batch(
        "bench",
        tasks.len() as u64 * cfg.bench.configs.len() as u64 * runs,
        Unit::Items,
    );
    for t in tasks {
        for (name, settings) in &cfg.bench.configs {
            let acc = by.entry(name.clone()).or_default();
            for n in 1..=cfg.bench.runs {
                bar.step(&format!("{} {name} {n}/{}", t.id, cfg.bench.runs));
                let usage = one(&t.prompt, settings, cfg.bench.timeout_s);
                acc.input += usage.0;
                acc.cache += usage.1;
                acc.output += usage.2;
                acc.cost += usage.3;
                acc.n += 1;
            }
            if check_passes(t) {
                acc.pass += 1;
            }
        }
    }
    bar.finish();
    write_results(&by, tasks.len(), cfg);
    let mut s = format!(
        "{:<8} {:>10} {:>10} {:>10} {:>10} {:>8}\n",
        "config", "input", "cache", "output", "cost", "pass"
    );
    for (name, a) in &by {
        let n = a.n.max(1) as f64;
        s.push_str(&format!(
            "{name:<8} {:>10.0} {:>10.0} {:>10.0} {:>10.4} {:>3}/{:<3}\n",
            a.input as f64 / n,
            a.cache as f64 / n,
            a.output as f64 / n,
            a.cost / n,
            a.pass,
            tasks.len()
        ));
    }
    s
}

/// Whether the task's `check` command exits 0. Its output is kept, not shown: it would draw
/// over the progress bar and into the table on stdout.
fn check_passes(t: &Task) -> bool {
    ProgressRunner::new(&format!("checking {}…", t.id), "sh", ["-c", &t.check])
        .output(Output::Capture)
        .run()
        .is_ok_and(|done| done.status.success())
}

const GRAPH_ARMS: [&str; 2] = ["mcp", "native"];

#[derive(Deserialize)]
struct GraphFile {
    repos: Vec<GraphRepo>,
    questions: Vec<GraphQ>,
}
#[derive(Deserialize)]
struct GraphRepo {
    id: String,
    path: String,
}
#[derive(Deserialize)]
struct GraphQ {
    id: String,
    repo: String,
    prompt: String,
    expect: String,
}

fn graph_toml(cfg: &Config) -> PathBuf {
    let t = &cfg.bench.tasks;
    if t.file_name().is_some_and(|n| n == "graph.toml") {
        t.clone()
    } else {
        t.parent().unwrap_or(Path::new("bench")).join("graph.toml")
    }
}

fn load_graph(path: &Path) -> Result<GraphFile> {
    let f = Figment::new()
        .merge(Toml::file(path))
        .extract::<GraphFile>()
        .with_context(|| path.display().to_string())?;
    if f.questions.len() < 10 {
        bail!(
            "graph suite needs ≥ 10 questions, got {}",
            f.questions.len()
        );
    }
    if f.repos.len() < 3 {
        bail!("graph suite needs ≥ 3 repos, got {}", f.repos.len());
    }
    let ids: Vec<&str> = f.repos.iter().map(|r| r.id.as_str()).collect();
    for q in &f.questions {
        if !ids.contains(&q.repo.as_str()) {
            bail!("question {} repo {} is not in [[repos]]", q.id, q.repo);
        }
        regex::Regex::new(&q.expect).with_context(|| format!("{} expect", q.id))?;
    }
    Ok(f)
}

fn run_graph(cfg: &Config) -> Result<String> {
    let file = load_graph(&graph_toml(cfg))?;
    if cfg.bench.dry_run {
        let mut out = String::new();
        for q in &file.questions {
            for arm in GRAPH_ARMS {
                for n in 1..=cfg.bench.runs {
                    out.push_str(&format!("{} {} {arm} {n}\n", q.id, q.repo));
                }
            }
        }
        return Ok(out);
    }
    Ok(graph_table(&file, cfg))
}

fn graph_table(file: &GraphFile, cfg: &Config) -> String {
    let mut s = String::from("id repo arm tools in out cache wall_ms cost resident pass\n");
    let runs = cfg.bench.runs;
    let mut bar = ProgressRunner::batch(
        "bench",
        file.questions.len() as u64 * GRAPH_ARMS.len() as u64 * u64::from(runs),
        Unit::Items,
    );
    let mut tot = [(0u64, 0u64, 0u64, 0u64, 0u64, 0.0, 0u64, 0u32); 2];
    for q in &file.questions {
        let repo = file.repos.iter().find(|r| r.id == q.repo).unwrap();
        let re = regex::Regex::new(&q.expect).expect("checked at load");
        for (i, arm) in GRAPH_ARMS.iter().enumerate() {
            let mut a = (0u64, 0u64, 0u64, 0u64, 0u64, 0.0, 0u64, 0u32);
            for n in 1..=runs {
                bar.step(&format!("{} {arm} {n}/{runs}", q.id));
                let u = graph_one(&q.prompt, Path::new(&repo.path), arm, cfg);
                if re.is_match(&u.7) {
                    a.7 += 1;
                }
                a.0 += u.0;
                a.1 += u.1;
                a.2 += u.2;
                a.3 += u.3;
                a.4 += u.4;
                a.5 += u.5;
                a.6 += u.6;
            }
            let n = runs.max(1) as f64;
            s.push_str(&format!(
                "{} {} {arm} {:.0} {:.0} {:.0} {:.0} {:.0} {:.4} {:.0} {}/{runs}\n",
                q.id,
                q.repo,
                a.0 as f64 / n,
                a.1 as f64 / n,
                a.2 as f64 / n,
                a.3 as f64 / n,
                a.4 as f64 / n,
                a.5 / n,
                a.6 as f64 / n,
                a.7
            ));
            tot[i].0 += a.0;
            tot[i].1 += a.1;
            tot[i].2 += a.2;
            tot[i].3 += a.3;
            tot[i].4 += a.4;
            tot[i].5 += a.5;
            tot[i].6 += a.6;
            tot[i].7 += a.7;
        }
    }
    bar.finish();
    let n = (file.questions.len() as u32 * runs).max(1) as f64;
    for (i, arm) in GRAPH_ARMS.iter().enumerate() {
        let a = tot[i];
        s.push_str(&format!(
            "TOTAL * {arm} {:.0} {:.0} {:.0} {:.0} {:.0} {:.4} {:.0} {}/{n:.0}\n",
            a.0 as f64 / n,
            a.1 as f64 / n,
            a.2 as f64 / n,
            a.3 as f64 / n,
            a.4 as f64 / n,
            a.5 / n,
            a.6 as f64 / n,
            a.7
        ));
    }
    s
}

fn graph_one(
    prompt: &str,
    repo: &Path,
    arm: &str,
    cfg: &Config,
) -> (u64, u64, u64, u64, u64, f64, u64, String) {
    let z = (0, 0, 0, 0, 0, 0.0, 0, String::new());
    if std::env::var("RTOK_BENCH_LIVE").is_err() {
        return z;
    }
    let start = Instant::now();
    let prefixed = if arm == "mcp" {
        format!("Prefer rtok MCP graph tools (symbol, callers, impact, outline, explore). {prompt}")
    } else {
        format!("Use only Read and Grep. Do not use MCP. {prompt}")
    };
    let mut args: Vec<String> = ["-p", prefixed.as_str(), "--output-format", "json"]
        .map(String::from)
        .into();
    let mcp = std::env::temp_dir().join(format!("rtok-bench-mcp-{}.json", std::process::id()));
    if arm == "mcp" {
        let _ = std::fs::write(
            &mcp,
            r#"{"mcpServers":{"rtok":{"command":"rtok","args":["mcp"]}}}"#,
        );
        args.extend(["--mcp-config".into(), mcp.display().to_string()]);
    } else {
        args.extend(["--allowedTools".into(), "Read,Grep,Glob".into()]);
    }
    let cmd = ProgressRunner::new("running claude -p…", "claude", args).cwd(repo);
    let Some(out) = run_bounded(cmd, Duration::from_secs(cfg.bench.timeout_s.max(1))) else {
        return z;
    };
    let v: Value = serde_json::from_slice(&out).unwrap_or(Value::Null);
    let u = v.get("usage").cloned().unwrap_or(Value::Null);
    let input = num(&u, "input_tokens");
    let cache = num(&u, "cache_read_input_tokens");
    let write = num(&u, "cache_creation_input_tokens");
    let output = num(&u, "output_tokens");
    let tools = count_tool_uses(&v);
    let wall = v
        .get("duration_ms")
        .and_then(Value::as_u64)
        .unwrap_or(start.elapsed().as_millis() as u64);
    let model = v.get("model").and_then(Value::as_str).unwrap_or("");
    let cost = cfg
        .stats
        .prices
        .get(model)
        .map(|p| {
            crate::measure::stats::row_cost(
                input as i64,
                write as i64,
                cache as i64,
                output as i64,
                p,
            )
            .0
        })
        .unwrap_or(0.0);
    let result = v
        .get("result")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    (
        tools,
        input,
        output,
        cache,
        wall,
        cost,
        input + cache,
        result,
    )
}

fn count_tool_uses(v: &Value) -> u64 {
    match v {
        Value::Object(m) => {
            u64::from(m.get("type").and_then(Value::as_str) == Some("tool_use"))
                + m.values().map(count_tool_uses).sum::<u64>()
        }
        Value::Array(a) => a.iter().map(count_tool_uses).sum(),
        _ => 0,
    }
}

fn write_results(by: &BTreeMap<String, Acc>, tasks: usize, cfg: &Config) {
    if cfg!(test) {
        return;
    }
    let dir = cfg
        .bench
        .tasks
        .parent()
        .unwrap_or(Path::new("bench"))
        .join("results");
    let _ = std::fs::create_dir_all(&dir);
    let live = std::env::var("RTOK_BENCH_LIVE").is_ok();
    for (name, a) in by {
        let n = a.n.max(1) as f64;
        let v = serde_json::json!({
            "config": name,
            "runs": a.n,
            "mean_input": a.input as f64 / n,
            "mean_cache": a.cache as f64 / n,
            "mean_output": a.output as f64 / n,
            "mean_cost_usd": a.cost / n,
            "pass": a.pass,
            "tasks": tasks,
            "live": live,
        });
        let path = dir.join(format!("{name}.json"));
        let _ = std::fs::write(
            &path,
            serde_json::to_string_pretty(&v).unwrap_or_default() + "\n",
        );
    }
}

/// `(input, cache, output, cost)` from `claude -p` JSON, or zeros if it cannot run or runs
/// past `timeout_s` — a host that hangs is one zero row, not a bench that never returns.
fn one(prompt: &str, settings: &Path, timeout_s: u64) -> (u64, u64, u64, f64) {
    if !settings.exists() || std::env::var("RTOK_BENCH_LIVE").is_err() {
        return (0, 0, 0, 0.0);
    }
    let cmd = ProgressRunner::new(
        "running claude -p…",
        "claude",
        [
            "-p",
            prompt,
            "--output-format",
            "json",
            "--settings",
            &settings.display().to_string(),
        ],
    );
    let Some(out) = run_bounded(cmd, Duration::from_secs(timeout_s.max(1))) else {
        return (0, 0, 0, 0.0);
    };
    parse_usage(&out)
}

/// Most stdout kept from one run; a run's JSON result is a few KiB, so a flood is a runaway.
const MAX_OUTPUT: u64 = 8 << 20;

/// Run `cmd` and collect its stdout, killing it once `timeout` has passed. `None` when it
/// could not be spawned, exited badly, or was killed. After the child exits only a finite
/// drain of its pipe follows (`ProgressRunner`), so a descendant holding stdout open cannot
/// stall the bench.
fn run_bounded(cmd: ProgressRunner, timeout: Duration) -> Option<Vec<u8>> {
    let (done, _) = cmd.timeout(timeout).run_capped(MAX_OUTPUT).ok()?;
    done.status.success().then_some(done.stdout)
}

fn parse_usage(bytes: &[u8]) -> (u64, u64, u64, f64) {
    let v: Value = serde_json::from_slice(bytes).unwrap_or(Value::Null);
    let u = v.get("usage").cloned().unwrap_or(Value::Null);
    (
        num(&u, "input_tokens"),
        num(&u, "cache_read_input_tokens") + num(&u, "cache_creation_input_tokens"),
        num(&u, "output_tokens"),
        v.get("total_cost_usd")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
    )
}

fn num(v: &Value, k: &str) -> u64 {
    v.get(k).and_then(Value::as_u64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn cfg(dry: bool) -> Config {
        let mut c = Config::default();
        c.bench.tasks = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("bench/tasks.toml");
        c.bench.dry_run = dry;
        c
    }

    #[test]
    fn graph_dry_run_lists_each_question_on_both_arms() {
        let mut c = cfg(true);
        c.bench.suite = "graph".into();
        c.bench.runs = 1;
        let s = run(&c).unwrap();
        assert!(s.lines().count() >= 20, "{s}");
        assert!(s.contains("plugin rtok mcp 1"), "{s}");
        assert!(s.contains("config-class mini-py native 1"), "{s}");
    }

    #[test]
    fn graph_offline_table_names_the_metrics() {
        let mut c = cfg(false);
        c.bench.suite = "graph".into();
        c.bench.runs = 1;
        let s = run(&c).unwrap();
        assert!(
            s.contains("tools") && s.contains("resident") && s.contains("TOTAL"),
            "{s}"
        );
        assert!(s.contains("0/1"), "{s}");
    }

    #[test]
    fn dry_run_lists_six_by_two_by_three() {
        let s = run(&cfg(true)).unwrap();
        let lines: Vec<_> = s.lines().collect();
        assert_eq!(lines.len(), 6 * 2 * 3, "{s}");
        assert!(
            s.contains("add-fn a 1") && s.contains("run-tests b 3"),
            "{s}"
        );
    }

    #[test]
    fn real_run_prints_a_table() {
        let s = run(&cfg(false)).unwrap();
        assert!(s.contains("config"), "{s}");
        assert!(s.contains("input") && s.contains("pass"), "{s}");
        assert!(s.lines().any(|l| l.starts_with('a')), "{s}");
        assert!(s.lines().any(|l| l.starts_with('b')), "{s}");
    }

    /// `[bench] timeout_s` was read by nothing, so a hung host held `rtok bench` forever.
    #[test]
    fn a_hung_run_is_killed_instead_of_hanging_the_bench() {
        // Spawn `sleep` directly: `sh -c 'sleep 30'` leaves the sleep child holding
        // the pipe after `kill` on the shell, so the reader thread waited the full 30s.
        let slow = ProgressRunner::quiet("sleep", ["30"]);
        let start = Instant::now();
        assert!(run_bounded(slow, Duration::from_millis(150)).is_none());
        assert!(
            start.elapsed() < Duration::from_secs(10),
            "killed, not waited out"
        );
    }

    #[test]
    fn a_finished_run_hands_back_its_output() {
        let quick = ProgressRunner::quiet("sh", ["-c", "printf hello"]);
        let out = run_bounded(quick, Duration::from_secs(30)).expect("runs");
        assert_eq!(out, b"hello");
    }

    /// More than a pipe buffer of output used to deadlock the child and score as a timeout.
    #[test]
    fn a_run_past_the_pipe_buffer_is_drained_not_timed_out() {
        let big = ProgressRunner::quiet("sh", ["-c", "head -c 300000 /dev/zero"]);
        let out = run_bounded(big, Duration::from_secs(10)).expect("runs");
        assert_eq!(out.len(), 300_000);
    }

    /// T638: a descendant that outlives the child keeps the pipe open; the drain after the
    /// child's exit is finite, so the bench neither stalls nor loses the child's output.
    /// The run budget only has to cover `sh` starting (slow on Windows runners); the stall
    /// this guards against would wait out the descendant's `sleep`, far past the bound.
    #[test]
    fn a_descendant_holding_the_pipe_does_not_stall_the_bench() {
        let cmd = ProgressRunner::quiet("sh", ["-c", "sleep 10 & printf parent; exit 0"]);
        let start = Instant::now();
        let out = run_bounded(cmd, Duration::from_secs(3)).expect("child exited cleanly");
        assert_eq!(out, b"parent");
        assert!(
            start.elapsed() < Duration::from_secs(6),
            "{:?}",
            start.elapsed()
        );
    }

    #[test]
    fn a_failing_run_is_not_a_result() {
        let bad = ProgressRunner::quiet("sh", ["-c", "printf partial; exit 3"]);
        assert!(run_bounded(bad, Duration::from_secs(30)).is_none());
    }

    fn p28_dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("bench/p28")
    }

    /// T385.9.3: the Gate P28 overlay (`research.md` §16.11) runs two arms whose settings
    /// differ only in the proxy they send `claude -p` to; the proxies differ only in the flag.
    #[test]
    fn p28_overlay_runs_two_arms_that_differ_only_in_their_proxy() {
        let home = crate::testutil::tmp_dir("bench-p28");
        let dir = p28_dir();
        let mut c =
            crate::config::layers::load(&home, Some(&dir.join("bench.toml")), None).unwrap();
        let arms: Vec<&str> = c.bench.configs.keys().map(String::as_str).collect();
        assert_eq!(arms, ["a", "b"]);
        let mut rest = Vec::new();
        let mut urls = Vec::new();
        for path in c.bench.configs.values() {
            let path = dir.join(path);
            let body = std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
            let mut v: Value = serde_json::from_slice(&body).unwrap();
            let url = v["env"]
                .as_object_mut()
                .unwrap()
                .remove("ANTHROPIC_BASE_URL");
            urls.push(url.and_then(|u| u.as_str().map(String::from)).unwrap());
            rest.push(v);
        }
        assert_eq!(urls, ["http://127.0.0.1:8811", "http://127.0.0.1:8812"]);
        assert_eq!(rest[0], rest[1], "only the proxy URL may differ");
        c.bench.tasks = dir.join(&c.bench.tasks);
        c.bench.dry_run = true;
        c.bench.runs = 1;
        assert_eq!(run(&c).unwrap().lines().count(), 6 * 2);
        let _ = std::fs::remove_dir_all(&home);
    }

    /// T385.9.3: every task's answer is a must-keep span of an archivable, non-code log (so
    /// the compressor takes it and the guard makes its summary keep the answer) that neither
    /// the pointer's head/tail nor the extractive view shows, and a summary carrying every span
    /// of that log still fits under the pointer.
    #[test]
    fn p28_tasks_ask_for_a_must_keep_span_no_cheaper_view_shows() {
        use crate::plugins::compress::{extractive_summary, llm::is_code, must_keep::spans};

        #[derive(Deserialize)]
        struct P28 {
            tasks: Vec<P28Task>,
        }
        #[derive(Deserialize)]
        struct P28Task {
            id: String,
            log: String,
            answer: String,
            prompt: String,
            check: String,
        }
        let dir = p28_dir();
        let cfg = Config::default();
        let (archive, llm) = (&cfg.plugins.archive, &cfg.plugins.compress.llm);
        let mut logs: Vec<String> = std::fs::read_dir(dir.join("logs"))
            .unwrap()
            .map(|e| format!("logs/{}", e.unwrap().file_name().to_string_lossy()))
            .collect();
        logs.sort();
        assert_eq!(logs.len(), 8);
        for log in &logs {
            let text = std::fs::read_to_string(dir.join(log)).unwrap();
            let est = crate::tokens::estimate(&text, rtok_plugin_sdk::Class::Code, &cfg.estimator);
            assert!(
                est >= archive.min_tokens,
                "{log}: {est} tokens is never archived"
            );
            assert!(
                !is_code(&text),
                "{log}: code is left to the extractive view"
            );
            assert!(spans(&text).len() <= llm.max_spans as usize, "{log}");
        }
        let file: P28 = Figment::new()
            .merge(Toml::file(dir.join("tasks.toml")))
            .extract()
            .unwrap();
        assert_eq!(file.tasks.len(), 6);
        for t in &file.tasks {
            let id = &t.id;
            let at: Vec<usize> = logs
                .iter()
                .map(|l| t.prompt.find(l.as_str()).unwrap())
                .collect();
            assert!(at.is_sorted(), "{id}: the prompt reads every log in order");
            assert!(
                logs[..3].contains(&t.log),
                "{id}: the fact is in one of the oldest logs"
            );
            assert!(t.check.contains(&format!("'{}'", t.answer)), "{id}");
            assert!(t.check.ends_with("rm -f answer.txt; exit $s"), "{id}");
            let text = std::fs::read_to_string(dir.join(&t.log)).unwrap();
            let lines: Vec<&str> = text.lines().collect();
            let hits: Vec<usize> = (0..lines.len())
                .filter(|&i| lines[i].contains(&t.answer))
                .collect();
            assert_eq!(hits.len(), 1, "{id}: the answer is on one line");
            let (head, tail) = (archive.head_lines as usize, archive.tail_lines as usize);
            assert!(
                (head..lines.len() - tail).contains(&hits[0]),
                "{id}: shown by the pointer"
            );
            assert!(
                !extractive_summary("id", &text).contains(&t.answer),
                "{id}: extractive view"
            );
            let all = spans(&text);
            assert!(
                all.iter().any(|s| s.text.contains(&t.answer)),
                "{id}: not a must-keep span"
            );
            let kept: usize = all.iter().map(|s| s.text.len() + 2).sum();
            let pointer: usize = lines[..head]
                .iter()
                .chain(&lines[lines.len() - tail..])
                .map(|l| l.len() + 1)
                .sum();
            assert!(
                kept + 80 < pointer,
                "{id}: {kept} span bytes cannot fit under a {pointer}-byte pointer"
            );
        }
    }
}

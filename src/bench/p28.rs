// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok bench --suite p28` (T385.9.3): Gate P28, the LLM compressor against the lossless
//! archive pointer. Each arm is a `claude --settings` file aimed at its own `rtok proxy`, and
//! that proxy's `RTOK_HOME`, read after the runs for the compressor's `internal`-lane tokens,
//! its summaries checked against the must-keep fixture, and `expand` recovery.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use figment::Figment;
use figment::providers::{Format, Toml};
use rtok_store::Store;
use rtok_sys::proc::{ProgressRunner, Unit};
use serde::Deserialize;
use serde_json::Value;

use crate::config::{Config, ModelPrice};

#[derive(Deserialize)]
struct P28File {
    fixture: PathBuf,
    arms: Vec<Arm>,
    tasks: Vec<P28Task>,
}

#[derive(Deserialize)]
struct Arm {
    id: String,
    settings: PathBuf,
    home: PathBuf,
}

#[derive(Deserialize)]
struct P28Task {
    id: String,
    first: String,
    question: String,
    expect: String,
}

#[derive(Deserialize)]
struct Fixture {
    body: Vec<Body>,
}

#[derive(Deserialize)]
struct Body {
    id: String,
    text: String,
    keep: Vec<Keep>,
}

#[derive(Deserialize)]
struct Keep {
    text: String,
}

/// What one arm's proxy store holds after (or before) the runs.
#[derive(Default, Clone, Copy)]
struct ArmStore {
    /// `internal`-lane tokens, all four legs.
    tokens: i64,
    /// USD over the priced `internal`-lane models.
    cost: f64,
    /// Some `internal`-lane model has no `[stats.prices]` row, so `cost` is a floor.
    partial: bool,
    summaries: usize,
    /// Summaries that keep every fixture span of the body they summarise.
    kept: usize,
    /// Summaries whose original `expand` still returns with every fixture span.
    recovered: usize,
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

fn p28_toml(cfg: &Config) -> PathBuf {
    let t = &cfg.bench.tasks;
    if t.file_name().is_some_and(|n| n == "p28.toml") {
        t.clone()
    } else {
        t.parent().unwrap_or(Path::new("bench")).join("p28.toml")
    }
}

/// The suite file with its paths resolved against its own directory, and the fixture bodies.
fn load(path: &Path) -> Result<(P28File, Vec<Body>)> {
    let mut f = Figment::new()
        .merge(Toml::file(path))
        .extract::<P28File>()
        .with_context(|| path.display().to_string())?;
    let dir = path.parent().unwrap_or(Path::new("."));
    f.fixture = dir.join(&f.fixture);
    for arm in &mut f.arms {
        arm.settings = dir.join(&arm.settings);
        arm.home = dir.join(&arm.home);
    }
    let bodies = Figment::new()
        .merge(Toml::file(&f.fixture))
        .extract::<Fixture>()
        .with_context(|| f.fixture.display().to_string())?
        .body;
    if f.arms.len() < 2 {
        bail!("p28 suite needs ≥ 2 arms, got {}", f.arms.len());
    }
    for (i, a) in f.arms.iter().enumerate() {
        // A shared id merges two arms' totals; a shared home mixes their compressor spend.
        if f.arms[..i].iter().any(|b| b.id == a.id || b.home == a.home) {
            bail!("arm {} repeats an earlier arm's id or home", a.id);
        }
    }
    if f.tasks.is_empty() {
        bail!("p28 suite has no tasks");
    }
    for t in &f.tasks {
        let Some(b) = bodies.iter().find(|b| b.id == t.first) else {
            bail!("task {} first {} is not a fixture body", t.id, t.first);
        };
        let re = regex::Regex::new(&t.expect).with_context(|| format!("{} expect", t.id))?;
        // The answer must be a span the compressor is bound to keep, or the gate tests nothing.
        if !b.keep.iter().any(|k| re.is_match(&k.text)) {
            bail!(
                "task {} expect matches no must-keep span of {}",
                t.id,
                t.first
            );
        }
    }
    Ok((f, bodies))
}

pub(super) fn run(cfg: &Config) -> Result<String> {
    let (file, bodies) = load(&p28_toml(cfg))?;
    if cfg.bench.dry_run {
        let mut out = String::new();
        for t in &file.tasks {
            for arm in &file.arms {
                for n in 1..=cfg.bench.runs {
                    out.push_str(&format!("{} {} {n}\n", t.id, arm.id));
                }
            }
        }
        return Ok(out);
    }
    table(&file, &bodies, cfg)
}

fn table(file: &P28File, bodies: &[Body], cfg: &Config) -> Result<String> {
    let runs = cfg.bench.runs;
    let prices = &cfg.stats.prices;
    let before: Vec<ArmStore> = file
        .arms
        .iter()
        .map(|a| arm_store(&a.home, bodies, prices))
        .collect::<Result<_>>()?;
    let mut bar = ProgressRunner::batch(
        "bench",
        (file.tasks.len() * file.arms.len()) as u64 * u64::from(runs),
        Unit::Items,
    );
    let mut s = String::from("id arm in cache out usd pass\n");
    let mut by: BTreeMap<&str, Acc> = BTreeMap::new();
    for t in &file.tasks {
        let re = regex::Regex::new(&t.expect).expect("checked at load");
        let prompt = prompt(t, bodies);
        for arm in &file.arms {
            let mut a = Acc::default();
            for n in 1..=runs {
                bar.step(&format!("{} {} {n}/{runs}", t.id, arm.id));
                let (usage, answer) = one(&prompt, bodies, arm, cfg.bench.timeout_s);
                a.input += usage.0;
                a.cache += usage.1;
                a.output += usage.2;
                a.cost += usage.3;
                a.n += 1;
                if re.is_match(&answer) {
                    a.pass += 1;
                }
            }
            let n = f64::from(a.n.max(1));
            s.push_str(&format!(
                "{} {} {:.0} {:.0} {:.0} {:.4} {}/{runs}\n",
                t.id,
                arm.id,
                a.input as f64 / n,
                a.cache as f64 / n,
                a.output as f64 / n,
                a.cost / n,
                a.pass
            ));
            let tot = by.entry(&arm.id).or_default();
            tot.input += a.input;
            tot.cache += a.cache;
            tot.output += a.output;
            tot.cost += a.cost;
            tot.pass += a.pass;
            tot.n += a.n;
        }
    }
    bar.finish();
    s.push_str(
        "\narm runs pass agent_usd compressor_tokens compressor_usd usd_per_pass summaries must_keep expand\n",
    );
    for (arm, was) in file.arms.iter().zip(before) {
        let now = arm_store(&arm.home, bodies, prices)?;
        let a = &by[arm.id.as_str()];
        let compressor = now.cost - was.cost;
        let per_pass = if a.pass == 0 {
            "-".to_string()
        } else {
            format!("{:.4}", (a.cost + compressor) / f64::from(a.pass))
        };
        s.push_str(&format!(
            "{} {} {}/{} {:.4} {} {:.4}{} {per_pass} {} {}/{} {}/{}\n",
            arm.id,
            a.n,
            a.pass,
            a.n,
            a.cost,
            now.tokens - was.tokens,
            compressor,
            if now.partial { "+" } else { "" },
            now.summaries,
            now.kept,
            now.summaries,
            now.recovered,
            now.summaries
        ));
    }
    Ok(s)
}

/// Every body by file name, `first` first, so it is the oldest result when the question comes.
fn prompt(t: &P28Task, bodies: &[Body]) -> String {
    let files: Vec<String> = std::iter::once(t.first.as_str())
        .chain(
            bodies
                .iter()
                .map(|b| b.id.as_str())
                .filter(|id| *id != t.first),
        )
        .map(|id| format!("{id}.txt"))
        .collect();
    format!(
        "Read each of these files with the Read tool, one file per call, in this order: {}. \
         Then answer in one line: {} A shortened tool result names an id; \
         `rtok expand <id>` prints the original.",
        files.join(", "),
        t.question
    )
}

/// One `claude -p` run in a fresh directory holding the fixture bodies: `(input, cache,
/// output, cost)` and the final answer, or zeros and `""` when it cannot run.
fn one(prompt: &str, bodies: &[Body], arm: &Arm, timeout_s: u64) -> ((u64, u64, u64, f64), String) {
    let none = ((0, 0, 0, 0.0), String::new());
    if !arm.settings.exists() || std::env::var("RTOK_BENCH_LIVE").is_err() {
        return none;
    }
    let dir = std::env::temp_dir().join(format!("rtok-p28-{}-{}", std::process::id(), arm.id));
    let _ = std::fs::remove_dir_all(&dir);
    if std::fs::create_dir_all(&dir).is_err()
        || bodies
            .iter()
            .any(|b| std::fs::write(dir.join(format!("{}.txt", b.id)), &b.text).is_err())
    {
        return none;
    }
    let settings = arm.settings.display().to_string();
    let cmd = ProgressRunner::new(
        "running claude -p…",
        "claude",
        [
            "-p",
            prompt,
            "--output-format",
            "json",
            "--settings",
            &settings,
            "--allowedTools",
            "Read,Bash(rtok expand:*)",
        ],
    )
    .cwd(&dir);
    let out = super::run_bounded(cmd, Duration::from_secs(timeout_s.max(1)));
    let _ = std::fs::remove_dir_all(&dir);
    let Some(out) = out else {
        return none;
    };
    let answer = serde_json::from_slice::<Value>(&out)
        .ok()
        .and_then(|v| v.get("result").and_then(Value::as_str).map(String::from))
        .unwrap_or_default();
    (super::parse_usage(&out), answer)
}

/// The arm proxy's store at `home/rtok.db`; all zeros when it does not exist yet (the proxy
/// never ran), never created here.
fn arm_store(
    home: &Path,
    bodies: &[Body],
    prices: &BTreeMap<String, ModelPrice>,
) -> Result<ArmStore> {
    let db = home.join("rtok.db");
    if !db.exists() {
        return Ok(ArmStore::default());
    }
    let store = Store::open(&db).with_context(|| db.display().to_string())?;
    let mut r = ArmStore::default();
    for row in store.usage_by_lane_model_tier()? {
        if row.kind.as_deref().map(crate::lane::lane_of_kind) != Some("internal") {
            continue;
        }
        let u = &row.usage;
        r.tokens += u.input + u.cache_create + u.cache_read + u.output;
        match prices.get(&u.model) {
            Some(p) => {
                r.cost += crate::measure::stats::row_cost(
                    u.input,
                    u.cache_create,
                    u.cache_read,
                    u.output,
                    p,
                )
                .0;
            }
            None => r.partial = true,
        }
    }
    for (id, summary) in store.archive_summaries()? {
        r.summaries += 1;
        let Some(original) = store.get_archive(&id, Some(&home.join("archive")))? else {
            continue;
        };
        let original = String::from_utf8_lossy(&original);
        // The archived Read output carries line numbers, so the body is found by its spans.
        let Some(b) = bodies
            .iter()
            .find(|b| b.keep.iter().all(|k| original.contains(&k.text)))
        else {
            continue;
        };
        r.recovered += 1;
        if b.keep.iter().all(|k| summary.contains(&k.text)) {
            r.kept += 1;
        }
    }
    Ok(r)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shipped() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("bench/p28.toml")
    }

    fn scratch(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("rtok-p28-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// The shipped suite, rewritten into `dir` with its arm homes there too, so a test never
    /// reads a real bench store under `/tmp/rtok-p28`.
    fn suite_in(dir: &Path, tasks: &str) -> PathBuf {
        let fixture =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/p28_must_keep.toml");
        let path = dir.join("p28.toml");
        std::fs::write(
            &path,
            format!(
                "fixture = {:?}\n\
                 [[arms]]\nid = \"lossless\"\nsettings = \"none.json\"\nhome = \"lossless\"\n\
                 [[arms]]\nid = \"llm\"\nsettings = \"none.json\"\nhome = \"llm\"\n{tasks}",
                fixture.display().to_string()
            ),
        )
        .unwrap();
        path
    }

    fn cfg(tasks: PathBuf, dry: bool) -> Config {
        let mut c = Config::default();
        c.bench.tasks = tasks;
        c.bench.suite = "p28".into();
        c.bench.dry_run = dry;
        c.bench.runs = 1;
        c
    }

    #[test]
    fn dry_run_lists_each_task_on_each_arm() {
        let mut c = cfg(shipped(), true);
        c.bench.runs = 2;
        let s = super::super::run(&c).unwrap();
        assert_eq!(s.lines().count(), 6 * 2 * 2, "{s}");
        assert!(s.contains("build-e0599 lossless 1\n"), "{s}");
        assert!(s.contains("note-feature llm 2\n"), "{s}");
    }

    /// Each shipped answer sits where the lossless pointer (8 head, 4 tail lines) hides it,
    /// so only `expand` or a summary that kept the span can pass.
    #[test]
    fn every_shipped_answer_is_outside_the_pointer_view() {
        let (file, bodies) = load(&shipped()).unwrap();
        for t in &file.tasks {
            let b = bodies.iter().find(|b| b.id == t.first).unwrap();
            let re = regex::Regex::new(&t.expect).unwrap();
            let lines: Vec<&str> = b.text.lines().collect();
            let shown = lines[..8].iter().chain(&lines[lines.len() - 4..]);
            assert!(
                !shown.into_iter().any(|l| re.is_match(l)),
                "{} visible",
                t.id
            );
        }
    }

    /// The arm proxies load, sit on the ports their settings files name, and differ only in
    /// the compressor switch, so the compressor is the one variable.
    #[test]
    fn the_arm_proxies_differ_only_in_the_compressor() {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("bench/p28");
        let arm = |id: &str| -> (Config, String) {
            let c: Config = Figment::new()
                .merge(Toml::file(dir.join(format!("{id}.toml"))))
                .extract()
                .unwrap();
            (
                c,
                std::fs::read_to_string(dir.join(format!("{id}.json"))).unwrap(),
            )
        };
        let (lossless, lossless_json) = arm("lossless");
        let (llm, llm_json) = arm("llm");
        for (c, json) in [(&lossless, &lossless_json), (&llm, &llm_json)] {
            assert_eq!(c.proxy.mode, "compress");
            assert_eq!(
                (c.plugins.archive.keep_turns, c.plugins.archive.min_tokens),
                (2, 300)
            );
            assert!(
                json.contains(&format!("http://127.0.0.1:{}\"", c.proxy.port)),
                "{json}"
            );
        }
        assert_ne!(lossless.proxy.port, llm.proxy.port);
        assert!(!lossless.plugins.compress.llm.enabled && llm.plugins.compress.llm.enabled);
        let (load, _) = load(&shipped()).unwrap();
        let homes: Vec<_> = load.arms.iter().map(|a| &a.home).collect();
        assert_ne!(homes[0], homes[1], "each arm reads its own store");
    }

    #[test]
    fn an_answer_that_is_not_a_must_keep_span_is_refused() {
        let dir = scratch("refuse");
        let path = suite_in(
            &dir,
            "[[tasks]]\nid = \"x\"\nfirst = \"grep-records\"\nquestion = \"?\"\nexpect = 'Compiling'\n",
        );
        let e = load(&path).err().unwrap().to_string();
        assert!(e.contains("matches no must-keep span"), "{e}");
        let path = suite_in(
            &dir,
            "[[tasks]]\nid = \"x\"\nfirst = \"nope\"\nquestion = \"?\"\nexpect = '1'\n",
        );
        let e = load(&path).err().unwrap().to_string();
        assert!(e.contains("is not a fixture body"), "{e}");
        let path = suite_in(
            &dir,
            "[[arms]]\nid = \"llm\"\nsettings = \"x.json\"\nhome = \"elsewhere\"\n\
             [[tasks]]\nid = \"x\"\nfirst = \"grep-records\"\nquestion = \"?\"\nexpect = '402'\n",
        );
        let e = load(&path).err().unwrap().to_string();
        assert!(e.contains("repeats an earlier arm"), "{e}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_prompt_reads_first_first_and_every_file_once() {
        let (file, bodies) = load(&shipped()).unwrap();
        let t = &file.tasks[2];
        let p = prompt(t, &bodies);
        assert!(p.contains(&format!("order: {}.txt, ", t.first)), "{p}");
        for b in &bodies {
            assert_eq!(p.matches(&format!("{}.txt", b.id)).count(), 1, "{p}");
        }
        assert!(p.ends_with("prints the original."), "{p}");
    }

    #[test]
    fn offline_table_names_the_gate_columns() {
        let dir = scratch("offline");
        let path = suite_in(
            &dir,
            "[[tasks]]\nid = \"log-addr\"\nfirst = \"service-log-trace\"\nquestion = \"?\"\nexpect = '10\\.20'\n",
        );
        let s = super::super::run(&cfg(path, false)).unwrap();
        assert!(s.contains("log-addr lossless 0 0 0 0.0000 0/1"), "{s}");
        assert!(
            s.contains("compressor_tokens") && s.contains("usd_per_pass"),
            "{s}"
        );
        assert!(
            s.contains("\nllm 1 0/1 0.0000 0 0.0000 - 0 0/0 0/0\n"),
            "{s}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The arm store report: internal-lane spend only, a summary that drops a fixture span
    /// is not kept, and one whose original is gone is not recovered.
    #[test]
    fn arm_store_prices_the_internal_lane_and_checks_each_summary() {
        let dir = scratch("store");
        let fixture =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/p28_must_keep.toml");
        let bodies = Figment::new()
            .merge(Toml::file(&fixture))
            .extract::<Fixture>()
            .unwrap()
            .body;
        let store = Store::open(&dir.join("rtok.db")).unwrap();
        store
            .upsert_session("s", None, None, None, Some("proxy"))
            .unwrap();
        for kind in ["api_request", "api_request:internal"] {
            let call = store
                .insert_call("s", "proxy", kind, None, None, None, None, Some("/x"))
                .unwrap();
            store
                .insert_usage(
                    "s",
                    Some("claude-haiku-4-5"),
                    "anthropic",
                    1_000_000,
                    0,
                    0,
                    0,
                    call,
                )
                .unwrap();
        }
        let archive = dir.join("archive");
        let numbered = |b: &Body| -> String {
            b.text
                .lines()
                .enumerate()
                .map(|(i, l)| format!("{:>6}\t{l}\n", i + 1))
                .collect()
        };
        let all: String = bodies[3]
            .keep
            .iter()
            .map(|k| format!("{}\n", k.text))
            .collect();
        let kept = store
            .put_archive("s", numbered(&bodies[3]).as_bytes(), &archive)
            .unwrap();
        store.put_archive_summary(&kept, "v", &all).unwrap();
        let dropped = store
            .put_archive("s", numbered(&bodies[5]).as_bytes(), &archive)
            .unwrap();
        store
            .put_archive_summary(&dropped, "v", "it failed")
            .unwrap();
        let gone = store
            .put_archive("s", numbered(&bodies[1]).as_bytes(), &archive)
            .unwrap();
        store.put_archive_summary(&gone, "v", &all).unwrap();
        std::fs::remove_file(archive.join(&gone)).unwrap();
        drop(store);

        let prices = Config::default().stats.prices;
        let r = arm_store(&dir, &bodies, &prices).unwrap();
        assert_eq!(r.tokens, 1_000_000, "agent lane not counted");
        assert!(r.cost > 0.0 && !r.partial);
        assert_eq!((r.summaries, r.recovered, r.kept), (3, 2, 1));
        let none = arm_store(&dir.join("absent"), &bodies, &prices).unwrap();
        assert_eq!(none.summaries, 0);
        assert!(!dir.join("absent/rtok.db").exists(), "never created");
        let _ = std::fs::remove_dir_all(&dir);
    }
}

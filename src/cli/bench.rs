// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use crate::render::with_loader;
use anyhow::Result;
use std::path::PathBuf;

fn bench_flags(
    tasks: Option<std::path::PathBuf>,
    runs: Option<u32>,
    dry_run: bool,
    timeout: Option<u64>,
    suite: Option<String>,
) -> Option<figment::value::Dict> {
    if tasks.is_none() && runs.is_none() && !dry_run && timeout.is_none() && suite.is_none() {
        return None;
    }
    use figment::value::{Dict, Value};
    let mut bench = Dict::new();
    if let Some(p) = tasks {
        bench.insert(
            "tasks".into(),
            Value::from(p.to_string_lossy().into_owned()),
        );
    }
    if let Some(n) = runs {
        bench.insert("runs".into(), Value::from(i64::from(n)));
    }
    if dry_run {
        bench.insert("dry_run".into(), Value::from(true));
    }
    if let Some(s) = timeout {
        bench.insert(
            "timeout_s".into(),
            Value::from(i64::try_from(s).unwrap_or(i64::MAX)),
        );
    }
    if let Some(s) = suite {
        bench.insert("suite".into(), Value::from(s));
    }
    let mut flags = Dict::new();
    flags.insert("bench".into(), Value::from(bench));
    Some(flags)
}

#[derive(clap::Args)]
pub(super) struct Args {
    /// Task list TOML
    #[arg(long)]
    pub(super) tasks: Option<std::path::PathBuf>,
    /// Repeats per task × config
    #[arg(long)]
    pub(super) runs: Option<u32>,
    /// Print the schedule and exit
    #[arg(long)]
    pub(super) dry_run: bool,
    /// Per-run timeout in seconds
    #[arg(long)]
    pub(super) timeout: Option<u64>,
    /// Task suite (`graph` = with/without rtok MCP)
    #[arg(long)]
    pub(super) suite: Option<String>,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args {
        tasks,
        runs,
        dry_run,
        timeout,
        suite,
    } = args;
    let cfg = Config::load_with(
        config_file.as_deref(),
        bench_flags(tasks, runs, dry_run, timeout, suite),
    )?;
    print!(
        "{}",
        with_loader("running bench", || crate::bench::run(&cfg))?
    );

    Ok(())
}

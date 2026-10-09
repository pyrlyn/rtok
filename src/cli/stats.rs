// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use anyhow::Result;
use std::path::PathBuf;

fn stats_flags(
    since: Option<String>,
    json: bool,
    plugin: Option<String>,
    compare: Option<String>,
    price: bool,
) -> Option<figment::value::Dict> {
    use figment::value::{Dict, Value};
    let mut stats = Dict::new();
    if let Some(s) = since {
        stats.insert("since".into(), Value::from(s));
    }
    if json {
        stats.insert("format".into(), Value::from("json"));
    }
    if let Some(p) = plugin {
        stats.insert("plugin".into(), Value::from(p));
    }
    if let Some(c) = compare {
        stats.insert("baseline".into(), Value::from(c));
    }
    if price {
        stats.insert("price".into(), Value::from(true));
    }
    if stats.is_empty() {
        return None;
    }
    let mut flags = Dict::new();
    flags.insert("stats".into(), Value::from(stats));
    Some(flags)
}

#[derive(clap::Args)]
pub(super) struct Args {
    /// How far back to read transcripts (`60d`, `24h`)
    #[arg(long)]
    pub(super) since: Option<String>,
    /// JSON instead of the table
    #[arg(long)]
    pub(super) json: bool,
    /// Restrict to one tool or plugin id
    #[arg(long)]
    pub(super) plugin: Option<String>,
    /// Write this report JSON to `<home>/measurements/<name>.json`
    #[arg(long, value_name = "NAME")]
    pub(super) save_baseline: Option<String>,
    /// Print deltas against a saved baseline
    #[arg(long, value_name = "NAME")]
    pub(super) compare: Option<String>,
    /// Fit chars-per-token via count_tokens (skipped without an API key)
    #[arg(long)]
    pub(super) calibrate: bool,
    /// Cache health per session from proxy usage rows: busts and their cause
    #[arg(long)]
    pub(super) cache: bool,
    /// Show per-model USD costs from `[stats.prices]` (`--price`)
    #[arg(long)]
    pub(super) price: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args {
        since,
        json,
        plugin,
        save_baseline,
        compare,
        calibrate,
        cache,
        price,
    } = args;
    // The flag is parsed here, before it merges into `stats.since`, so a later parse error
    // can only come from the config or the environment and says so.
    if let Some(s) = &since {
        crate::measure::stats::parse_since(s)?;
    }
    let cfg = Config::load_with(
        config_file.as_deref(),
        stats_flags(since, json, plugin.clone(), compare.clone(), price),
    )?;
    if calibrate {
        println!("{}", crate::tokens::calibrate_or_skip(&cfg.estimator));
        return Ok(());
    }
    // Everything `stats` prints below is a rendering of the operator model (T15.11):
    // the command owns no store of its own.
    if cache {
        let report = crate::model::cache_health(&cfg)?;
        print!(
            "{}",
            if cfg.stats.format == "json" {
                serde_json::to_string_pretty(&report)?
            } else {
                crate::measure::cache::table(&report)
            }
        );
        return Ok(());
    }
    if crate::config::CATALOGUE
        .iter()
        .any(|(id, _)| *id == cfg.stats.plugin)
    {
        print!(
            "{}",
            serde_json::to_string_pretty(&crate::model::plugin_stats(&cfg, &cfg.stats.plugin)?)?
        );
        return Ok(());
    }
    let report = crate::model::stats_report(&cfg)?;
    if let Some(name) = save_baseline {
        let p = crate::measure::baseline::save(&cfg.home, &name, &report)?;
        println!("{}", p.display());
    } else if let Some(name) = compare.or_else(|| {
        let b = cfg.stats.baseline.trim();
        if b.is_empty() {
            None
        } else {
            Some(b.to_string())
        }
    }) {
        print!(
            "{}",
            crate::measure::baseline::compare(&cfg.home, &name, &report)?
        );
    } else if cfg.stats.format == "json" {
        print!("{}", report.to_json()?);
    } else {
        print!("{}", report.to_table());
    }

    Ok(())
}

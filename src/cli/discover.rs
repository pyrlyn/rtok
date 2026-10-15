// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use anyhow::Result;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    /// How far back to read Bash calls (`30d`, `24h`)
    #[arg(long)]
    since: Option<String>,
    /// Families to list
    #[arg(long, default_value_t = 15)]
    top: usize,
    /// JSON instead of the table
    #[arg(long)]
    json: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    let since =
        crate::measure::stats::parse_since(args.since.as_deref().unwrap_or(&cfg.stats.since))?;
    let mut report = crate::model::discover(&cfg, since)?;
    if args.json {
        report.gaps.truncate(args.top);
        println!("{}", serde_json::to_string_pretty(&report)?);
    } else {
        print!("{}", crate::measure::discover::table(&report, args.top));
    }
    Ok(())
}

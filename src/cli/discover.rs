// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::print_json;
use crate::config::Config;
use anyhow::Result;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    /// How far back to read recorded shell calls (`30d`, `24h`)
    #[arg(long, default_value = "30d")]
    pub(super) since: String,
    /// Rows to print, most output first
    #[arg(long, default_value_t = 20)]
    pub(super) limit: usize,
    /// JSON instead of the table
    #[arg(long)]
    pub(super) json: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let since = crate::since::parse_since(&args.since)?;
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    let found = crate::model::discover(&cfg, since, args.limit)?;
    if args.json {
        print_json(&found)
    } else {
        print!("{}", found.to_table());
        Ok(())
    }
}

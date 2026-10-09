// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use anyhow::Result;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    pub(super) id: String,
    /// Inclusive 1-based range `a-b`
    #[arg(long)]
    pub(super) lines: Option<String>,
    /// Regex filter (literal when it does not compile); hits print as `N:line`
    #[arg(long)]
    pub(super) grep: Option<String>,
    /// With `--grep`: N lines around each hit, overlapping windows merged with `--`
    #[arg(long)]
    pub(super) context: Option<u32>,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args {
        id,
        lines,
        grep,
        context,
    } = args;
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    crate::expand::run(
        &cfg,
        &id,
        lines.as_deref(),
        grep.as_deref(),
        context.map_or(0, |n| n as usize),
    )?;

    Ok(())
}

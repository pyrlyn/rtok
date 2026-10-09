// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::print_json;
use crate::config::Config;
use crate::render::with_loader;
use anyhow::Result;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    /// JSON instead of the text lines
    #[arg(long)]
    pub(super) json: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args { json } = args;
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    let info = with_loader("reading status", || {
        crate::info::collect(&cfg, config_file.as_deref())
    });
    if json {
        print_json(&info)?;
    } else {
        print!("{}", info.to_text());
    }

    Ok(())
}

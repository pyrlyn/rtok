// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use anyhow::Result;
use clap::Subcommand;
use std::path::PathBuf;

#[cfg(feature = "docs")]
#[derive(Subcommand)]
pub(super) enum DocsCmd {
    /// Download rustdoc JSON for a Cargo.lock dependency (the only network path)
    Fetch { name: String },
}

pub(super) fn run(config_file: &Option<PathBuf>, action: DocsCmd) -> Result<()> {
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    match action {
        DocsCmd::Fetch { name } => {
            println!("{}", crate::plugins::docs::run_fetch(&cfg, &name)?);
        }
    }

    Ok(())
}

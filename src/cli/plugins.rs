// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::print_json;
use crate::config::Config;
use crate::web::model;
use anyhow::Result;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    /// JSON instead of the table
    #[arg(long)]
    pub(super) json: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args { json } = args;
    let config = Config::load_with(config_file.as_deref(), None)?;
    // The command renders the model's Plugins page (T15.11); the registry keeps
    // the same formatter for library users. The model opens the store.
    let pages = model::Model::plugin_pages(&config);
    if json {
        print_json(&pages)?;
    } else {
        let rows: Vec<(&str, bool, Vec<&str>)> = pages
            .iter()
            .map(|p| (p.id, p.enabled, p.surfaces.clone()))
            .collect();
        print!("{}", crate::render::plugins_table(&rows));
    }

    Ok(())
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use anyhow::Result;
use clap::CommandFactory;
use std::io;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    /// Write `rtok.1` and a page for every subcommand into this directory
    #[arg(long, value_name = "DIR")]
    pub(super) dir: Option<PathBuf>,
}

pub(super) fn run(_config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args { dir } = args;
    match dir {
        Some(dir) => {
            for page in crate::man::write_all(super::Cli::command(), &dir)? {
                println!("{}", page.display());
            }
        }
        None => crate::man::print(super::Cli::command(), &mut io::stdout())?,
    };
    Ok(())
}

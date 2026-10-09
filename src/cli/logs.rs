// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::print_json;
use crate::config::Config;
use crate::ui::style;
use crate::web::model;
use anyhow::Result;
use clap::Subcommand;
use std::io::{self, IsTerminal};
use std::path::PathBuf;

#[derive(Subcommand)]
pub(super) enum LogsCmd {
    /// Same selection, no numbering, no colour — for `rtok logs export > my.log`
    Export,
    /// Print the last lines, then follow: new lines arrive above the old, newest first
    Watch,
}

#[derive(clap::Args)]
pub(super) struct Args {
    #[command(subcommand)]
    pub(super) action: Option<LogsCmd>,
    /// Override `[log] lines`
    #[arg(long, global = true)]
    pub(super) lines: Option<usize>,
    /// JSON instead of the table
    #[arg(long)]
    pub(super) json: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args {
        action,
        lines,
        json,
    } = args;
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    let tty = io::stdout().is_terminal();
    let out = match action {
        // T24.3: runs until Ctrl-C. The loop only ever writes characters — no raw
        // mode, no alternate screen — so there is no terminal state to restore.
        // T225.1: through tailspin the stream is a pipe from the loop's point of
        // view; `tspin --print` colours each row as it arrives.
        Some(LogsCmd::Watch) => {
            if let Some(mut viewer) = crate::log::Tspin::start(&cfg, tty) {
                let watched = crate::log::watch(&cfg, lines, viewer.sink(), false);
                viewer.finish();
                watched?;
            } else {
                crate::log::watch(&cfg, lines, &mut io::stdout(), tty)?;
            }
            return Ok(());
        }
        // The selection is the model's Logs page (T15.11); the numbering and colour
        // are this command's rendering of it — tailspin's when `[log] tspin` says so.
        None => {
            let plain = model::Model::new(&cfg, None).log_lines(lines);
            if !json
                && !plain.is_empty()
                && let Some(viewer) = crate::log::Tspin::start(&cfg, tty)
            {
                viewer.print(&crate::log::numbered(&plain));
                return Ok(());
            }
            crate::log::screen(&plain)
        }
        Some(LogsCmd::Export) => model::Model::new(&cfg, None).log_lines(lines),
    };
    if json {
        print_json(&model::Model::new(&cfg, None).log_lines(lines))?;
        return Ok(());
    }
    if out.is_empty() {
        println!("{}", style::info("no logs yet"));
    } else {
        for line in out {
            println!("{line}");
        }
    }

    Ok(())
}

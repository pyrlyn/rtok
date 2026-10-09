// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::read_lossy;
use crate::config::Config;
use anyhow::Result;
use std::io;
#[cfg(feature = "cmd")]
use std::io::Read;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct RunArgs {
    // T127
    /// Sub-agent id from PreToolUse; scopes the dedup pointer
    #[arg(long, value_name = "ID")]
    pub(super) agent: Option<String>,
    #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
    pub(super) command: Vec<String>,
}

#[derive(clap::Args)]
pub(super) struct FilterArgs {
    /// Read the payload from stdin (OpenCode `tool.execute.after`)
    #[arg(long)]
    pub(super) stdin: bool,
    /// Command family hint (`git status`, `cargo test`, …)
    #[arg(long)]
    pub(super) cmd: Option<String>,
    /// Archive stdin and print the expand trailer (same path as `run`)
    #[arg(long)]
    pub(super) archive: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: RunArgs) -> Result<()> {
    match args {
        #[cfg(feature = "cmd")]
        RunArgs { agent, command } => {
            let cfg = Config::load_with(config_file.as_deref(), None)?;
            let code = crate::plugins::cmd::run::run(&cfg, &command, agent.as_deref())?;
            std::process::exit(code);
        }
        #[cfg(not(feature = "cmd"))]
        RunArgs { .. } => {
            let msg = "rtok run: not implemented (built without the cmd feature)";
            eprintln!("{msg}");
            let cfg = Config::load_lenient(config_file.as_deref(), None);
            crate::log::append(&cfg, "error", "cli", "run", msg);

            Ok(())
        }
    }
}

pub(super) fn filter(config_file: &Option<PathBuf>, args: FilterArgs) -> Result<()> {
    match args {
        #[cfg(feature = "cmd")]
        FilterArgs {
            stdin: _,
            cmd,
            archive,
        } => {
            let cfg = Config::load_with(config_file.as_deref(), None)?;
            let hint = cmd.unwrap_or_else(|| cfg.filter.cmd.clone());
            if archive {
                let mut buf = Vec::new();
                io::stdin().read_to_end(&mut buf)?;
                let argv: Vec<String> = hint.split_whitespace().map(str::to_string).collect();
                // No dispatch-time context reaches this surface (OpenCode's
                // `tool.execute.after`, not the Claude Code PreToolUse rewrite).
                crate::plugins::cmd::run::emit_filtered(&cfg, &argv, &buf, 0, None);
            } else {
                let buf = read_lossy(io::stdin())?;
                print!(
                    "{}",
                    crate::plugins::cmd::filter::run_with_store(&cfg, &hint, &buf)
                );
            }

            Ok(())
        }
        #[cfg(not(feature = "cmd"))]
        FilterArgs { .. } => {
            let _ = config_file;
            print!("{}", read_lossy(io::stdin())?);

            Ok(())
        }
    }
}

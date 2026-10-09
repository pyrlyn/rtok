// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use crate::config::layers;
use anyhow::Result;
use std::io::{self, Write};
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    #[arg(required_unless_present = "serve")]
    pub(super) event: Option<String>,
    /// Overlay `[hook] host` (`claude` | `cursor` | `copilot` | `devin` | `cline`)
    #[arg(long)]
    pub(super) host: Option<String>,
    // T178, D32
    /// Run the resident hook process `rtok-hook` talks to
    #[arg(long, hide = true, conflicts_with_all = ["event", "host"])]
    pub(super) serve: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    match args {
        Args { serve: true, .. } => {
            crate::hooks::resident::serve()?;
            Ok(())
        }
        // T159: these two events answer with a path and an exit code, not the JSON the hook
        // dispatcher writes, so they skip it.
        Args {
            event: Some(event), ..
        } if crate::worktree::host::handles(&event) => {
            let cfg = Config::load_lenient(config_file.as_deref(), None);
            if let Some(out) = crate::worktree::host::run(&event, io::stdin(), &cfg)? {
                println!("{out}");
            }

            Ok(())
        }
        Args { event, host, .. } => {
            let mut cfg =
                Config::load_lenient(config_file.as_deref(), layers::hook_host_flag(host));
            cfg.hook_client_pid = Some(std::process::id());
            crate::hooks::run(&event.unwrap_or_default(), io::stdin(), io::stdout(), &cfg);
            let _ = io::stdout().flush();

            Ok(())
        }
    }
}

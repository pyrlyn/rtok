// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use anyhow::Result;
use clap::Subcommand;
use std::io::{self, Read, Write};
use std::path::PathBuf;

/// `rtok archive rewrite` — the pi `context` carrier (T70.2): the same live-zone
/// rewrite the proxy runs, driven over a pi message array on stdin.
#[cfg(feature = "archive")]
#[derive(Subcommand)]
pub(super) enum ArchiveCmd {
    /// Rewrite old tool results to archive pointers; message array JSON on stdin,
    /// rewritten array on stdout (input bytes echoed when nothing is eligible)
    Rewrite {
        /// Read the message array from stdin (pi `context` event)
        #[arg(long)]
        stdin: bool,
    },
}

pub(super) fn run(config_file: &Option<PathBuf>, action: ArchiveCmd) -> Result<()> {
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    match action {
        ArchiveCmd::Rewrite { stdin: _ } => {
            let cx = crate::plugin::Runtime::open(cfg, "archive-rewrite")?;
            let mut buf = Vec::new();
            let _ = io::stdin().read_to_end(&mut buf);
            let out =
                crate::plugins::archive::pi::rewrite_stdin(&buf, &crate::plugin::Ctx::new(&cx))?;
            io::stdout().write_all(&out)?;
        }
    }

    Ok(())
}

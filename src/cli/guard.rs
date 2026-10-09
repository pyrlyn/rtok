// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use crate::config::layers;
use anyhow::Result;
use clap::Subcommand;
use std::path::PathBuf;

/// `rtok guard check` — the same allow/deny `plugins::guard` returns on PreToolUse.
#[cfg(feature = "guard")]
#[derive(Subcommand)]
pub(super) enum GuardCmd {
    /// Print `{"allow":true}` or `{"allow":false,"reason":…}` (fail open: bad input allows)
    Check {
        /// Host tool name (`bash`, `Read`, …)
        #[arg(long)]
        tool: String,
        /// Tool arguments as JSON
        #[arg(long, value_name = "INPUT")]
        json: String,
        /// Host session id (the cache is per session)
        #[arg(long)]
        session: Option<String>,
        /// Overlay `[hook] host`
        #[arg(long)]
        host: Option<String>,
    },
}

pub(super) fn run(config_file: &Option<PathBuf>, action: GuardCmd) -> Result<()> {
    let GuardCmd::Check {
        tool,
        json,
        session,
        host,
    } = action;
    let cfg = Config::load_with(config_file.as_deref(), layers::hook_host_flag(host))?;
    let sid = session.unwrap_or_else(|| "guard-check".into());
    let cx = crate::plugin::Runtime::open(cfg, sid)?;
    println!("{}", crate::plugins::guard::check(&tool, &json, &cx));

    Ok(())
}

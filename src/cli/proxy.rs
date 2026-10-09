// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use crate::config::layers;
use anyhow::Result;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct Args {
    /// Override `[proxy] port`
    #[arg(long)]
    pub(super) port: Option<u16>,
    /// Override `[proxy] upstream`
    #[arg(long)]
    pub(super) upstream: Option<String>,
    /// Override `[proxy] mode` (`passthrough` | `compress`)
    #[arg(long)]
    pub(super) mode: Option<String>,
    /// Print effective `[proxy]` settings and exit
    #[arg(long)]
    pub(super) dry_run: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args {
        port,
        upstream,
        mode,
        dry_run,
    } = args;
    let cfg = Config::load_with(
        config_file.as_deref(),
        layers::proxy_flags(port, dry_run, upstream, mode),
    )?;
    if cfg.proxy.dry_run {
        println!("bind = {}", cfg.proxy.bind);
        println!("port = {}", cfg.proxy.port);
        println!("mode = {}", cfg.proxy.mode);
        println!("upstream = {}", cfg.proxy.upstream);
        println!("openai_upstream = {}", cfg.proxy.openai_upstream);
        println!("timeout_s = {}", cfg.proxy.timeout_s);
        println!("include_usage = {}", cfg.proxy.include_usage);
        println!("dry_run = {}", cfg.proxy.dry_run);
        return Ok(());
    }
    crate::proxy::serve_blocking(cfg)?;

    Ok(())
}

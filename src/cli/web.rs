// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use crate::config::layers;
use crate::ui::style;
use anyhow::Result;
use std::path::PathBuf;

#[derive(clap::Args)]
pub(super) struct WebArgs {
    /// Override `[web] host`
    #[arg(long)]
    pub(super) host: Option<String>,
    /// Override `[web] port`
    #[arg(long)]
    pub(super) port: Option<u16>,
}

#[derive(clap::Args)]
pub(super) struct TuiArgs {
    /// Start on this tab (a page name both surfaces carry)
    #[arg(long)]
    pub(super) tab: Option<String>,
    /// Model re-read cadence in seconds
    #[arg(long)]
    pub(super) tick_secs: Option<u64>,
}

#[derive(clap::Args)]
pub(super) struct DashboardArgs {
    #[arg(long)]
    pub(super) host: Option<String>,
    #[arg(long)]
    pub(super) port: Option<u16>,
}

pub(super) fn serve(config_file: &Option<PathBuf>, args: WebArgs) -> Result<()> {
    let WebArgs { host, port } = args;
    let cfg = Config::load_with(config_file.as_deref(), layers::web_flags(host, port))?;
    crate::web::serve_blocking(cfg)?;

    Ok(())
}

pub(super) fn tui(config_file: &Option<PathBuf>, args: TuiArgs) -> Result<()> {
    let TuiArgs { tab, tick_secs } = args;
    let cfg = Config::load_with(config_file.as_deref(), layers::tui_flags(tab, tick_secs))?;
    crate::tui::run(cfg)?;

    Ok(())
}

pub(super) fn dashboard(config_file: &Option<PathBuf>, args: DashboardArgs) -> Result<()> {
    let DashboardArgs { host, port } = args;
    let msg = "`rtok dashboard` is deprecated; use `rtok web`";
    eprintln!("{}", style::warn(&format!("warning: {msg}")));
    let cfg = Config::load_with(config_file.as_deref(), layers::web_flags(host, port))?;
    crate::log::append(&cfg, "warn", "cli", "dashboard", msg);
    crate::web::serve_blocking(cfg)?;

    Ok(())
}

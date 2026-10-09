// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::print_json;
use crate::config::Config;
use crate::demon::Service;
use crate::model;
use anyhow::Result;
use clap::Subcommand;
use std::path::PathBuf;

/// Every verb takes optional services; with none they act on what is already up, falling back
/// to `[demon] services`; `status` alone shows every service. `Service` is a `ValueEnum`, so
/// clap validates the name, lists the choices in `--help` and completes them in a shell (D14).
#[derive(Subcommand)]
pub(super) enum DemonCmd {
    /// Detach a supervisor that restarts the service whenever it dies
    Start { service: Vec<Service> },
    /// Ask the supervisor and its child to exit
    Stop { service: Vec<Service> },
    /// Stop, then start
    Restart { service: Vec<Service> },
    /// Stop live surfaces, replace the binary, start the same set
    #[command(hide = true)]
    Upgrade,
    /// State, pids, uptime, restarts and log path; every service when none is named
    Status {
        service: Vec<Service>,
        /// JSON instead of the table
        #[arg(long)]
        json: bool,
    },
    /// SIGKILL instead of SIGTERM, and drop the state file
    Kill { service: Vec<Service> },
    /// The detached half; `demon start` runs this, you do not
    #[command(hide = true)]
    Supervise { service: Service },
}

pub(super) fn run(config_file: &Option<PathBuf>, action: DemonCmd) -> Result<()> {
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    let c = config_file.as_deref();
    // `status` renders the model's Demon page (T15.11); the other verbs
    // write state and stay CLI-only (D27).
    match action {
        DemonCmd::Start { service } => crate::demon::start(&cfg, c, &service)?,
        DemonCmd::Stop { service } => crate::demon::stop(&cfg, &service, false)?,
        DemonCmd::Restart { service } => crate::demon::restart(&cfg, c, &service)?,
        DemonCmd::Upgrade => crate::demon::upgrade(&cfg, c)?,
        DemonCmd::Status { service, json } => {
            let rows = model::Model::new(&cfg, None).demon(&service)?;
            if json {
                print_json(&rows)?;
            } else {
                print!("{}", crate::demon::table(&rows));
            }
        }
        DemonCmd::Kill { service } => crate::demon::stop(&cfg, &service, true)?,
        DemonCmd::Supervise { service } => crate::demon::supervise(&cfg, c, service)?,
    }

    Ok(())
}

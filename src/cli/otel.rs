// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::print_json;
use crate::config::Config;
use crate::render::with_loader;
use crate::web::model;
use anyhow::Result;
use clap::Subcommand;
use std::path::PathBuf;

#[derive(Subcommand)]
pub(super) enum OtelCmd {
    /// Post rows past the watermarks to the endpoint, once
    Flush {
        // T143
        /// Hook-spawned only. Coalesces concurrent hook flushes to at most one
        /// running + one queued process instead of one per `Stop`/`SessionEnd` event.
        /// A manual `rtok otel flush` never passes this — it always flushes.
        #[arg(long, hide = true)]
        coalesce: bool,
    },
    /// Endpoint, watermarks, pending rows, last exporter log line
    Status {
        /// JSON instead of the table
        #[arg(long)]
        json: bool,
    },
}

pub(super) fn run(config_file: &Option<PathBuf>, action: OtelCmd) -> Result<()> {
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    match action {
        OtelCmd::Flush { coalesce } => {
            let cx = crate::plugin::Runtime::open(cfg, "otel")?;
            // The coalesced run is the hook's detached child, with no terminal to draw on.
            let rep = if coalesce {
                crate::otel::export::flush_coalesced_blocking(&cx)
            } else {
                with_loader("flushing telemetry", || {
                    crate::otel::export::flush_blocking(&cx)
                })
            };
            println!("{rep}");
        }
        OtelCmd::Status { json } => {
            if json {
                print_json(&model::otel_status(&cfg)?)?;
            } else {
                let cx = crate::plugin::Runtime::open(cfg, "otel")?;
                print!("{}", crate::otel::export::status(&cx)?);
            }
        }
    }

    Ok(())
}

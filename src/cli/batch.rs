// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use anyhow::Result;
use clap::Subcommand;
use std::path::PathBuf;

/// Where the commands send their requests and as whom; the key itself is only read from the
/// environment (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`).
#[derive(clap::Args)]
pub(super) struct BatchTarget {
    /// Provider whose Batch API to call; default: from the id (`msgbatch_` is Anthropic)
    #[arg(long, value_enum)]
    pub(super) provider: Option<crate::batch::Provider>,
    /// Proxy base URL (default: `[proxy] bind` and `port`)
    #[arg(long, value_name = "URL")]
    pub(super) url: Option<String>,
}

#[derive(Subcommand)]
pub(super) enum BatchCmd {
    /// Create a batch from a JSONL file of provider-shaped requests; prints the batch object
    Submit {
        /// One request per line (Anthropic: `custom_id` + `params`; OpenAI: `custom_id`, `method`, `url`, `body`)
        file: PathBuf,
        #[command(flatten)]
        target: BatchTarget,
    },
    /// Print the provider's current state of a batch
    Status {
        /// Batch id (`msgbatch_…` is Anthropic, `batch_…` OpenAI)
        id: String,
        #[command(flatten)]
        target: BatchTarget,
    },
    /// Write the results of a finished batch to a new file
    Fetch {
        /// Batch id (`msgbatch_…` is Anthropic, `batch_…` OpenAI)
        id: String,
        /// File to create; an existing file is never overwritten
        out: PathBuf,
        #[command(flatten)]
        target: BatchTarget,
    },
}

/// `rtok batch …` (T385.12.1): one blocking request chain against the proxy.
pub(super) fn run(action: BatchCmd, config_file: Option<&std::path::Path>) -> Result<()> {
    use crate::batch::{Api, Provider};

    let (target, id) = match &action {
        BatchCmd::Submit { target, .. } => (target, None),
        BatchCmd::Status { id, target } | BatchCmd::Fetch { id, target, .. } => {
            (target, Some(id.as_str()))
        }
    };
    let provider = target
        .provider
        .or_else(|| id.map(Provider::of_id))
        .unwrap_or(Provider::Anthropic);
    let base = match &target.url {
        Some(url) => url.clone(),
        None => {
            let cfg = Config::load_with(config_file, None)?;
            crate::agents::anthropic_proxy_url(&cfg)
        }
    };
    let key = std::env::var(provider.key_env()).unwrap_or_default();
    let api = Api::new(&base, provider, &key)?;
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    match action {
        BatchCmd::Submit { file, .. } => println!("{}", rt.block_on(api.submit(&file))?),
        BatchCmd::Status { id, .. } => println!("{}", rt.block_on(api.status(&id))?),
        BatchCmd::Fetch { id, out, .. } => {
            let bytes = rt.block_on(api.fetch(&id, &out))?;
            println!("wrote {bytes} bytes to {}", out.display());
        }
    }
    Ok(())
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use super::util::emit;
use crate::config::Config;
use crate::render::with_loader;
use anyhow::{Result, bail};
use clap::ValueEnum;
use std::path::PathBuf;

/// `--format` for `rtok report` (D14: a `ValueEnum`, like `demon`'s `Service`, so clap
/// validates, lists and completes it). `Pdf` landed with T22.3.
#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub(super) enum ReportFormat {
    Md,
    Html,
    Pdf,
}

impl ReportFormat {
    fn as_str(self) -> &'static str {
        match self {
            Self::Md => "md",
            Self::Html => "html",
            Self::Pdf => "pdf",
        }
    }
}

/// The `[report]` flag layer (D12): an omitted `--format` sets nothing, but an explicit
/// `md` must still beat a lower layer that says `html` or `pdf`.
fn report_flags(
    format: Option<ReportFormat>,
    out: Option<PathBuf>,
    since: Option<String>,
    ai: bool,
) -> Option<figment::value::Dict> {
    if format.is_none() && out.is_none() && since.is_none() && !ai {
        return None;
    }
    use figment::value::{Dict, Value};
    let mut report = Dict::new();
    if let Some(format) = format {
        report.insert("format".into(), Value::from(format.as_str()));
    }
    if let Some(o) = out {
        report.insert("out".into(), Value::from(o.to_string_lossy().into_owned()));
    }
    if let Some(s) = since {
        report.insert("since".into(), Value::from(s));
    }
    if ai {
        report.insert("ai".into(), Value::from(true));
    }
    let mut flags = Dict::new();
    flags.insert("report".into(), Value::from(report));
    Some(flags)
}

#[derive(clap::Args)]
pub(super) struct Args {
    /// Output format
    #[arg(long, value_enum)]
    pub(super) format: Option<ReportFormat>,
    /// Write to this path instead of stdout
    #[arg(long, value_name = "PATH")]
    pub(super) out: Option<PathBuf>,
    /// How far back the report reads (`30d`, `24h`)
    #[arg(long)]
    pub(super) since: Option<String>,
    // T22.4
    /// Model-shaped rendering of the same document instead of `--format`
    #[arg(long)]
    pub(super) ai: bool,
}

pub(super) fn run(config_file: &Option<PathBuf>, args: Args) -> Result<()> {
    let Args {
        format,
        out,
        since,
        ai,
    } = args;
    // As for `stats`: the flag is parsed before it merges into `report.since`, so a later
    // parse error can only come from the config or the environment and says so.
    if let Some(s) = &since {
        crate::measure::stats::parse_since(s)?;
    }
    let cfg = Config::load_with(config_file.as_deref(), report_flags(format, out, since, ai))?;
    // D24: the command picks the renderer and the sink; every number was already
    // computed by the model (`src/report/` touches nothing else).
    let home = Config::home_dir();
    let doc = with_loader("building the report", || {
        crate::report::document(&cfg, &home, config_file.as_deref())
    })?;
    if cfg.report.ai {
        emit(
            &cfg.report.out,
            crate::report::ai::render(&doc, &cfg).as_bytes(),
        )?;
        return Ok(());
    }
    match cfg.report.format.as_str() {
        "md" => emit(
            &cfg.report.out,
            crate::report::markdown::render(&doc).as_bytes(),
        )?,
        "html" => emit(
            &cfg.report.out,
            crate::report::html::render(&doc).as_bytes(),
        )?,
        "pdf" => emit(&cfg.report.out, &crate::report::pdf::render(&doc))?,
        other => bail!("--format {other} is unknown (md, html, pdf)"),
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[derive(Parser)]
    struct Cli {
        #[command(flatten)]
        args: Args,
    }

    fn effective_format(configured: &str, argv: &[&str]) -> String {
        let dir = crate::testutil::tmp_dir("report-format");
        let file = dir.join("config.toml");
        std::fs::write(&file, format!("[report]\nformat = \"{configured}\"\n")).unwrap();
        let args = Cli::try_parse_from(std::iter::once("report").chain(argv.iter().copied()))
            .unwrap()
            .args;
        let flags = report_flags(args.format, args.out, args.since, args.ai);
        Config::load_with(Some(&file), flags).unwrap().report.format
    }

    #[test]
    fn explicit_md_overrides_a_configured_format() {
        for configured in ["html", "pdf"] {
            assert_eq!(effective_format(configured, &["--format", "md"]), "md");
            assert_eq!(
                effective_format(configured, &["--format", "md", "--since", "7d"]),
                "md"
            );
        }
    }

    #[test]
    fn omitted_format_keeps_the_configured_one_and_other_formats_win() {
        assert_eq!(effective_format("html", &[]), "html");
        assert_eq!(effective_format("html", &["--since", "7d"]), "html");
        assert_eq!(effective_format("html", &["--format", "pdf"]), "pdf");
        assert_eq!(effective_format("pdf", &["--format", "html"]), "html");
    }
}

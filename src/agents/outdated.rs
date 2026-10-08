// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok agents outdated` / `rtok agents update --check` (T279.1).

use std::cmp::Ordering;

use anyhow::{Context, Result, bail};
use semver::Version;
use serde::Serialize;

use super::plugin_install::{InstallProbe, install_probe};
use super::plugin_version::{Installed, Receipt, Source, is_behind, read_installed, receipt_path};
use super::{Agent, HOSTS, Kind, Support, host, wants};
use crate::config::Config;
use crate::render::{Col, table};
use crate::ui::agents as ui;

/// Exit code for `--exit-code` when at least one plugin is behind the running rtok.
pub const EXIT_OUTDATED: i32 = 10;

/// Host / variant selection — same flags as `agents update`.
#[derive(Debug, Clone, Default)]
pub struct OutdatedSelection {
    pub hosts: Vec<String>,
    pub cli: bool,
    pub desktop: bool,
    pub all: bool,
}

/// One outdated plugin row.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Outdated {
    pub agent: String,
    pub variant: String,
    pub installed: String,
    pub available: String,
    pub source: String,
    pub legacy: bool,
}

#[derive(Debug, Serialize)]
pub struct OutdatedReport {
    pub rtok: String,
    pub outdated: Vec<Outdated>,
    pub installed: usize,
}

/// List every selected host variant whose rtok plugin is older than the running binary.
pub fn outdated(cfg: &Config, selection: &OutdatedSelection) -> Result<Vec<Outdated>> {
    Ok(scan(cfg, selection)?.outdated)
}

pub fn report(cfg: &Config, selection: &OutdatedSelection) -> Result<OutdatedReport> {
    scan(cfg, selection)
}

fn scan(cfg: &Config, selection: &OutdatedSelection) -> Result<OutdatedReport> {
    let target = target_version()?;
    let receipt = Receipt::read(&receipt_path(cfg))?;
    let ids = host_ids(selection)?;
    let want = |kind: Kind| wants(kind, selection.cli, selection.desktop, selection.all);
    let mut out = Vec::new();
    let mut installed = 0usize;
    for id in ids {
        let Some(agent) = host(&id) else { continue };
        for v in agent.variants().iter().filter(|v| want(v.kind)) {
            let Some(found) = installed_plugin(agent, v.kind, cfg, &receipt)? else {
                continue;
            };
            installed += 1;
            if let Some(row) = outdated_row(&id, v.kind, &found, &target) {
                out.push(row);
            }
        }
    }
    Ok(OutdatedReport {
        rtok: target.to_string(),
        outdated: out,
        installed,
    })
}

fn outdated_row(agent: &str, kind: Kind, found: &Found, target: &Version) -> Option<Outdated> {
    if !is_behind(&found.got.version, target) {
        return None;
    }
    let legacy = found.unversioned;
    let installed = if legacy {
        "legacy".to_string()
    } else {
        found.got.version.to_string()
    };
    Some(Outdated {
        agent: agent.to_string(),
        variant: kind.as_str().to_string(),
        installed,
        available: target.to_string(),
        source: found.got.source.to_string(),
        legacy,
    })
}

/// A host variant's installed plugin as the version lookup reads it (T279 step 2).
pub(super) struct Found {
    pub got: Installed,
    /// No version file and no version recorded anywhere (receipt or host record): the install
    /// predates T279. `got.legacy` alone only says the installed copy has no version file; one
    /// the receipt or the host record dates (Claude's `installed_plugins.json` says `0.0.1`)
    /// shows that version.
    pub unversioned: bool,
}

/// True when the variant has rtok's plugin installed and the host supports one.
fn has_plugin(agent: &dyn Agent, kind: Kind, cfg: &Config) -> bool {
    agent.installed(cfg, kind).contains(&"plugin")
        && !matches!(agent.support(kind, "plugin"), Support::No(_))
}

/// The installed-plugin lookup shared by `agents outdated` and the `plugin` row of `agents
/// list` (T382): the host's probe, then [`read_installed`]. `None` when there is no plugin.
pub(super) fn installed_plugin(
    agent: &dyn Agent,
    kind: Kind,
    cfg: &Config,
    receipt: &Receipt,
) -> Result<Option<Found>> {
    if !has_plugin(agent, kind, cfg) {
        return Ok(None);
    }
    let id = agent.id();
    let probe = install_probe(id, cfg, kind, receipt).unwrap_or(InstallProbe {
        version_file: std::path::PathBuf::from("\0"),
        host_record_version: None,
        fallback_source: Source::Github,
    });
    let host_record = probe.host_record_version.as_deref();
    let got = read_installed(
        &probe.version_file,
        receipt,
        id,
        host_record,
        probe.fallback_source,
    )?;
    let recorded =
        receipt.get(id).is_some() || host_record.is_some_and(|v| Version::parse(v).is_ok());
    Ok(Some(Found {
        unversioned: got.legacy && !recorded,
        got,
    }))
}

/// What the `plugin` row of `agents list` and the JSON `plugin_*` fields say about one
/// installed plugin (T382).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginStatus {
    /// Absent when no version is recorded anywhere (the install predates T279).
    pub version: Option<String>,
    pub source: Option<String>,
    /// The text after `installed` on the row.
    pub note: String,
}

/// The installed plugin's version and source for one host variant, read with the same lookup
/// as `agents outdated`. `None` when there is no plugin, and also when the lookup fails: a
/// listing never turns a broken version file into an error.
pub fn plugin_status(agent: &dyn Agent, kind: Kind, cfg: &Config) -> Option<PluginStatus> {
    let receipt = Receipt::read(&receipt_path(cfg)).unwrap_or_default();
    let found = installed_plugin(agent, kind, cfg, &receipt).ok()??;
    let rtok = target_version().ok()?;
    if found.unversioned {
        return Some(PluginStatus {
            version: None,
            source: None,
            note: ui::LEGACY_NOTE.to_string(),
        });
    }
    let (version, source) = (found.got.version.to_string(), found.got.source.to_string());
    let hint = (found.got.version.cmp_precedence(&rtok) == Ordering::Less).then_some(agent.id());
    let note = if found.got.version.cmp_precedence(&rtok) == Ordering::Equal {
        ui::plugin_current(&version, &source)
    } else {
        ui::plugin_differs(&version, &source, &rtok.to_string(), hint)
    };
    Some(PluginStatus {
        version: Some(version),
        source: Some(source),
        note,
    })
}

fn host_ids(selection: &OutdatedSelection) -> Result<Vec<String>> {
    if selection.hosts.is_empty() {
        return Ok(HOSTS.iter().map(|s| (*s).to_string()).collect());
    }
    for h in &selection.hosts {
        if host(h).is_none() {
            bail!("unknown host: {h}");
        }
    }
    Ok(selection.hosts.clone())
}

fn target_version() -> Result<Version> {
    Version::parse(env!("CARGO_PKG_VERSION")).context("CARGO_PKG_VERSION")
}

/// Human output for a report; empty when `json` (caller prints JSON only).
pub fn print_human(rep: &OutdatedReport) -> String {
    if rep.installed == 0 {
        return format!("{}\n", ui::NO_PLUGINS);
    }
    if rep.outdated.is_empty() {
        return format!("{}\n", ui::all_current(rep.installed, &rep.rtok));
    }
    let cols = [Col::left(5), Col::left(8), Col::left(9), Col::left(6)];
    let mut rows: Vec<Vec<String>> = vec![vec![
        "agent".into(),
        "installed".into(),
        "available".into(),
        "source".into(),
    ]];
    rows.extend(rep.outdated.iter().map(|r| {
        vec![
            r.agent.clone(),
            r.installed.clone(),
            r.available.clone(),
            r.source.clone(),
        ]
    }));
    let hosts: Vec<String> = rep
        .outdated
        .iter()
        .map(|r| r.agent.clone())
        .collect::<std::collections::BTreeSet<_>>()
        .into_iter()
        .collect();
    format!(
        "{}\n{}\n",
        table(&cols, &rows),
        ui::update_hint(&hosts.join(","))
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cmp::Ordering;

    fn target() -> Version {
        Version::parse(env!("CARGO_PKG_VERSION")).unwrap()
    }

    #[test]
    fn is_behind_ignores_build_metadata_on_equal_base() {
        let t = target();
        let local = super::super::plugin_version::local_version(&t, "12c7e91");
        assert!(!is_behind(&local, &t));
    }

    #[test]
    fn is_behind_flags_older_patch() {
        let t = target();
        let old = Version::parse("0.0.1").unwrap();
        assert!(is_behind(&old, &t));
    }

    #[test]
    fn newer_installed_is_not_behind() {
        let t = target();
        let mut newer = t.clone();
        newer.patch += 1;
        assert!(!is_behind(&newer, &t));
        assert_eq!(newer.cmp_precedence(&t), Ordering::Greater);
    }
}

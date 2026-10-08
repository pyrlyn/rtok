// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Strings for `rtok agents outdated`, `rtok agents update --check` (T279.1) and the installed
//! plugin version on the `plugin` row of `rtok agents list` (T382).

/// Shown when no host variant has rtok's plugin installed.
pub const NO_PLUGINS: &str = "no rtok plugins installed";

/// Every installed plugin is at or above the running rtok version (`installed`, `rtok` version).
pub fn all_current(installed: usize, rtok: &str) -> String {
    format!("all rtok plugins are up to date ({installed} installed, rtok {rtok})")
}

/// Hint after the outdated table (`hosts` is comma-separated host ids).
pub fn update_hint(hosts: &str) -> String {
    format!("run: rtok agents update {hosts}")
}

/// The `plugin` row's text after `installed` when no version is recorded anywhere.
pub const LEGACY_NOTE: &str = "(legacy, no version)";

/// The installed version equals the running rtok.
pub fn plugin_current(version: &str, source: &str) -> String {
    format!("{version} ({source})")
}

/// The installed version differs from the running rtok; `update` names the host whose plugin
/// is older (a newer one has nothing to update).
pub fn plugin_differs(version: &str, source: &str, rtok: &str, update: Option<&str>) -> String {
    let base = format!("{version} ({source}), rtok is {rtok}");
    match update {
        Some(host) => format!("{base} — rtok agents update {host}"),
        None => base,
    }
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Augment installer (`rtok agents install augment`, plan T413.8): the Auggie CLI.
//!
//! Auggie reads user MCP servers from `~/.augment/settings.json` under `mcpServers`; a stdio
//! server is `{command, args, env}` (the docs' settings examples omit `type`), so this host calls
//! [`super::register_stdio_mcp`] (the shape Cline, Roo Code and Droid share) instead of a
//! second copy. `[setup.augment] settings_path` moves the file. Project `.augment/` files are
//! the user's and setup never writes them. The IDE extension keeps its servers behind a settings
//! panel with no documented file, so it has no variant.
//!
//! Hooks are documented but stay out: a command must be a script file (`.sh`, `.ps1`, `.cmd` or
//! `.bat`) and `PreToolUse` can only deny (no `updatedInput`), so rtok's rewrite has no path;
//! `research.md` section 32.8 has the sources.

use std::path::PathBuf;

use anyhow::Result;

use super::{
    Agent, Kind, Support, Variant, register_stdio_mcp, support_mcp_only, unregister_stdio_mcp,
};
use crate::config::Config;

/// Augment's Auggie CLI.
pub struct Augment;

const HOST: &str = "augment";

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "Augment Auggie CLI",
    bins: &["auggie"],
    apps: &[],
}];

/// `[setup.augment] settings_path`, by default `~/.augment/settings.json`.
pub fn settings_path(cfg: &Config) -> PathBuf {
    cfg.setup.augment.settings_path.clone()
}

/// `mcpServers.rtok` in Auggie's user settings file.
pub fn register_mcp(cfg: &Config) -> Result<String> {
    register_stdio_mcp(cfg, &settings_path(cfg), HOST)
}

/// Drop `mcpServers.rtok` (`rtok agents remove augment`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    unregister_stdio_mcp(cfg, &settings_path(cfg), HOST)
}

impl Agent for Augment {
    super::agent_identity!(HOST);

    fn support(&self, _kind: Kind, module: &str) -> Support {
        support_mcp_only(
            module,
            "Augment hooks must be script files and PreToolUse can only deny (no updatedInput), so rtok cannot rewrite a call through them",
            "Augment documents no model endpoint setting or bring-your-own key to point at the proxy",
            "Auggie plugins come from marketplaces through `auggie plugin install`; rtok ships no Augment plugin",
        )
    }

    super::mcp_only_file!(settings_path, register_mcp, unregister_mcp);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::assert_stdio_mcp_roundtrip;
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("augment", name, "settings.json", dry, |c, path| {
            c.setup.augment.settings_path = path;
        })
    }

    #[test]
    fn the_default_path_is_the_augment_user_settings() {
        let c = Config::default();
        assert!(
            settings_path(&c).ends_with(".augment/settings.json"),
            "{:?}",
            settings_path(&c)
        );
    }

    #[test]
    fn dry_run_names_the_file_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcpServers.rtok: "), "{out}");
        assert!(out.contains("mcp --host augment"), "{out}");
        assert!(!path.exists());
        assert!(Augment.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn mcp_entry_is_stdio_idempotent_and_remove_keeps_foreign() {
        let (c, path) = cfg("mcp", false);
        assert_stdio_mcp_roundtrip(
            &path,
            HOST,
            || register_mcp(&c),
            || unregister_mcp(&c),
            || Augment.installed(&c, Kind::Cli),
        );
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn apply_is_idempotent_via_the_agent_trait() {
        let (c, path) = cfg("apply", false);
        super::super::assert_mcp_only_apply_idempotent(&Augment, &c, Kind::Cli, "mcpServers");
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}

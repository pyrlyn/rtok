// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! MiMo Code installer (`rtok agents install mimo`, plan T186, T520).
//!
//! MiMo Code is Xiaomi's terminal coding agent, an OpenCode fork (`mimo`, install via
//! `curl -fsSL https://mimo.xiaomi.com/install | bash` or `npm i -g @mimo-ai/cli`). Its global
//! config, `[setup.mimo] config_path` (default `~/.config/mimocode/mimocode.json`,
//! `MIMOCODE_HOME`/`MIMOCODE_CONFIG` move it), carries MCP servers under `mcp.<name>` in the
//! exact shape OpenCode kept from upstream — `{type: "local", command: [..], enabled: true}`
//! (https://mimo.xiaomi.com/mimocode/mcp-servers, fetched 2026-09-24) — so install reuses
//! [`super::register_local_mcp`], the helper `opencode` was refactored onto rather than
//! respelling the same JSON here (T186, keeps `just dup` under its 2 % budget).
//!
//! The global dir accepts `config.json`, `mimocode.json` and `mimocode.jsonc`, merged in that
//! order with the later file winning (https://mimo.xiaomi.com/mimocode/config-overrides,
//! `packages/cli/src/config/config.ts`), and MiMo itself writes a starter `mimocode.jsonc` when
//! none of them exists. So when `mimocode.json` is absent and its `.jsonc` sibling is there,
//! that file is edited — surgically through [`super::jsonc`], keeping every comment and byte
//! but our entry (T520); when both exist, `mimocode.json` stays the target, since the two merge
//! and `mcp.rtok` is ours alone. A `config_path` with another file name is used as it is.
//!
//! Like Kilo, the host also links `plugins/opencode/rtok.ts` to `<config dir>/plugins/rtok.ts`
//! (T520): MiMo loads every `{plugin,plugins}/*.{ts,js}` there, `@mimo-ai/plugin` has the same
//! `tool.execute.before`/`after` hooks as OpenCode's, and the file imports only `node:` modules.
//! MiMo has no shell hook events, so `hooks` stays `Support::No`. Proxy is `Support::No`: no
//! base-URL override is documented anywhere in the config or env-var reference. MiMo Desktop
//! (early access) documents no config path, so no Desktop variant ships (T521).

use std::path::{Path, PathBuf};

use anyhow::Result;

use super::plugin::HostPlugin;
use super::{
    Agent, Kind, Mode, Support, Variant, apply_mcp_only, installed_mcp_only, jsonc,
    local_mcp_entry, mcp_summary, opencode, register_local_mcp, rtok_command, unregister_local_mcp,
};
use crate::config::Config;

/// MiMo Code: one CLI binary, no documented Desktop config path yet.
pub struct Mimo;

static VARIANTS: [Variant; 1] = [Variant {
    kind: Kind::Cli,
    name: "MiMo Code",
    bins: &["mimo"],
    apps: &[],
}];

/// The file `mcp.rtok` goes into: `[setup.mimo] config_path`, or its `.jsonc` sibling when
/// that is the only one MiMo has (module doc).
pub(crate) fn config_path(cfg: &Config) -> PathBuf {
    let json = &cfg.setup.mimo.config_path;
    let sibling = json.with_extension("jsonc");
    if json.file_name().is_some_and(|n| n == "mimocode.json") && !json.exists() && sibling.exists()
    {
        sibling
    } else {
        json.clone()
    }
}

fn is_jsonc(path: &Path) -> bool {
    path.extension().is_some_and(|e| e == "jsonc")
}

/// `mcp.rtok` — the local-argv shape confirmed against MiMo's own MCP docs (module doc).
pub fn register_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    if !is_jsonc(&path) {
        return register_local_mcp(cfg, &path, "mcp", "mimo");
    }
    let cmd = rtok_command();
    jsonc::upsert_entry(
        cfg,
        &path,
        "mcp",
        "rtok",
        &local_mcp_entry(&cmd, "mimo"),
        |_| format!("mcp.rtok: {}", mcp_summary(&cmd, "mimo")),
    )
}

/// Drop `mcp.rtok` (`rtok agents remove mimo`).
pub fn unregister_mcp(cfg: &Config) -> Result<String> {
    let path = config_path(cfg);
    if !is_jsonc(&path) {
        return unregister_local_mcp(cfg, &path, "mcp", "mimo");
    }
    jsonc::remove_entry(cfg, &path, "mcp", "rtok", &local_mcp_entry("rtok", "mimo"))
}

/// Offer / link / unlink the OpenCode plugin into MiMo's config dir (D21). Dry-run and the
/// unaccepted offer name `plugins/opencode` and `ketch install pyrlyn/rtok`.
pub static PLUGIN: HostPlugin = HostPlugin {
    src_rel: "plugins/opencode/rtok.ts",
    host: "MiMo Code",
    label: None,
    dest: plugin_dest,
    // MiMo's docs list no GitHub/subdir plugin install beside the config dir's `plugins/`,
    // so the local link is the only path there is and installs once MiMo is detected (T164).
    default_install: true,
};

/// Plugin dest: `<mimocode config dir>/plugins/rtok.ts` — MiMo loads `{plugin,plugins}/*.{ts,js}`.
pub fn plugin_dest(cfg: &Config) -> PathBuf {
    opencode::plugin_dest_beside(&cfg.setup.mimo.config_path)
}

impl Agent for Mimo {
    fn id(&self) -> &'static str {
        "mimo"
    }

    fn variants(&self) -> &'static [Variant] {
        &VARIANTS
    }

    fn readme(&self) -> &'static str {
        include_str!("README.md")
    }

    fn support(&self, _kind: Kind, module: &str) -> Support {
        match module {
            "mcp" | "plugin" => Support::Yes,
            "hooks" => Support::No(
                "MiMo Code has no shell hook events; the linked plugin filters bash output instead",
            ),
            _ => Support::No(
                "MiMo Code has no documented base-URL override; MIMOCODE_HOME/MIMOCODE_CONFIG relocate config, not the model endpoint",
            ),
        }
    }

    fn plugin_surfaces(&self) -> &'static [rtok_plugin_sdk::Surface] {
        &[rtok_plugin_sdk::Surface::Cli]
    }

    fn files(&self, cfg: &Config, _kind: Kind) -> Vec<PathBuf> {
        vec![config_path(cfg)]
    }

    fn markers(&self, cfg: &Config, kind: Kind) -> Vec<PathBuf> {
        let mut paths = self.files(cfg, kind);
        paths.push(plugin_dest(cfg));
        paths
    }

    fn installed(&self, cfg: &Config, _kind: Kind) -> Vec<&'static str> {
        let mut out = installed_mcp_only(&config_path(cfg));
        if PLUGIN.ours(cfg) {
            out.push("plugin");
        }
        out
    }

    fn apply(&self, cfg: &Config, _kind: Kind, mode: Mode) -> Result<Vec<String>> {
        let mut lines = apply_mcp_only(cfg, mode, register_mcp, unregister_mcp)?;
        lines.push(PLUGIN.offer(cfg, mode == Mode::Remove)?);
        Ok(lines)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::assert_local_mcp_roundtrip;
    use rtok_agent_sdk::NO_CHANGES;
    use serde_json::Value;
    use std::fs;

    fn cfg(name: &str, dry: bool) -> (Config, PathBuf) {
        super::super::test_scratch_cfg("mimo", name, "mimocode.json", dry, |c, path| {
            c.setup.mimo.config_path = path;
        })
    }

    fn dir_of(path: &Path) -> &Path {
        path.parent().unwrap()
    }

    /// MiMo's own starter file shape: comments, an inline comment and a trailing comma.
    const STARTER: &str = "{\n  // keep me\n  \"theme\": \"dark\", // inline\n}\n";

    #[test]
    fn dry_run_names_the_change_and_creates_nothing() {
        let (c, path) = cfg("dry", true);
        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcp.rtok: "), "{out}");
        assert!(!path.exists());
        assert!(Mimo.installed(&c, Kind::Cli).is_empty());
        let lines = Mimo.apply(&c, Kind::Cli, Mode::Install).unwrap().join("\n");
        assert!(lines.contains("plugins/opencode"), "{lines}");
        assert!(!plugin_dest(&c).exists());
        let _ = fs::remove_dir_all(dir_of(&path));
    }

    #[test]
    fn mcp_entry_is_local_argv_idempotent_and_remove_keeps_foreign() {
        let (c, path) = cfg("mcp", false);
        assert_local_mcp_roundtrip(
            &path,
            || register_mcp(&c),
            || unregister_mcp(&c),
            || Mimo.installed(&c, Kind::Cli),
        );
        assert!(Mimo.installed(&c, Kind::Cli).is_empty());
        let _ = fs::remove_dir_all(dir_of(&path));
    }

    #[test]
    fn install_links_the_plugin_and_remove_unlinks_it() {
        let (c, path) = cfg("apply", false);
        let lines = Mimo.apply(&c, Kind::Cli, Mode::Install).unwrap();
        assert!(lines[0].starts_with("mcp.rtok: "), "{lines:?}");
        assert_eq!(plugin_dest(&c), dir_of(&path).join("plugins/rtok.ts"));
        let body = fs::read_to_string(plugin_dest(&c)).unwrap();
        assert!(
            body.contains("tool.execute.after"),
            "link misses the plugin"
        );
        assert_eq!(Mimo.installed(&c, Kind::Cli), ["mcp", "plugin"]);

        let again = Mimo.apply(&c, Kind::Cli, Mode::Install).unwrap();
        assert!(again.iter().all(|l| l == NO_CHANGES), "{again:?}");

        let gone = Mimo.apply(&c, Kind::Cli, Mode::Remove).unwrap();
        assert_eq!(gone[0], "- mcp.rtok");
        assert!(!PLUGIN.linked(&c));
        assert!(Mimo.installed(&c, Kind::Cli).is_empty());
        let again = Mimo.apply(&c, Kind::Cli, Mode::Remove).unwrap();
        assert!(again.iter().all(|l| l == NO_CHANGES), "{again:?}");
        let _ = fs::remove_dir_all(dir_of(&path));
    }

    #[test]
    fn a_jsonc_only_home_gets_the_entry_in_the_jsonc_with_every_other_byte_kept() {
        let (c, path) = cfg("jsonc", false);
        let jsonc = dir_of(&path).join("mimocode.jsonc");
        fs::write(&jsonc, STARTER).unwrap();
        assert_eq!(config_path(&c), jsonc);
        assert_eq!(Mimo.files(&c, Kind::Cli), std::slice::from_ref(&jsonc));

        let out = register_mcp(&c).unwrap();
        assert!(out.starts_with("mcp.rtok: "), "{out}");
        assert!(!path.exists(), "a second config file was created");
        let body = fs::read_to_string(&jsonc).unwrap();
        assert!(body.starts_with(&STARTER[..STARTER.len() - 2]), "{body}");
        let root = super::jsonc::parse(&body).unwrap();
        assert_eq!(root["theme"], "dark");
        assert_eq!(root["mcp"]["rtok"]["type"], "local");
        assert_eq!(root["mcp"]["rtok"]["command"][1], "mcp");
        assert_eq!(root["mcp"]["rtok"]["command"][3], "mimo");
        assert_eq!(Mimo.installed(&c, Kind::Cli), ["mcp"]);
        assert_eq!(register_mcp(&c).unwrap(), NO_CHANGES);

        assert_eq!(unregister_mcp(&c).unwrap(), "- mcp.rtok");
        let left = fs::read_to_string(&jsonc).unwrap();
        assert!(
            left.contains("// keep me") && left.contains("// inline"),
            "{left}"
        );
        assert!(super::jsonc::parse(&left).unwrap().get("mcp").is_none());
        assert_eq!(unregister_mcp(&c).unwrap(), NO_CHANGES);
        let _ = fs::remove_dir_all(dir_of(&path));
    }

    #[test]
    fn a_dry_run_leaves_the_jsonc_untouched() {
        let (c, path) = cfg("jsonc-dry", true);
        let jsonc = dir_of(&path).join("mimocode.jsonc");
        fs::write(&jsonc, STARTER).unwrap();
        register_mcp(&c).unwrap();
        assert_eq!(fs::read_to_string(&jsonc).unwrap(), STARTER);
        let _ = fs::remove_dir_all(dir_of(&path));
    }

    #[test]
    fn the_json_wins_when_both_files_exist_and_the_jsonc_is_never_touched() {
        let (c, path) = cfg("both", false);
        let jsonc = dir_of(&path).join("mimocode.jsonc");
        fs::write(&jsonc, STARTER).unwrap();
        fs::write(&path, r#"{"mcp":{"other":{"type":"remote","url":"x"}}}"#).unwrap();
        assert_eq!(config_path(&c), path);
        register_mcp(&c).unwrap();
        let root: Value = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(root["mcp"]["rtok"]["type"], "local");
        assert_eq!(root["mcp"]["other"]["url"], "x");
        assert_eq!(fs::read_to_string(&jsonc).unwrap(), STARTER);
        let _ = fs::remove_dir_all(dir_of(&path));
    }

    #[test]
    fn another_file_name_is_used_as_it_is() {
        let (mut c, path) = cfg("named", false);
        let mine = dir_of(&path).join("mine.json");
        fs::write(mine.with_extension("jsonc"), STARTER).unwrap();
        c.setup.mimo.config_path = mine.clone();
        assert_eq!(config_path(&c), mine);
        let _ = fs::remove_dir_all(dir_of(&path));
    }

    #[test]
    fn support_matches_mcp_and_plugin_yes_hooks_proxy_no() {
        assert!(matches!(Mimo.support(Kind::Cli, "mcp"), Support::Yes));
        assert!(matches!(Mimo.support(Kind::Cli, "plugin"), Support::Yes));
        assert!(matches!(Mimo.support(Kind::Cli, "hooks"), Support::No(_)));
        assert!(matches!(Mimo.support(Kind::Cli, "proxy"), Support::No(_)));
    }
}

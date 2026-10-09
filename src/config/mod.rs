// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `~/.rtok/config.toml`. The owner is `rtok-config`; this module re-exports it so call sites
//! stay on `crate::config`. Loading returns warnings on [`Config::notes`]. The CLI prints them.

pub use rtok_config::*;

#[cfg(test)]
mod tests {
    use super::*;

    /// T169: configs that skip `finish` (`testutil`, `Runtime::in_memory`) never hand a `~`
    /// path to the filesystem, where it resolves against the cwd and grows a `./~`.
    #[test]
    fn unfinished_configs_never_keep_a_literal_tilde_path() {
        let dir = crate::testutil::tmp_dir("rebase");
        let rebased = crate::testutil::config_in(&dir);
        let rt = crate::plugin::Runtime::in_memory("t169").unwrap();
        for cfg in [&rebased, &rt.config] {
            assert_paths_expanded(cfg);
            assert!(
                cfg.core.archive_dir.is_absolute(),
                "{}",
                cfg.core.archive_dir.display()
            );
        }
        assert!(rebased.setup.claude.settings_path.starts_with(&dir));
    }

    /// The loader records a malformed `.env` as a note. The surface writes that note to the
    /// log and keeps it out of `errors.log`.
    #[test]
    fn a_malformed_dotenv_is_a_warning_and_not_an_error_file() {
        let home = crate::testutil::tmp_dir("dotenv-log");
        let log = home.join("rtok.log");
        std::fs::write(home.join(".env"), "this is not a pair\n").unwrap();
        let toml = format!("[log]\npath = '{}'\n", log.display());
        std::fs::write(Config::path_for(&home), toml).unwrap();
        let cfg = layers::load(&home, Some(&Config::path_for(&home)), None).unwrap();
        crate::log::emit_config_notes(&cfg);
        let text = std::fs::read_to_string(&cfg.log.path).unwrap();
        assert!(
            text.contains("warn config/dotenv") && text.contains(".env"),
            "{text}"
        );
        let errors = rtok_log::error_path(&cfg.log.path);
        assert!(
            !errors.exists() || !std::fs::read_to_string(&errors).unwrap().contains("dotenv"),
            "a warning stays out of errors.log"
        );
    }

    fn assert_paths_expanded(cfg: &Config) {
        let dumped = figment::value::Value::serialize(cfg).unwrap();
        let mut tilde = Vec::new();
        fn walk(v: &figment::value::Value, key: &str, out: &mut Vec<String>) {
            match v {
                figment::value::Value::String(_, s) if s.starts_with('~') => {
                    out.push(format!("{key} = {s}"));
                }
                figment::value::Value::Dict(_, d) => d
                    .iter()
                    .for_each(|(k, v)| walk(v, &format!("{key}.{k}"), out)),
                figment::value::Value::Array(_, a) => a.iter().for_each(|v| walk(v, key, out)),
                _ => {}
            }
        }
        walk(&dumped, "", &mut tilde);
        assert_eq!(tilde, Vec::<String>::new(), "unexpanded paths");
    }
}

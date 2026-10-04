// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok completions` with no shell (T408): a multi-select of the shells, pre-checked where the
//! script is installed. Confirming installs the newly checked and removes the unchecked; the
//! prompt is injected so tests drive the selection without a terminal.

use std::fmt;

use anyhow::{Context, Result};
use clap::Command;

use super::Shell;
use super::install::{self, Places, Status};

/// What a confirmed selection asks for, relative to what is installed now.
#[derive(Debug, PartialEq, Eq)]
pub struct Changes {
    pub install: Vec<Shell>,
    pub uninstall: Vec<Shell>,
}

pub fn plan(installed: &[Shell], chosen: &[Shell]) -> Changes {
    Changes {
        install: chosen
            .iter()
            .filter(|s| !installed.contains(s))
            .copied()
            .collect(),
        uninstall: installed
            .iter()
            .filter(|s| !chosen.contains(s))
            .copied()
            .collect(),
    }
}

/// `--list`: one line per shell, `name`, `yes`/`no`, path (`-` where the shell has no per-user
/// location on this system, e.g. Clink off Windows).
pub fn list(places: &Places) -> Vec<String> {
    install::status(places)
        .into_iter()
        .map(|s| {
            let path = s.path.map_or("-".into(), |p| p.display().to_string());
            let yes = if s.installed { "yes" } else { "no" };
            format!("{:<10} {:<3} {path}", s.shell.name(), yes)
        })
        .collect()
}

/// Ask which shells should have completions (`None` = cancelled), then apply the difference.
/// A shell without a per-user location here is never offered, so it is never touched.
pub fn run<F>(places: &Places, cmd: Command, ask: F) -> Result<Vec<String>>
where
    F: FnOnce(&[Status]) -> Result<Option<Vec<Shell>>>,
{
    let rows: Vec<Status> = install::status(places)
        .into_iter()
        .filter(|s| s.path.is_some())
        .collect();
    let Some(chosen) = ask(&rows)? else {
        return Ok(vec!["cancelled, nothing changed".into()]);
    };
    let installed: Vec<Shell> = rows
        .iter()
        .filter(|s| s.installed)
        .map(|s| s.shell)
        .collect();
    let changes = plan(&installed, &chosen);
    let mut lines = Vec::new();
    for shell in changes.uninstall {
        lines.extend(install::uninstall(shell, places)?);
    }
    for shell in changes.install {
        lines.extend(install::install(shell, cmd.clone(), places)?);
    }
    if lines.is_empty() {
        lines.push("no changes".into());
    }
    Ok(lines)
}

struct Row<'a>(&'a Status);

impl fmt::Display for Row<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mark = if self.0.installed {
            "installed"
        } else {
            "not installed"
        };
        write!(f, "{:<10} {mark}", self.0.shell.name())
    }
}

/// The terminal prompt. Esc and Ctrl-C are a cancel, not an error.
pub fn ask_terminal(rows: &[Status]) -> Result<Option<Vec<Shell>>> {
    use inquire::{InquireError, MultiSelect};
    let options: Vec<Row> = rows.iter().map(Row).collect();
    let on: Vec<usize> = (0..rows.len()).filter(|&i| rows[i].installed).collect();
    let picked = MultiSelect::new(
        "Shells with rtok completions (space toggles, enter applies, esc cancels)",
        options,
    )
    .with_default(&on)
    .prompt();
    match picked {
        Ok(v) => Ok(Some(v.into_iter().map(|r| r.0.shell).collect())),
        Err(InquireError::OperationCanceled | InquireError::OperationInterrupted) => Ok(None),
        Err(e) => {
            Err(e).context("the shell picker failed; use `rtok completions <shell>` or `--install`")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use Shell::{Bash, Fish, Zsh};
    use clap::CommandFactory;

    fn places() -> Places {
        Places {
            home: crate::testutil::tmp_dir("completions-picker"),
            ..Places::default()
        }
    }

    fn cmd() -> Command {
        crate::cli::Cli::command()
    }

    #[test]
    fn plan_covers_install_uninstall_mixed_and_noop() {
        let c = |install: &[Shell], uninstall: &[Shell]| Changes {
            install: install.to_vec(),
            uninstall: uninstall.to_vec(),
        };
        assert_eq!(plan(&[], &[Bash, Zsh]), c(&[Bash, Zsh], &[]));
        assert_eq!(plan(&[Bash, Zsh], &[Zsh]), c(&[], &[Bash]));
        assert_eq!(plan(&[Bash], &[Fish]), c(&[Fish], &[Bash]));
        assert_eq!(plan(&[Bash, Zsh], &[Zsh, Bash]), c(&[], &[]));
    }

    #[test]
    fn status_follows_the_files_install_and_uninstall_write() {
        let p = places();
        let on = |p: &Places| -> Vec<Shell> {
            install::status(p)
                .iter()
                .filter(|s| s.installed)
                .map(|s| s.shell)
                .collect()
        };
        assert!(on(&p).is_empty());
        install::install(Zsh, cmd(), &p).unwrap();
        assert_eq!(on(&p), [Zsh]);
        install::uninstall(Zsh, &p).unwrap();
        assert!(on(&p).is_empty());
        let clink = install::status(&p)
            .into_iter()
            .find(|s| s.shell == Shell::Clink)
            .unwrap();
        assert!(clink.path.is_none() && !clink.installed);
    }

    #[test]
    fn confirmed_selection_installs_and_removes_files() {
        let p = places();
        install::install(Bash, cmd(), &p).unwrap();
        let bash = install::status(&p).remove(0).path.unwrap();
        let lines = run(&p, cmd(), |rows| {
            assert!(
                rows.iter().all(|r| r.shell != Shell::Clink),
                "clink is not offered here"
            );
            assert!(rows.iter().find(|r| r.shell == Bash).unwrap().installed);
            Ok(Some(vec![Zsh]))
        })
        .unwrap();
        assert!(!bash.exists());
        assert!(p.home.join(".zfunc/_rtok").exists());
        assert!(lines.iter().any(|l| l.starts_with("removed ")), "{lines:?}");
        assert!(lines.iter().any(|l| l.starts_with("wrote ")), "{lines:?}");
    }

    #[test]
    fn unchanged_selection_and_cancel_touch_nothing() {
        let p = places();
        install::install(Fish, cmd(), &p).unwrap();
        let lines = run(&p, cmd(), |_| Ok(Some(vec![Fish]))).unwrap();
        assert_eq!(lines, ["no changes"]);
        let lines = run(&p, cmd(), |_| Ok(None)).unwrap();
        assert_eq!(lines, ["cancelled, nothing changed"]);
        assert!(p.home.join(".config/fish/completions/rtok.fish").exists());
        assert!(!p.home.join(".zfunc").exists());
    }

    #[test]
    fn a_failing_prompt_changes_no_file() {
        let p = places();
        let err = run(&p, cmd(), |_| anyhow::bail!("no tty")).unwrap_err();
        assert!(err.to_string().contains("no tty"));
        assert!(
            list(&p).iter().all(|l| l.contains(" no ")),
            "{:?}",
            list(&p)
        );
    }

    #[test]
    fn list_has_a_line_per_shell_with_state_and_path() {
        let p = places();
        install::install(Bash, cmd(), &p).unwrap();
        let lines = list(&p);
        assert_eq!(lines.len(), 6);
        assert!(lines[0].starts_with("bash       yes "), "{}", lines[0]);
        assert!(lines[0].ends_with("completions/rtok"), "{}", lines[0]);
        assert!(lines[1].starts_with("zsh        no  "), "{}", lines[1]);
        assert!(lines[5].ends_with(" -"), "{}", lines[5]);
    }
}

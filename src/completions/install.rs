// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok completions [<shell>] --install | --uninstall | --list` (T318, T408): the script goes to the shell's
//! standard per-user location. PowerShell also gets one dot-source line in `$PROFILE`, zsh one
//! in `.zshenv` (T943); only that line is added or removed, the rest of the file stays
//! byte-for-byte.
//!
//! For bash, zsh, fish and PowerShell the installed file is a wrapper that asks the installed
//! rtok for its script when the shell first needs it (T943): a static copy went stale on every
//! upgrade, and nothing rewrites it. Elvish and Clink keep the static script.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use change_preview::Preview;
use clap::{Command, ValueEnum};

use super::Shell;

/// Where the per-user files live. [`Places::from_env`] reads the process environment; tests
/// build one over a temp dir so no real home is touched.
#[derive(Debug, Clone, Default)]
pub struct Places {
    pub home: PathBuf,
    pub xdg_data: Option<PathBuf>,
    pub xdg_config: Option<PathBuf>,
    pub zdotdir: Option<PathBuf>,
    pub local_app_data: Option<PathBuf>,
    /// `$SHELL`, for picking a shell when none is named.
    pub shell: Option<String>,
    pub windows: bool,
}

impl Places {
    pub fn from_env() -> Result<Self> {
        let var = |k: &str| {
            std::env::var_os(k)
                .filter(|v| !v.is_empty())
                .map(PathBuf::from)
        };
        Ok(Self {
            home: crate::config::env_user_home().context("no HOME or USERPROFILE")?,
            xdg_data: var("XDG_DATA_HOME"),
            xdg_config: var("XDG_CONFIG_HOME"),
            zdotdir: var("ZDOTDIR"),
            local_app_data: var("LOCALAPPDATA"),
            shell: std::env::var("SHELL").ok(),
            windows: cfg!(windows),
        })
    }

    fn config(&self) -> PathBuf {
        self.xdg_config
            .clone()
            .unwrap_or_else(|| self.home.join(".config"))
    }

    /// The script path and, for shells that need it, the note on how the shell finds it.
    fn script(&self, shell: Shell) -> Result<(PathBuf, Option<String>)> {
        Ok(match shell {
            Shell::Bash => {
                let data = self
                    .xdg_data
                    .clone()
                    .unwrap_or_else(|| self.home.join(".local/share"));
                (data.join("bash-completion/completions/rtok"), None)
            }
            Shell::Zsh => (self.zfunc().join("_rtok"), None),
            Shell::Fish => (self.config().join("fish/completions/rtok.fish"), None),
            Shell::Elvish => (
                self.config().join("elvish/lib/rtok.elv"),
                Some("elvish loads it when rc.elv has: use rtok".into()),
            ),
            Shell::Powershell => (self.ps_profile().with_file_name("rtok.ps1"), None),
            Shell::Clink => {
                let Some(dir) = &self.local_app_data else {
                    bail!("clink needs %LOCALAPPDATA% (Clink runs on Windows)");
                };
                (dir.join("clink/rtok.lua"), None)
            }
        })
    }

    fn zdotdir(&self) -> PathBuf {
        self.zdotdir.clone().unwrap_or_else(|| self.home.clone())
    }

    fn zfunc(&self) -> PathBuf {
        self.zdotdir().join(".zfunc")
    }

    /// The rc file that gets one line of ours, and that line. zsh reads `.zshenv` before
    /// `.zshrc` and so before any `compinit`, whatever the rc files do after it.
    fn rc_line(&self, shell: Shell, script: &Path) -> Option<(PathBuf, String)> {
        match shell {
            Shell::Powershell => Some((self.ps_profile(), source_line(script))),
            Shell::Zsh => {
                let init = sh_quote(&self.zfunc().join(ZSH_INIT));
                Some((
                    self.zdotdir().join(".zshenv"),
                    format!("[[ -o interactive && -r {init} ]] && . {init}  # rtok completions"),
                ))
            }
            _ => None,
        }
    }

    /// Files written beside the script: zsh's init, which puts `.zfunc` on `fpath` and
    /// registers `_rtok` itself.
    fn extra_files(&self, shell: Shell) -> Vec<(PathBuf, String)> {
        match shell {
            Shell::Zsh => {
                let dir = sh_quote(&self.zfunc());
                vec![(
                    self.zfunc().join(ZSH_INIT),
                    ZSH_INIT_BODY.replace("{dir}", &dir),
                )]
            }
            _ => Vec::new(),
        }
    }

    /// pwsh's `$PROFILE` (CurrentUserCurrentHost). A Documents folder moved elsewhere (OneDrive)
    /// is not followed; pass the script to `$PROFILE` by hand there.
    fn ps_profile(&self) -> PathBuf {
        let dir = if self.windows {
            self.home.join("Documents/PowerShell")
        } else {
            self.config().join("powershell")
        };
        dir.join("Microsoft.PowerShell_profile.ps1")
    }

    /// The named shell, else the one `$SHELL` names.
    pub fn pick(&self, shell: Option<Shell>) -> Result<Shell> {
        if let Some(s) = shell {
            return Ok(s);
        }
        let name = self
            .shell
            .as_deref()
            .and_then(|s| Path::new(s).file_stem())
            .and_then(|s| s.to_str());
        match name.and_then(|n| Shell::from_str(n, true).ok()) {
            Some(s) => Ok(s),
            None => {
                let all: Vec<String> = Shell::value_variants().iter().map(|s| s.name()).collect();
                bail!(
                    "cannot tell the shell from $SHELL; name one of: {}",
                    all.join(", ")
                )
            }
        }
    }
}

/// One shell's completions as the per-user files hold them now.
#[derive(Debug, Clone)]
pub struct Status {
    pub shell: Shell,
    /// `None` where the shell has no per-user location here (Clink off Windows).
    pub path: Option<PathBuf>,
    pub installed: bool,
}

/// Every shell's state. Installed means the script exists, the one file [`uninstall`] removes.
pub fn status(places: &Places) -> Vec<Status> {
    Shell::value_variants()
        .iter()
        .map(|&shell| {
            let path = places.script(shell).ok().map(|(p, _)| p);
            let installed = path.as_deref().is_some_and(Path::exists);
            Status {
                shell,
                path,
                installed,
            }
        })
        .collect()
}

/// zsh's init beside `_rtok`, sourced from `.zshenv`.
const ZSH_INIT: &str = "rtok.zsh";

// `compinit -C` trusts a dump made before `_rtok` existed and never looks at `fpath` again,
// so the init registers `_rtok` itself on the first prompt, once the rc files ran `compinit`.
const ZSH_INIT_BODY: &str = "\
# Written by `rtok completions zsh --install`; `--uninstall` removes it and its .zshenv line.
fpath=({dir} ${fpath:#{dir}})
autoload -Uz add-zsh-hook
_rtok_compdef() {
  add-zsh-hook -d precmd _rtok_compdef
  unfunction _rtok_compdef
  (( $+functions[compdef] )) && { autoload -Uz _rtok; compdef _rtok rtok }
}
add-zsh-hook precmd _rtok_compdef
";

/// Each wrapper does nothing when `rtok` is not on `PATH`, so an uninstalled binary leaves no
/// error at every prompt.
const BASH_WRAPPER: &str = "\
# Written by `rtok completions bash --install`: the installed rtok prints the script, so an upgrade never leaves it stale.
if command -v rtok >/dev/null 2>&1; then eval \"$(command rtok completions bash)\"; fi
";

// Inside `eval` `$funcstack[1]` is `(eval)`, so the generated tail only runs `compdef`;
// calling `_rtok` here completes the very Tab that loaded it.
const ZSH_WRAPPER: &str = "\
#compdef rtok
# Written by `rtok completions zsh --install`: the installed rtok prints the script, so an upgrade never leaves it stale.
(( $+commands[rtok] )) || return 1
eval \"$(command rtok completions zsh)\" && _rtok \"$@\"
";

const FISH_WRAPPER: &str = "\
# Written by `rtok completions fish --install`: the installed rtok prints the script, so an upgrade never leaves it stale.
command -q rtok; and rtok completions fish | source
";

const POWERSHELL_WRAPPER: &str = "\
# Written by `rtok completions powershell --install`: the installed rtok prints the script, so an upgrade never leaves it stale.
if (Get-Command rtok -CommandType Application -ErrorAction SilentlyContinue) { rtok completions powershell | Out-String | Invoke-Expression }
";

fn source_line(script: &Path) -> String {
    format!(". \"{}\"  # rtok completions", script.display())
}

/// `path` as one POSIX-shell word: single quotes keep `$`, spaces and `"` literal.
fn sh_quote(path: &Path) -> String {
    format!("'{}'", path.display().to_string().replace('\'', r"'\''"))
}

/// The rc file with our line appended; `None` when it is already there.
fn profile_with(old: &str, line: &str) -> Option<String> {
    if old.lines().any(|l| l == line) {
        return None;
    }
    let sep = if old.is_empty() || old.ends_with('\n') {
        ""
    } else {
        "\n"
    };
    Some(format!("{old}{sep}{line}\n"))
}

/// The rc file without our line; `None` when it is not there.
fn profile_without(old: &str, line: &str) -> Option<String> {
    old.lines().any(|l| l == line).then(|| {
        old.split_inclusive('\n')
            .filter(|l| l.trim_end_matches(['\r', '\n']) != line)
            .collect()
    })
}

fn script_body(shell: Shell, cmd: Command) -> Result<String> {
    Ok(match shell {
        Shell::Bash => BASH_WRAPPER.into(),
        Shell::Zsh => ZSH_WRAPPER.into(),
        Shell::Fish => FISH_WRAPPER.into(),
        Shell::Powershell => POWERSHELL_WRAPPER.into(),
        _ => {
            let mut body = Vec::new();
            super::generate(shell, cmd, &mut body);
            String::from_utf8(body).context("completion script is not UTF-8")?
        }
    })
}

/// Every file [`install`] writes whole, script first.
fn files(shell: Shell, cmd: Command, places: &Places) -> Result<Vec<(PathBuf, String)>> {
    let (script, _) = places.script(shell)?;
    let mut files = vec![(script, script_body(shell, cmd)?)];
    files.extend(places.extra_files(shell));
    Ok(files)
}

/// What [`install`] would change, as the shared diff. Writes nothing.
pub fn preview_install(shell: Shell, cmd: Command, places: &Places) -> Result<Preview> {
    let mut preview = Preview::default();
    let files = files(shell, cmd, places)?;
    for (path, body) in &files {
        preview.edit(path, &read(path)?, body);
    }
    if let Some((rc, line)) = places.rc_line(shell, &files[0].0) {
        let old = read(&rc)?;
        if let Some(new) = profile_with(&old, &line) {
            preview.edit(&rc, &old, &new);
        }
    }
    Ok(preview)
}

/// What [`uninstall`] would remove or rewrite. Writes nothing.
pub fn preview_uninstall(shell: Shell, places: &Places) -> Result<Preview> {
    let (script, _) = places.script(shell)?;
    let mut preview = Preview::default();
    for path in owned(shell, places, &script) {
        if let Ok(meta) = std::fs::metadata(&path) {
            preview.remove(&path, meta.len(), 1);
        }
    }
    if let Some((rc, line)) = places.rc_line(shell, &script) {
        let old = read(&rc)?;
        if let Some(new) = profile_without(&old, &line) {
            preview.edit(&rc, &old, &new);
        }
    }
    Ok(preview)
}

/// Write the script, its extra files and the rc line; one report line per file.
pub fn install(shell: Shell, cmd: Command, places: &Places) -> Result<Vec<String>> {
    let (_, note) = places.script(shell)?;
    let files = files(shell, cmd, places)?;
    let mut lines = Vec::new();
    for (path, body) in &files {
        lines.push(write_if_changed(path, body)?);
    }
    if let Some((rc, line)) = places.rc_line(shell, &files[0].0) {
        let old = read(&rc)?;
        match profile_with(&old, &line) {
            None => lines.push(format!("unchanged {}", rc.display())),
            Some(new) => lines.push(write_if_changed(&rc, &new)?),
        }
    }
    lines.extend(note);
    Ok(lines)
}

/// The script and the extra files: what [`uninstall`] deletes.
fn owned(shell: Shell, places: &Places, script: &Path) -> Vec<PathBuf> {
    std::iter::once(script.to_path_buf())
        .chain(places.extra_files(shell).into_iter().map(|(p, _)| p))
        .collect()
}

/// Remove exactly what [`install`] wrote.
pub fn uninstall(shell: Shell, places: &Places) -> Result<Vec<String>> {
    let (script, _) = places.script(shell)?;
    let mut lines = Vec::new();
    for (i, path) in owned(shell, places, &script).iter().enumerate() {
        if path.exists() {
            std::fs::remove_file(path).with_context(|| format!("remove {}", path.display()))?;
            lines.push(format!("removed {}", path.display()));
        } else if i == 0 {
            lines.push(format!("absent {}", path.display()));
        }
    }
    if let Some((rc, line)) = places.rc_line(shell, &script) {
        let old = read(&rc)?;
        if let Some(kept) = profile_without(&old, &line) {
            fs_kit::write::atomic(&rc, &kept)?;
            lines.push(format!("updated {}", rc.display()));
        }
    }
    Ok(lines)
}

fn read(path: &Path) -> Result<String> {
    Ok(fs_kit::read::text_or_empty(path)?)
}

fn write_if_changed(path: &Path, body: &str) -> Result<String> {
    let verb = if fs_kit::write::if_changed(path, body)? {
        "wrote"
    } else {
        "unchanged"
    };
    Ok(format!("{verb} {}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn places() -> Places {
        Places {
            home: crate::testutil::tmp_dir("completions-install"),
            ..Places::default()
        }
    }

    fn cmd() -> Command {
        // Path tests only need the command name the scripts are written under.
        Command::new("rtok")
    }

    /// The path in a report line. Compared as a `Path`, so `/` and `\` match on Windows.
    fn path(line: &str, verb: &str) -> PathBuf {
        PathBuf::from(line.strip_prefix(verb).unwrap_or_else(|| panic!("{line}")))
    }

    #[test]
    fn each_shell_lands_in_its_standard_place_and_reinstall_is_unchanged() {
        let mut p = places();
        p.local_app_data = Some(p.home.join("AppData/Local"));
        for (shell, rel) in [
            (Shell::Bash, ".local/share/bash-completion/completions/rtok"),
            (Shell::Zsh, ".zfunc/_rtok"),
            (Shell::Fish, ".config/fish/completions/rtok.fish"),
            (Shell::Elvish, ".config/elvish/lib/rtok.elv"),
            (Shell::Powershell, ".config/powershell/rtok.ps1"),
            (Shell::Clink, "AppData/Local/clink/rtok.lua"),
        ] {
            let first = install(shell, cmd(), &p).unwrap();
            assert_eq!(path(&first[0], "wrote "), p.home.join(rel), "{shell:?}");
            let again = install(shell, cmd(), &p).unwrap();
            assert!(again[0].starts_with("unchanged "), "{shell:?}: {again:?}");
            assert_eq!(
                path(&uninstall(shell, &p).unwrap()[0], "removed "),
                p.home.join(rel)
            );
            assert!(!p.home.join(rel).exists());
        }
    }

    #[test]
    fn xdg_dirs_win_over_home_defaults() {
        let mut p = places();
        p.xdg_data = Some(p.home.join("data"));
        let lines = install(Shell::Bash, cmd(), &p).unwrap();
        assert!(
            path(&lines[0], "wrote ").ends_with("data/bash-completion/completions/rtok"),
            "{lines:?}"
        );
    }

    #[test]
    fn powershell_profile_keeps_other_content_byte_for_byte() {
        let p = places();
        let profile = p.ps_profile();
        std::fs::create_dir_all(profile.parent().unwrap()).unwrap();
        let mine = "Set-Alias ll ls\r\n# keep me";
        std::fs::write(&profile, mine).unwrap();
        install(Shell::Powershell, cmd(), &p).unwrap();
        let with = std::fs::read_to_string(&profile).unwrap();
        assert!(with.starts_with(&format!("{mine}\n. \"")), "{with}");
        let again = install(Shell::Powershell, cmd(), &p).unwrap();
        assert_eq!(again[1], format!("unchanged {}", profile.display()));
        uninstall(Shell::Powershell, &p).unwrap();
        assert_eq!(
            std::fs::read_to_string(&profile).unwrap(),
            format!("{mine}\n")
        );
    }

    /// T943: zsh needs no `.zshrc` edit: the init goes beside `_rtok` and `.zshenv` gets one
    /// line; the rest of `.zshenv` stays byte-for-byte and uninstall takes back all of it.
    #[test]
    fn zsh_writes_its_init_and_one_zshenv_line_and_uninstall_takes_them_back() {
        let p = places();
        let zshenv = p.home.join(".zshenv");
        let mine = "export A=1\r\n# keep me";
        std::fs::write(&zshenv, mine).unwrap();

        let lines = install(Shell::Zsh, cmd(), &p).unwrap();
        let init = p.home.join(".zfunc").join("rtok.zsh");
        assert_eq!(
            lines,
            [
                format!("wrote {}", p.home.join(".zfunc").join("_rtok").display()),
                format!("wrote {}", init.display()),
                format!("wrote {}", zshenv.display()),
            ]
        );
        let body = std::fs::read_to_string(&init).unwrap();
        let dir = sh_quote(&p.home.join(".zfunc"));
        assert!(
            body.contains(&format!("fpath=({dir} ${{fpath:#{dir}}})")),
            "{body}"
        );
        assert!(body.contains("compdef _rtok rtok"), "{body}");
        let with = std::fs::read_to_string(&zshenv).unwrap();
        let line = format!(
            "[[ -o interactive && -r {q} ]] && . {q}  # rtok completions",
            q = sh_quote(&init)
        );
        assert_eq!(with, format!("{mine}\n{line}\n"));

        let again = install(Shell::Zsh, cmd(), &p).unwrap();
        assert!(
            again.iter().all(|l| l.starts_with("unchanged ")),
            "{again:?}"
        );

        let gone = uninstall(Shell::Zsh, &p).unwrap();
        assert_eq!(gone.len(), 3, "{gone:?}");
        assert!(!init.exists() && !p.home.join(".zfunc/_rtok").exists());
        assert_eq!(
            std::fs::read_to_string(&zshenv).unwrap(),
            format!("{mine}\n")
        );
    }

    #[test]
    fn zsh_follows_zdotdir() {
        let mut p = places();
        p.zdotdir = Some(p.home.join("zd"));
        let lines = install(Shell::Zsh, cmd(), &p).unwrap();
        assert_eq!(path(&lines[0], "wrote "), p.home.join("zd/.zfunc/_rtok"));
        assert_eq!(path(&lines[2], "wrote "), p.home.join("zd/.zshenv"));
    }

    /// T943: a static copy goes stale on upgrade; the installed file asks the installed rtok.
    #[test]
    fn installed_scripts_ask_the_installed_rtok_except_elvish_and_clink() {
        for (shell, call) in [
            (Shell::Bash, "rtok completions bash"),
            (Shell::Zsh, "rtok completions zsh"),
            (Shell::Fish, "rtok completions fish"),
            (Shell::Powershell, "rtok completions powershell"),
        ] {
            let body = script_body(shell, cmd()).unwrap();
            assert!(body.contains(call), "{shell:?}: {body}");
            assert!(body.lines().count() <= 4, "{shell:?} is a wrapper: {body}");
        }
        for shell in [Shell::Elvish, Shell::Clink] {
            let body = script_body(shell, cmd()).unwrap();
            assert!(
                body.lines().count() > 4,
                "{shell:?} is the static script: {body}"
            );
        }
    }

    #[test]
    fn sh_quote_keeps_quotes_and_dollars_literal() {
        assert_eq!(sh_quote(Path::new("/h/it's $HOME")), r"'/h/it'\''s $HOME'");
    }

    #[test]
    fn shell_comes_from_dollar_shell_or_the_error_lists_them() {
        let mut p = places();
        p.shell = Some("/usr/local/bin/fish".into());
        assert_eq!(p.pick(None).unwrap(), Shell::Fish);
        assert_eq!(p.pick(Some(Shell::Zsh)).unwrap(), Shell::Zsh);
        p.shell = None;
        let err = p.pick(None).unwrap_err().to_string();
        assert!(
            err.contains("bash, zsh, fish, powershell, elvish, clink"),
            "{err}"
        );
    }

    #[test]
    fn clink_without_local_app_data_is_refused() {
        let err = install(Shell::Clink, cmd(), &places())
            .unwrap_err()
            .to_string();
        assert!(err.contains("LOCALAPPDATA"), "{err}");
    }
}

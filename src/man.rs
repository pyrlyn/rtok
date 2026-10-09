// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Man pages from the clap tree (T316): `rtok.1` plus one page per visible subcommand
//! (`rtok-agents.1`, `rtok-agents-install.1`, …), each with a `SEE ALSO` to its parent and
//! children. The same `Cli::command()` as `--help`, so there is no second source.

use std::io::{self, Write};
use std::path::{Path, PathBuf};

use clap::Command;
use clap_mangen::Man;

/// Write every page into `dir` (created if missing); returns the paths written, parent first.
pub fn write_all(cmd: Command, dir: &Path) -> io::Result<Vec<PathBuf>> {
    std::fs::create_dir_all(dir)?;
    let (cmd, source) = built(cmd);
    let mut out = Vec::new();
    walk(&cmd, None, &source, dir, &mut out)?;
    Ok(out)
}

fn walk(
    cmd: &Command,
    parent: Option<&str>,
    source: &str,
    dir: &Path,
    out: &mut Vec<PathBuf>,
) -> io::Result<()> {
    let page = page_name(cmd);
    let path = dir.join(format!("{page}.1"));
    let mut file = std::fs::File::create(&path)?;
    render(cmd, parent, source, &mut file)?;
    file.flush()?;
    out.push(path);
    for sub in visible(cmd) {
        walk(sub, Some(&page), source, dir, out)?;
    }
    Ok(())
}

/// `rtok.1` alone, as `rtok man` prints it: the same page [`write_all`] writes first.
pub fn print(cmd: Command, w: &mut dyn Write) -> io::Result<()> {
    let (cmd, source) = built(cmd);
    render(&cmd, None, &source, w)
}

/// The tree as the pages see it (no `help` subcommand, display names set) and the `.TH`
/// source every page shares, `rtok <version>`, so a subcommand's footer is not its bare name.
fn built(cmd: Command) -> (Command, String) {
    let mut cmd = cmd.disable_help_subcommand(true);
    cmd.build();
    let source = format!(
        "{} {}",
        cmd.get_name(),
        cmd.get_version().unwrap_or_default()
    );
    (cmd, source.trim_end().to_string())
}

/// One page: clap_mangen's sections, then `SEE ALSO` when the page has a parent or children.
pub fn render(
    cmd: &Command,
    parent: Option<&str>,
    source: &str,
    w: &mut dyn Write,
) -> io::Result<()> {
    Man::new(cmd.clone()).source(source).render(w)?;
    let refs: Vec<String> = parent
        .map(str::to_string)
        .into_iter()
        .chain(visible(cmd).map(page_name))
        .map(|p| format!("\\fB{p}\\fR(1)"))
        .collect();
    if !refs.is_empty() {
        writeln!(w, ".SH \"SEE ALSO\"\n{}", refs.join(",\n"))?;
    }
    Ok(())
}

fn visible(cmd: &Command) -> impl Iterator<Item = &Command> {
    cmd.get_subcommands().filter(|s| !s.is_hide_set())
}

/// `rtok-agents-install`: the display name clap sets on a built subcommand.
fn page_name(cmd: &Command) -> String {
    cmd.get_display_name()
        .unwrap_or_else(|| cmd.get_name())
        .to_string()
}

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use crate::config::Config;
use crate::ui::style;
use anyhow::Result;
use clap::Subcommand;
use std::path::PathBuf;

/// T329.4.2: which project a graph subcommand answers for, instead of a `path` or the cwd.
#[cfg(feature = "graph")]
#[derive(clap::Args)]
pub(super) struct ProjectFlag {
    /// Project id or directory (see `rtok graph projects`)
    #[arg(long, conflicts_with = "path")]
    pub(super) project: Option<String>,
}

#[cfg(feature = "graph")]
#[derive(Subcommand)]
pub(super) enum GraphCmd {
    /// Walk a tree and insert definitions + references
    Index {
        path: Option<PathBuf>,
        #[command(flatten)]
        project: ProjectFlag,
        /// Report what would be indexed and write no rows
        #[arg(long)]
        dry_run: bool,
    },
    /// List unreferenced private definitions (skips pub, trait impls, tests, macros)
    Dead {
        path: Option<PathBuf>,
        #[command(flatten)]
        project: ProjectFlag,
        // T60.1
        /// JSON rows instead of `path:line kind name` lines (uncapped)
        #[arg(long)]
        json: bool,
    },
    // T68.3
    /// Index health for the current or given root
    Status {
        path: Option<PathBuf>,
        #[command(flatten)]
        project: ProjectFlag,
        #[arg(long)]
        json: bool,
    },
    // T68.4
    /// Symbol impact or call paths to a target
    Impact {
        name: String,
        #[arg(long, default_value_t = 2)]
        depth: u32,
        #[arg(long)]
        to: Option<String>,
        /// Every reached row, not the file-grouped answer cut at `plugins.graph.impact_tokens`
        #[arg(long)]
        all: bool,
        path: Option<PathBuf>,
        #[command(flatten)]
        project: ProjectFlag,
    },
    // T329.2
    /// The project registry: list, add, remove, select
    Projects {
        #[command(subcommand)]
        action: Option<ProjectsCmd>,
        /// JSON instead of a table
        #[arg(long, global = true)]
        json: bool,
    },
    /// Risk-ranked reading list for a git diff (files, untested defs, line ranges)
    Review {
        /// Diff against this ref
        #[arg(long, conflicts_with = "staged")]
        since: Option<String>,
        /// Staged files only (`git diff --cached --name-only`)
        #[arg(long)]
        staged: bool,
        /// JSON instead of the text list
        #[arg(long)]
        json: bool,
        /// Project id or directory instead of the cwd (see `rtok graph projects`)
        #[arg(long)]
        project: Option<String>,
    },
    /// Tests that reach files changed in git (`git diff --name-only`)
    Affected {
        /// Diff against this ref
        #[arg(long, conflicts_with = "staged")]
        since: Option<String>,
        /// Staged files only (`git diff --cached --name-only`)
        #[arg(long)]
        staged: bool,
        /// JSON instead of `file ← via symbol` lines
        #[arg(long)]
        json: bool,
        /// Project id or directory instead of the cwd (see `rtok graph projects`)
        #[arg(long)]
        project: Option<String>,
    },
}

#[cfg(feature = "graph")]
#[derive(Subcommand)]
pub(super) enum ProjectsCmd {
    /// Register a directory as a project (a known one only refreshes its last-used time)
    Add { path: PathBuf },
    /// Make a project the selected one; the page and later the CLI answer for it
    Select {
        /// Project id or directory
        project: String,
    },
    /// Link a project into the selected one's graph scope (indexes it when it never was)
    Link {
        /// Project id or directory to link to
        project: String,
        /// Link from this project instead of the selected one
        #[arg(long)]
        from: Option<String>,
        /// Also link the other way
        #[arg(long)]
        both: bool,
        /// Why (shown next to the link)
        #[arg(long)]
        reason: Option<String>,
    },
    /// Remove a link; an auto link stays removed on re-index
    Unlink {
        /// Project id or directory to unlink
        project: String,
        /// Unlink from this project instead of the selected one
        #[arg(long)]
        from: Option<String>,
        /// Also remove the link the other way
        #[arg(long)]
        both: bool,
    },
    /// Drop a project and its index rows; its files are never touched
    Remove {
        /// Project id or directory
        project: String,
    },
}

pub(super) fn run(config_file: &Option<PathBuf>, action: GraphCmd) -> Result<()> {
    let cfg = Config::load_with(config_file.as_deref(), None)?;
    let cx = crate::plugin::Runtime::open(cfg.clone(), "graph")?;
    match action {
        GraphCmd::Index {
            path,
            project,
            dry_run,
        } => {
            let root = crate::plugins::graph::cli_root_for(&cx.store, path, project.project)?;
            let pb = crate::render::spinner("indexing");
            let r = crate::plugins::graph::index::run_with(
                &crate::plugin::Ctx::new(&cx),
                &root,
                dry_run,
                &pb,
            )?;
            let summary = format!(
                "indexed {} files · {} rows · {} skipped · {} read · exclude {} · include {} · mapped {}",
                r.indexed,
                r.inserted,
                r.skipped,
                r.read,
                r.exclude_skipped,
                r.include_added,
                r.extension_mapped,
            );
            println!("{}", style::success_op("index", &summary));
            if !dry_run {
                crate::plugins::graph::follow::report(&cx, &root);
            }
        }
        GraphCmd::Dead {
            path,
            project,
            json,
        } => {
            // T329.5: the project asked for (else `path`, else the cwd) and what it links to.
            let root = crate::plugins::graph::cli_root(path)?;
            let scope = crate::plugins::graph::scope::resolve(
                &cx.store,
                project.project.as_deref(),
                &root,
            )?;
            let ctx = crate::plugin::Ctx::new(&cx);
            if json {
                println!("{}", crate::plugins::graph::scope::dead_json(&ctx, &scope)?);
            } else {
                print!("{}", crate::plugins::graph::scope::dead(&ctx, &scope)?);
            }
        }
        GraphCmd::Status {
            path,
            project,
            json,
        } => {
            let root = crate::plugins::graph::cli_root_for(&cx.store, path, project.project)?;
            crate::plugins::graph::status::run(&cfg, Some(root), json)?;
        }
        GraphCmd::Projects { action, json } => {
            use crate::plugins::graph::projects::{Action, run};
            let action = match action {
                None => Action::List,
                Some(ProjectsCmd::Add { path }) => Action::Add(path),
                Some(ProjectsCmd::Select { project }) => Action::Select(project),
                Some(ProjectsCmd::Remove { project }) => Action::Remove(project),
                Some(ProjectsCmd::Link {
                    project,
                    from,
                    both,
                    reason,
                }) => Action::Link {
                    to: project,
                    from,
                    both,
                    reason,
                },
                Some(ProjectsCmd::Unlink {
                    project,
                    from,
                    both,
                }) => Action::Unlink {
                    to: project,
                    from,
                    both,
                },
            };
            print!("{}", run(&cx, action, json)?);
        }
        GraphCmd::Impact {
            name,
            depth,
            to,
            all,
            path,
            project,
        } => {
            // Without a project the scope starts at `path` (else the cwd), as for MCP.
            let root = crate::plugins::graph::cli_root(path)?;
            let scope = crate::plugins::graph::scope::resolve(
                &cx.store,
                project.project.as_deref(),
                &root,
            )?;
            let ctx = crate::plugin::Ctx::new(&cx);
            print!(
                "{}",
                crate::plugins::graph::scope::impact(
                    &ctx,
                    &scope,
                    &name,
                    depth,
                    &crate::plugins::graph::Filter {
                        all,
                        ..crate::plugins::graph::Filter::none()
                    },
                    to.as_deref(),
                )?
            );
        }
        GraphCmd::Review {
            since,
            staged,
            json,
            project,
        } => {
            let root = crate::plugins::graph::cli_root(None)?;
            let scope =
                crate::plugins::graph::scope::resolve(&cx.store, project.as_deref(), &root)?;
            print!(
                "{}",
                crate::plugins::graph::scope::review_git(
                    &crate::plugin::Ctx::new(&cx),
                    &scope,
                    since.as_deref(),
                    staged,
                    json,
                )?
            );
        }
        GraphCmd::Affected {
            since,
            staged,
            json,
            project,
        } => {
            let root = crate::plugins::graph::cli_root(None)?;
            let scope =
                crate::plugins::graph::scope::resolve(&cx.store, project.as_deref(), &root)?;
            print!(
                "{}",
                crate::plugins::graph::scope::affected_git(
                    &crate::plugin::Ctx::new(&cx),
                    &scope,
                    since.as_deref(),
                    staged,
                    json,
                )?
            );
        }
    }

    Ok(())
}

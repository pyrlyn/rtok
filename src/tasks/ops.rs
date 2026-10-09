// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Store-backed `rtok task` operations. The CLI parses flags and prints; the open lives here.

use anyhow::Result;

use super::run::Project;
use super::sync::SyncReport;
use super::{ClaimOutcome, NewTask, ReadyItem, Status, Task, TaskId};
use crate::config::Config;
use crate::store::Store;

fn store(cfg: &Config) -> Result<Store> {
    Store::open(&cfg.core.db_path).map_err(Into::into)
}

pub fn seed(cfg: &Config, project: &Project) -> Result<u32> {
    project.seed(&store(cfg)?, None)
}

pub fn create(cfg: &Config, project: &Project, new: &NewTask) -> Result<Task> {
    project.create(&store(cfg)?, new)
}

pub fn set_status(
    cfg: &Config,
    project: &Project,
    id: &TaskId,
    status: Option<Status>,
    force: bool,
) -> Result<Task> {
    project.status(&store(cfg)?, id, status, force)
}

pub fn sync(cfg: &Config, project: &Project) -> Result<SyncReport> {
    super::sync::sync(project, &store(cfg)?)
}

pub fn next(cfg: &Config, project: &Project) -> Result<Option<Task>> {
    project.next(&store(cfg)?)
}

pub fn ready(cfg: &Config, project: &Project) -> Result<Vec<ReadyItem>> {
    project.ready(&store(cfg)?)
}

pub fn claim(
    cfg: &Config,
    project: &Project,
    id: Option<&str>,
    agent: Option<&str>,
) -> Result<ClaimOutcome> {
    let store = store(cfg)?;
    let agent = super::run::require_agent(&store, agent)?;
    let id = id.map(str::parse::<TaskId>).transpose()?;
    project.claim(&store, id.as_ref(), &agent)
}

pub fn release(
    cfg: &Config,
    project: &Project,
    id: &str,
    agent: Option<&str>,
    force: bool,
) -> Result<Task> {
    let store = store(cfg)?;
    let agent = super::run::require_agent(&store, agent)?;
    project.release(&store, &id.parse()?, &agent, force)
}

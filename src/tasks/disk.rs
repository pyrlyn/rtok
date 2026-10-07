// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The `disk` adapter (T441 §7): one Markdown file per task, `<dir>/R12 - <slug>.md`, with a
//! YAML front matter and the description as the body — the Backlog.md layout, readable and
//! diffable in the repository. Done and closed tasks move to `<dir>/done/` (§8), so the plan is
//! a directory listing. Writes are temp file + rename.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};

use super::adapter::{Filter, TaskAdapter};
use super::{NewTask, Status, Task, TaskId};

/// Longest slug in a file name; the title itself lives in the front matter.
const SLUG_MAX: usize = 48;

pub struct DiskAdapter {
    dir: PathBuf,
}

/// The front matter. `description` is the body, not a key.
#[derive(Serialize, Deserialize)]
struct Head {
    id: TaskId,
    title: String,
    status: Status,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    parent: Option<TaskId>,
    created_at: i64,
    updated_at: i64,
}

impl DiskAdapter {
    /// Tasks live in `dir` (`[tasks.disk] dir` joined to the project root).
    pub fn new(dir: PathBuf) -> Self {
        Self { dir }
    }

    fn done_dir(&self) -> PathBuf {
        self.dir.join("done")
    }

    /// `(path, task)` of every task file in `dir`; a missing directory is an empty plan.
    fn scan(&self, dir: &Path) -> Result<Vec<(PathBuf, Task)>> {
        let entries = match std::fs::read_dir(dir) {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(e).with_context(|| format!("disk tasks: {}", dir.display())),
        };
        let mut out = Vec::new();
        for entry in entries {
            let path = entry?.path();
            // Only `<id> - <slug>.md` is ours; a README or notes beside the tasks stay theirs.
            if file_id(&path).is_none() {
                continue;
            }
            let text = std::fs::read_to_string(&path)
                .with_context(|| format!("disk tasks: {}", path.display()))?;
            let task = parse(&text).with_context(|| format!("disk tasks: {}", path.display()))?;
            out.push((path, task));
        }
        Ok(out)
    }

    /// The file holding `id`, in the plan or the archive.
    fn find(&self, id: &TaskId) -> Result<Option<(PathBuf, Task)>> {
        for dir in [self.dir.clone(), self.done_dir()] {
            if let Some(hit) = self
                .scan(&dir)?
                .into_iter()
                .find(|(path, _)| file_id(path).as_ref() == Some(id))
            {
                return Ok(Some(hit));
            }
        }
        Ok(None)
    }

    fn write(&self, dir: &Path, task: &Task) -> Result<PathBuf> {
        std::fs::create_dir_all(dir).with_context(|| format!("disk tasks: {}", dir.display()))?;
        let path = dir.join(file_name(&task.id, &task.title));
        rtok_agent_sdk::write_atomic(&path, &render(task)?)?;
        Ok(path)
    }
}

impl TaskAdapter for DiskAdapter {
    fn name(&self) -> &'static str {
        "disk"
    }

    fn create(&self, task: &NewTask, id: &TaskId) -> Result<Task> {
        if task.title.trim().is_empty() {
            bail!("disk tasks: a task needs a title");
        }
        if self.find(id)?.is_some() {
            bail!("disk tasks: {id} already exists in {}", self.dir.display());
        }
        let now = now();
        let task = Task {
            id: id.clone(),
            title: task.title.trim().to_string(),
            description: task.description.clone(),
            status: Status::Open,
            parent: id.parent(),
            created_at: now,
            updated_at: now,
            external: None,
        };
        self.write(&self.dir, &task)?;
        Ok(task)
    }

    fn list(&self, filter: &Filter) -> Result<Vec<Task>> {
        let mut tasks: Vec<Task> = self.scan(&self.dir)?.into_iter().map(|(_, t)| t).collect();
        if filter.wants_finished() {
            tasks.extend(self.scan(&self.done_dir())?.into_iter().map(|(_, t)| t));
        }
        tasks.retain(|t| filter.matches(t));
        tasks.sort_by(|a, b| a.id.cmp(&b.id));
        Ok(tasks)
    }

    fn get(&self, id: &TaskId) -> Result<Option<Task>> {
        Ok(self.find(id)?.map(|(_, t)| t))
    }

    fn write_status(&self, id: &TaskId, status: Status) -> Result<Task> {
        let Some((old, mut task)) = self.find(id)? else {
            bail!("disk tasks: no task {id} in {}", self.dir.display());
        };
        task.status = status;
        task.updated_at = now();
        let dir = if status.is_active() {
            self.dir.clone()
        } else {
            self.done_dir()
        };
        let new = self.write(&dir, &task)?;
        // The new file is complete before the old one goes: a crash in between leaves the task
        // in both directories (the plan copy wins in `find`), never in neither.
        if new != old {
            std::fs::remove_file(&old).with_context(|| format!("disk tasks: {}", old.display()))?;
        }
        Ok(task)
    }

    fn max_id(&self, prefix: &str) -> Result<Option<TaskId>> {
        let prefix = prefix.to_ascii_uppercase();
        let mut ids: Vec<TaskId> = Vec::new();
        for dir in [self.dir.clone(), self.done_dir()] {
            ids.extend(self.scan(&dir)?.into_iter().map(|(_, t)| t.id));
        }
        Ok(ids.into_iter().filter(|id| id.prefix() == prefix).max())
    }
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

/// The id a file name carries: `R12 - ship-it.md` → `R12`.
fn file_id(path: &Path) -> Option<TaskId> {
    if path.extension()? != "md" {
        return None;
    }
    let stem = path.file_stem()?.to_str()?;
    stem.split_once(" - ")?.0.parse().ok()
}

fn file_name(id: &TaskId, title: &str) -> String {
    format!("{id} - {}.md", slug(title))
}

/// Lower-case ASCII letters and digits joined by single dashes; `task` when nothing is left.
fn slug(title: &str) -> String {
    let mut out = String::new();
    for c in title.chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c.to_ascii_lowercase());
        } else if !out.is_empty() && !out.ends_with('-') {
            out.push('-');
        }
        if out.len() >= SLUG_MAX {
            break;
        }
    }
    let out = out.trim_end_matches('-');
    if out.is_empty() {
        "task".to_string()
    } else {
        out.to_string()
    }
}

fn render(task: &Task) -> Result<String> {
    let head = Head {
        id: task.id.clone(),
        title: task.title.clone(),
        status: task.status,
        parent: task.parent.clone(),
        created_at: task.created_at,
        updated_at: task.updated_at,
    };
    let yaml = serde_saphyr::to_string(&head)?;
    let body = task.description.trim_end();
    Ok(if body.is_empty() {
        format!("---\n{yaml}---\n")
    } else {
        format!("---\n{yaml}---\n\n{body}\n")
    })
}

fn parse(text: &str) -> Result<Task> {
    let text = text.replace("\r\n", "\n");
    let Some(rest) = text.strip_prefix("---\n") else {
        bail!("no front matter (the file must start with ---)");
    };
    let Some((yaml, body)) = rest
        .split_once("\n---\n")
        .or_else(|| rest.strip_suffix("\n---").map(|yaml| (yaml, "")))
    else {
        bail!("front matter is not closed with ---");
    };
    let head: Head = serde_saphyr::from_str(yaml)?;
    Ok(Task {
        id: head.id,
        title: head.title,
        description: body.trim().to_string(),
        status: head.status,
        parent: head.parent,
        created_at: head.created_at,
        updated_at: head.updated_at,
        external: None,
    })
}

#[cfg(test)]
mod tests {
    use super::super::adapter::set_status;
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rtok-disk-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    fn id(s: &str) -> TaskId {
        s.parse().unwrap()
    }

    fn new(title: &str) -> NewTask {
        NewTask {
            title: title.into(),
            description: String::new(),
            parent: None,
        }
    }

    #[test]
    fn create_get_and_list_the_plan() {
        let dir = tmp("plan");
        let disk = DiskAdapter::new(dir.clone());
        let task = NewTask {
            description: "Why and what done means.\n\nSecond paragraph.".into(),
            ..new("Ship the: allocator!")
        };
        disk.create(&task, &id("R1")).unwrap();
        disk.create(&new("Second"), &id("R2")).unwrap();
        disk.create(&new("Sub"), &id("R2.1")).unwrap();
        assert!(dir.join("R1 - ship-the-allocator.md").is_file());

        let got = disk.get(&id("r1")).unwrap().unwrap();
        assert_eq!(got.title, "Ship the: allocator!");
        assert_eq!(got.description, task.description);
        assert_eq!(got.status, Status::Open);
        assert_eq!(
            disk.get(&id("R2.1")).unwrap().unwrap().parent,
            Some(id("R2"))
        );
        assert_eq!(disk.get(&id("R9")).unwrap(), None);

        let ids: Vec<String> = disk
            .list(&Filter::default())
            .unwrap()
            .iter()
            .map(|t| t.id.to_string())
            .collect();
        assert_eq!(ids, ["R1", "R2", "R2.1"]);
        let subs = disk
            .list(&Filter {
                parent: Some(id("R2")),
                ..Filter::default()
            })
            .unwrap();
        assert_eq!(subs.len(), 1);
        assert!(disk.create(&new("Again"), &id("R1")).is_err(), "id taken");
        assert!(disk.create(&new("  "), &id("R3")).is_err(), "title");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn done_tasks_leave_the_plan_and_can_come_back() {
        let dir = tmp("done");
        let disk = DiskAdapter::new(dir.clone());
        disk.create(&new("Parent"), &id("R1")).unwrap();
        disk.create(&new("Child"), &id("R1.1")).unwrap();

        let err = set_status(&disk, &id("R1"), Status::Done, false).unwrap_err();
        assert!(err.to_string().contains("R1.1"), "{err}");

        set_status(&disk, &id("R1.1"), Status::Closed, false).unwrap();
        set_status(&disk, &id("R1"), Status::Done, false).unwrap();
        assert!(disk.list(&Filter::default()).unwrap().is_empty());
        assert!(dir.join("done/R1 - parent.md").is_file());
        assert!(!dir.join("R1 - parent.md").exists());

        let all = disk
            .list(&Filter {
                all: true,
                ..Filter::default()
            })
            .unwrap();
        assert_eq!(all.len(), 2);
        let closed = disk
            .list(&Filter {
                statuses: vec![Status::Closed],
                ..Filter::default()
            })
            .unwrap();
        assert_eq!(closed[0].id, id("R1.1"));

        set_status(&disk, &id("R1"), Status::InProgress, false).unwrap();
        assert!(dir.join("R1 - parent.md").is_file());
        assert_eq!(disk.max_id("r").unwrap(), Some(id("R1.1")));
        assert_eq!(disk.max_id("Q").unwrap(), None);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_forced_parent_closes_over_open_subtasks() {
        let dir = tmp("force");
        let disk = DiskAdapter::new(dir.clone());
        disk.create(&new("Parent"), &id("R1")).unwrap();
        disk.create(&new("Child"), &id("R1.1")).unwrap();
        set_status(&disk, &id("R1"), Status::Done, true).unwrap();
        assert!(set_status(&disk, &id("R7"), Status::Done, false).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn foreign_files_are_ignored_and_broken_ones_are_named() {
        let dir = tmp("foreign");
        let disk = DiskAdapter::new(dir.clone());
        assert!(
            disk.list(&Filter::default()).unwrap().is_empty(),
            "no dir yet"
        );
        disk.create(&new("Real"), &id("R1")).unwrap();
        std::fs::write(dir.join("README.md"), "# tasks\n").unwrap();
        std::fs::write(dir.join("R2 - notes.txt"), "x").unwrap();
        assert_eq!(disk.list(&Filter::default()).unwrap().len(), 1);
        std::fs::write(dir.join("R3 - broken.md"), "no front matter\n").unwrap();
        let err = disk.list(&Filter::default()).unwrap_err();
        assert!(format!("{err:#}").contains("R3 - broken.md"), "{err:#}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_file_round_trips_through_render_and_parse() {
        let task = Task {
            id: id("R4"),
            title: "Title: with \"quotes\" and # hash".into(),
            description: "Body\n---\nafter a rule".into(),
            status: Status::InProgress,
            parent: None,
            created_at: 10,
            updated_at: 20,
            external: None,
        };
        let text = render(&task).unwrap();
        assert!(text.starts_with("---\nid: R4\n"), "{text}");
        assert_eq!(parse(&text).unwrap(), task);
        assert_eq!(parse(&text.replace('\n', "\r\n")).unwrap(), task);
        let bare = Task {
            description: String::new(),
            ..task
        };
        assert_eq!(parse(&render(&bare).unwrap()).unwrap(), bare);
    }

    #[test]
    fn slugs_are_short_and_safe() {
        assert_eq!(slug("Fix ../../etc/passwd"), "fix-etc-passwd");
        assert_eq!(slug("Привет"), "task");
        assert!(slug(&"word ".repeat(40)).len() <= SLUG_MAX);
    }
}

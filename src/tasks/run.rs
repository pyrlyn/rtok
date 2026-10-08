// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! What `rtok task …` does (T441.5), shaped so the MCP `task_*` tools (T441.6) call the same
//! functions and print the same JSON: open the project's adapter, allocate ids from the store,
//! pick the next task, and write `[tasks]` into `.rtok.toml`.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use serde::Serialize;

use super::adapter::{Filter, Taken, TaskAdapter, set_status};
use super::disk::DiskAdapter;
use super::github::{self, GithubAdapter};
use super::remote;
use super::{NewTask, Status, Task, TaskId, check_prefix, resolve_prefix};
use crate::config::layers::git_root;
use crate::store::Store;

/// The adapters `[tasks] adapter` accepts; the config validator checks the same set.
pub const ADAPTERS: [&str; 3] = ["disk", "github", "gitlab"];

/// Creates tried when the id keeps turning out taken, before the last error is the answer.
const TAKEN_RETRIES: usize = 3;

/// One project's tasks: where they are stored, the counter they are numbered from and the
/// prefix new ids get.
pub struct Project {
    /// The counter key, [`crate::project::project_key`].
    pub key: String,
    /// The checkout's root: `.rtok.toml` and the disk adapter's directory live here.
    pub root: PathBuf,
    pub prefix: String,
    adapter: Box<dyn TaskAdapter>,
}

/// `rtok task show`: the task and the ids of its subtasks.
#[derive(Debug, Serialize)]
pub struct Shown {
    #[serde(flatten)]
    pub task: Task,
    pub subtasks: Vec<TaskId>,
}

impl Project {
    /// The project `cwd` belongs to, with the adapter `[tasks]` names.
    pub fn open(cfg: &crate::config::Tasks, cwd: &Path) -> Result<Self> {
        let root = git_root(cwd).context("rtok task: not inside a git checkout")?;
        let key = crate::project::project_key(cwd)
            .context("rtok task: cannot tell which project this checkout is")?;
        let name = crate::project::project_name(cwd);
        let prefix = resolve_prefix(&cfg.prefix, name.as_deref())?;
        let adapter: Box<dyn TaskAdapter> = match cfg.adapter.as_str() {
            "disk" => Box::new(DiskAdapter::new(root.join(&cfg.disk.dir))),
            "github" => {
                let repo = github::repo(&cfg.github.repo, &key)?;
                let token = remote::token(
                    "github",
                    &["GH_TOKEN", "GITHUB_TOKEN"],
                    &["gh", "auth", "token"],
                )?;
                Box::new(
                    GithubAdapter::new(github::API, &repo, &token, remote::WRITE_GAP)?
                        .with_project(cfg.github.project.into()),
                )
            }
            "gitlab" => bail!(
                "rtok task: the gitlab adapter is not built yet (T441.8); set [tasks] adapter = \"disk\" or \"github\""
            ),
            other => bail!("rtok task: unknown [tasks] adapter {other:?}"),
        };
        Ok(Self::with_adapter(key, root, prefix, adapter))
    }

    pub fn with_adapter(
        key: String,
        root: PathBuf,
        prefix: String,
        adapter: Box<dyn TaskAdapter>,
    ) -> Self {
        Self {
            key,
            root,
            prefix,
            adapter,
        }
    }

    pub fn adapter(&self) -> &dyn TaskAdapter {
        self.adapter.as_ref()
    }

    /// Store a new task under the next free id. The counter is first raised past every id
    /// the adapter already holds, so tasks that arrived without this machine's counter (a
    /// pull, another checkout's clone) are never numbered over.
    pub fn create(&self, store: &Store, new: &NewTask) -> Result<Task> {
        if let Some(p) = &new.parent
            && self.adapter.get(p)?.is_none()
        {
            bail!("rtok task: no parent task {p}");
        }
        let parent = new.parent.as_ref();
        self.seed(store, parent)?;
        for _ in 1..TAKEN_RETRIES {
            let id = store.allocate_task_id(&self.key, &self.prefix, parent)?;
            match self.adapter.create(new, &id) {
                // Another machine's counter got there between the seed and the create (a
                // remote adapter's own check); seeding again jumps past everything it made.
                Err(e) if e.downcast_ref::<Taken>().is_some() => self.seed(store, parent)?,
                done => return done,
            };
        }
        let id = store.allocate_task_id(&self.key, &self.prefix, parent)?;
        self.adapter.create(new, &id)
    }

    /// Raise the counter for `parent` (`None`: top level) to the highest number in use.
    /// Every prefix counts: the counter belongs to the project, not to its current prefix.
    pub fn seed(&self, store: &Store, parent: Option<&TaskId>) -> Result<u32> {
        let tasks = self.adapter.list(&Filter {
            all: true,
            parent: parent.cloned(),
            ..Filter::default()
        })?;
        let depth = parent.map_or(0, |p| p.path().len());
        let max = tasks
            .iter()
            .filter_map(|t| t.id.path().get(depth).copied())
            .max()
            .unwrap_or(0);
        store.seed_task_counter(&self.key, parent, max)
    }

    /// The task, or an error naming the id.
    pub fn get(&self, id: &TaskId) -> Result<Task> {
        self.adapter
            .get(id)?
            .with_context(|| format!("no task {id}"))
    }

    /// `status <id> [<status>]`: set the status when given, else read it.
    pub fn status(&self, id: &TaskId, status: Option<Status>, force: bool) -> Result<Task> {
        match status {
            Some(s) => set_status(self.adapter(), id, s, force),
            None => self.get(id),
        }
    }

    pub fn show(&self, id: &TaskId) -> Result<Shown> {
        let task = self.get(id)?;
        let subtasks = self
            .adapter
            .list(&Filter {
                all: true,
                parent: Some(id.clone()),
                ..Filter::default()
            })?
            .into_iter()
            .map(|t| t.id)
            .collect();
        Ok(Shown { task, subtasks })
    }

    /// The lowest open task with no active subtask: work starts at the leaves, and an
    /// in-progress task is already someone's.
    pub fn next(&self) -> Result<Option<Task>> {
        let active = self.adapter.list(&Filter::default())?;
        Ok(active
            .iter()
            .filter(|t| t.status == Status::Open)
            .find(|t| !active.iter().any(|c| c.parent.as_ref() == Some(&t.id)))
            .cloned())
    }
}

/// `rtok task init`: write `[tasks] adapter` and `prefix` into the checkout's `.rtok.toml`,
/// keeping every other line. Returns the file.
pub fn init(cwd: &Path, adapter: Option<&str>, prefix: Option<&str>) -> Result<PathBuf> {
    if let Some(a) = adapter
        && !ADAPTERS.contains(&a)
    {
        bail!(
            "rtok task init: --adapter must be one of {}",
            ADAPTERS.join(", ")
        );
    }
    if let Some(p) = prefix {
        check_prefix(p)?;
    }
    let root = git_root(cwd).context("rtok task init: not inside a git checkout")?;
    let path = root.join(".rtok.toml");
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(e).with_context(|| path.display().to_string()),
    };
    let mut doc: toml_edit::DocumentMut = text
        .parse()
        .with_context(|| format!("{}: not valid TOML", path.display()))?;
    let tasks = doc
        .entry("tasks")
        .or_insert_with(|| toml_edit::Item::Table(toml_edit::Table::new()))
        .as_table_mut()
        .with_context(|| format!("{}: `tasks` is not a table", path.display()))?;
    if let Some(a) = adapter {
        tasks["adapter"] = toml_edit::value(a);
    } else if !tasks.contains_key("adapter") {
        tasks["adapter"] = toml_edit::value("disk");
    }
    if let Some(p) = prefix {
        tasks["prefix"] = toml_edit::value(p.to_ascii_uppercase());
    }
    crate::config::write_file(&path, &doc.to_string())?;
    Ok(path)
}

/// `list`'s filter from what a caller passed: status names, `all`, a parent id.
pub fn filter(statuses: &[String], all: bool, parent: Option<&str>) -> Result<Filter> {
    Ok(Filter {
        statuses: statuses
            .iter()
            .map(|s| s.parse::<Status>())
            .collect::<Result<_>>()?,
        all,
        parent: parent.map(str::parse::<TaskId>).transpose()?,
    })
}

/// `rtok task list`: one line per task, subtasks indented under their parent.
pub fn table(tasks: &[Task]) -> String {
    let width = tasks
        .iter()
        .map(|t| t.id.to_string().len() + 2 * (t.id.path().len() - 1))
        .max()
        .unwrap_or(0);
    let mut out = String::new();
    for t in tasks {
        let id = format!("{}{}", "  ".repeat(t.id.path().len() - 1), t.id);
        out.push_str(&format!(
            "{id:<width$}  {:<11}  {}\n",
            t.status.as_str(),
            t.title
        ));
    }
    out
}

/// `rtok task show`: the fields, then the description.
pub fn details(shown: &Shown) -> String {
    let t = &shown.task;
    let mut out = format!("{}  {}\nstatus: {}\n", t.id, t.title, t.status);
    if let Some(p) = &t.parent {
        out.push_str(&format!("parent: {p}\n"));
    }
    if !shown.subtasks.is_empty() {
        let ids: Vec<String> = shown.subtasks.iter().map(ToString::to_string).collect();
        out.push_str(&format!("subtasks: {}\n", ids.join(", ")));
    }
    if let Some(x) = &t.external {
        out.push_str(&format!("link: {}\n", x.url));
    }
    if !t.description.is_empty() {
        out.push_str(&format!("\n{}\n", t.description));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn checkout(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rtok-taskrun-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(".git")).unwrap();
        std::fs::write(
            dir.join(".git/config"),
            "[remote \"origin\"]\n\turl = git@github.com:me/airtalk.git\n",
        )
        .unwrap();
        dir
    }

    fn new(title: &str, parent: Option<&str>) -> NewTask {
        NewTask {
            title: title.into(),
            description: String::new(),
            parent: parent.map(|p| p.parse().unwrap()),
        }
    }

    #[test]
    fn create_numbers_past_tasks_already_on_disk_and_next_picks_a_leaf() {
        let dir = checkout("create");
        let store = Store::open_in_memory().unwrap();
        let project = Project::open(&crate::config::Tasks::default(), &dir).unwrap();
        assert_eq!(project.prefix, "A");
        assert_eq!(project.key, "github.com/me/airtalk");

        // A task that came in with a pull: the counter never saw it.
        let pulled: TaskId = "A7".parse().unwrap();
        project
            .adapter()
            .create(&new("Pulled", None), &pulled)
            .unwrap();
        let t = project.create(&store, &new("First", None)).unwrap();
        assert_eq!(t.id.to_string(), "A8");
        let sub = project.create(&store, &new("Leaf", Some("A7"))).unwrap();
        assert_eq!(sub.id.to_string(), "A7.1");
        assert!(project.create(&store, &new("Orphan", Some("A99"))).is_err());

        assert_eq!(project.next().unwrap().unwrap().id.to_string(), "A7.1");
        let shown = project.show(&pulled).unwrap();
        assert_eq!(shown.subtasks, vec![sub.id.clone()]);
        assert!(details(&shown).contains("subtasks: A7.1"));
        let listed = project.adapter().list(&Filter::default()).unwrap();
        assert_eq!(
            table(&listed),
            "A7      open         Pulled\n  A7.1  open         Leaf\nA8      open         First\n"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn init_writes_tasks_and_keeps_the_rest_of_the_file() {
        let dir = checkout("init");
        std::fs::write(dir.join(".rtok.toml"), "# mine\n[proxy]\nport = 9999\n").unwrap();
        let path = init(&dir, None, Some("at")).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.starts_with("# mine\n[proxy]\nport = 9999\n"), "{text}");
        assert!(
            text.contains("[tasks]\nadapter = \"disk\"\nprefix = \"AT\"\n"),
            "{text}"
        );
        init(&dir, Some("github"), None).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.contains("adapter = \"github\"") && text.contains("prefix = \"AT\""));
        assert!(init(&dir, Some("jira"), None).is_err());
        assert!(init(&dir, None, Some("A1")).is_err());
        assert!(crate::config::validate::issues(&path).unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn remote_adapters_say_they_are_not_built_yet() {
        let dir = checkout("remote");
        let cfg = crate::config::Tasks {
            adapter: "gitlab".into(),
            ..crate::config::Tasks::default()
        };
        let err = Project::open(&cfg, &dir).err().unwrap();
        assert!(err.to_string().contains("not built yet"), "{err}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}

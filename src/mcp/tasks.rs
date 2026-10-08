// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T441.6: MCP `task_create`, `task_list`, `task_get`, `task_status` and `task_next`, the
//! agent-facing side of `rtok task …`. They call the same `tasks::run` functions as the CLI and
//! answer with the JSON its `--json` prints.

use anyhow::{Result, bail};
use serde_json::{Value, json};

use crate::config::Config;
use crate::plugin::{Runtime, ToolDef};
use crate::tasks::run::{Project, filter};
use crate::tasks::{NewTask, Status, TaskId};

pub fn defs() -> [ToolDef; 5] {
    [
        ToolDef {
            name: "task_create",
            description: "Add a task to this project's plan under the next free id (A12, or A12.3 under parent). Returns the task.",
            input_schema: json!({"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},"parent":{"type":"string","description":"parent task id, e.g. A12"}},"required":["title"]}),
        },
        ToolDef {
            name: "task_list",
            description: "This project's tasks: active ones by default, subtasks after their parent. status narrows to those statuses (open, in-progress, done, closed); all adds finished ones.",
            input_schema: json!({"type":"object","properties":{"status":{"type":"array","items":{"type":"string"}},"all":{"type":"boolean"},"parent":{"type":"string"}}}),
        },
        ToolDef {
            name: "task_get",
            description: "One task with its description and the ids of its subtasks.",
            input_schema: json!({"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}),
        },
        ToolDef {
            name: "task_status",
            description: "Read a task's status, or set it to open, in-progress, done or closed. Finishing a task with active subtasks is refused unless force.",
            input_schema: json!({"type":"object","properties":{"id":{"type":"string"},"status":{"type":"string"},"force":{"type":"boolean"}},"required":["id"]}),
        },
        ToolDef {
            name: "task_next",
            description: "The task to work on next: the lowest open task with no active subtask, or null.",
            input_schema: json!({"type":"object","properties":{}}),
        },
    ]
}

fn arg<'a>(args: &'a Value, key: &str) -> Option<&'a str> {
    args.get(key)
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
}

fn id(args: &Value) -> Result<TaskId> {
    match arg(args, "id") {
        Some(id) => id.parse(),
        None => bail!("`id` is required, e.g. A12"),
    }
}

/// The answer of the task tool `name`.
pub fn call(cx: &Runtime, name: &str, args: &Value) -> Result<String> {
    let cwd = std::env::current_dir()?;
    // Read for the cwd now, not at server start: `roots/list` can move the cwd into the
    // project after launch, and `[tasks] prefix` lives in that project's `.rtok.toml`.
    let cfg = Config::load()?;
    let project = Project::open(&cfg.tasks, &cwd)?;
    let out = match name {
        "task_create" => {
            let Some(title) = arg(args, "title") else {
                bail!("`title` is required");
            };
            let new = NewTask {
                title: title.into(),
                description: arg(args, "description").unwrap_or_default().into(),
                parent: arg(args, "parent").map(str::parse).transpose()?,
            };
            serde_json::to_value(project.create(&cx.store, &new)?)?
        }
        "task_list" => {
            let statuses: Vec<String> = match &args["status"] {
                Value::Array(items) => items
                    .iter()
                    .filter_map(Value::as_str)
                    .map(String::from)
                    .collect(),
                Value::String(s) => s.split(',').map(|s| s.trim().to_string()).collect(),
                _ => Vec::new(),
            };
            let all = args["all"].as_bool().unwrap_or(false);
            let f = filter(&statuses, all, arg(args, "parent"))?;
            serde_json::to_value(project.adapter().list(&f)?)?
        }
        "task_get" => serde_json::to_value(project.show(&id(args)?)?)?,
        "task_status" => {
            let status = arg(args, "status").map(str::parse::<Status>).transpose()?;
            let force = args["force"].as_bool().unwrap_or(false);
            serde_json::to_value(project.status(&id(args)?, status, force)?)?
        }
        "task_next" => serde_json::to_value(project.next()?)?,
        other => bail!("unknown tool {other}"),
    };
    Ok(out.to_string())
}

#[cfg(test)]
mod tests {
    use super::defs;

    /// The shipped skill is the rule line that sends agents here (T441.10): it must name every
    /// tool this server lists, and no `task_*` tool it lacks.
    #[test]
    fn the_task_skill_names_exactly_these_tools() {
        let skill = include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/skills/rtok-tasks/SKILL.md"
        ));
        let named: std::collections::BTreeSet<&str> = skill
            .split('`')
            .skip(1)
            .step_by(2)
            .filter(|s| s.starts_with("task_"))
            .collect();
        let listed: std::collections::BTreeSet<&str> = defs().iter().map(|d| d.name).collect();
        assert_eq!(named, listed);
    }
}

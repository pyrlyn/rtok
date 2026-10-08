// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Projects v2 `Status` for the `github` adapter (T441.11). With `[tasks.github] project` set,
//! each task's issue joins that project and its `Status` single-select follows the task
//! status. The issue stays the source of truth: every failure here is a warning, never an
//! error, so a missing scope or a renamed option cannot stop a task write.

use std::cell::RefCell;

use anyhow::{Context, Result, bail};
use reqwest::Method;
use serde::Deserialize;
use serde_json::{Value, json};

use super::remote::Http;
use super::{Status, TaskId};

/// One query for both owner kinds: a project belongs to a user or an organization, and the
/// repository owner is whichever the repo path names.
const RESOLVE: &str = "query($owner:String!,$number:Int!){repositoryOwner(login:$owner){\
    ... on User{projectV2(number:$number){...P}} ... on Organization{projectV2(number:$number){...P}}}}\
    fragment P on ProjectV2{id field(name:\"Status\"){... on ProjectV2SingleSelectField{id options{id name}}}}";

/// `addProjectV2ItemById` returns the existing item when the issue is already in the project,
/// so one call both adds a new issue and finds an old one.
const ADD: &str = "mutation($project:ID!,$content:ID!){addProjectV2ItemById(input:{projectId:$project,contentId:$content}){item{id}}}";

const SET: &str = "mutation($project:ID!,$item:ID!,$field:ID!,$option:String!){updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$field,value:{singleSelectOptionId:$option}}){projectV2Item{id}}}";

/// The option names a status may have, first the project template's default. A project with
/// other names is left alone rather than guessed at. `Closed` shares `Done`: the default
/// Status field has no "won't do" column, GitHub's own close workflow files closed issues
/// under Done, and the `not_planned` reason stays on the issue.
fn option_names(status: Status) -> &'static [&'static str] {
    match status {
        Status::Open => &["Todo", "To do"],
        Status::InProgress => &["In Progress"],
        Status::Done | Status::Closed => &["Done"],
    }
}

#[derive(Deserialize, Clone)]
struct Option_ {
    id: String,
    name: String,
}

#[derive(Clone)]
struct Resolved {
    project: String,
    field: String,
    options: Vec<Option_>,
}

pub struct ProjectSync {
    owner: String,
    number: u64,
    /// Only a success is kept: a network blip must not switch the project off for a long-lived
    /// MCP session, and a misconfigured project only costs one request per write.
    resolved: RefCell<Option<Resolved>>,
}

impl ProjectSync {
    /// `repo` is `owner/name`; the owner is the project's owner.
    pub fn new(repo: &str, number: u64) -> Self {
        Self {
            owner: repo.split_once('/').map_or(repo, |(o, _)| o).to_string(),
            number,
            resolved: RefCell::new(None),
        }
    }

    /// Put the issue behind `node_id` in the project with the Status `status` maps to.
    pub fn sync(&self, http: &Http, id: &TaskId, node_id: Option<&str>, status: Status) {
        if let Err(e) = self.try_sync(http, node_id, status) {
            log::warn!(
                "github tasks: project #{}: Status of {id} not updated, the issue is: {e:#}",
                self.number
            );
        }
    }

    fn try_sync(&self, http: &Http, node_id: Option<&str>, status: Status) -> Result<()> {
        let node = node_id.context("the issue came back without a node_id")?;
        let r = self.resolve(http)?;
        let names = option_names(status);
        let option = r
            .options
            .iter()
            .find(|o| names.iter().any(|n| o.name.eq_ignore_ascii_case(n)))
            .with_context(|| format!("the Status field has no {:?} option", names[0]))?;
        let added = graphql(http, ADD, json!({"project": r.project, "content": node}))?;
        let item = str_at(&added, "/addProjectV2ItemById/item/id")?;
        graphql(
            http,
            SET,
            json!({"project": r.project, "item": item, "field": r.field, "option": option.id}),
        )?;
        Ok(())
    }

    fn resolve(&self, http: &Http) -> Result<Resolved> {
        if let Some(r) = self.resolved.borrow().clone() {
            return Ok(r);
        }
        let data = graphql(
            http,
            RESOLVE,
            json!({"owner": self.owner, "number": self.number}),
        )?;
        let project = data
            .pointer("/repositoryOwner/projectV2")
            .filter(|p| !p.is_null())
            .with_context(|| format!("{} has no project #{}", self.owner, self.number))?;
        let field = project
            .get("field")
            .filter(|f| f.get("id").is_some())
            .context("the project has no single-select field named Status")?;
        let r = Resolved {
            project: str_at(project, "/id")?,
            field: str_at(field, "/id")?,
            options: serde_json::from_value(field["options"].clone())
                .context("the Status field has no options")?,
        };
        *self.resolved.borrow_mut() = Some(r.clone());
        Ok(r)
    }
}

fn str_at(v: &Value, pointer: &str) -> Result<String> {
    v.pointer(pointer)
        .and_then(Value::as_str)
        .map(String::from)
        .with_context(|| format!("GraphQL answer lacks {pointer}"))
}

/// A GraphQL call. The server answers 200 with an `errors` list for a missing scope or an
/// unknown project, so the status code alone says nothing.
fn graphql(http: &Http, query: &str, variables: Value) -> Result<Value> {
    let body = json!({"query": query, "variables": variables});
    let mut res: Value = http.send(Method::POST, "/graphql", &body)?;
    if let Some(errors) = res.get("errors").and_then(Value::as_array)
        && !errors.is_empty()
    {
        let msgs: Vec<&str> = errors
            .iter()
            .filter_map(|e| e.get("message")?.as_str())
            .collect();
        bail!("GraphQL: {}", msgs.join("; "));
    }
    Ok(res["data"].take())
}

#[cfg(test)]
mod tests {
    use super::super::adapter::TaskAdapter;
    use super::super::github::GithubAdapter;
    use super::super::{NewTask, Status, TaskId};
    use httpmock::{Mock, MockServer};
    use serde_json::{Value, json};
    use std::time::Duration;

    const ISSUES: &str = "/repos/me/app/issues";

    fn id(s: &str) -> TaskId {
        s.parse().unwrap()
    }

    fn issue(number: u64, task: &str, state: &str, reason: Option<&str>) -> Value {
        json!({
            "id": number * 100, "node_id": format!("I_kw{number}"), "number": number,
            "html_url": format!("https://github.com/me/app/issues/{number}"),
            "title": format!("{task}. Task {number}"), "body": "", "state": state,
            "state_reason": reason,
            "labels": [{"name": "rtok"}, {"name": format!("rtok:{task}")}],
            "created_at": "2026-10-07T10:00:00Z", "updated_at": "2026-10-07T11:00:00Z",
        })
    }

    fn adapter(server: &MockServer, project: u64) -> GithubAdapter {
        GithubAdapter::new(&server.base_url(), "me/app", "tok", Duration::ZERO)
            .unwrap()
            .with_project(project)
    }

    fn gql<'a>(server: &'a MockServer, needle: &str, answer: Value) -> Mock<'a> {
        server.mock(|when, then| {
            when.method("POST").path("/graphql").body_includes(needle);
            then.status(200).json_body(answer);
        })
    }

    fn project_answer(options: &[(&str, &str)]) -> Value {
        let options: Vec<Value> = options
            .iter()
            .map(|(id, name)| json!({"id": id, "name": name}))
            .collect();
        json!({"data": {"repositoryOwner": {"projectV2": {
            "id": "PVT_1",
            "field": {"id": "PVTSSF_1", "options": options},
        }}}})
    }

    fn standard(server: &MockServer) -> Mock<'_> {
        gql(
            server,
            "repositoryOwner",
            project_answer(&[
                ("o_todo", "Todo"),
                ("o_doing", "In Progress"),
                ("o_done", "Done"),
            ]),
        )
    }

    fn added(server: &MockServer) -> Mock<'_> {
        gql(
            server,
            "addProjectV2ItemById",
            json!({"data": {"addProjectV2ItemById": {"item": {"id": "PVTI_1"}}}}),
        )
    }

    fn set_to<'a>(server: &'a MockServer, option: &str) -> Mock<'a> {
        let needle = format!(r#""option":"{option}""#);
        gql(
            server,
            &needle,
            json!({"data": {"updateProjectV2ItemFieldValue": {"projectV2Item": {"id": "PVTI_1"}}}}),
        )
    }

    fn label_lookup(server: &MockServer, label: &str, found: Value) {
        server.mock(|when, then| {
            when.method("GET")
                .path(ISSUES)
                .query_param("labels", label)
                .query_param("state", "all");
            then.status(200).json_body(found);
        });
    }

    /// Issue 1 can be found by its label and patched.
    fn issue_one_exists(server: &MockServer) {
        label_lookup(server, "rtok:A1", json!([issue(1, "A1", "open", None)]));
        server.mock(|when, then| {
            when.method("PATCH").path(format!("{ISSUES}/1"));
            then.status(200).json_body(issue(1, "A1", "open", None));
        });
    }

    fn new_task() -> NewTask {
        NewTask {
            title: "Mine".into(),
            description: String::new(),
            parent: None,
        }
    }

    fn create_mocks(server: &MockServer) {
        label_lookup(server, "rtok:A1", json!([]));
        server.mock(|when, then| {
            when.method("POST").path(ISSUES);
            then.status(201).json_body(issue(1, "A1", "open", None));
        });
    }

    #[test]
    fn a_new_issue_joins_the_project_as_todo() {
        let server = MockServer::start();
        create_mocks(&server);
        let resolve = standard(&server);
        let add = gql(
            &server,
            r#""content":"I_kw1""#,
            json!({"data": {"addProjectV2ItemById": {"item": {"id": "PVTI_1"}}}}),
        );
        let set = set_to(&server, "o_todo");
        adapter(&server, 4).create(&new_task(), &id("A1")).unwrap();
        for m in [&resolve, &add, &set] {
            m.assert();
        }
    }

    #[test]
    fn status_changes_follow_and_the_ids_are_resolved_once() {
        let server = MockServer::start();
        let resolve = standard(&server);
        let add = added(&server);
        let sets = ["o_todo", "o_doing", "o_done"].map(|o| set_to(&server, o));
        issue_one_exists(&server);
        let gh = adapter(&server, 4);
        for status in [
            Status::InProgress,
            Status::Done,
            Status::Closed,
            Status::Open,
        ] {
            gh.write_status(&id("A1"), status).unwrap();
        }
        assert_eq!(resolve.calls(), 1, "cached after the first write");
        assert_eq!(add.calls(), 4);
        let hits: Vec<usize> = sets.iter().map(|m| m.calls()).collect();
        assert_eq!(hits, [1, 1, 2], "Closed shares Done");
    }

    #[test]
    fn project_zero_never_calls_graphql() {
        let server = MockServer::start();
        create_mocks(&server);
        let any = server.mock(|when, then| {
            when.method("POST").path("/graphql");
            then.status(200).json_body(json!({"data": {}}));
        });
        adapter(&server, 0).create(&new_task(), &id("A1")).unwrap();
        assert_eq!(any.calls(), 0);
    }

    #[test]
    fn project_trouble_warns_and_leaves_the_issue_write_alone() {
        // A missing project, a missing scope, a project without Status, a Status without
        // the option and a plain 500 all end the same way: the task is written.
        let cases: [(&str, Value); 4] = [
            (
                "repositoryOwner",
                json!({"data": {"repositoryOwner": {"projectV2": null}},
                    "errors": [{"message": "Could not resolve to a ProjectV2 with the number 4."}]}),
            ),
            (
                "repositoryOwner",
                json!({"errors": [{"message": "Your token has not been granted the required scopes"}]}),
            ),
            (
                "repositoryOwner",
                json!({"data": {"repositoryOwner": {"projectV2": {"id": "P", "field": {}}}}}),
            ),
            ("repositoryOwner", project_answer(&[("o_done", "Done")])),
        ];
        for (needle, answer) in cases {
            let server = MockServer::start();
            create_mocks(&server);
            let resolve = gql(&server, needle, answer);
            let add = added(&server);
            let task = adapter(&server, 4).create(&new_task(), &id("A1")).unwrap();
            assert_eq!(task.id, id("A1"));
            assert_eq!(resolve.calls(), 1);
            assert_eq!(
                add.calls(),
                0,
                "nothing is added to a project we cannot set"
            );
        }

        let server = MockServer::start();
        create_mocks(&server);
        server.mock(|when, then| {
            when.method("POST").path("/graphql");
            then.status(500);
        });
        adapter(&server, 4).create(&new_task(), &id("A1")).unwrap();
    }

    #[test]
    fn a_failed_resolve_is_retried_on_the_next_write() {
        let server = MockServer::start();
        let mut broken = server.mock(|when, then| {
            when.method("POST").path("/graphql");
            then.status(502);
        });
        let gh = adapter(&server, 4);
        issue_one_exists(&server);
        gh.write_status(&id("A1"), Status::InProgress).unwrap();
        assert_eq!(broken.calls(), 1);
        broken.delete();
        let resolve = standard(&server);
        added(&server);
        let set = set_to(&server, "o_doing");
        gh.write_status(&id("A1"), Status::InProgress).unwrap();
        resolve.assert();
        set.assert();
    }
}

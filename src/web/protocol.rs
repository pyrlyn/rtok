// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The `/ws` contract (T310.2): the frames the server sends and the messages a client may send.
//! Rust is the one source of truth — `web/src/api/ws.schema.json` is generated from these types
//! (and [`model::Snapshot`]), and the SPA's `snapshot.gen.ts` from that schema.

use schemars::JsonSchema;
use schemars::generate::SchemaSettings;
use serde::{Deserialize, Serialize};

use super::calls_view::CallsView;
use super::model::{
    DiffReport, DiffRequest, DrillGraph, DrillRequest, ExportFile, ExportRequest, GraphExport,
    ImportRequest, Snapshot,
};
use crate::agents::junk_clear::Cleared;
use crate::doctor::web::{Fixed, Plan, Selection};

/// Committed schema, relative to the repository root.
pub const SCHEMA_PATH: &str = "web/src/api/ws.schema.json";

/// A frame the server pushes besides the [`Snapshot`] itself.
#[derive(Debug, Serialize, JsonSchema)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum ServerFrame {
    /// A one-line notice for the operator (refused key, failed `set`, unknown archive id).
    Message { text: String },
    /// The archived payload a client asked for with [`ClientMessage::Expand`].
    Expand { id: String, text: String },
    /// What removing a project would drop (T329.54): the dry run `graph projects remove
    /// --dry-run` prints. Nothing was removed; the page asks the user before it sends the
    /// confirmed request.
    ProjectPlan { project: String, plan: String },
    /// The checklist of `rtok doctor --fix` and the diff of the selection (T331.12).
    DoctorPlan { plan: Plan },
    /// What a confirmed `doctor` apply did.
    DoctorFixed { fixed: Fixed },
    /// The dry run of "clear safe junk" (T330.7): what `agents junk clear` would remove for every
    /// agent; nothing was removed.
    JunkPlan { plan: Cleared },
    /// What a confirmed junk apply removed.
    JunkCleared { cleared: Cleared },
    /// The answer to [`ClientMessage::Graph`]: one project's nodes and edges (T329.14).
    Graph { graph: DrillGraph },
    /// The answer to [`ClientMessage::Diff`] (T329.35); `project` echoes the request's, which is
    /// how the page matches a reply to its question.
    Diff { project: String, diff: DiffReport },
    /// The answer to [`ClientMessage::Export`] (T329.40): the file the CLI would write.
    Export { file: ExportFile },
    /// The answer to [`ClientMessage::Import`]: the saved export, parsed and checked against the
    /// schema; the page shows it read-only.
    Imported { name: String, export: GraphExport },
    /// The live calls panel's whole state (T329.15, T484): running calls, the feed and the totals
    /// of every window. Sent after [`ClientMessage::Calls`] subscribed, first at once and then
    /// when something in it changed, at most four times a second.
    Calls { calls: CallsView },
}

impl ServerFrame {
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }
}

/// A request with the id its answer comes back under (T623). The server reads `req` off any
/// message, so [`ClientMessage`] itself stays the bare shape; this is the wire form a client with
/// more than one request in flight sends.
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct Request {
    /// Chosen by the client, never reused on one page: a reply for a request that timed out
    /// still carries its own id, so it cannot be taken for the answer to a later one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub req: Option<u64>,
    #[serde(flatten)]
    pub message: ClientMessage,
}

/// A frame as it goes out for a request that carried `req`: the same fields plus the id, on
/// success and on failure (a `message` notice) alike.
#[derive(Debug, Serialize, JsonSchema)]
pub struct Reply {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub req: Option<u64>,
    #[serde(flatten)]
    pub frame: ServerFrame,
}

/// `frame` (one JSON object from [`ServerFrame::to_json`]) with `req` written in, as
/// [`Reply`] would serialize it. Spliced rather than re-parsed because graph and export frames
/// are large and the reply path is the page's latency.
pub fn tag_reply(frame: String, req: Option<u64>) -> String {
    match (req, frame.strip_prefix('{')) {
        (Some(req), Some(rest)) if rest.starts_with('"') => format!("{{\"req\":{req},{rest}"),
        _ => frame,
    }
}

/// A message a client sends over `/ws`.
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
#[serde(untagged)]
pub enum ClientMessage {
    /// Ask for the archived payload behind an archive id.
    Expand { expand: String },
    /// Flip an allowlisted boolean key (`plugins.<id>.enabled`).
    Set { set: SetRequest },
    /// Change the project registry (T329.12, T329.20): select a project, or link two of them.
    Project { project: ProjectRequest },
    /// The `doctor --fix` checklist: plan it, or write it once the user confirmed.
    Doctor { doctor: DoctorRequest },
    /// "Clear safe junk" on the Hosts page: plan it, or remove once the user confirmed.
    Junk { junk: JunkRequest },
    /// The inside of one project for the graph page's level 2 (T329.14); read-only.
    Graph { graph: DrillRequest },
    /// What a change did to one project's graph, for Compare mode (T329.35); read-only.
    Diff { diff: DiffRequest },
    /// Download the graph as JSON, SVG or PNG (T329.40); read-only.
    Export { export: ExportRequest },
    /// Open a saved JSON export, sent as text, for the page's read-only view (T329.40).
    Import { import: ImportRequest },
    /// Start or stop the graph call events (T329.15); the page subscribes while the live
    /// graph is visible, so a hidden one costs nothing.
    Calls { calls: CallsRequest },
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct CallsRequest {
    pub subscribe: bool,
}

/// The registry writes the graph page offers; `<project>` is an id or a
/// root path, as in `rtok graph projects`.
#[derive(Debug, Deserialize, Serialize, JsonSchema)]
#[serde(tag = "action", rename_all = "lowercase")]
pub enum ProjectRequest {
    Select {
        project: String,
    },
    Link {
        from: String,
        to: String,
        #[serde(default)]
        both: bool,
    },
    Unlink {
        from: String,
        to: String,
        #[serde(default)]
        both: bool,
    },
    /// Re-read the project's files (T329.54). Answered at once; the row reads `indexing` until
    /// the worker ends, then `ok` or `failed` with the error.
    Reindex {
        project: String,
    },
    /// Drop the project from the registry with its index rows; its files are never touched.
    /// Without `confirmed` nothing is written and the dry run comes back as a
    /// [`ServerFrame::ProjectPlan`].
    Remove {
        project: String,
        #[serde(default)]
        confirmed: bool,
    },
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum DoctorAction {
    /// Return the items and the diff; nothing is written.
    Plan,
    /// Write the selection: the page sends this only after its confirmation.
    Apply,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct DoctorRequest {
    pub action: DoctorAction,
    #[serde(default)]
    pub selection: Selection,
    /// For `apply`: the `token` of the plan the user confirmed. Without the plan's token, or with
    /// one the files no longer produce, nothing is written and a fresh plan comes back.
    #[serde(default)]
    pub confirmed: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum JunkAction {
    /// Return the dry run; nothing is removed.
    Plan,
    /// Remove the confirmed paths: the page sends this only after its confirmation.
    Apply,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct JunkRequest {
    pub action: JunkAction,
    /// For `apply`: the paths of the plan the user was shown. A planned item not named here
    /// stays, so nothing that appeared since the plan goes unseen.
    #[serde(default)]
    pub paths: Vec<String>,
}

#[derive(Debug, Deserialize, Serialize, JsonSchema)]
pub struct SetRequest {
    #[serde(default)]
    pub key: String,
    pub value: bool,
}

/// Root of the schema: one property per direction, so every type lands in `$defs` once.
#[derive(JsonSchema)]
#[schemars(title = "WsProtocol")]
#[allow(dead_code)] // never built: exists only to be described
struct WsProtocol {
    snapshot: Snapshot,
    server: ServerFrame,
    client: ClientMessage,
    // The envelopes are named properties too, or the schema inlines the enums they wrap.
    reply: Reply,
    request: Request,
}

/// The schema as committed: pretty JSON with a trailing newline.
pub fn schema_json() -> String {
    let schema = SchemaSettings::draft2020_12()
        .for_serialize()
        .into_generator()
        .into_root_schema_for::<WsProtocol>();
    let mut out = serde_json::to_string_pretty(&schema).unwrap_or_default();
    out.push('\n');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Fails when a `/ws` type changed without regenerating; `RTOK_BLESS=1` rewrites the file,
    /// then `npm --prefix web run gen:api` regenerates the TS from it.
    #[test]
    fn committed_schema_is_current() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(SCHEMA_PATH);
        let want = schema_json();
        if std::env::var_os("RTOK_BLESS").is_some() {
            std::fs::write(&path, &want).unwrap();
            return;
        }
        let have = std::fs::read_to_string(&path).unwrap_or_default();
        assert!(
            have == want,
            "{SCHEMA_PATH} is stale: run `RTOK_BLESS=1 cargo nextest run -E 'test(committed_schema_is_current)'` then `npm --prefix web run gen:api`"
        );
    }

    #[test]
    fn client_messages_parse() {
        let m: ClientMessage = serde_json::from_str(r#"{"expand":"abc"}"#).unwrap();
        assert!(matches!(m, ClientMessage::Expand { expand } if expand == "abc"));
        let m: ClientMessage =
            serde_json::from_str(r#"{"set":{"key":"plugins.x.enabled","value":true}}"#).unwrap();
        assert!(
            matches!(m, ClientMessage::Set { set } if set.value && set.key == "plugins.x.enabled")
        );
        assert!(serde_json::from_str::<ClientMessage>(r#"{"set":{"value":"yes"}}"#).is_err());
        let m: ClientMessage =
            serde_json::from_str(r#"{"project":{"action":"select","project":"2"}}"#).unwrap();
        assert!(matches!(
            m,
            ClientMessage::Project {
                project: ProjectRequest::Select { .. }
            }
        ));
        let m: ClientMessage =
            serde_json::from_str(r#"{"project":{"action":"link","from":"1","to":"2"}}"#).unwrap();
        assert!(matches!(
            m,
            ClientMessage::Project {
                project: ProjectRequest::Link { both: false, .. }
            }
        ));
        assert!(serde_json::from_str::<ClientMessage>(r#"{"project":{"action":"drop"}}"#).is_err());
        let m: ClientMessage =
            serde_json::from_str(r#"{"graph":{"project":"1","expand":["a.rs"]}}"#).unwrap();
        assert!(matches!(m, ClientMessage::Graph { .. }));
        assert!(serde_json::from_str::<ClientMessage>(r#"{"graph":{}}"#).is_err());
        let m: ClientMessage =
            serde_json::from_str(r#"{"junk":{"action":"apply","paths":["/a"]}}"#).unwrap();
        assert!(matches!(
            m,
            ClientMessage::Junk { junk: JunkRequest { action: JunkAction::Apply, paths } } if paths == ["/a"]
        ));
        let m: ClientMessage = serde_json::from_str(r#"{"junk":{"action":"plan"}}"#).unwrap();
        assert!(matches!(
            m,
            ClientMessage::Junk { junk: JunkRequest { action: JunkAction::Plan, paths } } if paths.is_empty()
        ));
        assert!(serde_json::from_str::<ClientMessage>(r#"{"junk":{"action":"all"}}"#).is_err());
    }

    #[test]
    fn a_request_id_rides_beside_any_message_and_is_optional() {
        let r: Request = serde_json::from_str(r#"{"req":7,"doctor":{"action":"plan"}}"#).unwrap();
        assert!(matches!(r.message, ClientMessage::Doctor { .. }));
        assert_eq!(r.req, Some(7));
        let r: Request = serde_json::from_str(r#"{"expand":"abc"}"#).unwrap();
        assert_eq!(r.req, None);
        // The bare message still parses with the id beside it: the server reads `req` itself.
        let m: ClientMessage = serde_json::from_str(r#"{"req":7,"expand":"abc"}"#).unwrap();
        assert!(matches!(m, ClientMessage::Expand { .. }));
    }

    /// `tag_reply` writes into the rendered text what [`Reply`] serializes, for every shape of
    /// frame, and leaves a frame alone when the request had no id.
    #[test]
    fn tag_reply_matches_the_reply_envelope() {
        let frames = [
            ServerFrame::Message {
                text: "no \"such\" key".into(),
            },
            ServerFrame::Expand {
                id: "a".into(),
                text: "{\"x\":1}".into(),
            },
        ];
        for frame in frames {
            let json = frame.to_json();
            let want = serde_json::to_value(Reply {
                req: Some(7),
                frame,
            })
            .unwrap();
            let got: serde_json::Value =
                serde_json::from_str(&tag_reply(json.clone(), Some(7))).unwrap();
            assert_eq!(got, want);
            assert_eq!(tag_reply(json.clone(), None), json);
        }
        assert_eq!(tag_reply("not json".into(), Some(1)), "not json");
    }

    #[test]
    fn a_diff_request_carries_a_project_and_the_text_of_an_export() {
        let m: ClientMessage = serde_json::from_str(
            r#"{"diff":{"project":"3","from":["main"],"export":{"name":"a.json","text":"{}"}}}"#,
        )
        .unwrap();
        let ClientMessage::Diff { diff } = m else {
            panic!("not a diff");
        };
        assert_eq!((diff.project.as_str(), diff.from.len()), ("3", 1));
        assert_eq!(diff.export.unwrap().name, "a.json");
        assert!(serde_json::from_str::<ClientMessage>(r#"{"diff":{"from":[]}}"#).is_err());
    }

    #[test]
    fn export_requests_make_a_file_or_open_a_text_never_a_path() {
        let m: ClientMessage = serde_json::from_str(
            r#"{"export":{"project":"3","level":"symbols","format":"png","scale":2}}"#,
        )
        .unwrap();
        let ClientMessage::Export { export } = m else {
            panic!("not an export");
        };
        assert_eq!(
            (export.project.as_str(), export.scale, export.transparent),
            ("3", Some(2), false)
        );
        let m: ClientMessage =
            serde_json::from_str(r#"{"import":{"name":"a.json","text":"{}"}}"#).unwrap();
        assert!(matches!(m, ClientMessage::Import { import } if import.text == "{}"));
        for bad in [
            r#"{"import":{"path":"/etc/passwd"}}"#,
            r#"{"export":{"project":"3","level":"all","format":"json"}}"#,
        ] {
            assert!(serde_json::from_str::<ClientMessage>(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn server_frames_carry_their_type() {
        let f = ServerFrame::Expand {
            id: "a".into(),
            text: "b".into(),
        }
        .to_json();
        assert_eq!(f, r#"{"type":"expand","id":"a","text":"b"}"#);
        let f = ServerFrame::Message { text: "hi".into() }.to_json();
        assert_eq!(f, r#"{"type":"message","text":"hi"}"#);
    }
}

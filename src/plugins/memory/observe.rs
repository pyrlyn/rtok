// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Synthetic PostToolUse observations — no LLM (T454).
//!
//! Shape adapted from agentmemory `src/functions/compress-synthetic.ts` (Apache-2.0,
//! https://github.com/rohitg00/agentmemory v0.9.30): `inferType`, `extractFiles`, and a
//! scrubbed `prompt|input|output` narrative truncated to 400 chars. Full tool output stays
//! in the archive for `rtok expand <id>` (D4).

use serde_json::Value;

use rtok_plugin_sdk::{Ctx, PostToolUse};

use super::project_name;
use super::scrub::strip_private;

/// Same cap as `guard`'s PostToolUse archive: a megabyte body must not own the ≤10 ms path.
const ARCHIVE_CAP_BYTES: usize = 256 * 1024;
const TITLE_MAX: usize = 80;
const NARRATIVE_MAX: usize = 400;
const PATH_MAX: usize = 512;

/// Adapted from agentmemory `inferType` (hook + tool name → small enum).
pub fn infer_type(tool_name: &str, hook_type: &str) -> &'static str {
    match hook_type {
        "post_tool_failure" => return "error",
        "prompt_submit" => return "conversation",
        "subagent_stop" | "task_completed" => return "subagent",
        "notification" => return "notification",
        _ => {}
    }
    if tool_name.is_empty() {
        return "other";
    }
    let n = camel_to_snake(tool_name);
    let has_word = |word: &str| {
        n == word
            || n.starts_with(&format!("{word}_"))
            || n.ends_with(&format!("_{word}"))
            || n.contains(&format!("_{word}_"))
    };
    if ["fetch", "http", "web"].into_iter().any(has_word) {
        return "web_fetch";
    }
    if ["grep", "search", "glob", "find"].into_iter().any(has_word) {
        return "search";
    }
    if ["bash", "shell", "exec", "run"].into_iter().any(has_word) {
        return "command_run";
    }
    if ["edit", "update", "patch", "replace"]
        .into_iter()
        .any(has_word)
    {
        return "file_edit";
    }
    if ["write", "create"].into_iter().any(has_word) {
        return "file_write";
    }
    if ["read", "view"].into_iter().any(has_word) {
        return "file_read";
    }
    if ["task", "agent"].into_iter().any(has_word) {
        return "subagent";
    }
    "other"
}

fn camel_to_snake(name: &str) -> String {
    let mut out = String::with_capacity(name.len() + 4);
    for (i, c) in name.chars().enumerate() {
        if c == '-' || c.is_whitespace() {
            out.push('_');
            continue;
        }
        if c.is_uppercase() {
            if i > 0 && !out.ends_with('_') {
                out.push('_');
            }
            out.extend(c.to_lowercase());
        } else {
            out.push(c);
        }
    }
    out
}

fn is_search_tool(tool_name: &str, input: &Value) -> bool {
    let name_hit = {
        let lower = tool_name.to_ascii_lowercase();
        lower.contains("grep")
            || lower.contains("glob")
            || lower.contains("search")
            || lower.contains("find")
            || lower == "ls"
            || lower.contains("list")
    };
    name_hit
        || input.get("pattern").and_then(Value::as_str).is_some()
        || input.get("query").and_then(Value::as_str).is_some()
}

/// Adapted from agentmemory `extractFiles`.
pub fn extract_files(tool_name: &str, input: &Value) -> Vec<String> {
    let Some(obj) = input.as_object() else {
        return Vec::new();
    };
    let keys: &[&str] = if is_search_tool(tool_name, input) {
        &["file_path", "filepath", "filePath", "notebook_path"]
    } else {
        &[
            "file_path",
            "filepath",
            "path",
            "filePath",
            "file",
            "notebook_path",
        ]
    };
    let mut out = Vec::new();
    for key in keys {
        if let Some(v) = obj.get(*key).and_then(Value::as_str)
            && !v.is_empty()
            && v.len() < PATH_MAX
            && !out.iter().any(|p| p == v)
        {
            out.push(v.to_string());
        }
    }
    out
}

fn stringify(v: &Value) -> String {
    match v {
        Value::Null => String::new(),
        Value::String(s) => s.clone(),
        other => serde_json::to_string(other).unwrap_or_else(|_| other.to_string()),
    }
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        return s.to_string();
    }
    let keep = n.saturating_sub(1);
    let mut out: String = s.chars().take(keep).collect();
    out.push('\u{2026}');
    out
}

fn response_bytes(v: &Value) -> Vec<u8> {
    if let Some(s) = v.as_str() {
        return s.as_bytes().to_vec();
    }
    for k in ["stdout", "content"] {
        if let Some(s) = v.get(k).and_then(Value::as_str) {
            return s.as_bytes().to_vec();
        }
    }
    serde_json::to_vec(v).unwrap_or_default()
}

/// One synthetic observation ready to insert (scrubbed narrative, never a raw dump).
#[derive(Debug, Clone, PartialEq)]
pub struct Synthetic {
    pub kind: &'static str,
    pub title: String,
    pub narrative: String,
    pub files: Vec<String>,
    pub importance: i32,
    pub confidence: f64,
}

/// Adapted from agentmemory `buildSyntheticCompression`.
pub fn build_synthetic(tool_name: &str, tool_input: &Value, tool_response: &Value) -> Synthetic {
    let input_str = stringify(tool_input);
    let output_str = stringify(tool_response);
    let mut parts = Vec::new();
    if !input_str.is_empty() {
        parts.push(input_str.as_str());
    }
    if !output_str.is_empty() {
        parts.push(output_str.as_str());
    }
    let narrative_raw = parts.join(" | ");
    let title_src = if tool_name.is_empty() {
        "observation"
    } else {
        tool_name
    };
    Synthetic {
        kind: infer_type(tool_name, "post_tool"),
        title: truncate(title_src, TITLE_MAX),
        narrative: strip_private(&truncate(&narrative_raw, NARRATIVE_MAX)),
        files: extract_files(tool_name, tool_input),
        importance: 5,
        confidence: 0.3,
    }
}

fn resolved_project(cx: &Ctx<'_>) -> Option<String> {
    cx.cwd()
        .map(std::path::Path::new)
        .and_then(project_name)
        .or_else(|| std::env::current_dir().ok().and_then(|d| project_name(&d)))
}

/// Record one PostToolUse observation. Fail open: any error is logged and ignored.
pub fn record(ev: &PostToolUse<'_>, cx: &Ctx<'_>) {
    let synthetic = build_synthetic(ev.tool_name, ev.tool_input, ev.tool_response);
    let body = response_bytes(ev.tool_response);
    let archive_id = if body.len() <= ARCHIVE_CAP_BYTES {
        cx.put_archive(&body).ok()
    } else {
        None
    };
    let project = resolved_project(cx);
    let _ = cx.insert_observation(
        project.as_deref(),
        synthetic.kind,
        &synthetic.title,
        &synthetic.narrative,
        &synthetic.files,
        archive_id.as_deref(),
        synthetic.importance,
        synthetic.confidence,
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn infer_type_maps_common_tools() {
        assert_eq!(infer_type("Read", "post_tool"), "file_read");
        assert_eq!(infer_type("Edit", "post_tool"), "file_edit");
        assert_eq!(infer_type("Write", "post_tool"), "file_write");
        assert_eq!(infer_type("Bash", "post_tool"), "command_run");
        assert_eq!(infer_type("Grep", "post_tool"), "search");
        assert_eq!(infer_type("WebFetch", "post_tool"), "web_fetch");
        assert_eq!(infer_type("Agent", "post_tool"), "subagent");
        assert_eq!(infer_type("Read", "post_tool_failure"), "error");
    }

    #[test]
    fn extract_files_skips_path_on_search_tools() {
        let input = json!({"pattern": "foo", "path": "src", "file_path": "a.rs"});
        assert_eq!(extract_files("Grep", &input), vec!["a.rs".to_string()]);
        let edit = json!({"file_path": "src/main.rs"});
        assert_eq!(
            extract_files("Edit", &edit),
            vec!["src/main.rs".to_string()]
        );
    }

    #[test]
    fn build_synthetic_scrubs_and_truncates() {
        let long = "x".repeat(500);
        let input =
            json!({"file_path": "a.rs", "api_key": format!("k{}", "abcdefghijklmnopqrstuvwxyz")});
        let response = json!(long);
        let s = build_synthetic("Read", &input, &response);
        assert_eq!(s.kind, "file_read");
        assert_eq!(s.title, "Read");
        assert!(s.narrative.chars().count() <= NARRATIVE_MAX);
        assert!(s.narrative.contains("[REDACTED_SECRET]"), "{}", s.narrative);
        assert!(!s.narrative.contains("abcdefghijklmnopqrstuvwxyz"));
        assert_eq!(s.files, vec!["a.rs".to_string()]);
        assert_eq!((s.importance, s.confidence), (5, 0.3));
    }
}

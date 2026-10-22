// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T923: which directory the relative paths of `read`, `search`, `tree` and `outline` resolve
//! against. A shared `rtok mcp` runs in the main checkout while the agent works in a linked
//! worktree, so the caller's registered cwd (when it passes the path guard) is the base, and an
//! answer that could have come from the wrong checkout names the root it used.

use std::path::Path;

use serde_json::Value;

use crate::plugins::read::roots;

/// The tools whose `path` is relative to a root.
const ROOTED: &[&str] = &["read", "search", "tree", "outline"];

/// Whether `name` takes a path relative to the caller's root.
pub(super) fn is_rooted(name: &str) -> bool {
    ROOTED.contains(&name)
}

/// A relative (or absent) `path` resolves against a root; an absolute one does not.
fn is_relative(args: &Value) -> bool {
    args["path"]
        .as_str()
        .is_none_or(|p| !Path::new(p).is_absolute())
}

/// `outline` resolves its path against the server's cwd inside the graph plugin, so a caller
/// root is applied by making the path absolute; the other tools read [`roots::cwd`] themselves.
pub(super) fn anchor_outline(name: &str, args: &Value) -> Value {
    let mut args = args.clone();
    if name == "outline"
        && is_relative(&args)
        && let (Some(p), Some(root)) = (args["path"].as_str(), roots::caller_root())
    {
        args["path"] = Value::String(root.join(p).to_string_lossy().into_owned());
    }
    args
}

/// `text` plus what the answer says about the root it resolved against: always on a failure
/// or an empty search (the two ways a wrong root shows), and on a success only when the
/// caller's root is unknown and the server's repository has other worktrees it could have
/// mistaken it for.
pub(super) fn annotate(
    name: &str,
    args: &Value,
    text: String,
    ok: bool,
    caller_known: bool,
) -> String {
    if !is_rooted(name) || !is_relative(args) {
        return text;
    }
    let Ok(root) = roots::cwd() else {
        return text;
    };
    let empty = name == "search" && ok && text.trim().is_empty();
    if ok && !empty && (caller_known || !roots::has_other_worktrees(&root)) {
        return text;
    }
    let note = format!("relative paths resolved against {}", root.display());
    match (ok, empty) {
        (_, true) => format!("no matches ({note})"),
        (true, _) => format!("{text}\n[{note}]"),
        (false, _) => format!("{text} ({note})"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn root() -> String {
        roots::cwd().unwrap().display().to_string()
    }

    #[test]
    fn a_failed_relative_path_names_the_root_it_resolved_against() {
        let text = annotate(
            "read",
            &json!({"path": "src/gone.rs"}),
            "No such file or directory: src/gone.rs".into(),
            false,
            false,
        );
        assert!(
            text.ends_with(&format!("(relative paths resolved against {})", root())),
            "{text}"
        );
    }

    #[test]
    fn an_empty_search_names_the_root_even_when_the_caller_is_known() {
        let text = annotate(
            "search",
            &json!({"pattern": "x"}),
            String::new(),
            true,
            true,
        );
        assert_eq!(
            text,
            format!("no matches (relative paths resolved against {})", root())
        );
    }

    #[test]
    fn a_known_caller_root_keeps_a_good_answer_byte_exact() {
        let args = json!({"path": "a.rs"});
        assert_eq!(annotate("read", &args, "1:x".into(), true, true), "1:x");
    }

    #[test]
    fn absolute_paths_and_other_tools_are_left_alone() {
        let abs = json!({"path": std::env::temp_dir().join("x")});
        assert_eq!(annotate("read", &abs, "err".into(), false, false), "err");
        assert_eq!(
            annotate("expand", &json!({"id": "a"}), "err".into(), false, false),
            "err"
        );
    }
}

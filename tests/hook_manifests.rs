// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T390: every `plugins/*/hooks/hooks.json` registers exactly the events the one table in
//! `rtok::agents::hook_events` lists for its host, each running the table's `rtok hook <event>`.
//! Installers read their lists from the same table, so a manifest cannot drift from one: this
//! test is what failed to exist when Cursor's manifest silently lacked two events.

use std::collections::BTreeSet;
use std::fs;
use std::path::PathBuf;

use rtok::agents::hook_events::{HOOK_EVENTS, for_host};
use serde_json::Value;

fn plugins() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("plugins")
}

/// Every string below `v`, whatever key holds it (`command`, `bash`, `commandWindows`, …).
fn strings<'a>(v: &'a Value, out: &mut Vec<&'a str>) {
    match v {
        Value::String(s) => out.push(s),
        Value::Array(a) => a.iter().for_each(|x| strings(x, out)),
        Value::Object(o) => o.values().for_each(|x| strings(x, out)),
        _ => {}
    }
}

/// The rtok events an entry runs: the word after a `hook` token (`rtok hook X`, `rtok-hook X`),
/// or ZCode's launcher argument (`"args": ["X"]`). Entries naming neither (Claude's worktree
/// scripts) are not rtok hook events and yield nothing.
fn rtok_events(entry: &Value) -> BTreeSet<String> {
    let mut found = BTreeSet::new();
    let mut all = Vec::new();
    strings(entry, &mut all);
    for s in all {
        let words: Vec<&str> = s.split_whitespace().collect();
        for pair in words.windows(2) {
            let name = pair[1].trim_end_matches(';');
            // `command -v rtok-hook >/dev/null` also has a word after `rtok-hook`.
            if pair[0].ends_with("hook") && name.chars().all(|c| c.is_ascii_alphanumeric()) {
                found.insert(name.to_string());
            }
        }
    }
    let mut stack = vec![entry];
    while let Some(v) = stack.pop() {
        match v {
            Value::Object(o) => {
                if let Some(Value::Array(args)) = o.get("args")
                    && let Some(Value::String(first)) = args.first()
                {
                    found.insert(first.clone());
                }
                stack.extend(o.values());
            }
            Value::Array(a) => stack.extend(a),
            _ => {}
        }
    }
    found
}

#[test]
fn every_plugin_manifest_matches_the_event_table() {
    let mut seen_hosts = BTreeSet::new();
    for dir in fs::read_dir(plugins()).unwrap().flatten() {
        let manifest = dir.path().join("hooks/hooks.json");
        let Ok(raw) = fs::read_to_string(&manifest) else {
            continue;
        };
        let host = dir.file_name().to_string_lossy().into_owned();
        let doc: Value = serde_json::from_str(&raw).unwrap();
        let mut actual = BTreeSet::new();
        for (event, entries) in doc["hooks"].as_object().unwrap() {
            for rtok_event in rtok_events(entries) {
                actual.insert((event.clone(), rtok_event));
            }
        }
        let want: BTreeSet<(String, String)> = for_host(&host)
            .map(|e| (e.host_event.to_string(), e.rtok_event.to_string()))
            .collect();
        assert_eq!(
            actual,
            want,
            "{}: manifest and hook_events::HOOK_EVENTS disagree (left: manifest, right: table)",
            manifest.display()
        );
        seen_hosts.insert(host);
    }
    for e in HOOK_EVENTS {
        assert!(
            seen_hosts.contains(e.host),
            "hook_events names host {} with no plugins/{}/hooks/hooks.json",
            e.host,
            e.host
        );
    }
}

#[test]
fn table_has_no_duplicate_rows_and_rtok_events_are_known() {
    let mut seen = BTreeSet::new();
    for e in HOOK_EVENTS {
        assert!(
            seen.insert((e.host, e.host_event, e.rtok_event)),
            "duplicate row {} {} {}",
            e.host,
            e.host_event,
            e.rtok_event
        );
    }
    assert!(
        for_host("cursor").all(|e| e.host_event != "subagentStart"),
        "Cursor's subagentStart output has no context field (research.md §23)"
    );
}

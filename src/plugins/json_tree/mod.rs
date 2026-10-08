// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `json_tree` — nested JSON with repeated objects, folded to a positional tree.
//!
//! Off by default. A block is rewritten only when the folded form estimates fewer
//! tokens than the original. The original is archived first; `expand` returns those
//! bytes. Uniform scalar tables stay with `toon`.
//!
//! Clean-room: count-gated hoisting of repeated values and templates for repeated
//! object bodies. Content ids are sha256 (already a direct dependency), truncated
//! to 8 hex and lengthened by 4 on collision — not sha1.

use std::collections::{HashMap, HashSet};

use serde_json::{Map, Value};

use rtok_plugin_sdk::{
    Archive, Class, Ctx, DashboardPage, Manifest, Measurement, Plugin, Surface, ToolResultRef,
    WireRequest,
};

pub struct JsonTree;

/// How a json_tree pointer starts. `archive` shares `archive_decisions` and uses this
/// to leave the folded block alone.
pub(crate) const PREFIX: &str = "[json-tree ";

/// A folded tree. `text` may be larger than the input; callers drop it when it does
/// not shrink.
pub struct Folded {
    pub text: String,
    pub nodes: usize,
    pub templates: usize,
}

impl Plugin for JsonTree {
    fn manifest(&self) -> Manifest {
        Manifest {
            id: "json_tree",
            surfaces: &[Surface::Proxy, Surface::Mcp],
            default_on: false,
        }
    }

    fn dashboard_page(&self) -> DashboardPage {
        DashboardPage::new(
            "JSON tree",
            "Fold repeated objects in nested JSON; expand returns the original.",
            true,
        )
    }

    fn proxy_filter(&self, req: &mut WireRequest<'_>, cx: &Ctx) -> Vec<Measurement> {
        if !cx
            .plugin_config::<crate::config::JsonTree>("json_tree")
            .enabled
        {
            return Vec::new();
        }
        rewrite(req.tool_results(), cx)
    }
}

fn rewrite(results: Vec<ToolResultRef<'_>>, cx: &Ctx) -> Vec<Measurement> {
    crate::plugins::archive::outside_live_zone(results, cx)
        .filter_map(|r| rewrite_block(&r.id, r.content, cx))
        .collect()
}

fn rewrite_block(tool_use_id: &str, content: &mut Value, cx: &Ctx) -> Option<Measurement> {
    let orig = crate::plugins::archive::block_text(content)?;
    match cx.archive_decision(tool_use_id) {
        Ok(Some(d)) if d.expanded => return None,
        Ok(Some(d)) if d.pointer.starts_with(PREFIX) => {
            let m = Measurement {
                plugin: "json_tree",
                kind: "fold",
                before_bytes: orig.len() as u64,
                after_bytes: d.pointer.len() as u64,
                est_before: cx.estimate(&orig, Class::Code),
                est_after: cx.estimate(&d.pointer, Class::Code),
                ref_id: Some(d.archive_id.clone()),
                call_id: None,
            };
            *content = Value::String(d.pointer);
            return Some(m);
        }
        // A `toon` pointer under the same `tool_use_id` is that plugin's block.
        Ok(Some(d)) if d.pointer.starts_with(crate::plugins::toon::PREFIX) => return None,
        // An `archive` pointer (or anything else) was measured there.
        Ok(Some(_)) => return None,
        Ok(None) => {}
        Err(e) => {
            cx.log("error", "plugin", "json_tree", &format!("decision: {e}"));
            return None;
        }
    }

    let value: Value = serde_json::from_str(&orig).ok()?;
    let folded = fold_json(&value)?;
    let est_before = cx.estimate(&orig, Class::Code);
    let est_pointer = cx.estimate(&format!("{PREFIX}{}]\n", "0".repeat(64)), Class::Code);
    if est_pointer + cx.estimate(&folded.text, Class::Code) >= est_before {
        return None;
    }
    let archive_id = cx
        .put_archive(orig.as_bytes())
        .map_err(|e| cx.log("error", "plugin", "json_tree", &format!("put: {e}")))
        .ok()?;
    let replacement = format!("{PREFIX}{archive_id}]\n{}", folded.text);
    cx.put_archive_decision(tool_use_id, &archive_id, &replacement)
        .map_err(|e| cx.log("error", "plugin", "json_tree", &format!("decision: {e}")))
        .ok()?;
    let m = Measurement {
        plugin: "json_tree",
        kind: "fold",
        before_bytes: orig.len() as u64,
        after_bytes: replacement.len() as u64,
        est_before,
        est_after: cx.estimate(&replacement, Class::Code),
        ref_id: Some(archive_id),
        call_id: None,
    };
    *content = Value::String(replacement);
    Some(m)
}

/// Fold `value` when it is a large nested JSON object or array that `toon` will not
/// encode. `None` for scalars, small values, values with no nested object, and uniform
/// scalar tables.
pub fn fold_json(value: &Value) -> Option<Folded> {
    if !matches!(value, Value::Object(_) | Value::Array(_)) {
        return None;
    }
    let compact = serde_json::to_string(value).ok()?;
    if compact.len() < 256 {
        return None;
    }
    if !has_nested_object(value) {
        return None;
    }
    // Uniform scalar tables belong to `toon`, including a one-row table.
    if crate::plugins::toon::tabular_keys(value, 1).is_some() {
        return None;
    }
    Some(fold(value))
}

fn has_nested_object(value: &Value) -> bool {
    match value {
        Value::Object(map) => map.values().any(contains_object),
        Value::Array(items) => items.iter().any(contains_object),
        _ => false,
    }
}

fn contains_object(value: &Value) -> bool {
    match value {
        Value::Object(_) => true,
        Value::Array(items) => items.iter().any(contains_object),
        _ => false,
    }
}

fn is_hoistable(value: &Value) -> bool {
    match value {
        Value::Object(map) => !map.is_empty(),
        Value::Array(items) => !items.is_empty(),
        _ => false,
    }
}

fn fold(value: &Value) -> Folded {
    let mut counts: HashMap<String, usize> = HashMap::new();
    count_fields(value, &mut counts);
    let mut var_keys: Vec<String> = counts
        .into_iter()
        .filter(|(_, n)| *n >= 2)
        .map(|(s, _)| s)
        .collect();
    var_keys.sort();
    let mut used_vars = HashSet::new();
    let mut vars: HashMap<String, String> = HashMap::new();
    let mut var_lines: Vec<(String, String)> = Vec::new();
    for key in var_keys {
        let id = short_id("v_", &key, &mut used_vars);
        vars.insert(key.clone(), id.clone());
        var_lines.push((id, key));
    }

    let mut body_counts: HashMap<String, usize> = HashMap::new();
    each_root(value, &mut |map| {
        let (n, body) = body_of(map, &vars);
        if n > 1 {
            *body_counts.entry(body).or_default() += 1;
        }
    });
    let mut body_keys: Vec<String> = body_counts
        .into_iter()
        .filter(|(_, n)| *n >= 2)
        .map(|(s, _)| s)
        .collect();
    body_keys.sort();
    let mut used_els = HashSet::new();
    let mut elements: HashMap<String, String> = HashMap::new();
    let mut el_lines: Vec<(String, String)> = Vec::new();
    for body in body_keys {
        let id = short_id("EL-", &body, &mut used_els);
        elements.insert(body.clone(), id.clone());
        el_lines.push((id, body));
    }

    let mut text = String::new();
    push_section(&mut text, "VARS", &var_lines);
    push_section(&mut text, "ELEMENTS", &el_lines);
    if !text.is_empty() && !text.ends_with('\n') {
        text.push('\n');
    }
    let nodes_at = text.len();
    text.push_str("NODES:\n");
    let nodes = emit_root(value, &vars, &elements, &mut text);
    if nodes == 0 {
        text.truncate(nodes_at);
    }
    Folded {
        text,
        nodes,
        templates: el_lines.len(),
    }
}

fn push_section(out: &mut String, title: &str, lines: &[(String, String)]) {
    if lines.is_empty() {
        return;
    }
    out.push_str(title);
    out.push_str(":\n");
    for (id, body) in lines {
        out.push_str(id);
        out.push_str(": ");
        out.push_str(body);
        out.push('\n');
    }
}

fn count_fields(value: &Value, counts: &mut HashMap<String, usize>) {
    match value {
        Value::Object(map) => {
            for (key, child) in map {
                if key != "children" && is_hoistable(child) {
                    *counts.entry(stable_string(child)).or_default() += 1;
                }
                count_fields(child, counts);
            }
        }
        Value::Array(items) => {
            for child in items {
                count_fields(child, counts);
            }
        }
        _ => {}
    }
}

fn each_root(value: &Value, f: &mut impl FnMut(&Map<String, Value>)) {
    if let Value::Array(items) = value {
        for item in items {
            each_node(item, f);
        }
    } else {
        each_node(value, f);
    }
}

fn each_node(value: &Value, f: &mut impl FnMut(&Map<String, Value>)) {
    let Value::Object(map) = value else {
        return;
    };
    f(map);
    if let Some(Value::Array(children)) = map.get("children") {
        for child in children {
            each_node(child, f);
        }
    }
}

fn body_keys(map: &Map<String, Value>) -> Vec<&String> {
    let mut keys: Vec<&String> = map
        .keys()
        .filter(|k| *k != "id" && *k != "name" && *k != "children")
        .collect();
    keys.sort();
    keys
}

fn body_of(map: &Map<String, Value>, vars: &HashMap<String, String>) -> (usize, String) {
    let keys = body_keys(map);
    let mut body = Map::new();
    for key in &keys {
        body.insert((*key).clone(), field_value(&map[*key], vars));
    }
    (keys.len(), stable_string(&Value::Object(body)))
}

fn field_value(value: &Value, vars: &HashMap<String, String>) -> Value {
    if is_hoistable(value)
        && let Some(id) = vars.get(&stable_string(value))
    {
        return Value::String(id.clone());
    }
    value.clone()
}

fn short_id(prefix: &str, material: &str, used: &mut HashSet<String>) -> String {
    let hex = crate::store::hex_sha256(material.as_bytes());
    let mut n = 8usize;
    loop {
        let end = n.min(hex.len());
        let id = format!("{prefix}{}", &hex[..end]);
        if used.insert(id.clone()) || end == hex.len() {
            return id;
        }
        n += 4;
    }
}

fn stable_string(value: &Value) -> String {
    match value {
        Value::Object(map) => {
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort();
            let mut out = String::from("{");
            for (i, key) in keys.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(&serde_json::to_string(key).unwrap_or_default());
                out.push(':');
                out.push_str(&stable_string(&map[*key]));
            }
            out.push('}');
            out
        }
        Value::Array(items) => {
            let mut out = String::from("[");
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(&stable_string(item));
            }
            out.push(']');
            out
        }
        other => serde_json::to_string(other).unwrap_or_default(),
    }
}

fn label_of(map: &Map<String, Value>) -> &str {
    map.get("type")
        .and_then(Value::as_str)
        .or_else(|| map.get("kind").and_then(Value::as_str))
        .unwrap_or("obj")
}

fn id_token(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

fn fmt_value(value: &Value, vars: &HashMap<String, String>) -> String {
    if is_hoistable(value)
        && let Some(id) = vars.get(&stable_string(value))
    {
        return id.clone();
    }
    match value {
        Value::String(s) if !s.contains([' ', '"', '=']) => s.clone(),
        other => stable_string(other),
    }
}

fn emit_root(
    value: &Value,
    vars: &HashMap<String, String>,
    elements: &HashMap<String, String>,
    out: &mut String,
) -> usize {
    if let Value::Array(items) = value {
        items
            .iter()
            .map(|item| emit_node(item, 0, vars, elements, out))
            .sum()
    } else {
        emit_node(value, 0, vars, elements, out)
    }
}

fn emit_node(
    value: &Value,
    depth: usize,
    vars: &HashMap<String, String>,
    elements: &HashMap<String, String>,
    out: &mut String,
) -> usize {
    let Some(map) = value.as_object() else {
        out.push_str(&"  ".repeat(depth));
        out.push_str("[obj] ");
        out.push_str(&stable_string(value));
        out.push('\n');
        return 1;
    };
    out.push_str(&"  ".repeat(depth));
    out.push('[');
    out.push_str(label_of(map));
    out.push(']');
    if let Some(id) = map.get("id").and_then(id_token) {
        out.push_str(" #");
        out.push_str(&id);
    }
    if let Some(name) = map.get("name") {
        out.push_str(" name=");
        out.push_str(&fmt_value(name, vars));
    }
    let (nkeys, body) = body_of(map, vars);
    if nkeys > 1
        && let Some(el) = elements.get(&body)
    {
        out.push_str(" template=");
        out.push_str(el);
    } else {
        for key in body_keys(map) {
            out.push(' ');
            out.push_str(key);
            out.push('=');
            out.push_str(&fmt_value(&map[key], vars));
        }
    }
    out.push('\n');
    let mut nodes = 1;
    if let Some(Value::Array(children)) = map.get("children") {
        for child in children {
            nodes += emit_node(child, depth + 1, vars, elements, out);
        }
    }
    nodes
}

/// MCP wrapper: fold one text block when the tree fits the line cap and is smaller.
/// The caller skips `read` and `search`. `None` leaves the line cut in place.
#[cfg(feature = "json_tree")]
pub(crate) fn mcp_replacement(
    cx: &crate::plugin::Runtime,
    text: &str,
    max_lines: u32,
) -> Option<String> {
    let value: Value = serde_json::from_str(text).ok()?;
    let folded = fold_json(&value)?;
    let folded_lines = folded.text.lines().count() as u32;
    if folded_lines > max_lines || folded.text.len() >= text.len() {
        return None;
    }
    let id = cx.put_archive(text.as_bytes()).ok()?;
    let printed = format!("{PREFIX}{id}]\n{}", folded.text);
    cx.put_archive_decision(&id, &id, &printed).ok()?;
    let _ = cx.record(&Measurement {
        plugin: "json_tree",
        kind: "fold",
        before_bytes: text.len() as u64,
        after_bytes: printed.len() as u64,
        est_before: cx.estimate(text, Class::Code),
        est_after: cx.estimate(&printed, Class::Code),
        ref_id: Some(id),
        call_id: None,
    });
    Some(printed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::expand;
    use crate::proxy::anthropic::ANTHROPIC;
    use rtok_plugin_sdk::{Archive, WireRequest};
    use serde_json::json;

    fn fill() -> Value {
        json!({"hex": "#ffffff", "opacity": 1, "blend": "normal"})
    }

    fn pad() -> Value {
        json!({"top": 8, "right": 12, "bottom": 8, "left": 12})
    }

    fn tree() -> Value {
        json!({
            "name": "x".repeat(200),
            "children": [
                {"id": 1, "type": "RECT", "fill": fill()},
                {"id": 2, "type": "RECT", "fill": fill()},
                {"id": 3, "type": "RECT", "fill": fill(), "padding": pad()},
            ]
        })
    }

    fn fat_fill() -> Value {
        let mut map = Map::new();
        for i in 0..20 {
            map.insert(
                format!("k{i:02}"),
                Value::String(format!("value-{i:02}-repeated-fill-token")),
            );
        }
        Value::Object(map)
    }

    fn fat_tree(n: usize) -> Value {
        let fill = fat_fill();
        let children: Vec<Value> = (0..n)
            .map(|i| json!({"id": i, "type": "RECT", "fill": fill}))
            .collect();
        json!({"name": "x".repeat(80), "children": children})
    }

    fn cx(name: &str, enabled: bool) -> crate::plugin::Runtime {
        let dir =
            std::env::temp_dir().join(format!("rtok-json-tree-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut cx = crate::plugin::Runtime::in_memory("s").unwrap();
        cx.config.core.archive_dir = dir;
        cx.config.plugins.json_tree.enabled = enabled;
        cx.config.plugins.archive.keep_turns = 0;
        cx.config.proxy.mode = "compress".into();
        cx
    }

    fn tool_req(value: Value) -> Value {
        let content = serde_json::to_string_pretty(&value).unwrap();
        json!({"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content": content}]}]})
    }

    fn six_turn_req(value: Value) -> Value {
        let content = serde_json::to_string_pretty(&value).unwrap();
        let mut messages = Vec::new();
        for i in 1..=6 {
            messages.push(json!({
                "role": "user",
                "content": [{"type":"tool_result","tool_use_id": format!("t{i}"), "content": content}]
            }));
            if i < 6 {
                messages.push(json!({"role":"assistant","content":"ok"}));
            }
        }
        json!({"messages": messages})
    }

    fn filter(body: &mut Value, cx: &Ctx) -> Vec<Measurement> {
        JsonTree.proxy_filter(&mut WireRequest::new(&ANTHROPIC, body), cx)
    }

    #[test]
    fn repeated_fill_is_one_var_and_one_template() {
        let folded = fold_json(&tree()).expect("tree folds");
        assert_eq!(folded.nodes, 4);
        assert_eq!(folded.templates, 1);
        let text = &folded.text;
        assert!(!text.contains("\n\n"), "{text}");
        let vars = text.split("ELEMENTS:").next().unwrap();
        let fill_s = stable_string(&fill());
        let id = format!("v_{}", &crate::store::hex_sha256(fill_s.as_bytes())[..8]);
        assert_eq!(vars.matches("v_").count(), 1, "{vars}");
        assert!(vars.contains(&format!("{id}: {fill_s}")), "{vars}");
        assert!(
            !vars.contains("\"top\""),
            "unique padding stays inline: {vars}"
        );
        let body = format!(r#"{{"fill":"{id}","type":"RECT"}}"#);
        let el = format!("EL-{}", &crate::store::hex_sha256(body.as_bytes())[..8]);
        assert!(text.contains(&format!("{el}: {body}")), "{text}");
        let nodes = text.split("NODES:\n").nth(1).unwrap();
        let lines: Vec<&str> = nodes.lines().collect();
        assert!(lines[0].starts_with("[obj] name="), "{}", lines[0]);
        assert!(lines[0].contains(&"x".repeat(200)));
        assert!(lines[1].contains(&format!("template={el}")), "{}", lines[1]);
        assert!(lines[2].contains(&format!("template={el}")), "{}", lines[2]);
        assert!(!lines[3].contains("template="), "{}", lines[3]);
        assert!(
            lines[3].contains("padding={\"bottom\":8,\"left\":12,\"right\":12,\"top\":8}"),
            "{}",
            lines[3]
        );
        assert!(lines[3].contains(&format!("fill={id}")), "{}", lines[3]);
    }

    #[test]
    fn key_order_does_not_change_the_fold() {
        let name = "x".repeat(200);
        let a: Value = serde_json::from_str(&format!(
            r##"{{"name":"{name}","children":[{{"type":"RECT","id":1,"fill":{{"hex":"#fff","opacity":1,"blend":"normal"}}}},{{"id":2,"fill":{{"blend":"normal","hex":"#fff","opacity":1}},"type":"RECT"}}]}}"##
        ))
        .unwrap();
        let b: Value = serde_json::from_str(&format!(
            r##"{{"children":[{{"fill":{{"opacity":1,"blend":"normal","hex":"#fff"}},"type":"RECT","id":1}},{{"type":"RECT","fill":{{"hex":"#fff","blend":"normal","opacity":1}},"id":2}}],"name":"{name}"}}"##
        ))
        .unwrap();
        let fa = fold_json(&a).expect("a");
        let fb = fold_json(&b).expect("b");
        assert_eq!(fa.text, fb.text);
    }

    #[test]
    fn a_uniform_scalar_table_is_not_folded() {
        let rows: Vec<Value> = (0..30)
            .map(|i| json!({"aa": i, "bb": "x".repeat(40), "cc": format!("row-{i}")}))
            .collect();
        let value = Value::Array(rows);
        assert!(serde_json::to_string(&value).unwrap().len() >= 256);
        assert!(crate::plugins::toon::tabular_keys(&value, 1).is_some());
        assert!(fold_json(&value).is_none());
    }

    #[test]
    fn disabled_plugin_leaves_the_body_identical() {
        let cx = cx("off", false);
        let mut body = tool_req(fat_tree(6));
        let original = body.clone();
        assert!(filter(&mut body, &Ctx::new(&cx)).is_empty());
        assert_eq!(body, original);
    }

    #[test]
    fn proxy_folds_when_the_estimate_shrinks_and_expand_is_exact() {
        let cx = cx("proxy", true);
        let tree = fat_tree(6);
        let original = serde_json::to_string_pretty(&tree).unwrap();
        let mut first = tool_req(tree.clone());
        let ms = filter(&mut first, &Ctx::new(&cx));
        assert_eq!(ms.len(), 1);
        assert!(ms[0].after_bytes < ms[0].before_bytes);
        assert!(ms[0].est_after < ms[0].est_before);
        assert_eq!(ms[0].plugin, "json_tree");
        assert_eq!(ms[0].kind, "fold");
        let text = first["messages"][0]["content"][0]["content"]
            .as_str()
            .unwrap();
        assert!(text.starts_with(PREFIX), "{text}");
        let archive_id = ms[0].ref_id.clone().unwrap();

        let mut second = tool_req(tree.clone());
        filter(&mut second, &Ctx::new(&cx));
        assert_eq!(first, second, "second pass is byte-identical");

        let got = expand::fetch(&cx, &archive_id).unwrap().unwrap();
        assert_eq!(got, original.as_bytes());
        let expand_rows: Vec<_> = cx
            .store
            .list_measurements("json_tree")
            .unwrap()
            .into_iter()
            .filter(|m| m.kind == "expand")
            .collect();
        assert_eq!(expand_rows.len(), 1);
        assert_eq!(expand_rows[0].kind, "expand");
    }

    #[test]
    fn live_zone_turns_stay_byte_stable() {
        let mut cx = cx("live", true);
        cx.config.plugins.archive.keep_turns = 4;
        let tree = fat_tree(6);
        let mut body = six_turn_req(tree.clone());
        let ms = filter(&mut body, &Ctx::new(&cx));
        assert_eq!(ms.len(), 2, "only the two oldest turns fold");
        let texts: Vec<_> = (0..6)
            .map(|i| {
                body["messages"][2 * i]["content"][0]["content"]
                    .as_str()
                    .unwrap()
                    .to_string()
            })
            .collect();
        assert!(texts[0].starts_with(PREFIX), "{}", texts[0]);
        assert!(texts[1].starts_with(PREFIX), "{}", texts[1]);
        let pretty = serde_json::to_string_pretty(&tree).unwrap();
        for t in &texts[2..] {
            assert_eq!(t, &pretty);
        }
        let mut again = six_turn_req(tree);
        filter(&mut again, &Ctx::new(&cx));
        assert_eq!(body, again);
        let s1 = body.to_string();
        let s2 = again.to_string();
        assert_eq!(
            s1.split(PREFIX).next().unwrap(),
            s2.split(PREFIX).next().unwrap()
        );
    }

    #[test]
    fn a_toon_table_writes_no_decision() {
        let cx = cx("toon-owned", true);
        let rows: Vec<Value> = (0..30)
            .map(|i| json!({"aa": i, "bb": "x".repeat(40), "cc": format!("row-{i}")}))
            .collect();
        let mut body = tool_req(Value::Array(rows));
        let original = body.clone();
        assert!(filter(&mut body, &Ctx::new(&cx)).is_empty());
        assert_eq!(body, original);
        assert!(cx.archive_decision("t").unwrap().is_none());
    }

    #[test]
    fn archive_does_not_replace_a_fold() {
        let mut cx = cx("archive-skip", true);
        cx.config.plugins.archive.min_tokens = 1;
        let mut body = tool_req(fat_tree(6));
        let ms = filter(&mut body, &Ctx::new(&cx));
        assert_eq!(ms.len(), 1);
        let text = body["messages"][0]["content"][0]["content"]
            .as_str()
            .unwrap()
            .to_string();
        assert!(text.starts_with(PREFIX), "{text}");
        let archived = crate::plugins::archive::Archive
            .proxy_filter(&mut WireRequest::new(&ANTHROPIC, &mut body), &Ctx::new(&cx));
        assert!(archived.is_empty(), "{archived:?}");
        assert_eq!(cx.store.measurement_count("archive").unwrap(), 0);
        assert_eq!(
            body["messages"][0]["content"][0]["content"].as_str(),
            Some(text.as_str())
        );
    }
}

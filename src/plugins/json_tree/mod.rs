// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `json_tree` — fold a nested JSON tree into hoisted values and repeated element
//! bodies, so a large design or AST is not replaced by an archive head/tail.
//!
//! Off until a `Measurement` row shows a saving. The original bytes are archived
//! before the block is rewritten; `rtok expand <id>` returns them.

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value};

use rtok_plugin_sdk::{
    Class, Ctx, DashboardPage, Manifest, Measurement, Plugin, Surface, ToolResultRef, WireRequest,
};

pub struct JsonTree;

/// How a json_tree pointer starts. `archive` and `toon` share `archive_decisions` and
/// use it to leave this plugin's blocks alone.
pub(crate) const PREFIX: &str = "[json-tree ";

const MIN_BYTES: usize = 256;
const CHILDREN: &str = "children";

/// Folded tree. `nodes` counts object lines; `templates` counts `EL-` bodies.
#[derive(Debug, Clone, PartialEq, Eq)]
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
            "Fold repeated structure in old JSON tool results; expand returns the original.",
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
        Ok(Some(d)) if d.pointer.starts_with(crate::plugins::toon::PREFIX) => return None,
        Ok(Some(d)) if d.pointer.starts_with(PREFIX) => {
            let m = measurement(cx, &orig, &d.pointer, &d.archive_id);
            *content = Value::String(d.pointer);
            return Some(m);
        }
        Ok(Some(_)) => return None,
        Ok(None) => {}
        Err(e) => {
            cx.log("error", "plugin", "json_tree", &format!("decision: {e}"));
            return None;
        }
    }

    let value: Value = serde_json::from_str(&orig).ok()?;
    let folded = fold_json(&value)?;
    let preview = format!("{PREFIX}{}]\n{}", "0".repeat(64), folded.text);
    let est_before = cx.estimate(&orig, Class::Code);
    if cx.estimate(&preview, Class::Code) >= est_before {
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
    let m = measurement(cx, &orig, &replacement, &archive_id);
    *content = Value::String(replacement);
    Some(m)
}

fn measurement(cx: &Ctx, before: &str, after: &str, archive_id: &str) -> Measurement {
    Measurement {
        plugin: "json_tree",
        kind: "fold",
        before_bytes: before.len() as u64,
        after_bytes: after.len() as u64,
        est_before: cx.estimate(before, Class::Code),
        est_after: cx.estimate(after, Class::Code),
        ref_id: Some(archive_id.to_string()),
        call_id: None,
    }
}

/// Fold `value` with identity keys `id` and `name`.
///
/// `None` when the value is not a large enough object or array, has no nested
/// object, or is a uniform scalar table (`toon` keeps those).
pub fn fold_json(value: &Value) -> Option<Folded> {
    fold_json_with(value, &["id", "name"])
}

/// [`fold_json`] with a caller-chosen set of identity keys. Those keys, and
/// `children`, are not part of an element body.
pub fn fold_json_with(value: &Value, identity: &[&str]) -> Option<Folded> {
    if !value.is_object() && !value.is_array() {
        return None;
    }
    if serde_json::to_vec(value).ok()?.len() < MIN_BYTES {
        return None;
    }
    if !has_nested_object(value) {
        return None;
    }
    if crate::plugins::toon::tabular_keys(value, 1).is_some() {
        return None;
    }

    let mut counts: BTreeMap<String, usize> = BTreeMap::new();
    tally(value, &mut counts);
    let vars = assign_ids("V-", &counts, 2);

    let mut pieces = match value {
        Value::Array(items) => items
            .iter()
            .map(|item| piece_of(item, identity, &vars))
            .collect(),
        Value::Object(map) => vec![Piece::Node(build_node(map, identity, &vars))],
        _ => return None,
    };

    let mut bodies: BTreeMap<String, usize> = BTreeMap::new();
    collect_bodies(&pieces, &mut bodies);
    let elements = assign_ids("EL-", &bodies, 2);
    stamp(&mut pieces, &elements);

    let referenced = referenced_vars(&pieces, &vars);
    let text = render(&vars, &elements, &referenced, &pieces);
    if text.is_empty() {
        return None;
    }
    Some(Folded {
        nodes: count_nodes(&pieces),
        templates: elements.len(),
        text,
    })
}

fn has_nested_object(value: &Value) -> bool {
    match value {
        Value::Array(items) => items.iter().any(contains_object),
        Value::Object(map) => map.values().any(contains_object),
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

fn tally(value: &Value, counts: &mut BTreeMap<String, usize>) {
    match value {
        Value::Array(items) => {
            for item in items {
                tally(item, counts);
            }
        }
        Value::Object(map) => {
            for (key, child) in map {
                if key == CHILDREN && child.is_array() {
                    tally(child, counts);
                    continue;
                }
                if child.is_object() || child.is_array() {
                    *counts.entry(canonical(child)).or_default() += 1;
                }
                tally(child, counts);
            }
        }
        _ => {}
    }
}

fn assign_ids(
    prefix: &str,
    counts: &BTreeMap<String, usize>,
    min: usize,
) -> BTreeMap<String, String> {
    let mut materials: Vec<&str> = counts
        .iter()
        .filter(|(_, n)| **n >= min)
        .map(|(k, _)| k.as_str())
        .collect();
    materials.sort_unstable();
    let mut used = BTreeMap::new();
    let mut out = BTreeMap::new();
    for material in materials {
        let id = content_id(prefix, material, &mut used);
        out.insert(material.to_string(), id);
    }
    out
}

/// Truncated content id. A clash with a different body lengthens the hex by 4
/// until the slot is free or the full hash is used.
fn content_id(prefix: &str, material: &str, used: &mut BTreeMap<String, String>) -> String {
    let hash = sha1_hex(material.as_bytes());
    let mut len = 8;
    loop {
        let take = len.min(hash.len());
        let id = format!("{prefix}{}", &hash[..take]);
        match used.get(&id) {
            Some(existing) if existing == material || take == hash.len() => return id,
            Some(_) => len += 4,
            None => {
                used.insert(id.clone(), material.to_string());
                return id;
            }
        }
    }
}

fn piece_of(value: &Value, identity: &[&str], vars: &BTreeMap<String, String>) -> Piece {
    match value {
        Value::Object(map) => Piece::Node(build_node(map, identity, vars)),
        other => Piece::Atom(canonical(other)),
    }
}

struct ObjNode {
    label: String,
    bracket: Option<String>,
    id_text: Option<String>,
    extras: BTreeMap<String, String>,
    body: Value,
    children: Vec<Piece>,
    template: Option<String>,
}

enum Piece {
    Node(ObjNode),
    Atom(String),
}

fn build_node(
    obj: &Map<String, Value>,
    identity: &[&str],
    vars: &BTreeMap<String, String>,
) -> ObjNode {
    let bracket = bracket_key(obj);
    let label = bracket
        .as_ref()
        .and_then(|key| obj.get(key))
        .map_or_else(|| "object".to_string(), scalar_text);
    let id_text = obj.get("id").map(scalar_text);
    let mut extras = BTreeMap::new();
    let mut body = Map::new();
    let mut children = Vec::new();
    let id_is_identity = identity.contains(&"id");
    for (key, val) in obj {
        if key == CHILDREN && val.is_array() {
            children.extend(
                val.as_array()
                    .into_iter()
                    .flatten()
                    .map(|item| piece_of(item, identity, vars)),
            );
            continue;
        }
        if key == "id" {
            if !id_is_identity {
                insert_field(&mut body, key, val, vars);
            }
            continue;
        }
        if identity.contains(&key.as_str()) {
            extras.insert(key.clone(), render_value(val));
            continue;
        }
        insert_field(&mut body, key, val, vars);
    }
    ObjNode {
        label,
        bracket,
        id_text,
        extras,
        body: Value::Object(body),
        children,
        template: None,
    }
}

fn insert_field(
    body: &mut Map<String, Value>,
    key: &str,
    val: &Value,
    vars: &BTreeMap<String, String>,
) {
    if val.is_object() || val.is_array() {
        let canon = canonical(val);
        if let Some(id) = vars.get(&canon) {
            body.insert(key.to_string(), Value::String(id.clone()));
            return;
        }
    }
    body.insert(key.to_string(), val.clone());
}

fn bracket_key(obj: &Map<String, Value>) -> Option<String> {
    for key in ["type", "kind"] {
        match obj.get(key) {
            Some(Value::Object(_) | Value::Array(_)) | None => {}
            Some(_) => return Some(key.to_string()),
        }
    }
    None
}

fn scalar_text(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        other => canonical(other),
    }
}

fn type_only(body: &Value) -> bool {
    body.as_object()
        .is_none_or(|map| map.keys().all(|key| key == "type" || key == "kind"))
}

fn collect_bodies(pieces: &[Piece], counts: &mut BTreeMap<String, usize>) {
    for piece in pieces {
        if let Piece::Node(node) = piece {
            if !type_only(&node.body) {
                *counts.entry(canonical(&node.body)).or_default() += 1;
            }
            collect_bodies(&node.children, counts);
        }
    }
}

fn stamp(pieces: &mut [Piece], elements: &BTreeMap<String, String>) {
    for piece in pieces {
        if let Piece::Node(node) = piece {
            let canon = canonical(&node.body);
            if let Some(id) = elements.get(&canon) {
                node.template = Some(id.clone());
            }
            stamp(&mut node.children, elements);
        }
    }
}

fn referenced_vars(pieces: &[Piece], vars: &BTreeMap<String, String>) -> BTreeSet<String> {
    let ids: BTreeSet<&str> = vars.values().map(String::as_str).collect();
    let mut out = BTreeSet::new();
    walk_nodes(pieces, &mut |node| {
        mark_ids(&node.body, &ids, &mut out);
    });
    out
}

fn walk_nodes(pieces: &[Piece], visit: &mut dyn FnMut(&ObjNode)) {
    for piece in pieces {
        if let Piece::Node(node) = piece {
            visit(node);
            walk_nodes(&node.children, visit);
        }
    }
}

fn mark_ids(value: &Value, ids: &BTreeSet<&str>, out: &mut BTreeSet<String>) {
    match value {
        Value::String(text) if ids.contains(text.as_str()) => {
            out.insert(text.clone());
        }
        Value::Array(items) => {
            for item in items {
                mark_ids(item, ids, out);
            }
        }
        Value::Object(map) => {
            for child in map.values() {
                mark_ids(child, ids, out);
            }
        }
        _ => {}
    }
}

fn count_nodes(pieces: &[Piece]) -> usize {
    pieces
        .iter()
        .map(|piece| match piece {
            Piece::Atom(_) => 0,
            Piece::Node(node) => 1 + count_nodes(&node.children),
        })
        .sum()
}

fn render(
    vars: &BTreeMap<String, String>,
    elements: &BTreeMap<String, String>,
    referenced: &BTreeSet<String>,
    pieces: &[Piece],
) -> String {
    let mut out = String::new();
    let mut var_lines: Vec<(&str, &str)> = vars
        .iter()
        .filter(|(_, id)| referenced.contains(*id))
        .map(|(canon, id)| (id.as_str(), canon.as_str()))
        .collect();
    var_lines.sort_unstable();
    if !var_lines.is_empty() {
        out.push_str("VARS:\n");
        for (id, canon) in var_lines {
            out.push_str(id);
            out.push_str(": ");
            out.push_str(canon);
            out.push('\n');
        }
    }
    let mut element_lines: Vec<(&str, &str)> = elements
        .iter()
        .map(|(canon, id)| (id.as_str(), canon.as_str()))
        .collect();
    element_lines.sort_unstable();
    if !element_lines.is_empty() {
        out.push_str("ELEMENTS:\n");
        for (id, canon) in element_lines {
            out.push_str(id);
            out.push_str(": ");
            out.push_str(canon);
            out.push('\n');
        }
    }
    render_pieces(pieces, 0, &mut out);
    out
}

fn render_pieces(pieces: &[Piece], depth: usize, out: &mut String) {
    for piece in pieces {
        match piece {
            Piece::Atom(text) => {
                push_indent(out, depth);
                out.push_str(text);
                out.push('\n');
            }
            Piece::Node(node) => render_node(node, depth, out),
        }
    }
}

fn render_node(node: &ObjNode, depth: usize, out: &mut String) {
    push_indent(out, depth);
    out.push('[');
    out.push_str(&bracket_text(&node.label));
    out.push(']');
    if let Some(id) = &node.id_text {
        out.push_str(" #");
        out.push_str(&render_token(id));
    }
    for (key, value) in &node.extras {
        out.push(' ');
        out.push_str(&render_token(key));
        out.push('=');
        out.push_str(value);
    }
    if let Some(template) = &node.template {
        out.push_str(" template=");
        out.push_str(template);
    } else if let Some(map) = node.body.as_object() {
        let mut keys: Vec<&String> = map.keys().collect();
        keys.sort();
        for key in keys {
            if node.bracket.as_ref() == Some(key) {
                continue;
            }
            out.push(' ');
            out.push_str(&render_token(key));
            out.push('=');
            out.push_str(&render_value(&map[key]));
        }
    }
    out.push('\n');
    render_pieces(&node.children, depth + 1, out);
}

fn push_indent(out: &mut String, depth: usize) {
    for _ in 0..depth {
        out.push_str("  ");
    }
}

fn bracket_text(text: &str) -> String {
    if text.is_empty() || text.contains([']', ' ', '\n']) {
        serde_json::to_string(text).unwrap_or_else(|_| "\"object\"".to_string())
    } else {
        text.to_string()
    }
}

fn render_value(value: &Value) -> String {
    match value {
        Value::String(text) => render_token(text),
        other => canonical(other),
    }
}

fn render_token(text: &str) -> String {
    if bare(text) {
        text.to_string()
    } else {
        serde_json::to_string(text).unwrap_or_else(|_| "\"\"".to_string())
    }
}

fn bare(text: &str) -> bool {
    !text.is_empty()
        && text.chars().all(|c| {
            c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.' | '/' | ':' | '+' | '@')
        })
}

fn canonical(value: &Value) -> String {
    let mut out = String::new();
    write_canon(&mut out, value);
    out
}

fn write_canon(out: &mut String, value: &Value) {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(true) => out.push_str("true"),
        Value::Bool(false) => out.push_str("false"),
        Value::Number(n) => out.push_str(&n.to_string()),
        Value::String(text) => {
            out.push_str(&serde_json::to_string(text).unwrap_or_else(|_| "\"\"".to_string()));
        }
        Value::Array(items) => {
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_canon(out, item);
            }
            out.push(']');
        }
        Value::Object(map) => {
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort();
            out.push('{');
            for (i, key) in keys.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(&serde_json::to_string(key).unwrap_or_else(|_| "\"\"".to_string()));
                out.push(':');
                write_canon(out, &map[*key]);
            }
            out.push('}');
        }
    }
}

fn sha1_hex(data: &[u8]) -> String {
    let mut h = [
        0x6745_2301_u32,
        0xEFCD_AB89,
        0x98BA_DCFE,
        0x1032_5476,
        0xC3D2_E1F0,
    ];
    let bit_len = (data.len() as u64).saturating_mul(8);
    let mut msg = Vec::with_capacity(data.len() + 72);
    msg.extend_from_slice(data);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());
    let (chunks, _) = msg.as_chunks::<64>();
    for chunk in chunks {
        let mut w = [0_u32; 80];
        for (i, word) in w.iter_mut().enumerate().take(16) {
            let start = i * 4;
            *word = u32::from_be_bytes([
                chunk[start],
                chunk[start + 1],
                chunk[start + 2],
                chunk[start + 3],
            ]);
        }
        for i in 16..80 {
            let mixed = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
            w[i] = mixed.rotate_left(1);
        }
        let mut a = h[0];
        let mut b = h[1];
        let mut c = h[2];
        let mut d = h[3];
        let mut e = h[4];
        for (i, word) in w.iter().enumerate() {
            let (f, k) = match i {
                0..=19 => ((b & c) | (!b & d), 0x5A82_7999),
                20..=39 => (b ^ c ^ d, 0x6ED9_EBA1),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1B_BCDC),
                _ => (b ^ c ^ d, 0xCA62_C1D6),
            };
            let temp = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(*word);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = temp;
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
    }
    h.iter().map(|word| format!("{word:08x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::expand;
    use crate::proxy::anthropic::ANTHROPIC;
    use rtok_plugin_sdk::{Ctx, Measurement, WireRequest};
    use serde_json::json;

    fn design() -> Value {
        let fill = json!({"type": "SOLID", "color": "#C0FFEE"});
        let mut children = Vec::new();
        for i in 1..=3 {
            let mut child = json!({
                "id": format!("child-{i}"),
                "name": format!("Card {i}"),
                "type": "FRAME",
                "fills": fill,
            });
            if i == 3 {
                child["padding"] = json!({"top": 4, "right": 8, "bottom": 4, "left": 8});
            }
            children.push(child);
        }
        let mut root = json!({
            "id": "root",
            "name": "Screen",
            "type": "FRAME",
            "children": children,
        });
        let mut note = String::new();
        while serde_json::to_vec(&root).unwrap().len() < MIN_BYTES {
            note.push_str("screen-note-");
            root["note"] = Value::String(note.clone());
        }
        root
    }

    fn reverse_keys(value: &Value) -> Value {
        match value {
            Value::Array(items) => Value::Array(items.iter().map(reverse_keys).collect()),
            Value::Object(map) => {
                let mut keys: Vec<&String> = map.keys().collect();
                keys.reverse();
                let mut out = Map::new();
                for key in keys {
                    out.insert(key.clone(), reverse_keys(&map[key]));
                }
                Value::Object(out)
            }
            other => other.clone(),
        }
    }

    #[test]
    fn sha1_matches_known_vectors() {
        assert_eq!(sha1_hex(b""), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    #[test]
    fn a_truncated_hash_clash_lengthens_by_four() {
        let material = "beta";
        let hash = sha1_hex(material.as_bytes());
        let mut used = BTreeMap::new();
        used.insert(format!("EL-{}", &hash[..8]), "alpha".to_string());
        let id = content_id("EL-", material, &mut used);
        assert_eq!(id, format!("EL-{}", &hash[..12]));
    }

    #[test]
    fn repeated_children_share_one_var_and_one_element() {
        let value = design();
        let folded = fold_json(&value).expect("design folds");
        let again = fold_json(&reverse_keys(&value)).expect("key order folds");
        assert_eq!(
            folded.text, again.text,
            "key order must not change the text"
        );
        assert_eq!(folded.templates, 1);
        let var_lines: Vec<_> = folded
            .text
            .lines()
            .filter(|line| line.starts_with("V-"))
            .collect();
        assert_eq!(var_lines.len(), 1, "one VARS entry:\n{}", folded.text);
        assert_eq!(
            folded.text.matches("#C0FFEE").count(),
            1,
            "the shared fill is hoisted once:\n{}",
            folded.text
        );
        let element_lines: Vec<_> = folded
            .text
            .lines()
            .filter(|line| line.starts_with("EL-"))
            .collect();
        assert_eq!(element_lines.len(), 1, "one EL- id:\n{}", folded.text);
        let element_id = element_lines[0].split(':').next().unwrap();
        assert_eq!(
            folded
                .text
                .matches(&format!("template={element_id}"))
                .count(),
            2,
            "the two repeated children share the template:\n{}",
            folded.text
        );
        assert!(
            folded.text.contains("padding="),
            "single-use padding stays on the node:\n{}",
            folded.text
        );
        assert!(
            !var_lines[0].contains("padding") && !var_lines[0].contains("\"top\""),
            "padding is not a VARS entry: {}",
            var_lines[0]
        );
        assert!(folded.nodes >= 4, "root plus three children");
    }

    #[test]
    fn small_flat_and_tabular_values_are_left_alone() {
        assert!(fold_json(&json!({"a": {"b": 1}})).is_none());
        let mut flat = json!({"a": "x", "b": "y", "c": "z"});
        flat["note"] = Value::String("n".repeat(300));
        assert!(serde_json::to_vec(&flat).unwrap().len() >= MIN_BYTES);
        assert!(fold_json(&flat).is_none(), "no nested object");

        let mut rows = Vec::new();
        for i in 0..8 {
            rows.push(json!({
                "alpha": format!("alpha-value-{i}-padding"),
                "beta": format!("beta-value-{i}-padding"),
                "gamma": i,
                "delta": format!("delta-value-{i}-padding"),
            }));
        }
        let table = Value::Array(rows);
        assert!(serde_json::to_vec(&table).unwrap().len() >= MIN_BYTES);
        assert!(crate::plugins::toon::tabular_keys(&table, 1).is_some());
        assert!(fold_json(&table).is_none(), "toon keeps the table");
    }

    use proptest::prelude::*;

    fn json_leaf() -> impl Strategy<Value = serde_json::Value> {
        prop_oneof![
            Just(Value::Null),
            any::<bool>().prop_map(Value::Bool),
            (0_i32..20).prop_map(Value::from),
            "[a-z]{0,6}".prop_map(Value::String),
        ]
    }

    fn json_value() -> impl Strategy<Value = serde_json::Value> {
        json_leaf().prop_recursive(2, 8, 3, |inner| {
            prop_oneof![
                prop::collection::vec(inner.clone(), 0..3).prop_map(Value::Array),
                prop::collection::btree_map("[a-z]{1,4}", inner, 0..3)
                    .prop_map(|map| { Value::Object(map.into_iter().collect()) }),
            ]
        })
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(32))]
        #[test]
        fn fold_is_deterministic(value in json_value()) {
            let once = fold_json(&value).map(|folded| folded.text);
            let twice = fold_json(&value).map(|folded| folded.text);
            prop_assert_eq!(&once, &twice);
            prop_assert_eq!(once, fold_json(&reverse_keys(&value)).map(|folded| folded.text));
        }
    }

    fn cx(name: &str) -> crate::plugin::Runtime {
        let dir =
            std::env::temp_dir().join(format!("rtok-json-tree-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut cx = crate::plugin::Runtime::in_memory("s").unwrap();
        cx.config.core.archive_dir = dir;
        cx.config.plugins.json_tree.enabled = true;
        cx.config.plugins.archive.keep_turns = 0;
        cx
    }

    fn tool_req(body: &Value) -> Value {
        let content = serde_json::to_string_pretty(body).unwrap();
        json!({"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content": content}]}]})
    }

    fn filter(body: &mut Value, cx: &Ctx) -> Vec<Measurement> {
        JsonTree.proxy_filter(&mut WireRequest::new(&ANTHROPIC, body), cx)
    }

    #[test]
    fn default_off_leaves_bytes_identical() {
        let mut cx = cx("off");
        cx.config.plugins.json_tree.enabled = false;
        let mut body = tool_req(&design());
        let original = body.clone();
        assert!(filter(&mut body, &Ctx::new(&cx)).is_empty());
        assert_eq!(body, original);
    }

    #[test]
    fn an_old_result_is_folded_and_expand_returns_the_original() {
        let cx = cx("fold");
        let value = design();
        let original = serde_json::to_string_pretty(&value).unwrap();
        let mut body = tool_req(&value);
        let ms = filter(&mut body, &Ctx::new(&cx));
        assert_eq!(ms.len(), 1);
        assert_eq!(ms[0].plugin, "json_tree");
        assert_eq!(ms[0].kind, "fold");
        assert!(ms[0].est_after < ms[0].est_before);
        let text = body["messages"][0]["content"][0]["content"]
            .as_str()
            .unwrap()
            .to_string();
        assert!(text.starts_with(PREFIX), "{text}");
        assert!(!text.contains("[toon "));
        let archive_id = ms[0].ref_id.clone().unwrap();

        let mut second = tool_req(&value);
        filter(&mut second, &Ctx::new(&cx));
        assert_eq!(
            second["messages"][0]["content"][0]["content"].as_str(),
            Some(text.as_str()),
            "a second pass replays the stored pointer"
        );

        let expanded = expand::fetch(&cx, &archive_id).unwrap().unwrap();
        assert_eq!(expanded, original.as_bytes());
    }

    #[test]
    fn live_zone_turns_are_untouched() {
        let mut cx = cx("live");
        cx.config.plugins.archive.keep_turns = 4;
        let pretty = serde_json::to_string_pretty(&design()).unwrap();
        let mut fresh = tool_messages(&pretty);
        let ms = filter(&mut fresh, &Ctx::new(&cx));
        assert_eq!(ms.len(), 2, "only older-than-live-zone results fold");
        for i in 0..6 {
            let text = fresh["messages"][2 * i]["content"][0]["content"]
                .as_str()
                .unwrap();
            if i < 2 {
                assert!(text.starts_with(PREFIX), "turn {} folds: {text}", i + 1);
            } else {
                assert_eq!(text, pretty, "live-zone turn stays original");
            }
        }
        let mut second = tool_messages(&pretty);
        filter(&mut second, &Ctx::new(&cx));
        assert_eq!(fresh, second, "a second pass replays the stored pointers");
    }

    fn tool_messages(pretty: &str) -> Value {
        let mut messages = Vec::new();
        for i in 1..=6 {
            messages.push(json!({
                "role": "user",
                "content": [{"type":"tool_result","tool_use_id": format!("t{i}"), "content": pretty}]
            }));
            if i < 6 {
                messages.push(json!({"role":"assistant","content":"ok"}));
            }
        }
        json!({"messages": messages})
    }

    #[test]
    fn a_toon_table_is_not_folded_on_the_proxy() {
        let cx = cx("toon");
        let mut rows = Vec::new();
        for i in 0..8 {
            rows.push(json!({
                "alpha": format!("alpha-value-{i}-padding"),
                "beta": format!("beta-value-{i}-padding"),
                "gamma": i,
                "delta": format!("delta-value-{i}-padding"),
            }));
        }
        let table = Value::Array(rows);
        let mut body = tool_req(&table);
        let original = body.clone();
        assert!(filter(&mut body, &Ctx::new(&cx)).is_empty());
        assert_eq!(body, original);
        let ms = crate::plugins::toon::Toon
            .proxy_filter(&mut WireRequest::new(&ANTHROPIC, &mut body), &Ctx::new(&cx));
        assert!(!ms.is_empty(), "toon still encodes the table");
        let text = body["messages"][0]["content"][0]["content"]
            .as_str()
            .unwrap();
        assert!(text.starts_with(crate::plugins::toon::PREFIX), "{text}");
        assert!(!text.contains(PREFIX), "{text}");
    }

    #[cfg(feature = "cmd")]
    #[test]
    fn mcp_folds_a_long_tree_and_skips_read_and_search() {
        use crate::mcp::wrap::shorten_result;
        use crate::plugins::cmd::rules::Settings;

        let cx = cx("mcp");
        let settings = Settings::from_config(&cx.config);
        let mut value = design();
        for i in 0..50 {
            value[format!("pad{i:02}")] = Value::String(format!("pad-value-{i:02}"));
        }
        let text = serde_json::to_string_pretty(&value).unwrap();
        assert!(
            text.lines().count() > 40,
            "the fixture must exceed max_lines"
        );
        let mut result = json!({"content":[{"type":"text","text": text}]});
        assert!(shorten_result(
            &cx,
            &settings,
            "figma",
            "get_design",
            &mut result,
            "cmd",
            "wrap"
        ));
        let printed = result["content"][0]["text"].as_str().unwrap();
        assert!(printed.contains(PREFIX), "{printed}");
        assert!(printed.lines().count() <= 40, "{printed}");
        let rows = cx.store.list_measurements("json_tree").unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].kind, "fold");
        let id = rows[0].ref_id.clone().unwrap();
        let expanded = expand::fetch(&cx, &id).unwrap().unwrap();
        assert_eq!(expanded, text.as_bytes());

        for tool in ["read", "search"] {
            let mut blocked = json!({"content":[{"type":"text","text": text}]});
            assert!(shorten_result(
                &cx,
                &settings,
                "rtok",
                tool,
                &mut blocked,
                "cmd",
                "wrap"
            ));
            let cut = blocked["content"][0]["text"].as_str().unwrap();
            assert!(!cut.contains(PREFIX), "{tool} must not fold: {cut}");
        }
    }

    #[cfg(feature = "cmd")]
    #[test]
    fn mcp_leaves_json_alone_while_the_plugin_is_off() {
        use crate::mcp::wrap::shorten_result;
        use crate::plugins::cmd::rules::Settings;

        let mut cx = cx("mcp-off");
        cx.config.plugins.json_tree.enabled = false;
        let settings = Settings::from_config(&cx.config);
        let mut value = design();
        for i in 0..50 {
            value[format!("pad{i:02}")] = Value::String(format!("pad-value-{i:02}"));
        }
        let text = serde_json::to_string_pretty(&value).unwrap();
        let mut result = json!({"content":[{"type":"text","text": text}]});
        assert!(shorten_result(
            &cx,
            &settings,
            "figma",
            "get_design",
            &mut result,
            "cmd",
            "wrap"
        ));
        let printed = result["content"][0]["text"].as_str().unwrap();
        assert!(!printed.contains(PREFIX), "{printed}");
        assert!(cx.store.list_measurements("json_tree").unwrap().is_empty());
    }
}

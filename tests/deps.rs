// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Crate boundaries for heavy dependencies. #632 extends this file.

use std::collections::BTreeSet;

/// Diesel (and only Diesel's SQLite stack) belongs to `rtok-store`.
#[test]
fn only_store_depends_on_diesel() {
    let root = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let out = std::process::Command::new("cargo")
        .args(["metadata", "--format-version", "1", "--offline", "--locked"])
        .current_dir(&root)
        .output()
        .expect("cargo metadata");
    assert!(
        out.status.success(),
        "cargo metadata failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    let meta: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    let members: BTreeSet<&str> = meta["workspace_members"]
        .as_array()
        .unwrap()
        .iter()
        .map(|id| id.as_str().unwrap())
        .collect();
    let mut owners = Vec::new();
    for pkg in meta["packages"].as_array().unwrap() {
        let id = pkg["id"].as_str().unwrap();
        if !members.contains(id) {
            continue;
        }
        let name = pkg["name"].as_str().unwrap();
        let uses_diesel = pkg["dependencies"]
            .as_array()
            .unwrap()
            .iter()
            .any(|d| d["name"].as_str() == Some("diesel") && d["kind"].as_str() != Some("dev"));
        // Dev-dependencies count too: a test that opens Diesel is still this package
        // depending on it.
        let uses_diesel = uses_diesel
            || pkg["dependencies"]
                .as_array()
                .unwrap()
                .iter()
                .any(|d| d["name"].as_str() == Some("diesel"));
        if uses_diesel {
            owners.push(name);
        }
    }
    owners.sort();
    assert_eq!(
        owners,
        ["rtok-store"],
        "only rtok-store may depend on diesel"
    );
}

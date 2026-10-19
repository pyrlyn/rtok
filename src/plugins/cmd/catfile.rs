// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `cat <source file>` and `sed -n a,bp <file>` (T674).
//!
//! A long source file prints as its definitions with line spans — the signature and the
//! `start-end` it occupies — instead of a head/tail cut, so the agent sees every function
//! and can read one body with `sed -n a,bp`. A file read again in the same session is a
//! pointer when unchanged and a diff when it changed (the read cache `rtok read` uses).
//! Idea studied in lean-ctx (`cat`/`sed` rewritten to a `read` tool); no code copied.

use std::path::Path;

use crate::plugins::read::outline::{self, TagHit};

/// Files at or under this many lines print whole: an outline would not be shorter.
const MIN_LINES: usize = 80;

/// Config and markup files have a grammar but no definitions worth an outline: their
/// tables and keys are the content, and the `cat` rule keeps a better head and tail.
const DATA_EXTENSIONS: &[&str] = &[
    "toml", "json", "yaml", "yml", "md", "markdown", "html", "htm", "css", "xml", "ini", "lock",
];

/// The outline of a long source file named by a bare `cat <file>`. `None` for any other
/// argv, a short file, or a language without a grammar — the `cat` rule then applies.
pub fn outline_of(argv: &[String], output: &str) -> Option<String> {
    let [cat, file] = argv else {
        return None;
    };
    if super::formatters::cmd_stem(cat) != "cat" || file.starts_with('-') {
        return None;
    }
    let total = output.lines().count();
    let path = Path::new(file);
    let data = path
        .extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| DATA_EXTENSIONS.iter().any(|d| e.eq_ignore_ascii_case(d)));
    if total <= MIN_LINES || data || !outline::supported(path) {
        return None;
    }
    let mut defs: Vec<TagHit> = outline::tags(path, output)
        .ok()?
        .into_iter()
        .filter(|h| h.is_def && h.kind != "import")
        .collect();
    if defs.is_empty() {
        return None;
    }
    defs.sort_by_key(|h| (h.line, std::cmp::Reverse(h.end_line)));
    defs.dedup_by_key(|h| (h.line, h.end_line));
    let mut out = vec![format!(
        "{file}: {total} lines, definitions as start-end (body: sed -n 'A,Bp' {file})"
    )];
    let mut open: Vec<usize> = Vec::new();
    for h in &defs {
        while open.last().is_some_and(|&end| end < h.line) {
            open.pop();
        }
        let indent = "  ".repeat(open.len());
        out.push(format!(
            "{indent}{}-{} {}",
            h.line,
            h.end_line,
            h.line_text.trim()
        ));
        open.push(h.end_line);
    }
    Some(out.join("\n"))
}

/// The read-cache key of a command that prints one file or one line range of it:
/// `cat [-n] f` or `sed -n A,Bp f`. `None` for anything else, so only these commands
/// are remembered and diffed.
pub fn reread_key(argv: &[String]) -> Option<(String, String)> {
    let stem = super::formatters::cmd_stem(argv.first()?);
    let (file, range) = match (stem, &argv[1..]) {
        ("cat", [f]) if !f.starts_with('-') => (f, None),
        ("cat", [n, f]) if n == "-n" && !f.starts_with('-') => (f, None),
        ("sed", [n, range, f]) if n == "-n" && is_print_range(range) && !f.starts_with('-') => {
            (f, Some(range.as_str()))
        }
        _ => return None,
    };
    let abs = std::env::current_dir().ok()?.join(file);
    let path = abs.to_string_lossy().into_owned();
    let key = crate::plugins::read::cache::key(&path, "cmd", range);
    Some((path, key))
}

/// `5,20p` / `5p`.
fn is_print_range(spec: &str) -> bool {
    spec.strip_suffix('p').is_some_and(|r| {
        !r.is_empty() && r.split(',').count() <= 2 && r.split(',').all(|n| n.parse::<u32>().is_ok())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(s: &str) -> Vec<String> {
        s.split_whitespace().map(str::to_string).collect()
    }

    fn rust_file() -> String {
        let mut s = String::from("use std::fmt;\n\n");
        for i in 0..12 {
            s.push_str(&format!("/// doc {i}\npub fn item_{i}(a: u32) -> u32 {{\n"));
            for j in 0..6 {
                s.push_str(&format!("    let v{j} = a + {j};\n"));
            }
            s.push_str("    a\n}\n\n");
        }
        s.push_str("mod holder {\n    pub fn method() {\n        let x = 1;\n    }\n}\n");
        s
    }

    #[test]
    fn long_source_becomes_signatures_with_spans() {
        let src = rust_file();
        let got = outline_of(&argv("cat src/lib.rs"), &src).unwrap();
        assert!(got.contains("pub fn item_0(a: u32) -> u32 {"), "{got}");
        assert!(
            got.contains("pub fn item_11"),
            "every definition survives:\n{got}"
        );
        assert!(got.lines().count() < src.lines().count() / 3, "{got}");
        // The span points at the real lines: item_0 opens on line 4 and closes on line 12.
        assert!(got.contains("4-12 pub fn item_0"), "{got}");
    }

    #[test]
    fn nested_definitions_are_indented_under_their_parent() {
        let got = outline_of(&argv("cat src/lib.rs"), &rust_file()).unwrap();
        let line = got.lines().find(|l| l.contains("fn method")).unwrap();
        assert!(line.starts_with("  "), "{got}");
    }

    #[test]
    fn short_unknown_or_flagged_cat_is_left_to_the_rule() {
        let src = rust_file();
        assert!(outline_of(&argv("cat src/lib.rs"), "fn a() {}\n").is_none());
        assert!(outline_of(&argv("cat notes.xyz"), &src).is_none());
        assert!(outline_of(&argv("cat Cargo.toml"), &src).is_none());
        assert!(outline_of(&argv("cat -n src/lib.rs"), &src).is_none());
        assert!(outline_of(&argv("cat a.rs b.rs"), &src).is_none());
    }

    #[test]
    fn reread_key_names_the_file_and_range() {
        let (path, key) = reread_key(&argv("cat -n src/a.rs")).unwrap();
        assert!(
            path.ends_with("src/a.rs") && key.ends_with("\tcmd\t"),
            "{key}"
        );
        let (_, key) = reread_key(&argv("sed -n 5,20p src/a.rs")).unwrap();
        assert!(key.ends_with("\tcmd\t5,20p"), "{key}");
        assert!(reread_key(&argv("sed -i s/a/b/ f")).is_none());
        assert!(reread_key(&argv("cat a b")).is_none());
        assert!(reread_key(&argv("sed -n /x/p f")).is_none());
    }
}

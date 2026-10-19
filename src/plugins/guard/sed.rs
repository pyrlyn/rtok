// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T557: whether a `sed` invocation can only print. A conservative subset: anything the
//! parser does not recognise (`w`, `e`, `r`, script files, in-place, `[` bracket regexes whose
//! delimiter rules differ between seds) counts as a writer, which only skips a dedup.

/// `args` are the shell words after `sed`.
pub(super) fn read_only(args: &[String]) -> bool {
    let (mut scripts, mut files) = (Vec::new(), Vec::new());
    let mut it = args.iter().map(String::as_str);
    while let Some(a) = it.next() {
        if a == "--" {
            files.extend(it.by_ref());
        } else if let Some(s) = a.strip_prefix("--expression=") {
            scripts.push(s);
        } else if a == "--expression" || a == "-e" {
            let Some(s) = it.next() else { return false };
            scripts.push(s);
        } else if matches!(
            a,
            "--quiet" | "--silent" | "--regexp-extended" | "--null-data" | "--separate"
        ) {
        } else if let Some(cluster) = a.strip_prefix('-').filter(|c| !c.is_empty()) {
            // `-ne script`: a trailing `e` takes the next word as the script.
            let (flags, takes_script) = match cluster.strip_suffix('e') {
                Some(f) => (f, true),
                None => (cluster, false),
            };
            if !flags.chars().all(|c| "nErszu".contains(c)) {
                return false;
            }
            if takes_script {
                let Some(s) = it.next() else { return false };
                scripts.push(s);
            }
        } else {
            files.push(a);
        }
    }
    if scripts.is_empty() && !files.is_empty() {
        scripts.push(files.remove(0));
    }
    !scripts.is_empty() && scripts.into_iter().all(script_read_only)
}

fn script_read_only(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    loop {
        while i < b.len() && (b[i].is_ascii_whitespace() || matches!(b[i], b';' | b'{' | b'}')) {
            i += 1;
        }
        if i >= b.len() {
            return true;
        }
        // `addr1[,addr2]`; `addr1,+N` and `addr1,~N` are the GNU forms.
        let Some(first) = address(b, i) else {
            return false;
        };
        let had_addr = first > i;
        i = first;
        if had_addr && b.get(i) == Some(&b',') {
            i += 1;
            if matches!(b.get(i), Some(b'+' | b'~')) {
                i += 1;
            }
            let Some(second) = address(b, i) else {
                return false;
            };
            i = second;
        }
        while b
            .get(i)
            .is_some_and(|c| c.is_ascii_whitespace() || *c == b'!')
        {
            i += 1;
        }
        match b.get(i) {
            Some(
                b'p' | b'P' | b'd' | b'D' | b'n' | b'N' | b'g' | b'G' | b'h' | b'H' | b'x' | b'=',
            ) => {
                i += 1;
            }
            Some(b'q' | b'Q' | b'l') => {
                i += 1;
                while b.get(i).is_some_and(u8::is_ascii_digit) {
                    i += 1;
                }
            }
            Some(b's') => {
                let Some(d) = b.get(i + 1).copied().filter(|c| !c.is_ascii_alphanumeric()) else {
                    return false;
                };
                let Some(end) = part_end(b, i + 2, d).and_then(|e| part_end(b, e, d)) else {
                    return false;
                };
                i = end;
                // `w file` and `e` are the only flags that leave the process.
                while let Some(c) = b.get(i) {
                    match c {
                        b'g' | b'p' | b'i' | b'I' | b'm' | b'M' | b' ' | b'0'..=b'9' => i += 1,
                        b';' | b'\n' | b'}' => break,
                        _ => return false,
                    }
                }
            }
            _ => return false,
        }
        if b.get(i)
            .is_some_and(|c| !matches!(c, b';' | b'\n' | b'}' | b' ' | b'\t'))
        {
            return false;
        }
    }
}

/// End of one address at `i` (`i` itself when there is none); `None` when malformed.
fn address(b: &[u8], mut i: usize) -> Option<usize> {
    match b.get(i) {
        Some(b'$') => i += 1,
        Some(c) if c.is_ascii_digit() => {
            while b.get(i).is_some_and(|c| c.is_ascii_digit() || *c == b'~') {
                i += 1;
            }
        }
        Some(b'/') => i = part_end(b, i + 1, b'/')?,
        Some(b'\\') => i = part_end(b, i + 2, *b.get(i + 1)?)?,
        _ => return Some(i),
    }
    while b.get(i).is_some_and(|c| matches!(c, b'I' | b'M')) {
        i += 1;
    }
    Some(i)
}

/// Index just past the closing `delim`, skipping `\`-escapes. A `[` anywhere makes the
/// delimiter rules implementation-specific, so it is refused.
fn part_end(b: &[u8], mut i: usize, delim: u8) -> Option<usize> {
    while let Some(&c) = b.get(i) {
        match c {
            b'\\' => i += 1,
            b'[' => return None,
            _ if c == delim => return Some(i + 1),
            _ => {}
        }
        i += 1;
    }
    None
}

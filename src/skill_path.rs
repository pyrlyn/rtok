// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `~/.claude/skills/<name>/SKILL.md` — the path `doctor` and `guard` both resolve.

use std::path::{Path, PathBuf};

/// `~/.claude/skills/<name>/SKILL.md`, joined by components so Windows never
/// sees a single path segment with embedded slashes.
pub(crate) fn skill_md_path(home: &Path, name: &str) -> PathBuf {
    home.join(".claude")
        .join("skills")
        .join(name)
        .join("SKILL.md")
}

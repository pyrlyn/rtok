// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Path identity shared by the store and the host: Windows case, and the `\\?\` prefix
//! `std::fs::canonicalize` adds. It lives in `fs-kit`, so a root the store drops is the same
//! root the host refuses to walk, and the same one every other project sees.

pub use fs_kit::path::{
    canon, is_unwalkable_root, path_ends_with, path_starts_with, same_path, strip_prefix,
};

// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! rtok owns installation and prompts; script generation is shared with other CLIs.

pub use shell_completions::{Clink, Shell, generate};

pub mod install;
pub mod picker;

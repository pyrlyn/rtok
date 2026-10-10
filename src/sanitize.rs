// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T431: noise a saved request body carries without saying anything.
//!
//! The functions live in `rtok-store` because the store applies them when it saves a body.
//! This module keeps the path `crate::sanitize`.

// Only the cmd plugin skips escapes itself; ungated, a build without it warns.
#[cfg(feature = "cmd")]
pub(crate) use rtok_store::sanitize::skip_escape;
pub use rtok_store::sanitize::{body, strings, terminal_noise, text};

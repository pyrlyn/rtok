// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Task ids (`R12`, `R2.1`) shared by the task adapters, the store allocator and config validation.
//!
//! The type lives in `rtok-store` so the allocator does not depend on this crate. This module
//! keeps the path `crate::task_id`.

pub use rtok_store::{MAX_DEPTH, MAX_PREFIX, TaskId, check_prefix};

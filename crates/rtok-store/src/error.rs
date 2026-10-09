// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The store's public error. Callers print it; this crate does not.

use anyhow::Error as AnyhowError;

/// A store operation failed. Display is the underlying error, including its chain.
#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    /// Diesel, I/O, or a `bail!` in a query.
    #[error(transparent)]
    Other(#[from] AnyhowError),
}

/// `Result` for every public store method.
pub type Result<T> = std::result::Result<T, StoreError>;

impl From<diesel::result::Error> for StoreError {
    fn from(e: diesel::result::Error) -> Self {
        Self::Other(AnyhowError::from(e))
    }
}

impl From<diesel::ConnectionError> for StoreError {
    fn from(e: diesel::ConnectionError) -> Self {
        Self::Other(AnyhowError::from(e))
    }
}

impl From<std::io::Error> for StoreError {
    fn from(e: std::io::Error) -> Self {
        Self::Other(AnyhowError::from(e))
    }
}

impl From<serde_json::Error> for StoreError {
    fn from(e: serde_json::Error) -> Self {
        Self::Other(AnyhowError::from(e))
    }
}

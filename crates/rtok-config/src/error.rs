// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use thiserror::Error;

/// Errors from loading or editing config. Callers that return `anyhow::Result` convert with `?`.
#[derive(Debug, Error)]
pub enum ConfigError {
    #[error(transparent)]
    Other(#[from] anyhow::Error),
}

/// `E` defaults to [`ConfigError`]. Provider impls pass `figment::Error` as the second argument.
pub type Result<T, E = ConfigError> = std::result::Result<T, E>;

impl From<std::io::Error> for ConfigError {
    fn from(e: std::io::Error) -> Self {
        Self::Other(e.into())
    }
}

impl From<figment::Error> for ConfigError {
    fn from(e: figment::Error) -> Self {
        Self::Other(anyhow::Error::from(e))
    }
}

impl From<toml_edit::TomlError> for ConfigError {
    fn from(e: toml_edit::TomlError) -> Self {
        Self::Other(anyhow::Error::from(e))
    }
}

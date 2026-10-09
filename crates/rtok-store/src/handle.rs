// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Store methods: open, lock, and migrate.

use super::*;

impl Store {
    /// Open (creating directories and the file as needed) and migrate.
    pub fn open(path: &Path) -> Result<Self> {
        Self::open_with(path, LockWait::STEADY)
    }

    /// [`Store::open`] with its own bound on waiting for other processes' locks.
    pub fn open_with(path: &Path, wait: LockWait) -> Result<Self> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let url = path.to_str().context("db path is not UTF-8")?;
        // A first run races: hooks, the MCP server, the proxy and `otel flush` all open
        // the same fresh file, and the `journal_mode = WAL` switch can return
        // "database is locked" straight away — SQLite does not always run the busy
        // handler for a journal-mode change. Retry with a fresh connection instead of
        // failing the open.
        let attempts = wait.attempts.max(1);
        for attempt in 0..attempts {
            match Self::connect(url, wait) {
                Ok(store) => return Ok(store),
                Err(e) if is_locked(&e) && attempt + 1 < attempts => {
                    std::thread::sleep(OPEN_RETRY_DELAY);
                }
                Err(e) => {
                    return Err(e)
                        .context(path.display().to_string())
                        .map_err(Into::into);
                }
            }
        }
        unreachable!("open: the retry loop always returns")
    }

    fn connect(url: &str, wait: LockWait) -> Result<Self> {
        let mut conn = SqliteConnection::establish(url)?;
        // Hooks, the MCP server, the proxy and the detached `otel flush` child all write this one
        // file. SQLite's default busy timeout is 0, so a second writer failed at once with
        // "database is locked" instead of waiting the few ms the first one holds the lock. First,
        // so switching to WAL waits too; `wait.busy` bounds each statement's wait.
        set_busy(&mut conn, wait.busy)?;
        // T352: the mode is fixed once a table exists, so only a brand-new file gets it, before
        // WAL and the first migration. Existing stores convert in `housekeeping` (T352).
        if std::fs::metadata(url).map_or(true, |m| m.len() == 0) {
            sql_ext::pragma_auto_vacuum_incremental(&mut conn)?;
        }
        sql_ext::pragma_journal_wal(&mut conn)?;
        sql_ext::pragma_synchronous_normal(&mut conn)?;
        Self::init(conn, wait)
    }

    /// Fresh in-memory store for tests and examples.
    pub fn open_in_memory() -> Result<Self> {
        Self::init(SqliteConnection::establish(":memory:")?, LockWait::STEADY)
    }

    fn init(mut conn: SqliteConnection, wait: LockWait) -> Result<Self> {
        #[cfg(any(test, feature = "test-util"))]
        OPEN_COUNT.with(|n| n.set(n.get() + 1));
        sql_ext::pragma_foreign_keys_on(&mut conn)?;
        let store = Self {
            conn: Mutex::new(conn),
            wait,
            store_raw: std::sync::atomic::AtomicBool::new(false),
        };
        store.migrate()?;
        Ok(store)
    }

    pub(crate) fn lock(&self) -> Result<std::sync::MutexGuard<'_, SqliteConnection>> {
        Ok(self.conn.lock().unwrap_or_else(PoisonError::into_inner))
    }

    /// Apply pending migrations; returns how many ran. Idempotent.
    ///
    /// Diesel runs each `up.sql` in its own transaction and records the directory version, so a
    /// crash cannot leave a column added with no version row. Names already in `schema_migrations`
    /// (`NNNN.sql`, the pre-T163.4 runner) are marked applied first and are not run again.
    ///
    /// The apply takes one `BEGIN EXCLUSIVE`: hooks, the MCP server and the proxy all open this
    /// file, and on a fresh store two of them used to land between the version check and the
    /// `ALTER`. A store with nothing pending stays off that write lock.
    pub fn migrate(&self) -> Result<usize> {
        let mut conn = self.lock()?;
        migrations::bridge_legacy(&mut conn)?;
        if !migrations::has_pending(&mut conn)? {
            return Ok(0);
        }
        // Only a fresh or upgraded store reaches here. `open`'s 1 s is the steady-state bound;
        // one migration run plus the queue of other openers outlives it. Restored below. The
        // hook keeps its few ms here too and fails open instead (T178).
        set_busy(&mut conn, self.wait.migrate)?;
        let apply = |conn: &mut SqliteConnection| -> Result<usize> {
            conn.exclusive_transaction(|conn| {
                migrations::bridge_legacy(conn)?;
                migrations::run_pending(conn)
            })
        };
        // A person at a terminal waits out an upgrade's migrations. The hook's few-ms wait
        // draws nothing: it must stay inside its budget and fails open instead of waiting.
        let applied = if self.wait.migrate < std::time::Duration::from_secs(1) {
            apply(&mut conn)
        } else {
            match LONG_MIGRATE.get().copied() {
                Some(hook) => hook("migrating the store", &mut || apply(&mut conn)),
                None => apply(&mut conn),
            }
        };
        set_busy(&mut conn, self.wait.busy)?;
        applied
    }
}

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
            create_private_dir(dir)?;
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
                Ok(store) => {
                    restrict_db_files(path);
                    return Ok(store);
                }
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
            idf_memo: Mutex::new(None),
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
                Some(hook) => hook("migrating the store…", &mut || apply(&mut conn)),
                None => apply(&mut conn),
            }
        };
        set_busy(&mut conn, self.wait.busy)?;
        applied
    }
}

/// Create `dir` (and missing parents) owner-only on Unix: the store holds prompt and tool bodies.
/// An existing directory keeps its mode; the files in it are restricted one by one.
pub(crate) fn create_private_dir(dir: &std::path::Path) -> std::io::Result<()> {
    if dir.as_os_str().is_empty() || dir.is_dir() {
        return Ok(());
    }
    let mut b = std::fs::DirBuilder::new();
    b.recursive(true);
    #[cfg(unix)]
    std::os::unix::fs::DirBuilderExt::mode(&mut b, 0o700);
    b.create(dir)
}

/// Owner-only read/write for the database file and its WAL/SHM siblings. Best effort: a file
/// owned by someone else (a shared path the operator chose) keeps its mode.
fn restrict_db_files(path: &std::path::Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut names = vec![path.as_os_str().to_owned()];
        for ext in ["-wal", "-shm"] {
            let mut n = path.as_os_str().to_owned();
            n.push(ext);
            names.push(n);
        }
        for n in names {
            let p = std::path::Path::new(&n);
            if let Ok(m) = std::fs::metadata(p)
                && m.permissions().mode() & 0o077 != 0
            {
                let _ = std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o600));
            }
        }
    }
    #[cfg(not(unix))]
    let _ = path;
}

#[cfg(all(test, unix))]
mod private_mode_tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn mode(p: &std::path::Path) -> u32 {
        std::fs::metadata(p).unwrap().permissions().mode() & 0o777
    }

    /// S3: a fresh store directory is 0700 and the database file 0600.
    #[test]
    fn fresh_store_is_owner_only() {
        let base = crate::support::tmp_dir("private-store");
        let db = base.join("home").join("rtok.db");
        let store = Store::open(&db).unwrap();
        drop(store);
        let (d, f) = (mode(&base.join("home")), mode(&db));
        let _ = std::fs::remove_dir_all(&base);
        assert_eq!((d, f), (0o700, 0o600));
    }

    /// S3: an existing world-readable database is tightened on open.
    #[test]
    fn existing_readable_db_is_tightened() {
        let base = crate::support::tmp_dir("private-existing");
        let db = base.join("rtok.db");
        drop(Store::open(&db).unwrap());
        std::fs::set_permissions(&db, std::fs::Permissions::from_mode(0o644)).unwrap();
        drop(Store::open(&db).unwrap());
        let f = mode(&db);
        let _ = std::fs::remove_dir_all(&base);
        assert_eq!(f, 0o600);
    }
}

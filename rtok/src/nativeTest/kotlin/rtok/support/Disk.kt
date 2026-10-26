package rtok.support

import rtok.db.Db
import rtok.sys.*

/** Every test binary installs the app's SQLite adapter before any test runs. */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val installDriver = Db.installIfAbsent(rtok.db.room.RoomDbDriver)

private var nextTmp = 0

/** Port of testutil.rs `tmp_dir`: a fresh, empty directory under `$TMPDIR`. */
fun tmpDir(tag: String): String = "${tempDir()}/rtok-$tag-${pid()}-${nextTmp++}".also { removeDirAll(it); createDirAll(it) }

/** Port of testutil.rs `worktree_layout`: `dir/repo` and its linked worktree `dir/wt/repo-t1`, without git. */
fun worktreeLayout(dir: String): Pair<String, String> {
    val main = "$dir/repo"; val wt = "$dir/wt/repo-t1"; val admin = "$main/.git/worktrees/repo-t1"
    createDirAll(admin); createDirAll(wt)
    writeString("$admin/commondir", "../..\n")
    writeString("$wt/.git", "gitdir: $admin\n")
    return main to wt
}

// `fs::remove_dir_all` with its errors (the shelled `removeDirAll` ignores them), and the `trash` crate's delete
// (per OS: Trash.macos.kt, Trash.linux.kt).

package rtok.sys

import platform.posix.errno
import platform.posix.rmdir

/** Removes [path] and everything under it; a symlink inside is removed, never followed. @throws IoException */
fun removeTree(path: String) {
    if (symlinkMeta(path).kind == EntryKind.Dir) {
        for (name in readDir(path)) removeTree(join(path, name))
        if (rmdir(path) != 0) throw IoException(ioKind(errno), "rmdir $path: errno $errno")
    } else {
        removeFile(path)
    }
}

/** Moves [path] to the user's trash. @throws IoException */
expect fun moveToTrash(path: String)

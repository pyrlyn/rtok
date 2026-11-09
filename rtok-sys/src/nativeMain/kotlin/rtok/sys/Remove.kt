// `fs::remove_dir_all` (the walk is `removeDirAll`'s, which also tolerates a missing root), and the `trash` crate's
// delete (per OS: Trash.macos.kt, Trash.linux.kt).

package rtok.sys

/** Removes [path] and everything under it; a symlink inside is removed, never followed. @throws IoException, also when [path] is missing */
fun removeTree(path: String) {
    // Rust's remove_dir_all fails on a missing root; removeDirAll does not.
    symlinkMeta(path)
    removeDirAll(path)
}

/** Moves [path] to the user's trash. @throws IoException */
expect fun moveToTrash(path: String)

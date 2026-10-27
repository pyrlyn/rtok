// `fs::remove_dir_all` with its errors (the shelled `removeDirAll` ignores them), and the `trash` crate's macOS
// delete through `NSFileManager` (no Finder: no Automation prompt, no sound).
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package rtok.sys

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
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
fun moveToTrash(path: String) {
    memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val ok = NSFileManager.defaultManager.trashItemAtURL(NSURL.fileURLWithPath(path), null, err.ptr)
        if (!ok) throw IoException(IoException.Kind.Other, err.value?.localizedDescription ?: "cannot move $path to trash")
    }
}

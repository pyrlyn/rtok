// The `trash` crate's macOS delete through `NSFileManager` (no Finder: no Automation prompt, no sound).
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

actual fun moveToTrash(path: String) {
    memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val ok = NSFileManager.defaultManager.trashItemAtURL(NSURL.fileURLWithPath(path), null, err.ptr)
        if (!ok) throw IoException(IoException.Kind.Other, err.value?.localizedDescription ?: "cannot move $path to trash")
    }
}

// FSEvents recursive watch (notify's macOS backend): file-level events on a private dispatch queue, handed to the
// caller through an [FsEventQueue].
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreServices.*
import platform.darwin.dispatch_queue_create

private class MacWatch(
    private val queue: FsEventQueue,
    private val ref: StableRef<FsEventQueue>,
    private val stream: FSEventStreamRef,
) : FsWatch by queue {
    override fun close() {
        FSEventStreamStop(stream)
        FSEventStreamInvalidate(stream)
        FSEventStreamRelease(stream)
        queue.end()
        ref.dispose()
    }
}

private const val LATENCY_S = 0.05

private val RESCAN_FLAGS = (kFSEventStreamEventFlagMustScanSubDirs or kFSEventStreamEventFlagUserDropped or kFSEventStreamEventFlagKernelDropped).toUInt()

private val callback =
    staticCFunction {
        _: ConstFSEventStreamRef?,
        info: COpaquePointer?,
        n: ULong,
        paths: COpaquePointer?,
        flags: CPointer<FSEventStreamEventFlagsVar>?,
        _: CPointer<FSEventStreamEventIdVar>?,
        ->
        val q = info?.asStableRef<FsEventQueue>()?.get() ?: return@staticCFunction
        val names = paths?.reinterpret<CPointerVar<ByteVar>>() ?: return@staticCFunction
        val list = ArrayList<String>(n.toInt())
        var rescan = false
        for (i in 0 until n.toInt()) {
            names[i]?.toKString()?.let(list::add)
            if ((flags?.get(i) ?: 0u) and RESCAN_FLAGS != 0u) rescan = true
        }
        q.push(FsEvent(list, rescan))
    }

actual fun watchRecursive(root: String): FsWatch =
    memScoped {
        if (!isDir(root)) throw IoException(IoException.Kind.NotFound, "watch $root: no such directory")
        val queue = FsEventQueue()
        val ref = StableRef.create(queue)
        val ctx = alloc<FSEventStreamContext>()
        ctx.version = 0
        ctx.info = ref.asCPointer()
        val path = CFStringCreateWithCString(null, root, kCFStringEncodingUTF8)
        val arr = allocArrayOf(path)
        val paths = CFArrayCreate(null, arr.reinterpret(), 1, kCFTypeArrayCallBacks.ptr)
        val stream =
            FSEventStreamCreate(
                null,
                callback,
                ctx.ptr,
                paths,
                kFSEventStreamEventIdSinceNow,
                LATENCY_S,
                (kFSEventStreamCreateFlagFileEvents or kFSEventStreamCreateFlagNoDefer).toUInt(),
            )
        CFRelease(paths)
        CFRelease(path)
        if (stream == null) {
            ref.dispose()
            throw IoException(IoException.Kind.Other, "watch $root: FSEventStreamCreate failed")
        }
        FSEventStreamSetDispatchQueue(stream, dispatch_queue_create("rtok-watch", null))
        if (!FSEventStreamStart(stream)) {
            FSEventStreamInvalidate(stream)
            FSEventStreamRelease(stream)
            ref.dispose()
            throw IoException(IoException.Kind.Other, "watch $root: FSEventStreamStart failed")
        }
        MacWatch(queue, ref, stream)
    }

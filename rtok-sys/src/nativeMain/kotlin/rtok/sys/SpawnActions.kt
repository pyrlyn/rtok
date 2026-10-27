// The one posix_spawn entry point of rtok-sys: an ordered list of file actions handed to the `rtok_spawn` C helper,
// so no Kotlin code names the posix_spawn types (on Linux they live in platform.linux, out of nativeMain's reach).
@file:OptIn(ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.*
import platform.posix.ENOENT
import rtok.sys.spawn.rtok_spawn

/** What the child's descriptors and directory become before it execs, in order. */
internal class SpawnActions {
    private val kind = mutableListOf<Int>()
    private val fd = mutableListOf<Int>()
    private val arg = mutableListOf<Int>()
    private val mode = mutableListOf<Int>()
    private val path = mutableListOf<String?>()

    private fun add(
        k: Int,
        f: Int,
        a: Int = 0,
        m: Int = 0,
        p: String? = null,
    ) = apply {
        kind += k
        fd += f
        arg += a
        mode += m
        path += p
    }

    /** The child's [to] is our [from]. */
    fun dup2(
        from: Int,
        to: Int,
    ) = add(DUP2, to, from)

    /** The child's [target] is [file] opened with [flags] (and [perm] when it creates). */
    fun open(
        target: Int,
        file: String,
        flags: Int,
        perm: Int = 0,
    ) = add(OPEN, target, flags, perm, file)

    fun close(target: Int) = add(CLOSE, target)

    /** The child starts in [dir]. */
    fun chdir(dir: String) = add(CHDIR, -1, p = dir)

    /**
     * Starts [program] with [args] and [envp] (ours when null); [setsid] gives it its own session, [search] looks a
     * slash-less name up on PATH. The pid; throws [IoException] (NotFound for ENOENT) when it cannot start.
     */
    fun spawn(
        program: String,
        args: List<String>,
        envp: List<String>?,
        setsid: Boolean = false,
        search: Boolean = true,
    ): Int =
        memScoped {
            val argv = allocArrayOf((listOf(program) + args).map { it.cstr.ptr } + listOf(null))
            val env = envp?.let { allocArrayOf(it.map { e -> e.cstr.ptr } + listOf(null)) } ?: rtok.sys.spawn.rtok_environ()
            val paths = allocArrayOf(path.map { it?.cstr?.ptr })
            val pid = alloc<IntVar>()
            val rc =
                rtok_spawn(
                    pid.ptr,
                    program,
                    argv,
                    env,
                    kind.size,
                    kind.toIntArray().toCValues(),
                    fd.toIntArray().toCValues(),
                    arg.toIntArray().toCValues(),
                    mode.toIntArray().toCValues(),
                    paths.reinterpret(),
                    if (setsid) 1 else 0,
                    if (search) 1 else 0,
                )
            if (rc != 0) throw IoException(if (rc == ENOENT) IoException.Kind.NotFound else IoException.Kind.Other, "$program: spawn errno $rc")
            pid.value
        }

    private companion object {
        const val DUP2 = 0
        const val OPEN = 1
        const val CLOSE = 2
        const val CHDIR = 3
    }
}

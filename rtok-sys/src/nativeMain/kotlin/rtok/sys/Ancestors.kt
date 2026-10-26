// Port of rtok_sys::parent_of / ancestors: the parent chain of a process.
package rtok.sys

import kotlinx.cinterop.ExperimentalForeignApi

/** Parent pid of [pid], or null. macOS asks the kernel (proc_pidinfo); Linux reads `/proc/<pid>/stat`. */
@OptIn(ExperimentalForeignApi::class)
fun parentOf(pid: Int): Int? {
    if (pid <= 0) return null
    val viaKernel = rtok.sys.spawn.rtok_parent_of(pid)
    if (viaKernel >= 0) return viaKernel
    val stat = runCatching { readToString("/proc/$pid/stat") }.getOrNull() ?: return null
    // `comm` (field 2) may hold spaces and parentheses, so fields count from the last `)`.
    return stat.substring(stat.lastIndexOf(')') + 1).trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
}

/** Up to [max] ancestors of [pid], nearest first; stops at init (1), a loop or an unknown parent. */
fun processAncestors(pid: Int, max: Int): List<Int> {
    val chain = ArrayList<Int>()
    var at = pid
    while (chain.size < max) {
        val p = parentOf(at) ?: break
        if (p <= 1 || p == pid || p in chain) break
        chain += p; at = p
    }
    return chain
}

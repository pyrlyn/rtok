@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import platform.posix.stat

internal actual fun mtimeNanos(st: stat): Long = st.st_mtim.tv_sec * 1_000_000_000L + st.st_mtim.tv_nsec

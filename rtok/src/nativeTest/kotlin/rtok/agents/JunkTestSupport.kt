// Shared helpers of the junk kind tests (junk_kinds.rs `put`, `age`, `row`, `vim_swap`).
package rtok.agents

import rtok.sys.createDirAll
import rtok.sys.parent
import rtok.sys.setModified
import rtok.sys.writeBytes
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

internal val LIMIT = 10.seconds
private const val NANOS = 1_000_000_000L
private const val SWAP_SIZE = 64
private const val PID_AT = 24
private const val PID_BYTES = 4
private const val BITS = 8

internal fun putFile(
    path: String,
    bytes: ByteArray = "x".encodeToByteArray(),
) {
    createDirAll(parent(path)!!)
    writeBytes(path, bytes)
}

internal fun ageBy(
    path: String,
    secs: Long,
) = setModified(path, nowNanos() - secs * NANOS)

internal fun junkRow(
    items: List<JunkItem>,
    name: String,
): JunkItem = assertNotNull(items.firstOrNull { it.path.endsWith("/$name") }, "no item ending in $name: $items")

/** A vim swap file naming [pid]: the `b0` block, pid little-endian at byte 24. */
internal fun vimSwap(
    path: String,
    pid: Int,
) {
    val head = ByteArray(SWAP_SIZE)
    head[0] = 'b'.code.toByte()
    head[1] = '0'.code.toByte()
    for (i in 0 until PID_BYTES) head[PID_AT + i] = (pid ushr (BITS * i)).toByte()
    putFile(path, head)
}

// Shared fixtures of the demon tests: a signal recorder, a temp state store and state builders.
package rtok.demon

import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.pid
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Records every signal instead of sending one; [onPause] runs once, on the first wait. */
internal class Recorder(
    val boot: Long?,
    alive: List<Int>,
    val ignoresTerm: Boolean = false,
    var onPause: (() -> Unit)? = null,
) : Procs {
    val live = alive.toMutableList()
    val calls = mutableListOf<Pair<String, Int>>()

    override fun alive(pid: Int) = pid in live

    override fun term(pid: Int) {
        calls += "term" to pid
        if (!ignoresTerm) live.remove(pid)
    }

    override fun kill(pid: Int) {
        calls += "kill" to pid
        live.remove(pid)
    }

    override fun bootTime() = boot

    override fun pause(ms: Long) {
        onPause?.also { onPause = null }?.invoke()
    }
}

private var seq = 0

internal fun store(tag: String): Store {
    val dir = "${tempDir()}/rtok-demon-$tag-${pid()}-${seq++}"
    removeDirAll(dir)
    createDirAll(dir)
    return Store(dir)
}

internal fun now() = Clock.System.now().epochSeconds

internal fun state(
    supervisor: Int,
    child: Int,
    since: Long,
) = State("web", supervisor, child, since, 0)

// Port of src/log.rs `Tspin` (T225.1): `rtok logs` and `rtok logs watch` through tailspin.
package rtok

import rtok.config.Config
import rtok.sys.PipedChild

/** A running `tspin --print` that `rtok logs` writes its plain rows into: the numbering stays rtok's, tailspin colours. */
class Tspin private constructor(
    private val child: PipedChild,
) {
    /** One chunk to the viewer; false once it stopped reading. A viewer dying mid-stream is not rtok's error. */
    fun write(text: String): Boolean = child.send(text.encodeToByteArray()) && child.drainInput()

    /** Hand the viewer every row, then [finish]. */
    fun print(rows: List<String>) {
        for (row in rows) if (!write("$row\n")) break
        finish()
    }

    /** Close the pipe and wait for tailspin to flush what it has coloured. */
    fun finish() {
        child.finishInput()
        child.drainInput()
        child.waitExit()
    }

    companion object {
        /**
         * `[log] tspin`: `auto` starts the viewer when stdout is a terminal, `always` regardless, `off` never. Null also
         * when `tspin` is not on PATH or will not start: the caller prints plain instead, so a missing viewer never
         * costs a line.
         */
        fun start(
            cfg: Config,
            tty: Boolean,
        ): Tspin? {
            val wanted =
                when (cfg.log.tspin) {
                    "always" -> true
                    "auto" -> tty
                    else -> false
                }
            if (!wanted) return null
            return runCatching { Tspin(PipedChild.spawn("tspin", listOf("--print"), inheritStderr = true, inheritStdout = true)) }.getOrNull()
        }
    }
}

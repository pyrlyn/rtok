@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

// The debug log on stderr (src/log.rs `init_stderr`): Rust's `log` facade behind `env_logger`, on only while `RUST_LOG`
// enables the record's level for its target. Unset, the filter is `off`, so a command prints nothing extra.

package rtok

import kotlinx.cinterop.toKString

object StderrLog {
    enum class Level { Error, Warn, Info, Debug, Trace }

    private const val LEVEL_WIDTH = 5

    /** `RUST_LOG` as the process sees it. */
    fun spec(): String? = platform.posix.getenv("RUST_LOG")?.toKString()

    /** One directive: a target (empty for all) and its ceiling (null for `off`). */
    private class Directive(
        val target: String,
        val max: Level?,
    )

    private fun directive(d: String): Directive {
        val eq = d.indexOf('=')
        return when {
            eq >= 0 -> Directive(d.substring(0, eq), parse(d.substring(eq + 1)))
            parse(d) != null || d.equals("off", true) -> Directive("", parse(d))
            else -> Directive(d, Level.Trace)
        }
    }

    /**
     * Whether `RUST_LOG`'s [spec] enables [level] for [target] (`rtok::tasks`). env_logger's directives:
     * comma-separated `level`, `target` (all levels) or `target=level`; the longest matching target wins.
     */
    fun enabled(
        level: Level,
        target: String,
        spec: String? = spec(),
    ): Boolean {
        val matching =
            spec
                .orEmpty()
                .substringBefore('/')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map(::directive)
                .filter { it.target.isEmpty() || target == it.target || target.startsWith("${it.target}::") }
        // The longest target wins; among equals, the later directive.
        val chosen = matching.fold(null as Directive?) { best, d -> if (best == null || d.target.length >= best.target.length) d else best }
        val max = chosen?.max
        return max != null && level <= max
    }

    private fun parse(s: String): Level? = Level.entries.firstOrNull { it.name.equals(s.trim(), true) }

    /** env_logger's default line: `[2026-10-27T07:35:19Z WARN  rtok::tasks] message`. */
    fun line(
        level: Level,
        target: String,
        message: String,
        now: Long = platform.posix.time(null),
    ) = "[${utc(now)} ${level.name.uppercase().padEnd(LEVEL_WIDTH)} $target] $message"

    private fun utc(secs: Long) = rtok.log.stamp(secs).replace(' ', 'T') + "Z"
}

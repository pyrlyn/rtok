// Port of src/doctor/fix.rs, part 1: what `rtok doctor --fix` (T331.5/T331.6) may remove. The edit, the write and
// the report land with the rest of fix.rs.
package rtok.doctor

import rtok.agents.isRtokBin
import rtok.mcp.config.RTOK
import rtok.sys.Shlex

internal object Fix {
    /** What `--fix` works on, by [Problem.kind]. */
    val KINDS = listOf("broken-hook", "duplicate-hook", "duplicate-mcp")

    /**
     * rtok's own hook entries are installed, updated and removed by `rtok agents`, which knows the plugin rules (T275);
     * a doctor removal would be undone by the next update.
     */
    fun isRtokOwn(command: String): Boolean =
        Shlex.split(command).orEmpty().any { w ->
            isRtokBin(w) || w.substringAfterLast('/').let { n -> if (n.lastIndexOf('.') > 0) n.substringBeforeLast('.') else n } == "rtok-hook"
        }

    /**
     * What install writes again: a hook that runs rtok, or the MCP entry named `rtok`. A copy of the server under
     * another name (`rtok-mcp`) is a hand-written extra and may go (T331.10).
     */
    fun isOwn(x: Problem): Boolean = if (x.kind == "duplicate-mcp") x.path.substringAfterLast('.') == RTOK.name else isRtokOwn(x.command)

    /** `(event, group, hook)` of a `hooks.<event>[g]` or `hooks.<event>[g].hooks[h]` key path. */
    @Suppress("ReturnCount")
    fun parsePath(path: String): Triple<String, Int, Int?>? {
        val rest = path.removePrefix("hooks.").takeIf { path.startsWith("hooks.") } ?: return null
        val open = rest.indexOf('[').takeIf { it >= 0 } ?: return null
        val event = rest.substring(0, open)
        val tail = rest.substring(open + 1)
        val close = tail.indexOf(']').takeIf { it >= 0 } ?: return null
        val g = tail.substring(0, close).toUIntOrNull()?.toInt() ?: return null
        val r = tail.substring(close + 1)
        if (r.isEmpty()) return Triple(event, g, null)
        if (!r.startsWith(".hooks[") || !r.endsWith("]")) return null
        val h =
            r
                .removePrefix(".hooks[")
                .removeSuffix("]")
                .toUIntOrNull()
                ?.toInt() ?: return null
        return Triple(event, g, h)
    }

    /** A finding `--fix` would remove: a broken hook, or a copy of a duplicate that is not the one kept. */
    fun wanted(
        x: Problem,
        kinds: List<String>,
    ) = x.kind in kinds && (x.kind == "broken-hook" || !x.keep)

    /** A finding that `--fix` removes unless the user's selection says otherwise. */
    fun removable(
        x: Problem,
        kinds: List<String>,
    ) = wanted(x, kinds) && x.fixable && !isOwn(x)
}

// Port of src/agents/mod.rs host binary lookup: `RTOK_HOST_SANDBOX`, `expand_spec`/`expand_app`, `under`,
// `find_bin`, `find_on_path`, `probe_installed`, `host_program`. POSIX only: no `PATHEXT` (no Windows target).
package rtok.agents

import rtok.config.envVar
import rtok.sys.exists
import rtok.sys.isFile

object HostBins {
    /** Set by the test harness: rtok sees only hosts under the home dir (a test's own fakes). */
    const val SANDBOX_ENV = "RTOK_HOST_SANDBOX"

    /** The home dir when [SANDBOX_ENV] is set. */
    fun sandbox(): String? = envVar(SANDBOX_ENV)?.let { McpPaths.home() }

    /** True under the test harness: probes of the machine's real processes are off too. */
    fun sandboxed() = sandbox() != null

    /** `~/x` and `$VAR/x` as a path on this machine; an unset variable is left as written. */
    fun expandSpec(spec: String): String =
        when {
            spec.startsWith("~/") -> {
                join(McpPaths.home(), spec.removePrefix("~/"))
            }

            spec.startsWith("$") -> {
                val rest = spec.removePrefix("$")
                val root = envVar(rest.substringBefore('/'))
                if (root == null) spec else join(root, rest.substringAfter('/', ""))
            }

            else -> {
                spec
            }
        }.trimEnd('/').ifEmpty { "/" }

    /** [expandSpec], re-rooted under the home dir when [SANDBOX_ENV] is set. */
    fun expandApp(spec: String): String {
        val path = expandSpec(spec)
        return sandbox()?.let { under(it, path) } ?: path
    }

    /** [path] when inside [home], else re-rooted beneath it (`/Applications/X.app` → `<home>/Applications/X.app`). */
    fun under(
        home: String,
        path: String,
    ): String {
        val h = home.trimEnd('/')
        if (path == h || path.startsWith("$h/")) return path
        val rel = path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return (listOf(h) + rel).joinToString("/")
    }

    /** The first file named [bin] in a [path] (`:`-separated) directory; with [limit], only directories under it. */
    fun findBin(
        bin: String,
        path: String?,
        limit: String? = null,
    ): String? =
        path
            ?.split(':')
            ?.filter { it.isNotEmpty() && (limit == null || under(limit, it) == it) }
            ?.map { join(it, bin) }
            ?.firstOrNull(::isFile)

    /** The first [bin] on PATH; under [SANDBOX_ENV] only PATH entries under the home dir count. */
    fun findOnPath(bin: String): String? = findBin(bin, envVar("PATH"), sandbox())

    /** [path] when it exists (a file or an `.app` bundle). */
    fun probeInstalled(path: String): String? = path.takeIf(::exists)

    /** What to spawn for host CLI [bin]: the name itself, or under [SANDBOX_ENV] the path found for it. */
    fun hostProgram(bin: String): String? = if (sandbox() == null || bin.startsWith('/')) bin else findOnPath(bin)
}

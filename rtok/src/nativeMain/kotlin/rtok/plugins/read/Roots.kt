// Port of src/plugins/read/roots.rs (T351): roots the path guard accepts beyond cwd and `allow_paths`, for
// `rtok mcp` only — sibling worktrees of the cwd's repository and the client's `roots/list` directories.
// Nothing here runs unless `rtok mcp` called [enable], so hooks never spawn git.
package rtok.plugins.read

import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.ThreadLocal
import kotlin.time.TimeSource
import rtok.HostPaths
import rtok.sys.*
import rtok.worktree.Git

object Roots {
    /** A linked worktree can be added after the server started, so the listing is re-read after this long. */
    private const val WORKTREE_TTL_MS = 30_000L
    private val enabled = AtomicReference(false)
    private val denySecrets = AtomicReference(false)
    private val client = AtomicReference<List<String>>(emptyList())
    private val clock = TimeSource.Monotonic
    private val worktreeCache = AtomicReference<Map<String, Pair<TimeSource.Monotonic.ValueTimeMark, List<String>>>>(emptyMap())

    /** Called once by `rtok mcp`; turns the extra roots on for this process. */
    fun enable() { enabled.value = true }
    /** Called by `rtok mcp --http`: refuse credential files in this process. */
    fun denySecrets() { denySecrets.value = true }
    fun secretsDenied() = denySecrets.value
    fun isSecret(path: String) = HostPaths.isSecret(path)
    /** The directories of the client's latest `roots/list` answer (replaces the previous one). */
    fun setClientRoots(roots: List<String>) { client.value = roots }
    fun clientRoots(): List<String> = client.value

    /** Test hook: back to the process defaults. */
    internal fun reset() { enabled.value = false; denySecrets.value = false; client.value = emptyList(); worktreeCache.value = emptyMap() }

    /** Client roots plus the worktrees of the repositories of [cwd] and of every client root; empty unless enabled. */
    fun dynamic(cwd: String): List<String> {
        if (!enabled.value) return emptyList()
        val c = client.value
        // T435: the session's own repository may be known only as a client root.
        return c + (listOf(cwd) + c).flatMap(::worktrees)
    }

    /** Whether [dir]'s repository has a linked worktree besides [dir] itself. */
    fun hasOtherWorktrees(dir: String) = worktrees(dir).size > 1

    @ThreadLocal private object Caller { var dir: String? = null }

    /** The caller's root for this call, when [enterCaller] set one. */
    fun callerRoot(): String? = Caller.dir

    /** The base of relative paths: the caller's root for this call, else the process cwd. */
    fun cwd(): String = Caller.dir ?: rtok.sys.cwd()

    /** Restores the previous caller root on [close]. */
    class CallerGuard internal constructor(private val prev: String?) : AutoCloseable { override fun close() { Caller.dir = prev } }

    /**
     * T923: makes [dir] the base of relative paths until the guard closes, if the guard would accept it from a
     * server in [serverCwd]: inside it, a worktree of its repository or a client root. Anything else (a missing
     * directory, `/`, `$HOME`, an unrelated project) is ignored, since the root comes from an untrusted agent row.
     */
    fun enterCaller(serverCwd: String, dir: String): CallerGuard? {
        val d = canonicalize(dir)?.takeIf(::isDir) ?: return null
        val known = (listOf(serverCwd) + client.value).flatMap { listOf(it) + worktrees(it) }
        val home = homeDir()
        if (known.map { canonicalize(it) ?: it }.filter { !isUnwalkableRoot(it, home) }.none { pathStartsWith(d, it) }) return null
        val guard = CallerGuard(Caller.dir); Caller.dir = d
        return guard
    }

    /** T972: ` (moved? it exists at <path>)` when a known worktree/client root of the same name holds the file. */
    fun movedHint(cwd: String, path: String): String {
        if (!path.startsWith("/")) return ""
        val trees = dynamic(cwd)
        val parts = components(path)
        for ((i, part) in parts.withIndex()) {
            val rest = parts.drop(i + 1).joinToString("/")
            for (t in trees) {
                if (fileName(t) != part) continue
                val p = if (rest.isEmpty()) t else join(t, rest)
                if (p != path && isFile(p)) return " (moved? it exists at $p)"
            }
        }
        return ""
    }

    internal fun worktrees(cwd: String): List<String> {
        worktreeCache.value[cwd]?.let { (at, list) -> if (at.elapsedNow().inWholeMilliseconds < WORKTREE_TTL_MS) return list }
        // Fail soft: no git, not a repository, or a broken listing grants nothing.
        val list = runCatching { Git.list(cwd).map { it.path } }.getOrDefault(emptyList())
        while (true) { val cur = worktreeCache.value; if (worktreeCache.compareAndSet(cur, cur + (cwd to (clock.markNow() to list)))) break }
        return list
    }
}

// Shared fixtures of the junk.rs report tests (`write`, `roots`, `agent`, `folder`, `walk_names`, `disk_usage`).
package rtok.agents

import rtok.config.Config
import rtok.support.diskConfig
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.diskUsageUntil
import rtok.sys.isDir
import rtok.sys.listDirOrEmpty
import rtok.sys.parent
import rtok.sys.writeBytes
import kotlin.test.assertNotNull

internal fun write(
    path: String,
    bytes: Int,
) {
    createDirAll(parent(path)!!)
    writeBytes(path, ByteArray(bytes) { 'x'.code.toByte() })
}

/** Roots over a fixture home with [env] as the only environment. */
internal fun roots(
    home: String,
    env: Map<String, String> = emptyMap(),
) = JunkRoots(home, { env[it] })

internal fun agent(
    r: JunkReport,
    name: String,
): AgentJunk = assertNotNull(r.agents.firstOrNull { it.name == name }, "no `$name` row")

internal fun folder(
    a: AgentJunk,
    path: String,
): Folder = assertNotNull(a.folders.firstOrNull { it.path == path }, "`${a.name}` has no folder $path: ${a.folders}")

internal fun diskUsage(path: String) = diskUsageUntil(path, null).bytes

/** Every path under [dir], sorted: "a report changes no file". */
internal fun walkNames(dir: String): List<String> {
    val out = ArrayList<String>()
    val stack = ArrayDeque(listOf(dir))
    while (true) {
        val d = stack.removeLastOrNull() ?: break
        for (name in listDirOrEmpty(d)) {
            val p = "$d/$name"
            out += p
            if (isDir(p) && !isSymlink(p)) stack.addLast(p)
        }
    }
    return out.sorted()
}

/** A config whose rtok folders are outside the fixture home, which holds the agents' folders. */
internal fun agentsConfig(tag: String): Pair<Config, String> {
    installDb()
    val (cfg, dir) = diskConfig(tag)
    return cfg.copy(log = cfg.log.copy(path = "$dir/none/rtok.log"), core = cfg.core.copy(archiveDir = "$dir/none/archive")) to dir
}

/** [JunkOptions] over every host, outside the fixture so no tagged cache is "the worktree this runs from". */
internal fun everyHost(worktrees: List<Pair<String, String>> = emptyList()) = JunkOptions(all = true, cwd = rtok.sys.tempDir(), worktrees = worktrees)

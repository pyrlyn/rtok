// Port of src/agents/junk.rs, the host rows (T330.2): each host's folders from the §22 map and its config-file
// folders, sized once however many hosts share them, with the items `clear` may remove.
package rtok.agents

import rtok.Col
import rtok.config.Config
import rtok.config.HOSTS
import rtok.plugins.graph.Lsp
import rtok.store.Store
import rtok.store.archivesPendingRetention
import rtok.store.convertToIncrementalVacuum
import rtok.store.projects
import rtok.store.runRetention
import rtok.sys.ChangePreview
import rtok.sys.EntryKind
import rtok.sys.canonicalize
import rtok.sys.cwd
import rtok.sys.diskUsageUntil
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDirOrEmpty
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.removeFile
import rtok.sys.symlinkMeta
import rtok.table
import rtok.worktree.notedTable
import kotlin.time.Duration
import kotlin.time.TimeSource

object JunkHosts {
    private fun deadline(limit: Duration) = TimeSource.Monotonic.markNow() + limit

    internal fun staleFor(
        stale: List<Pair<String, JunkItem>>,
        name: String,
    ) = stale.filter { it.first == name }.map { it.second }

    /** What `list` says about the session threshold in use (T330 "Old sessions: time only"). */
    internal fun sessionNote(days: UInt): String {
        val note = "old sessions: not touched for more than $days days"
        return if (days == 0u) "$note (threshold 0: every closed session is junk)" else note
    }

    /** The folders a host's own config files sit in, under the home only (rtok keeps `.backup_configs` there, T249). */
    private fun markerDirs(
        id: String,
        cfg: Config,
        roots: JunkRoots,
    ): List<String> {
        val variants = Hosts.host(id)?.variants.orEmpty()
        val agent = Agents.agent(id) ?: return emptyList()
        val home = roots.home
        return variants
            .flatMap { agent.markers(cfg, it.kind) }
            .mapNotNull(::parent)
            .filter { d -> parent(d) != null && d != home && pathStartsWith(d, home) && isDir(d) }
            .sorted()
            .distinct()
    }

    /** A folder a host writes, before it is sized. */
    private data class Found(
        val path: String,
        val role: JunkRole,
        val documented: Boolean,
        val perCommit: Boolean,
    )

    private fun lexists(p: String) = runCatching { symlinkMeta(p) }.isSuccess

    private fun depth(p: String) = p.split('/').count { it.isNotEmpty() } + if (p.startsWith('/')) 1 else 0

    /** The folders of one host that exist: the §22 map, then the folders its own config files sit in. */
    private fun hostFolders(
        id: String,
        cfg: Config,
        roots: JunkRoots,
    ): List<Found> {
        val found = ArrayList<Found>()
        for (s in JunkMap.specs(id)) {
            val path = roots.resolve(s.path)
            if (found.none { it.path == path } && lexists(path)) found += Found(path, s.role, s.documented, s.perCommit)
        }
        for (d in markerDirs(id, cfg, roots).sortedBy(::depth)) {
            if (found.none { pathStartsWith(d, it.path) }) found += Found(d, JunkRole.Data, documented = false, perCommit = false)
        }
        return found.sortedBy { it.path }
    }

    /** The real path of [path] when it is a symlink: the folder is sized at its target and the listing says so. */
    private fun symlinkTarget(path: String): String? {
        val meta = runCatching { symlinkMeta(path) }.getOrNull() ?: return null
        return if (meta.kind == EntryKind.Symlink) canonicalize(path) else null
    }

    /** Every host as an agent row: installed ones, or all with [JunkOptions.all]; each folder sized once. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun hostRows(
        cfg: Config,
        roots: JunkRoots,
        opts: JunkOptions,
        limit: Duration,
        cx: JunkCtx,
    ): Pair<List<AgentJunk>, MutableMap<String, Long>> {
        val rows = ArrayList<AgentJunk>()
        val sized = HashMap<String, rtok.sys.DiskUsage>()
        val owners = HashMap<String, MutableList<String>>()
        val keys = ArrayList<List<String>>()
        val junk = cfg.agents.junk
        val listed =
            HOSTS
                .mapNotNull { id -> Hosts.host(id)?.let { h -> Triple(id, h, h.variants.any { Locate.present(cfg, id, it) }) } }
                .filter { it.third || opts.all }
        for ((id, h, installed) in listed) {
            val deadline = deadline(limit)
            val folders = ArrayList<Folder>()
            val agentKeys = ArrayList<String>()
            val owned = ArrayList<OwnedCache>()
            val temp = ArrayList<String>()
            val logs = ArrayList<String>()
            for (f in hostFolders(id, cfg, roots)) {
                val target = symlinkTarget(f.path)
                val key = target ?: f.path
                if (f.perCommit) {
                    owned += staleCodeCaches(f.path)
                } else if (f.role == JunkRole.Cache && f.documented) {
                    owned += OwnedCache(f.path, JunkCache.SECTION_22, idleRule = false)
                }
                if (f.role == JunkRole.Temp && f.documented) temp += f.path
                if (f.role == JunkRole.Logs && f.documented) logs += f.path
                val usage = sized.getOrPut(key) { diskUsageUntil(key, deadline) }
                val notes = ArrayList<String>()
                if (target != null) notes += "symlink to $target"
                if (usage.denied) notes += "permission denied: size is a lower bound"
                if (usage.timedOut) notes += "scan stopped after ${limit.inWholeSeconds}s: size is a lower bound"
                owners.getOrPut(key) { ArrayList() } += id
                agentKeys += key
                folders += Folder(f.path, f.role.word, usage.bytes, f.documented, note = notes.takeIf { it.isNotEmpty() }?.joinToString("; "))
            }
            val (extraOwned, extra) = extraItems(junk, id, cfg.home, roots.home, cx, limit)
            owned += extraOwned
            val tagRoots = Junk.outermost(agentKeys)
            val items = ArrayList(cacheItems(owned, tagRoots, cx, limit))
            items += extra
            items += tempItems(temp, junkHours(junk.tempMinAgeHours), cx, limit)
            items += logItems(logs, junk, cx, limit)
            items += foundItems(tagRoots, limit)
            items += backupItems(markerDirs(id, cfg, roots), cfg.setup.backupFiles, limit)
            if (id == "gemini") items += snapshotItems(roots, limit)
            items += diagnosticReports(processNames(h), roots, limit)
            val notes = ArrayList<String>()
            if (id == "claude") {
                val old = sessionItems(roots, junk.staleSessionDays.toInt(), cx, limit)
                if (old.isNotEmpty()) notes += sessionNote(junk.staleSessionDays)
                items += old
            }
            if (folders.isNotEmpty()) retention(roots, id)?.let { notes += it }
            items += buildItems(opts.worktrees.filter { it.first == id }.map { it.second }, cx, limit)
            items += staleFor(opts.staleWorktrees, id)
            exclude(items, junk.exclude, cfg.home, roots.home, limit)
            val kept = dropNested(items)
            val kinds = Junk.kindRows(kept)
            keys += agentKeys
            rows += AgentJunk(id, true, installed, folders, 0, kinds, kept, notes, Junk.freed(kinds, false), Junk.freed(kinds, true))
        }
        val done =
            rows.mapIndexed { i, row ->
                val agentKeys = keys[i]
                val folders = row.folders.mapIndexed { j, f -> f.copy(sharedWith = owners.getValue(agentKeys[j]).filter { it != row.name }) }
                row.copy(folders = folders, totalBytes = Junk.topLevelBytes(folders.mapIndexed { j, f -> agentKeys[j] to f.sizeBytes }))
            }
        val byKey = sized.mapValuesTo(HashMap()) { it.value.bytes }
        return done to byKey
    }
}

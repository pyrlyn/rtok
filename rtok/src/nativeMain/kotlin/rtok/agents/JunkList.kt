// Port of src/agents/junk.rs `to_list` and `folder_link`: the text of `agents junk list`.
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

object JunkList {
    private const val FIRST_PRINTABLE = 0x21
    private const val DEL = 0x7f
    private const val BYTE = 0xff
    private const val HEX = 16

    /** `url::Url::from_file_path`'s path escaping: controls, space, `"#%<>?`{}` and non-ASCII bytes. */
    private fun fileUrl(path: String): String =
        "file://" +
            path.encodeToByteArray().joinToString("") { b ->
                val c = b.toInt() and BYTE
                if (c < FIRST_PRINTABLE || c >= DEL || c.toChar() in "\"#%<>?`{}") "%" + c.toString(HEX).uppercase().padStart(2, '0') else c.toChar().toString()
            }

    /** [text] as an OSC 8 hyperlink to the folder; a path that is not absolute stays plain. */
    internal fun folderLink(
        path: String,
        text: String,
    ): String = if (path.startsWith('/')) "\u001b]8;;${fileUrl(path)}\u001b\\$text\u001b]8;;\u001b\\" else text

    /** Text of `agents junk list` and of the Hosts page's junk section. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun toList(
        report: JunkReport,
        view: JunkView,
    ): String {
        val size = view::size
        val groups = JunkItems.groups(JunkItems.rows(report), view)
        val out = StringBuilder()
        for (a in report.agents) {
            out.append(a.name)
            if (a.host && !a.installed) out.append(" (not installed)")
            out.append('\n')
            for (f in a.folders) {
                val path = if (view.links) folderLink(f.path, f.path) else f.path
                out.append("  $path  ${f.role}  ${size(f.sizeBytes)}")
                if (!f.documented) out.append("  not documented: not cleared")
                if (f.sharedWith.isNotEmpty()) out.append("  shared with ${f.sharedWith.joinToString(", ")}")
                f.note?.let { out.append("  ($it)") }
                out.append('\n')
            }
            if (a.host && a.folders.isEmpty()) out.append("  no folders found\n")
            for (k in a.kinds) {
                out.append("    ${k.kind} (${k.cls}): ${k.items} items, ${size(k.sizeBytes)}\n")
                groups.firstOrNull { it.agent == a.name && it.kind == k.kind }?.let { out.append(it.lines(view, "      ")) }
            }
            for (note in a.notes) out.append("  $note\n")
            // A find nothing documents is one line per kind: a worktree can hold hundreds.
            val (listed, kept) = a.items.filter { !it.counted }.partition { it.kept == JunkKinds.NOT_DOCUMENTED }
            for (kind in Junk.KINDS) {
                val ofKind = listed.filter { it.kind == kind }
                if (ofKind.isNotEmpty()) {
                    out.append("    $kind: ${ofKind.size} listed, ${size(ofKind.sumOf { it.bytes })}  ${JunkKinds.NOT_DOCUMENTED}\n")
                }
            }
            // One line per reason when several items share it.
            val byWhy = LinkedHashMap<Pair<String, String>, MutableList<JunkItem>>()
            kept.forEach { byWhy.getOrPut(it.kind to it.kept.orEmpty()) { ArrayList() } += it }
            for ((key, items) in byWhy) {
                val bytes = size(items.sumOf { it.bytes })
                if (items.size == 1) {
                    out.append("    kept ${items[0].path}  $bytes  (${key.second})\n")
                } else {
                    out.append("    kept ${key.first}: ${items.size} items, $bytes  (${key.second})\n")
                }
            }
            if (!a.host || a.kinds.isNotEmpty()) {
                out.append("  Freed by `clear`: ${size(a.freedDefaultBytes)}\n  Freed with `--include review`: ${size(a.freedReviewBytes)}\n")
            }
        }
        out.append(
            "total\n  Folders: ${size(report.totalBytes)}\n  Freed by `clear`: ${size(report.freedDefaultBytes)}\n" +
                "  Freed with `--include review`: ${size(report.freedReviewBytes)}\n",
        )
        return out.toString()
    }
}

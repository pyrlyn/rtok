// Port of src/plugins/graph/resolve.rs (T368): import evidence, then directory. Above ~5% of files, or a
// same-class tie, stays unresolved: one name has one IDF.
package rtok.plugins.graph

import kotlin.math.ln
import rtok.plugin.sdk.Ctx

/** [files] null keeps every reference: unambiguous, too common, or nothing resolved. */
data class ResolveHit(val files: Set<String>?, val others: Int, val defPath: String?) {
    fun allows(path: String) = files == null || path in files
    companion object { val Unresolved = ResolveHit(null, 0, null) }
}

object Resolve {
    /** More than ~5% of indexed files reference the name. */
    internal fun tooCommon(df: Long, nFiles: Long) = nFiles > 0 && df * 20 > nFiles

    internal fun idf(df: Long, nFiles: Long): Double = if (df <= 0 || nFiles <= 0) 0.0 else ln(nFiles.toDouble() / df)

    private fun parentDir(path: String) = path.substringBeforeLast('/', "")

    private class Cand(val path: String, var cls: Int, val idf: Double, val files: MutableSet<String> = HashSet())

    private fun beats(a: Cand, b: Cand) = a.cls < b.cls || (a.cls == b.cls && a.idf > b.idf)

    fun rankName(cx: Ctx, root: String, name: String): ResolveHit {
        val defs = cx.symbolDefs(root, name)
        if (defs.size < 2) return ResolveHit.Unresolved
        val (df, nFiles) = cx.symbolNameFreq(root, name)
        if (tooCommon(df, nFiles)) return ResolveHit.Unresolved
        val nameIdf = idf(df, nFiles)
        val imported = HashMap<String, MutableSet<String>>()
        for ((refFile, defFile) in cx.symbolImportedDefs(root, name)) imported.getOrPut(refFile) { HashSet() } += defFile
        val by = LinkedHashMap<String, Cand>()
        for (group in cx.symbolRefGroups(root, name)) {
            val path = group.path
            val best = ArrayList<String>(); var cls = 3
            for (def in defs) {
                val c = when { imported[path]?.contains(def.a) == true -> 0; parentDir(path) == parentDir(def.a) -> 1; else -> 2 }
                if (c < cls) { cls = c; best.clear(); best += def.a } else if (c == cls) best += def.a
            }
            // Class 2 is "no evidence"; a tie at the best class is not a resolution.
            if (cls >= 2 || best.size != 1) continue
            val cand = by.getOrPut(best[0]) { Cand(best[0], 3, nameIdf) }
            if (cls < cand.cls) cand.cls = cls
            cand.files += path
        }
        var top: Cand? = null; var tied = false
        for (cand in by.values) {
            val t = top
            when {
                t == null -> top = cand
                beats(cand, t) -> { top = cand; tied = false }
                !beats(t, cand) -> tied = true
            }
        }
        val winner = top?.takeIf { !tied } ?: return ResolveHit.Unresolved
        return ResolveHit(winner.files.toSet(), (defs.size - 1).coerceAtLeast(0), winner.path)
    }
}

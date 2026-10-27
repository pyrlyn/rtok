// Port of src/doctor/dupes.rs. Duplicate hooks (T331.3): one agent loads the same hook twice, so it runs twice.
// Entries of the same host, event, matcher and normalized command are copies; one copy is recommended to keep
// (plugin-owned, then a project's shared file, then the user's, then a `.local` file, first in load order among
// equals). A copy in a file `--fix` may edit is fixable (T331.6): removable when it is not the kept copy, which the
// checklist may change (T331.7).
package rtok.doctor

internal object Dupes {
    /** `Bash|Edit`, ` Edit | Bash ` and the same list reordered match the same tools; none, empty and `*` match all. */
    fun matcherKey(m: String?): String {
        val t = m?.trim().orEmpty()
        if (t == "*") return ""
        return t
            .split('|')
            .map { it.trim() }
            .sorted()
            .joinToString("|")
    }

    private fun why(rank: Int) =
        when (rank) {
            Hooks.RANK_PLUGIN -> "owned by a plugin"
            1 -> "shared project file"
            2 -> "user file"
            else -> "first in load order"
        }

    /** Every group of two or more copies, as one finding per copy, groups in load order. */
    fun find(seen: List<Seen>): List<Problem> {
        val groups = ArrayList<MutableList<Int>>()
        val index = HashMap<List<String>, Int>()
        seen.forEachIndexed { i, s ->
            val key = listOf(s.agent, s.event, matcherKey(s.matcher), s.normal)
            val g = index.getOrPut(key) { groups.add(ArrayList()).let { groups.size - 1 } }
            groups[g].add(i)
        }
        val out = ArrayList<Problem>()
        groups.filter { it.size > 1 }.forEachIndexed { n, copies ->
            // `minBy` takes the first of equals, which is the first in load order.
            val keep = copies.minBy { seen[it].rank }
            for (i in copies) {
                val s = seen[i]
                val detail =
                    if (i == keep) {
                        "runs ${copies.size} times; keep this copy (${why(s.rank)})"
                    } else {
                        "runs ${copies.size} times; an extra copy"
                    }
                out +=
                    Problem(
                        kind = "duplicate-hook",
                        agent = s.agent,
                        source = s.source,
                        path = s.path,
                        event = s.event,
                        matcher = s.matcher,
                        command = s.command,
                        detail = detail,
                        fixable = s.editable,
                        group = n,
                        keep = i == keep,
                    )
            }
        }
        return out
    }

    /** The `duplicate hooks` lines of the doctor text: each group once, its copies below it. */
    fun render(problems: List<Problem>) = renderKinds(problems, listOf("duplicate-hook"), "duplicate hooks")

    /** The same for MCP servers (T331.4); a version conflict and rtok's own entry (T331.10) are listed with them. */
    fun renderMcp(problems: List<Problem>) = renderKinds(problems, listOf("duplicate-mcp", "conflicting-mcp", "own-mcp"), "duplicate mcp servers")

    private fun renderKinds(
        problems: List<Problem>,
        kinds: List<String>,
        title: String,
    ): String {
        val dupes = problems.filter { it.kind in kinds }
        if (dupes.isEmpty()) return "$title none found\n"
        val out = StringBuilder("$title\n")
        var last: Pair<String, Int?>? = null
        for (p in dupes) {
            if (last != (p.kind to p.group)) {
                last = p.kind to p.group
                val matcher = p.matcher?.let { "[$it]" }.orEmpty()
                val event = if (p.event.isEmpty()) "" else "${p.event} "
                out.append("  ${p.agent} $event$matcher`${p.command}`: ${p.detail.substringBefore(';')}\n")
            }
            val role =
                when {
                    p.kind == "conflicting-mcp" -> "other"
                    p.kind == "own-mcp" && !p.keep -> "info "
                    p.keep -> "keep "
                    else -> "extra"
                }
            out.append("    $role ${p.source} ${p.path}\n")
        }
        return out.toString()
    }
}

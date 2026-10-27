@file:Suppress("MagicNumber")

// Port of the skills audit of src/doctor.rs (T61.3, T392, T393): what the hosts list and what it costs the system
// prompt. Advice only: nothing here edits a file. `SKILL_ROOTS`, `root_hosts`, `audit_from`, `skill_duplicates`,
// `skill_row`, `frontmatter_desc`, `frontmatter_body`, `repeat_rows`, `plugin_skill_dirs`, `skills_audit`.

package rtok.doctor

import rtok.agents.Skills
import rtok.codePointCountCompat
import rtok.json.StrictJson
import rtok.sys.canon
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.listDirOrEmpty
import rtok.sys.readToString
import rtok.sys.samePath

data class SkillsAudit(
    val rows: List<SkillRow> = emptyList(),
    /** Description bytes the listing rides with every request (≈ tokens/4). */
    val descBytes: Long = 0,
    /** Names one host lists from more than one real path (T392). */
    val duplicates: List<SkillDuplicate> = emptyList(),
    /** Skills loaded again with no compaction between, from the transcripts (T392). */
    val repeats: List<SkillRepeat> = emptyList(),
) {
    /** `(skills over the description cap, tokens a request would shed if each were cut to it)`. */
    fun capSaving(): Pair<Int, Long> {
        val over = rows.filter { it.descChars > Skills.SKILL_DESC_MAX }
        return over.size to over.sumOf { (it.descChars - Skills.SKILL_DESC_MAX).toLong() } / 4
    }
}

/** One skill name listed from several places (T392). */
data class SkillDuplicate(
    val name: String,
    /** `(source, directory)` of every listing, in root order. */
    val copies: List<Pair<String, String>>,
    /** Every body is the same text. */
    val identical: Boolean,
    /** Description tokens the extra listings add to every request. */
    val extraTokens: Long,
)

/** A skill body re-sent into the same context window (T392). */
data class SkillRepeat(
    val name: String,
    val loads: Long,
    val tokens: Long,
)

data class SkillRow(
    val name: String,
    /** `user`, `project`, or `plugin:<id>@<marketplace>`. */
    val source: String,
    val descChars: Int,
    val bodyBytes: Long,
    /** Invocations in the last 30 d; null = no data. */
    val invocations: Long? = null,
    val warnDesc: Boolean = false,
    /** Body over 8 KB: almost always a `references/` candidate. */
    val warnBody: Boolean = false,
    /** Listed but never invoked in the window (only when data exists). */
    val warnNever: Boolean = false,
)

internal object SkillsAuditProbe {
    /** A skills root and the hosts that read it; [project] = also scanned under the working directory. */
    private class Root(
        val dir: String,
        val project: Boolean,
        val hosts: List<String>,
    )

    private val ROOTS =
        listOf(
            Root(".claude/skills", true, listOf("claude")),
            // The shared root: Codex, Cursor, Gemini and Copilot read it, Claude does not.
            Root(".agents/skills", true, listOf("codex", "cursor", "gemini", "copilot")),
            Root(".codex/skills", false, listOf("codex")),
            Root(".cursor/skills", false, listOf("cursor")),
            Root(".gemini/skills", false, listOf("gemini")),
            Root(".copilot/skills", false, listOf("copilot")),
        )

    /** Plugin skill dirs come from Claude's `installed_plugins.json`, so only Claude lists them. */
    private val PLUGIN_HOSTS = listOf("claude")

    private const val BODY_WARN_BYTES = 8192L
    private const val FENCE = "\n---\n"

    private fun rootHosts(
        dir: String,
        plugin: Boolean,
    ): List<String> {
        if (plugin) return PLUGIN_HOSTS
        val d = dir.replace('\\', '/')
        return ROOTS.firstOrNull { d.endsWith(it.dir) }?.hosts.orEmpty()
    }

    /** The probe: every host's documented roots under [home] and [cwd], and the enabled plugin skill dirs. */
    fun audit(
        home: String,
        cwd: String,
        invocations: Map<String, Long>?,
        repeats: Map<String, Pair<Long, Long>>?,
    ): SkillsAudit {
        val roots =
            listOf(
                "user" to ROOTS.map { "$home/${it.dir}" },
                "project" to ROOTS.filter { it.project }.map { "$cwd/${it.dir}" },
                "plugin" to pluginSkillDirs(home),
            )
        val subdirs = { d: String -> listDirOrEmpty(d).map { "$d/$it" }.filter(::isDir).sorted() }
        val read = { p: String -> runCatching { readToString(p) }.getOrNull() }
        return auditFrom(roots, read, subdirs, invocations).copy(repeats = repeats?.let(::repeatRows).orEmpty())
    }

    /** Biggest waste first; the name keeps the order byte-stable. */
    fun repeatRows(bySkill: Map<String, Pair<Long, Long>>): List<SkillRepeat> =
        bySkill
            .map { (name, v) -> SkillRepeat(name, v.first, v.second) }
            .sortedWith(compareByDescending<SkillRepeat> { it.tokens }.thenBy { it.name })

    /** `<installPath>/skills [<id>]` of every enabled plugin entry. */
    @Suppress("ReturnCount")
    private fun pluginSkillDirs(home: String): List<String> {
        val v =
            runCatching { StrictJson.parseOrNull(readToString("$home/.claude/plugins/installed_plugins.json")) }.getOrNull()
                ?: return emptyList()
        val plugins = Advice.pointer(v, "plugins") as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        return plugins.keys.flatMap { id -> Advice.pluginInstallPaths(v, id).map { "$it/skills [$id]" } }
    }

    /** One `SKILL.md` found under a root, before the listing is deduplicated. */
    private class Listing(
        val row: SkillRow,
        val dir: String,
        val hosts: List<String>,
        val md: String,
    )

    /**
     * Walk [roots] (`source label` to dirs whose one-level subdirs may hold a `SKILL.md`) through [read] and
     * [subdirs], so tests drive an in-memory tree. Sorted by body bytes desc, then name.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    fun auditFrom(
        roots: List<Pair<String, List<String>>>,
        read: (String) -> String?,
        subdirs: (String) -> List<String>,
        invocations: Map<String, Long>?,
    ): SkillsAudit {
        val rows = mutableListOf<SkillRow>()
        val listings = mutableListOf<Listing>()
        for ((label, dirs) in roots) {
            for (raw in dirs) {
                // A `[id]` suffix carries the plugin id into the source label.
                val cut = raw.indexOf(" [")
                val dir = if (cut >= 0) raw.substring(0, cut) else raw
                val plugin = if (cut >= 0) "plugin:${raw.substring(cut + 2).trimEnd(']')}" else null
                val hosts = rootHosts(dir, plugin != null)
                val source = plugin ?: label
                for (sub in subdirs(dir)) {
                    val name = fileName(sub)?.takeIf { it.isNotEmpty() } ?: continue
                    val md = read("$sub/SKILL.md") ?: continue
                    val row = skillRow(name, source, md, invocations)
                    listings += Listing(row, sub, hosts, md)
                    rows += row
                }
            }
        }
        val duplicates = skillDuplicates(listings)
        val seen = HashSet<Pair<String, String>>()
        val sorted =
            rows
                .sortedWith(compareByDescending<SkillRow> { it.bodyBytes }.thenBy { it.name })
                // The same skill reachable from two roots under one source is one listing.
                .filter { seen.add(it.source to it.name) }
        return SkillsAudit(sorted, sorted.sumOf { it.descChars.toLong() }, duplicates)
    }

    /** Names ONE host lists from two distinct real paths (T392); a symlinked mirror is still one copy. */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun skillDuplicates(listings: List<Listing>): List<SkillDuplicate> {
        val out = mutableListOf<SkillDuplicate>()
        for (host in listings.flatMap { it.hosts }.distinct().sorted()) {
            val byName = LinkedHashMap<String, MutableList<Listing>>()
            for (l in listings.filter { host in it.hosts }) {
                val copies = byName.getOrPut(l.row.name) { mutableListOf() }
                val real = canon(l.dir)
                if (copies.none { samePath(canon(it.dir), real) }) copies += l
            }
            for ((name, copies) in byName.sortedByKey()) {
                if (copies.size < 2) continue
                val copiesOf = copies.map { it.row.source to it.dir }
                // Codex, Gemini and Copilot read the same pair: one finding, not three.
                if (out.any { it.name == name && it.copies == copiesOf }) continue
                out +=
                    SkillDuplicate(
                        name = name,
                        copies = copiesOf,
                        identical = copies.all { body(it.md) == body(copies[0].md) },
                        // The first listing is the one worth keeping; the rest are what a request pays extra.
                        extraTokens = copies.drop(1).sumOf { it.row.descChars.toLong() } / 4,
                    )
            }
        }
        return out.sortedBy { it.name }
    }

    private fun skillRow(
        name: String,
        source: String,
        md: String,
        invocations: Map<String, Long>?,
    ): SkillRow {
        val descChars = frontmatterDesc(md)
        val bodyBytes = body(md).encodeToByteArray().size.toLong()
        val calls = invocations?.get(name)
        return SkillRow(
            name = name,
            source = source,
            descChars = descChars,
            bodyBytes = bodyBytes,
            invocations = calls,
            warnDesc = descChars > Skills.SKILL_DESC_MAX,
            warnBody = bodyBytes > BODY_WARN_BYTES,
            warnNever = invocations != null && calls == null,
        )
    }

    /** The `description:` value's char count from the frontmatter block, if any. */
    @Suppress("ReturnCount")
    fun frontmatterDesc(md: String): Int {
        val lines = md.lines()
        if (lines.firstOrNull()?.trim() != "---") return 0
        for (l in lines.drop(1).map { it.trim() }) {
            if (l == "---") break
            if (l.startsWith("description:")) {
                return l
                    .removePrefix("description:")
                    .trim()
                    .trim('"')
                    .codePointCountCompat()
            }
        }
        return 0
    }

    /** The text after the closing `---` fence; a missing or unclosed fence is the whole file (fail open). */
    fun body(md: String): String {
        if (!md.startsWith("---\n")) return md
        val rest = md.substring(4)
        val i = rest.indexOf(FENCE)
        return if (i >= 0) rest.substring(i + FENCE.length) else md
    }
}

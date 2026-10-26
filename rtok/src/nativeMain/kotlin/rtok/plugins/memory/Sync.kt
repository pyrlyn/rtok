// Port of src/plugins/memory/sync.rs — `rtok memory sync`: a managed block in CLAUDE.md / AGENTS.md
// (T69.6). Not a hook: writing a host file on SessionStart would break fail-open.
package rtok.plugins.memory

import rtok.agent.sdk.Apply
import rtok.agent.sdk.backup
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.projectName
import rtok.store.*
import rtok.sys.*
import rtok.estimate as estimateTokens

object Sync {
    const val START = "<!-- rtok:memory -->"
    const val END = "<!-- /rtok:memory -->"

    /** T59.7 overlap line: the same titles in the synced block and hook recall. */
    const val OVERLAP_LINE = "duplicate: a memory sync block and hook recall both carry titles — rtok side: [plugins.memory] enabled = false"

    fun hasBlock(text: String) = runCatching { blockRange(text) }.getOrNull() != null

    /** Per destination file, parent resolved even when the file does not exist yet (T563). */
    internal fun kvKey(path: String): String {
        val dir = parent(path)
        val name = components(path).lastOrNull()
        val identity = if (dir != null && name != null) join(canon(dir), name) else canon(path)
        return "memory.sync.sha:$identity"
    }

    /** Pre-T563 key by basename: read-only fallback. */
    internal fun legacyKvKey(path: String) = "memory.sync.sha:${components(path).lastOrNull() ?: ""}"

    /** A title as one line that cannot open or close the block. */
    private fun singleLine(title: String) = title.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("").replace("<!--", "&lt;!--")

    /** Pinned-first, then live notes by id desc, trimmed from the end to [budget] tokens. */
    fun render(
        notes: List<Pair<Int, String>>,
        budget: UInt,
        estimate: (String) -> UInt,
    ): String {
        var entries = notes
        while (true) {
            val text = (listOf(START) + entries.map { (id, t) -> "$id ${singleLine(t)}" } + END).joinToString("\n")
            if (estimate(text) <= budget || entries.isEmpty()) return text
            entries = entries.dropLast(1)
        }
    }

    internal fun blockRange(text: String): IntRange? {
        val start = text.indexOf(START)
        if (start < 0) return null
        val restAt = start + START.length
        if (text.indexOf(START, restAt) >= 0) error("multiple rtok memory blocks")
        val rel = text.indexOf(END, restAt)
        if (rel < 0) error("unclosed rtok memory block")
        return start until rel + END.length
    }

    /** The generated block must be exactly one START..END pair. */
    internal fun checkBlock(block: String) {
        val r = blockRange(block)
        if (r == null || r.first != 0 || r.last + 1 != block.length) error("generated memory block is malformed")
    }

    /** Replace or remove the managed block; bytes outside the markers are unchanged. */
    fun splice(
        file: String,
        block: String?,
    ): String {
        val r = blockRange(file)
        return when {
            r == null && block == null -> {
                file
            }

            r == null -> {
                buildString {
                    append(file)
                    if (isNotEmpty() && !endsWith('\n')) append('\n')
                    append(block)
                    if (!endsWith('\n')) append('\n')
                }
            }

            else -> {
                file.substring(0, r.first) + (block ?: "") + file.substring(r.last + 1)
            }
        }
    }

    fun guardedSplice(
        file: String,
        block: String?,
        storedSha: String?,
        force: Boolean,
    ): String {
        blockRange(file)?.let { r ->
            val sha = hexSha256(file.substring(r).encodeToByteArray())
            if (!force && storedSha != sha) error("memory block was hand-edited; pass --force to overwrite")
        }
        return splice(file, block)
    }

    /** fs-kit `read::text_or_empty`: only an absent file is empty; unreadable or non-UTF-8 is an error naming it. */
    private fun textOrEmpty(path: String): String {
        if (!exists(path) && canonicalize(path) == null) return ""
        val bytes =
            try {
                readBytes(path)
            } catch (e: IoException) {
                throw IoException(e.kind, "$path: ${e.message}")
            }
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw IoException(IoException.Kind.Other, "$path: stream did not contain valid UTF-8")
        }
    }

    fun run(
        cfg: Config,
        file: String,
        budget: UInt?,
        dryRun: Boolean,
        remove: Boolean,
        force: Boolean,
        out: (String) -> Unit,
    ) {
        val name = components(file).lastOrNull() ?: ""
        if (name != "CLAUDE.md" && name != "AGENTS.md") error("--file must be CLAUDE.md or AGENTS.md")
        val path = if (file.startsWith('/')) file else join(cwd(), file)
        // Only an absent file starts empty: reading a damaged one as empty would overwrite it.
        val before = textOrEmpty(path)
        Runtime.open(cfg, "memory").use { cx ->
            val key = kvKey(path)
            val stored = cx.store.kvGet(key) ?: cx.store.kvGet(legacyKvKey(path))
            val newBlock =
                if (remove) {
                    null
                } else {
                    val notes = cx.store.listNoteTitles(projectName(cwd()), UInt.MAX_VALUE)
                    render(notes, budget ?: cfg.plugins.memory.syncTokens) { estimateTokens(it, rtok.plugin.sdk.Class.Prose, cfg.estimator).toUInt() }.also(::checkBlock)
                }
            val after = guardedSplice(before, newBlock, stored, force)
            if (dryRun) {
                out(rtok.StatPreview.showFile(path, ChangePreview.unifiedDiff(path, before, after)))
            } else {
                if (after != before) {
                    backup(path, cfg.setup.backupFiles.toInt())
                    rtok.agent.sdk.write(Apply(), path, after, "memory sync")
                }
                if (newBlock != null) cx.store.kvSet(key, hexSha256(newBlock.encodeToByteArray())) else cx.store.kvDelete(key)
            }
        }
        if (!remove && cfg.pluginEnabled("memory", true) && cfg.plugins.memory.recallTokens > 0u) out("$OVERLAP_LINE\n")
    }
}

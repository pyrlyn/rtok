// Port of src/agents/post_create.rs (T289.3, D34): `rtok agents install|uninstall <host> --project`. Cursor, Kilo and
// Devin / Windsurf only run a script after they create a worktree in their own pool, so rtok adds one entry to the
// project's file for it that runs `rtok worktree adopt`. Only our entry is written or removed; every other byte of
// the file stays, and a file we leave empty goes with it. Unix only: the command is always the POSIX resolver.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.write
import rtok.config.Config
import rtok.mcp.config.Jsonc
import rtok.mcp.config.JsoncItems
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeFile

object PostCreate {
    /** The hosts that have a post-create script, in the order they are documented. */
    val HOSTS = listOf("cursor", "kilo", "windsurf", "devin")

    private const val CURSOR_FILE = ".cursor/worktrees.json"

    /** Unix and Windows keys take precedence over the generic one on their OS, so our command goes into each list. */
    private val CURSOR_KEYS = listOf("setup-worktree", "setup-worktree-unix", "setup-worktree-windows")
    private const val DEVIN_FILE = ".devin/hooks.json"
    private const val WINDSURF_FILE = ".windsurf/hooks.json"
    private val HOOK = listOf("hooks", "post_setup_worktree")

    /**
     * The line a host runs after it created a worktree. Fail open: the resolver `exec`s rtok, so the subshell and
     * `|| true` keep a refused `adopt` from failing the host's setup.
     */
    fun command(): String = "( ${HookLine.hookResolver("worktree adopt")} ) || true"

    /** Whether [cmd] is a command [command] wrote, under any rtok path. */
    internal fun ours(cmd: String): Boolean {
        val at = cmd.indexOf(" worktree adopt")
        if (at < 0) return false
        var head = cmd.substring(0, at)
        while (head.startsWith("( ")) head = head.removePrefix("( ")
        return head.startsWith("command -v rtok") || isRtokBin(HookLine.unquoteBin(head))
    }

    private fun isOursItem(v: JsonElement): Boolean {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ((v as? JsonObject)?.get("command") as? JsonPrimitive)?.content
        return s != null && ours(s)
    }

    /** `rtok agents install|uninstall <hosts> --project`: one report line per host. */
    fun run(
        cfg: Config,
        hosts: List<String>,
        remove: Boolean,
        root: String,
    ): String {
        hosts.firstOrNull { it !in HOSTS }?.let {
            throw AgentsFailure("$it has no post-create script to add rtok to; --project works for ${HOSTS.joinToString(", ")}")
        }
        // These files belong to the project and are normally committed, so git is the undo.
        val apply = applyOf(cfg).copy(backup = false)
        return buildString {
            for (host in hosts) {
                val (file, note) =
                    when (host) {
                        "cursor" -> cursor(apply, root, remove)
                        "kilo" -> PostCreateKilo.kilo(apply, root, remove)
                        else -> hooksJson(apply, root, remove)
                    }
                append("$host: ${file.removePrefix("$root/")}: $note\n")
            }
        }
    }

    private fun readDoc(raw: String): JsonObject = if (raw.isBlank()) JsonObject(emptyMap()) else Jsonc.parse(raw) as? JsonObject ?: JsonObject(emptyMap())

    private fun hasOurs(v: JsonElement?) = (v as? JsonArray)?.any(::isOursItem) == true

    /** `.cursor/worktrees.json`: our command in each `setup-worktree*` list; a key holding a script path gets a note. */
    private fun cursor(
        apply: Apply,
        root: String,
        remove: Boolean,
    ): Pair<String, String> {
        val path = join(root, CURSOR_FILE)
        val raw = ProjectFile.readOrEmpty(path)
        val doc = readDoc(raw)
        val present = CURSOR_KEYS.filter { it in doc }
        var text = raw
        var did = false
        val notes = mutableListOf<String>()
        if (remove) {
            for (key in present) {
                val (next, n) = JsoncItems.pullItems(text, path, listOf(key), ::isOursItem)
                text = next
                did = did || n > 0
            }
        } else {
            val lists = present.filter { doc[it] is JsonArray }
            present.filter { it !in lists }.forEach { notes += "$it runs a script; add `${command()}` to it by hand" }
            for (key in if (present.isEmpty()) listOf(CURSOR_KEYS[0]) else lists) {
                if (!hasOurs(doc[key])) {
                    text = JsoncItems.pushItem(text, path, listOf(key), JsonPrimitive(command()))
                    did = true
                }
            }
        }
        val note = ProjectFile.verb(apply, remove, did)
        ProjectFile.save(apply, path, text, did && JsoncItems.isEmptyAt(text, emptyList()), note)
        return path to notes.fold(note) { acc, n -> "$acc; $n" }
    }

    /** Whether the hooks file at [path] defines any hook, the test the host applies before it falls back to the legacy file. */
    private fun definesHooks(path: String): Boolean = runCatching { ((Jsonc.parse(ProjectFile.readOrEmpty(path)) as? JsonObject)?.get("hooks") as? JsonObject)?.isNotEmpty() == true }.getOrDefault(false)

    /**
     * Devin and Windsurf share one hook: `post_setup_worktree` in the workspace hooks file the host reads today.
     * `.devin/hooks.json` wins when it defines hooks; a legacy `.windsurf/hooks.json` is read only while it does not.
     */
    private fun hooksJson(
        apply: Apply,
        root: String,
        remove: Boolean,
    ): Pair<String, String> {
        val devin = join(root, DEVIN_FILE)
        val legacy = join(root, WINDSURF_FILE)
        if (remove) {
            var done: Pair<String, String>? = null
            for (path in listOf(devin, legacy)) {
                val raw = ProjectFile.readOrEmpty(path)
                if (raw.isBlank()) continue
                val (text, n) = JsoncItems.pullItems(raw, path, HOOK, ::isOursItem)
                val note = ProjectFile.verb(apply, true, n > 0)
                ProjectFile.save(apply, path, text, n > 0 && JsoncItems.isEmptyAt(text, emptyList()), note)
                if (n > 0) done = path to note
            }
            return done ?: (devin to NO_CHANGES)
        }
        val path = if (!definesHooks(devin) && definesHooks(legacy)) legacy else devin
        val raw = ProjectFile.readOrEmpty(path)
        val has = hasOurs((readDoc(raw)["hooks"] as? JsonObject)?.get("post_setup_worktree"))
        val text = if (has) raw else JsoncItems.pushItem(raw, path, HOOK, JsonObject(mapOf("command" to JsonPrimitive(command()))))
        val note = ProjectFile.verb(apply, false, !has)
        write(apply, path, text, note)
        return path to note
    }
}

/** The write side every post-create file shares: the report verb, save-or-remove, and an absent file read as empty. */
internal object ProjectFile {
    fun verb(
        apply: Apply,
        remove: Boolean,
        did: Boolean,
    ): String =
        when {
            !did -> NO_CHANGES
            apply.dryRun -> if (remove) "would remove" else "would add"
            else -> if (remove) "removed" else "added"
        }

    /** Write [text] over [path], or remove the file when a removal left nothing in it: it only existed for our entry. */
    fun save(
        apply: Apply,
        path: String,
        text: String,
        empty: Boolean,
        report: String,
    ) = if (empty && apply.writes(report)) removeFile(path) else write(apply, path, text, report)

    /** jsonc.rs `read_or_empty`: an absent file reads as empty. */
    fun readOrEmpty(path: String) = if (exists(path)) readToString(path) else ""
}

/** Kilo's `.kilo/setup-script`: a marked shell block, not JSON. */
internal object PostCreateKilo {
    private val KILO_FILES = listOf(".kilo/setup-script", ".kilo/setup-script.sh")
    private const val KILO_BEGIN = "# >>> rtok worktree adopt (rtok agents install kilo --project) >>>"
    private const val KILO_END = "# <<< rtok worktree adopt <<<"
    private const val SHEBANG = "#!/bin/sh\n"

    /** The block that runs `rtok worktree adopt` in a subshell: the resolver ends in `exec`. */
    private fun kiloBlock() = "$KILO_BEGIN\n${PostCreate.command()}\n$KILO_END\n"

    /** `.kilo/setup-script`: a marked block right after the shebang; `.kilo/setup-script.sh` when that is the one Kilo reads. */
    fun kilo(
        apply: Apply,
        root: String,
        remove: Boolean,
    ): Pair<String, String> {
        val plain = join(root, KILO_FILES[0])
        val path = if (!exists(plain) && exists(join(root, KILO_FILES[1]))) join(root, KILO_FILES[1]) else plain
        val raw = ProjectFile.readOrEmpty(path)
        val text = kiloText(raw, path, remove)
        val note = ProjectFile.verb(apply, remove, text != raw)
        ProjectFile.save(apply, path, text, remove && text != raw && text.trim() == SHEBANG.trim(), note)
        return path to note
    }

    private fun kiloText(
        raw: String,
        path: String,
        remove: Boolean,
    ): String {
        val b = raw.indexOf(KILO_BEGIN)
        if (b < 0) return if (remove) raw else insertKilo(raw)
        val endAt = raw.indexOf(KILO_END, b)
        if (endAt < 0) throw AgentsFailure("$path: the rtok block has no end marker `$KILO_END`; fix it by hand")
        var e = endAt + KILO_END.length
        if (raw.startsWith("\n", e)) e++
        return raw.substring(0, b) + (if (remove) "" else kiloBlock()) + raw.substring(e)
    }

    private fun insertKilo(raw: String): String {
        if (raw.isEmpty()) return SHEBANG + kiloBlock()
        val at = if (raw.startsWith("#!")) raw.indexOf('\n').let { if (it < 0) raw.length else it + 1 } else 0
        val head = raw.substring(0, at)
        val nl = if (head.isEmpty() || head.endsWith('\n')) "" else "\n"
        return head + nl + kiloBlock() + raw.substring(at)
    }
}

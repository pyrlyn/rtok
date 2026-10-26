// Port of src/plugins/guard/grep_symbol.rs (T369): answer a symbol-shaped native `Grep` from the symbol
// index. Index lookup only: no walk and no indexing (it would break the hook's 10 ms budget). Every other
// outcome (flag off, a regex, a scoped search, no or too many definitions, a stale index) returns null and
// the call proceeds as before.
package rtok.plugins.guard

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Measurement
import rtok.plugin.sdk.PreToolDecision
import rtok.plugin.sdk.PreToolUse
import rtok.plugin.sdk.SymDef
import rtok.plugins.graph.Index
import rtok.plugins.graph.Tools
import rtok.plugins.graph.graphCfg

object GrepSymbol {
    /** Above this many definitions the name is a common word, not a symbol worth answering. */
    private const val MAX_DEFS = 5
    private const val MAX_IDENT = 64

    /** Words that may precede the identifier in a definition-shaped pattern. */
    private val DEF_WORDS = setOf("fn", "def", "class", "struct", "type", "func", "interface")

    /** In every answer's head line; `rtok stats` finds the answers in transcripts by it (T369.1). */
    const val ANSWERED = "answered from the rtok index, Grep not run"

    fun answer(
        ev: PreToolUse,
        cx: Ctx,
    ): PreToolDecision? {
        if (!guardCfg(cx).grepSymbol || !graphCfg(cx).enabled) return null
        val name = symbolName(ev.toolInput) ?: return null
        val cwd = cx.cwd() ?: return null
        val scope = ((ev.toolInput as? JsonObject)?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val reason = reason(cx, cwd, scope, name) ?: return null
        // Countable but claims no saving: the Grep output it replaces was never produced (D3).
        runCatching { cx.record(Measurement("guard", "grep_symbol", 0uL, 0uL, 0u, 0u, null)) }
        return PreToolDecision.Deny(reason)
    }

    /** The identifier a `Grep` input looks for, when that is all it asks for. */
    fun symbolName(input: JsonElement): String? {
        val o = input as? JsonObject ?: return null

        fun set(k: String) =
            when (val v = o[k]) {
                is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else v.booleanOrNull == true
                else -> false
            }
        if (listOf("glob", "type", "-i", "multiline").any(::set)) return null
        val pattern = (o["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        val ws = pattern.indexOfFirst { it.isWhitespace() }
        var rest = if (ws > 0 && pattern.substring(0, ws) in DEF_WORDS) pattern.substring(ws + 1).trimStart() else pattern
        // `\bfoo\b`, `\bfoo\(`, `foo\(` and `\bfoo` match the same word a bare `foo` does.
        rest = rest.removePrefix("\\b")
        val name = listOf("\\b", "\\(").firstOrNull { rest.endsWith(it) }?.let { rest.removeSuffix(it) } ?: rest
        return name.takeIf(::isIdentifier)
    }

    private fun isIdentifier(s: String) = s.length in 2..MAX_IDENT && (s[0].isAsciiLetter() || s[0] == '_') && s.all { it.isAsciiLetterOrDigit() || it == '_' }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || this in '0'..'9'

    /** `under` for a `path` scope: "" for none or the root, `rel/` for a directory inside it, null otherwise. */
    private fun under(
        cwd: String,
        root: String,
        scope: String?,
    ): String? {
        val p = scope?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        val abs = rtok.sys.canonicalize(if (p.startsWith("/")) p else "$cwd/$p") ?: return null
        val rel =
            when {
                abs == root -> ""
                abs.startsWith("$root/") -> abs.substring(root.length + 1)
                else -> return null
            }
        if (!rtok.sys.isDir(abs)) return null
        return if (rel.isEmpty()) "" else "$rel/"
    }

    /** The text the deny carries, or null to let the Grep run. */
    private fun reason(
        cx: Ctx,
        cwd: String,
        scope: String?,
        name: String,
    ): String? {
        val (key, all) = definitions(cx, cwd, name) ?: return null
        val under = under(cwd, key, scope) ?: return null
        val rows = all.filter { it.a.startsWith(under) }
        if (rows.isEmpty() || rows.size > MAX_DEFS) return null
        val budget = graphCfg(cx).bodyLines.toInt()
        val defs = StringBuilder()
        var src: Pair<String, String>? = null
        for (d in rows) {
            if (src?.first != d.a) src = d.a to (runCatching { rtok.sys.readToString("$key/${d.a}") }.getOrNull() ?: return null)
            val text = src.second
            // The index lags the files between watcher runs; a row that no longer names the symbol must not answer.
            val at = d.line - 1
            if (at < 0 || text.lines().getOrNull(at)?.contains(name) != true) return null
            defs.append(Tools.defText(text, name, d.a, d.kind, d.line, d.endLine, budget) { runCatching { cx.putArchive(it) }.getOrNull() })
        }
        val refs = runCatching { cx.symbolRefGroups(key, name) }.getOrNull()?.filter { it.path.startsWith(under) }?.sumOf { it.count } ?: return null
        return fit(cx, "Grep `$name` $ANSWERED: ${rows.size} def, $refs refs (`callers`)\n$defs")
    }

    /** The definitions of [name] under the cwd's index key, else its checkout root's; with that key. */
    private fun definitions(
        cx: Ctx,
        cwd: String,
        name: String,
    ): Pair<String, List<SymDef>>? {
        // `checkout`, not `main`: the index is built per checkout, and a worktree's files differ.
        val roots =
            listOfNotNull(
                cwd,
                rtok.config.ProjectRoot
                    .find(cwd)
                    ?.checkout,
            )
        for (root in roots) {
            val key = Index.canon(root)
            val rows = runCatching { cx.symbolDefs(key, name) }.getOrNull() ?: return null
            if (rows.isNotEmpty()) return key to rows
        }
        return null
    }

    /** [text] within `plugins.inject.budget_tokens`: whole lines dropped from the end, never the first two. */
    private fun fit(
        cx: Ctx,
        text: String,
    ): String {
        val budget = cx.pluginConfig("inject", rtok.config.Inject.serializer(), rtok.config.Inject()).budgetTokens
        val lines = text.lines().let { if (text.endsWith("\n")) it.dropLast(1) else it }.toMutableList()
        var out = text
        while (lines.size > 2 && cx.estimate(out, Class.Code) > budget) {
            lines.removeAt(lines.size - 1)
            out = lines.joinToString("\n")
        }
        return out
    }
}

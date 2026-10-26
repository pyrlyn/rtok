// Port of src/plugins/inject/mod.rs — budgeted SessionStart / UserPromptSubmit injection (plan T2.4, D5).
package rtok.plugins.inject

import rtok.config.Estimator
import rtok.config.Inject as InjectCfg
import rtok.config.Memory
import rtok.config.Setup
import rtok.plugin.fitBudget
import rtok.plugin.sdk.*
import rtok.plugins.checkpoint.offer
import rtok.plugins.checkpoint.offerSession
import rtok.plugins.checkpoint.save
import rtok.sys.readPrefix
import rtok.sys.takeScalars
import kotlin.math.ceil

private const val ID = "inject"

object InjectPlugin : Plugin {
    override fun manifest() = Manifest(ID, listOf(Surface.Hook), true)
    override fun dashboardPage() = DashboardPage("Inject", "Budgeted SessionStart / prompt context that stays byte-stable.", true)

    override fun sessionStart(ev: SessionStart, cx: Ctx): Injection? {
        val text = StringBuilder(); var priority: UByte = 5u
        val restored = when (ev.source) {
            "compact" -> offer(cx)
            "startup" -> cx.pluginConfig("memory", Memory.serializer(), Memory()).takeIf { it.enabled && it.startupRecall }?.let { offerSession(cx) }
            else -> null
        }
        restored?.let { text.append(it.text); priority = 9u }
        modesText(cx)?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
        return if (text.isEmpty()) null else Injection(ID, text.toString(), priority)
    }

    override fun preCompact(ev: PreCompact, cx: Ctx) { runCatching { save(ev.transcriptPath, cx) } }
}

/** Canonical mode names and their compatibility aliases, embedded. */
private fun builtin(name: String): String? = when (name) {
    "terse", "cave" -> TERSE_MD; "yagni", "pony" -> YAGNI_MD; "nudges" -> NUDGES_MD; else -> null
}

private fun cfg(cx: Ctx) = cx.pluginConfig(ID, InjectCfg.serializer(), InjectCfg())

private fun modesText(cx: Ctx): String? {
    val cfg = cfg(cx)
    val names = cfg.modes.ifEmpty { cx.config("setup", Setup.serializer(), Setup()).modes }
    if (names.isEmpty()) return null
    val rate = cx.config("estimator", Estimator.serializer(), Estimator()).prose.coerceAtLeast(0.1f)
    return buildString {
        for (name in names) {
            val body = readMode("${cfg.modesDir.trimEnd('/')}/$name.md", builtin(name), cfg.budgetTokens, rate) ?: continue
            val fit = fitBudget(cx, body, Class.Prose, cfg.budgetTokens)
            append(fit); if (!fit.endsWith('\n')) append('\n')
        }
    }
}

/** A mode file read only as far as the budget can use: one character past what fits (so the fitter still
 *  sees an overshoot), four bytes per character, a partial trailing scalar dropped; the builtin when unreadable. */
internal fun readMode(path: String, builtin: String?, budget: UInt, rate: Float): String? {
    val chars = ceil(budget.toDouble() * rate.toDouble()).toLong() + 1
    val bytes = readPrefix(path, chars * 4) ?: return builtin
    return decodeLossyTrimmed(bytes).takeScalars(chars.toInt())
}

/** UTF-8 decode where an incomplete scalar at the very end is dropped, other invalid bytes become U+FFFD. */
internal fun decodeLossyTrimmed(bytes: ByteArray): String {
    var end = bytes.size; var back = 0
    while (back < 3 && end - back - 1 >= 0 && (bytes[end - back - 1].toInt() and 0xC0) == 0x80) back++
    val lead = end - back - 1
    if (lead >= 0) {
        val b = bytes[lead].toInt() and 0xFF
        val need = when { b >= 0xF0 && b <= 0xF4 -> 4; b >= 0xE0 -> 3; b >= 0xC2 && b < 0xE0 -> 2; else -> 0 }
        if (need > 0 && back + 1 < need) end = lead
    }
    return bytes.copyOf(end).decodeToString()
}

/** Injections in priority order until the budget; a candidate that starts under budget is cut, not dropped (T2.4). */
fun apply(cx: Ctx, offered: List<Injection>): String {
    val sorted = offered.sortedWith(compareByDescending<Injection> { it.priority }.thenComparator { a, b -> rtok.sys.compareUtf8(a.plugin, b.plugin) })
    val budget = cfg(cx).budgetTokens
    val parts = ArrayList<String>(); val dropped = ArrayList<String>()
    var used = 0u; var before = 0u; var beforeBytes = 0uL
    fun minus(a: UInt, b: UInt) = if (a > b) a - b else 0u
    for (i in sorted) {
        val t = cx.estimate(i.text, Class.Prose)
        before += t; beforeBytes += i.text.encodeToByteArray().size.toULong()
        val marker = "dropped:${i.plugin}:$t"
        if (used >= budget) { dropped += marker; continue }
        if (t <= budget) { used += t; parts += i.text; continue }
        var room = minus(minus(budget, used), cx.estimate("\n$marker", Class.Prose))
        if (parts.isNotEmpty()) room = minus(room, 1u)
        val prefix = fitBudget(cx, i.text, Class.Prose, room)
        used = budget; dropped += marker
        if (prefix.isNotEmpty()) parts += prefix
    }
    var text = parts.joinToString("\n")
    if (dropped.isNotEmpty()) { if (text.isNotEmpty()) text += "\n"; text += dropped.joinToString("\n") }
    runCatching { cx.record(Measurement(ID, ID, beforeBytes, text.encodeToByteArray().size.toULong(), before, cx.estimate(text, Class.Prose), dropped.firstOrNull())) }
    return text
}

// Port of src/plugins/memory/pack.rs — budgeted note packing for MCP `mem_pack`. Every hit is placed
// at the cheapest tier that fits, then leftover budget deepens the best hits. No model calls.
package rtok.plugins.memory

/** How much of a note `mem_pack` spent; declaration order is the depth rank. */
enum class Tier(
    val str: String,
) {
    Uri("uri"),
    Abstract("abstract"),
    Overview("overview"),
    Full("full"),
}

data class PackEntry(
    val id: Int,
    val tier: Tier,
    val text: String,
    val tokens: UInt,
)

/** The notes that fit, in search order. */
data class Pack(
    val entries: List<PackEntry>,
    val usedTokens: UInt,
    val dropped: UInt,
)

/** A ranked search hit as packing sees it. */
data class PackHit(
    val id: Int,
    val title: String,
    val snippet: String,
)

private const val OVERVIEW_CHARS = 400
private const val SEPARATOR = "\n---\n"
private const val SEPARATOR_TOKENS = 1L

private class Slot(
    val hit: PackHit,
    val body: String?,
    var tier: Tier,
    var text: String,
    var tokens: Long,
)

private fun Long.sat(): Long = coerceAtMost(UInt.MAX_VALUE.toLong())

/** [hits] already ranked; [bodyOf] the stored body; [estimate] the prose token estimator. */
fun packNotes(
    hits: List<PackHit>,
    bodyOf: (Int) -> String?,
    maxTokens: UInt,
    estimate: (String) -> UInt,
): Pack {
    if (maxTokens == 0u || hits.isEmpty()) return Pack(emptyList(), 0u, 0u)
    val max = maxTokens.toLong()
    val est = { s: String -> estimate(s).toLong() }
    val cap = ((max / hits.size) * 2).sat().coerceAtLeast(1)
    val slots = mutableListOf<Slot>()
    var used = 0L
    var dropped = 0u
    val seen = HashSet<String>()
    for (h in hits) {
        val body = bodyOf(h.id)
        var placed: Pair<Slot, Long>? = null
        for (tier in listOf(Tier.Abstract, Tier.Uri)) {
            val text = tierText(h, body, tier) ?: continue
            val tokens = est(entryRepr(h.id, tier, text))
            if (tokens > cap) continue
            val cost = (tokens + if (slots.isEmpty()) 0 else SEPARATOR_TOKENS).sat()
            if ((used + cost).sat() > max) continue
            placed = Slot(h, body, tier, text, tokens) to cost
            break
        }
        if (placed == null || !seen.add("${h.title}\n${h.snippet}")) {
            dropped++
            continue
        }
        slots += placed.first
        used = (used + placed.second).sat()
    }

    fun upgrade(
        target: Tier,
        respectCap: Boolean,
    ) {
        for (s in slots) {
            if (s.tier >= target) continue
            val text = tierText(s.hit, s.body, target) ?: continue
            val tokens = est(entryRepr(s.hit.id, target, text))
            if (respectCap && tokens > cap) continue
            val delta = tokens - s.tokens
            if (used + delta > max) continue
            s.tier = target
            s.text = text
            s.tokens = tokens
            used += delta
        }
    }
    upgrade(Tier.Overview, true)
    upgrade(Tier.Full, true)
    upgrade(Tier.Full, false)
    val entries = slots.map { PackEntry(it.hit.id, it.tier, it.text, it.tokens.toUInt()) }.toMutableList()
    // The additive bookkeeping approximates the joined estimate: measure what is returned, shed until it fits.
    while (true) {
        val measured = if (entries.isEmpty()) 0u else estimate(renderPack(entries))
        if (measured <= maxTokens) return Pack(entries, measured, dropped)
        entries.removeLast()
        dropped++
    }
}

private fun tierText(
    h: PackHit,
    body: String?,
    tier: Tier,
): String? =
    when (tier) {
        Tier.Uri -> "${h.id} ${h.title}"
        Tier.Abstract -> "${h.id} ${h.title}\n${h.snippet}"
        Tier.Overview -> body?.let(::firstParagraph)?.let { "${h.id} ${h.title}\n${h.snippet}\n$it" }
        Tier.Full -> body?.let { "${h.id} ${h.title}\n$it" }
    }

private fun firstParagraph(body: String): String? {
    val para = body.substringBefore("\n\n").trim()
    if (para.isEmpty()) return null
    return para.scalars().take(OVERVIEW_CHARS).joinToString("")
}

/** Unicode scalar values of a string (Rust `chars()`). */
internal fun String.scalars(): List<String> =
    buildList {
        var i = 0
        while (i < length) {
            val n = if (this@scalars[i].isHighSurrogate() && i + 1 < length) 2 else 1
            add(substring(i, i + n))
            i += n
        }
    }

/** `{id}\t{tier}\n{text}`: budgeting measures this form so the header counts. */
private fun entryRepr(
    id: Int,
    tier: Tier,
    text: String,
) = "$id\t${tier.str}\n$text"

private fun renderPack(entries: List<PackEntry>) = entries.joinToString(SEPARATOR) { entryRepr(it.id, it.tier, it.text) }

/** Stable rendering: entry blocks separated by `---`. */
fun render(pack: Pack): String = renderPack(pack.entries)

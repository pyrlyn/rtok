// Port of src/plugins/memory/admit.rs — admission gate for `mem_save` (T671): character trigrams
// and Jaccard overlap, all local text.
package rtok.plugins.memory

object Admit {
    /** How many of the newest notes a save is compared with. */
    const val WINDOW: UInt = 200u

    sealed interface Verdict {
        data object Admit : Verdict
        data object TooShort : Verdict
        /** The id of the stored note this one repeats. */
        data class Duplicate(val id: Int) : Verdict
    }

    data class Row(val id: Int, val title: String, val body: String)

    private fun normalize(t: String) = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    private fun trigrams(t: String): Set<String> {
        val cps = t.scalars()
        return (0..cps.size - 3).map { i -> cps.subList(i, i + 3).joinToString("") }.toSet()
    }


    /** Jaccard overlap as a percentage; equal texts are 100 even when shorter than a trigram. */
    internal fun similarityPct(a: String, b: String): UInt {
        if (a == b) return 100u
        val ta = trigrams(a); val tb = trigrams(b)
        val union = (ta + tb).size
        if (union == 0) return 0u
        return ((ta intersect tb).size * 100 / union).toUInt()
    }

    /** Judge [body] against rows already stored. The title is not compared. */
    fun judge(minChars: UInt, similarity: UInt, body: String, stored: List<Row>): Verdict {
        val b = normalize(body)
        if (b.scalars().size.toUInt() < minChars) return Verdict.TooShort
        return stored.firstOrNull { similarityPct(b, normalize(it.body)) >= similarity }?.let { Verdict.Duplicate(it.id) } ?: Verdict.Admit
    }
}

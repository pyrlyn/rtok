// Port of src/bytes.rs: parsing binary byte sizes (`10MB`, `1.5 GB`); printing them is `ChangePreview.humanBytes`.
package rtok

import kotlin.math.pow

object Bytes {
    private const val KIB = 1024.0

    /** `10MB`, `1.5 GB` or a bare `512` (bytes), binary units. @throws IllegalArgumentException naming the bad part. */
    fun parse(s: String): Long {
        val t = s.trim().lowercase()
        val cut = t.indexOfFirst { !it.isDigit() && it != '.' }.let { if (it < 0) t.length else it }
        val n = t.substring(0, cut).toDoubleOrNull() ?: throw IllegalArgumentException("not a size: $s (try 10MB)")
        val shift =
            when (t.substring(cut).trim()) {
                "", "b" -> 0
                "k", "kb" -> 1
                "m", "mb" -> 2
                "g", "gb" -> 3
                "t", "tb" -> 4
                else -> throw IllegalArgumentException("unknown size unit in $s (B, KB, MB, GB, TB)")
            }
        return (n * KIB.pow(shift)).toLong()
    }
}

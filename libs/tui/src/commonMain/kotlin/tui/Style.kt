// Cell styles and their SGR escape codes.
package tui

/** A terminal colour: one of the 16 ANSI colours or a 256-colour index. */
sealed interface Color {
    /** ANSI colour 0..15 (8..15 are the bright ones). */
    data class Ansi(
        val n: Int,
    ) : Color

    /** xterm 256-colour index. */
    data class Indexed(
        val n: Int,
    ) : Color

    companion object {
        val Black = Ansi(0)
        val Red = Ansi(1)
        val Green = Ansi(2)
        val Yellow = Ansi(3)
        val Blue = Ansi(4)
        val Magenta = Ansi(5)
        val Cyan = Ansi(6)
        val White = Ansi(7)
        val Gray = Ansi(8)
    }
}

/** How a cell is drawn; [Style.Default] is the terminal's own look. */
data class Style(
    val fg: Color? = null,
    val bg: Color? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
) {
    /** The SGR sequence that turns the default look into this one (always starts with a reset). */
    fun sgr(): String {
        val codes = mutableListOf("0")
        if (bold) codes += "1"
        if (dim) codes += "2"
        if (underline) codes += "4"
        if (reverse) codes += "7"
        fg?.let { codes += colorCode(it, FG) }
        bg?.let { codes += colorCode(it, BG) }
        return "\u001b[${codes.joinToString(";")}m"
    }

    companion object {
        val Default = Style()
        private const val FG = 30
        private const val BG = 40
        private const val BRIGHT = 60
        private const val ANSI_BASE = 8

        private fun colorCode(
            c: Color,
            base: Int,
        ): String =
            when (c) {
                is Color.Ansi -> if (c.n < ANSI_BASE) "${base + c.n}" else "${base + BRIGHT + c.n - ANSI_BASE}"
                is Color.Indexed -> "${base + ANSI_BASE};5;${c.n}"
            }
    }
}

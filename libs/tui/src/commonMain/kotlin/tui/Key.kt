// Decoding raw terminal input bytes into keys (xterm / VT sequences).
package tui

private const val BS = 0x08
private const val SPACE = 0x20
private const val BYTE = 0xff
private val PARAM = 0x30..0x3f
private const val LEAD4 = 0xf0
private const val LEAD3 = 0xe0
private const val LEAD2 = 0xc0
private const val UTF8_4 = 4
private const val UTF8_3 = 3
private const val UTF8_2 = 2

/** One key press. */
sealed interface Key {
    data class Char(
        val c: String,
    ) : Key

    /** Ctrl with a letter, lower case (`Ctrl+C` is `Ctrl('c')`). */
    data class Ctrl(
        val c: kotlin.Char,
    ) : Key

    data object Enter : Key

    data object Esc : Key

    data object Tab : Key

    data object BackTab : Key

    data object Backspace : Key

    data object Up : Key

    data object Down : Key

    data object Left : Key

    data object Right : Key

    data object Home : Key

    data object End : Key

    data object PageUp : Key

    data object PageDown : Key

    data object Delete : Key

    /** A sequence this decoder does not know. */
    data class Unknown(
        val seq: String,
    ) : Key
}

private const val ESC = 0x1b
private const val DEL = 0x7f
private const val CTRL_MAX = 26

private val CSI =
    mapOf(
        "A" to Key.Up,
        "B" to Key.Down,
        "C" to Key.Right,
        "D" to Key.Left,
        "H" to Key.Home,
        "F" to Key.End,
        "Z" to Key.BackTab,
        "1~" to Key.Home,
        "7~" to Key.Home,
        "4~" to Key.End,
        "8~" to Key.End,
        "3~" to Key.Delete,
        "5~" to Key.PageUp,
        "6~" to Key.PageDown,
    )

private fun control(b: Int): Key =
    when (b) {
        '\r'.code, '\n'.code -> Key.Enter
        '\t'.code -> Key.Tab
        DEL, BS -> Key.Backspace
        in 1..CTRL_MAX -> Key.Ctrl('a' + (b - 1))
        else -> Key.Unknown(b.toString())
    }

/** Keys in [bytes], in order: escape sequences, control bytes and UTF-8 text. A lone ESC is [Key.Esc]. */
fun decodeKeys(bytes: ByteArray): List<Key> {
    val out = mutableListOf<Key>()
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xff
        when {
            b == ESC && i + 1 < bytes.size && (bytes[i + 1].toInt() == '['.code || bytes[i + 1].toInt() == 'O'.code) -> {
                var j = i + 2
                while (j < bytes.size && (bytes[j].toInt() and BYTE) in PARAM) j++
                val end = minOf(j, bytes.size - 1)
                val body = bytes.copyOfRange(i + 2, end + 1).decodeToString()
                out += CSI[body] ?: Key.Unknown("\u001b" + bytes.copyOfRange(i + 1, end + 1).decodeToString())
                i = end + 1
            }

            b == ESC -> {
                out += Key.Esc
                i++
            }

            b < SPACE || b == DEL -> {
                out += control(b)
                i++
            }

            else -> {
                val n = utf8Len(b)
                val end = minOf(i + n, bytes.size)
                out += Key.Char(bytes.copyOfRange(i, end).decodeToString())
                i = end
            }
        }
    }
    return out
}

private fun utf8Len(b: Int): Int =
    when {
        b >= LEAD4 -> UTF8_4
        b >= LEAD3 -> UTF8_3
        b >= LEAD2 -> UTF8_2
        else -> 1
    }

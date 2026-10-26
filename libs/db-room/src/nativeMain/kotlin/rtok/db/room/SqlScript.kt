// Splits a multi-statement SQL script into statements. androidx.sqlite prepares one statement at
// a time (no sqlite3_exec), so `Db.exec` runs each piece. Follows sqlite3_complete's rules:
// quotes ('…', "…", `…`, […]), -- and /* */ comments, and CREATE TRIGGER bodies whose `;` end
// only at `END;`.
package rtok.db.room

internal fun splitSql(script: String): List<String> {
    val out = ArrayList<String>()
    val n = script.length
    var start = 0; var i = 0
    val head = ArrayList<String>(3); var last = ""; val word = StringBuilder()
    fun flushWord() {
        if (word.isEmpty()) return
        last = word.toString().uppercase(); word.clear()
        if (head.size < 3) head += last
    }
    fun isTrigger() = head.getOrNull(0) == "CREATE" &&
        (head.getOrNull(1) == "TRIGGER" || (head.getOrNull(1) in setOf("TEMP", "TEMPORARY") && head.getOrNull(2) == "TRIGGER"))
    while (i < n) {
        val c = script[i]
        when {
            c == '\'' || c == '"' || c == '`' || c == '[' -> {
                flushWord(); last = ""; val close = if (c == '[') ']' else c; i++
                while (i < n) { if (script[i] == close) { if (close != ']' && i + 1 < n && script[i + 1] == close) i += 2 else break } else i++ }
            }
            c == '-' && i + 1 < n && script[i + 1] == '-' -> { flushWord(); while (i < n && script[i] != '\n') i++ }
            c == '/' && i + 1 < n && script[i + 1] == '*' -> { flushWord(); i += 2; while (i + 1 < n && !(script[i] == '*' && script[i + 1] == '/')) i++; i++ }
            c.isLetterOrDigit() || c == '_' -> word.append(c)
            c == ';' -> {
                flushWord()
                if (!isTrigger() || last == "END") {
                    script.substring(start, i + 1).trim().takeIf { !isOnlyComments(it.trimEnd(';')) }?.let { out += it }
                    start = i + 1; head.clear()
                }
                last = ";"
            }
            else -> flushWord()
        }
        i++
    }
    if (start < n) script.substring(start).trim().takeIf { !isOnlyComments(it) }?.let { out += it }
    return out
}

private fun isOnlyComments(s: String): Boolean = s.lines().all { it.isBlank() || it.trim().startsWith("--") }

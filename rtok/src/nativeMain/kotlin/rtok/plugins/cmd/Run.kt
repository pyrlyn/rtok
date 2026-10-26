// Port of the quoting helpers of src/plugins/cmd/run.rs (`sh_quote`, `wrap_quote`); the rest of
// `rtok run` arrives with the cmd plugin.
package rtok.plugins.cmd

/** POSIX single-quote [s]; an embedded `'` becomes `'"'"'`. */
fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

/** Quote a command for the PreToolUse rewrite `rtok run -- <quoted>` (PowerShell `''` on Windows). */
fun wrapQuote(s: String, windows: Boolean = false): String = if (windows) "'" + s.replace("'", "''") + "'" else shQuote(s)

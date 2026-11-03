package rtok.plugins.cmd

import rtok.config.Config
import rtok.config.envVar
import rtok.store.MeasRow
import rtok.store.Store
import rtok.store.listMeasurements
import rtok.support.tmpDir
import rtok.sys.*

/** Port of testutil.rs `config`: every path under a fresh temp dir. */
internal fun cmdConfig(tag: String): Pair<Config, String> = tmpDir(tag).let { Config().rebasePaths(it) to it }

internal fun archived(c: Config): List<String> = if (isDir(c.core.archiveDir)) listDir(c.core.archiveDir).map { "${c.core.archiveDir}/$it" } else emptyList()

internal fun cmdRows(c: Config): List<MeasRow> = Store.open(c.core.dbPath).use { it.listMeasurements("cmd") }

internal fun numbered(
    n: Int,
    prefix: String = "line ",
) = (0 until n).joinToString("\n") { "$prefix$it" }

internal data class Golden(
    val argv: List<String>,
    val exit: Int,
    val minSaving: Int?,
    val output: String,
)

internal fun goldenDir() = "${envVar("RTOK_FIXTURES")}/cmd_golden"

internal fun goldenWords(s: String) = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

internal fun goldenInputs() = listDir(goldenDir()).filter { it.endsWith(".in") }.sorted()

internal fun golden(name: String) = parseGolden(readToString("${goldenDir()}/$name"))

/** `argv: 'a && b'` (single-quoted) is one argv element, the shape the hook sends. */
private fun parseGolden(s: String): Golden {
    var argv = emptyList<String>()
    var exit = 0
    var min: Int? = null
    var rest = s
    for (line in s.rustLines()) {
        line.removePrefixOrNull("argv: ")?.let { a ->
            argv = if (a.length >= 2 && a.startsWith('\'') && a.endsWith('\'')) listOf(a.substring(1, a.length - 1)) else goldenWords(a)
        }
        line.removePrefixOrNull("exit: ")?.let { exit = it.toIntOrNull() ?: 0 }
        line.removePrefixOrNull("min_saving: ")?.let { min = it.trim().toIntOrNull() }
        if (line == "---") {
            rest = s.substringAfter("---\n", "")
            break
        }
    }
    return Golden(argv, exit, min, rest)
}

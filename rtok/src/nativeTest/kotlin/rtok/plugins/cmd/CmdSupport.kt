package rtok.plugins.cmd

import rtok.config.Config
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

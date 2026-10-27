package rtok

import rtok.config.Config
import rtok.store.RetentionJob
import rtok.support.tmpDir
import rtok.sys.removeDirAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** store/mod.rs `spawn_retention`: a clean pass warns nothing; a store under a file is a warning, not an error. */
private const val CALLS_DAYS = 30u
private const val BODIES_DAYS = 3u

class RetentionTest {
    @Test fun aCleanPassWarnsNothingAndABadPathIsOneWarning() {
        val dir = tmpDir("retention")
        try {
            val c = Config.loadFrom(dir)
            val cfg = c.copy(log = c.log.copy(path = "$dir/rtok.log")).also { it.home = dir }
            assertEquals(emptyList(), Retention.run(cfg, "mcp", RetentionJob("$dir/rtok.db", CALLS_DAYS, BODIES_DAYS)))
            rtok.sys.writeString("$dir/file", "")
            val warnings = Retention.run(cfg, "mcp", RetentionJob("$dir/file/rtok.db", CALLS_DAYS, BODIES_DAYS))
            assertEquals(1, warnings.size, "$warnings")
            assertTrue(warnings[0].startsWith("retention skipped until next start"), warnings[0])
        } finally {
            removeDirAll(dir)
        }
    }
}

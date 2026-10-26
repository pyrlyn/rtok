package semver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionKeyTest {
    @Test fun followsSemverPrecedence() {
        val order = listOf("0.1.9", "0.1.10-alpha", "0.1.10-alpha.2", "0.1.10-alpha.10", "0.1.10-beta", "0.1.10", "1.0.0")
        val keys = order.map { VersionKey.parse(it)!! }
        assertEquals(keys, keys.shuffled().sorted())
    }

    @Test fun rejectsNonSemver() {
        assertNull(VersionKey.parse("current"))
        assertNull(VersionKey.parse("1.2"))
        assertNull(VersionKey.parse("1.2.3-"))
        assertNull(VersionKey.parse("1.x.3"))
    }

    @Test fun ignoresBuildMetadata() {
        assertEquals(0, VersionKey.parse("1.2.3+abc")!!.compareTo(VersionKey.parse("1.2.3")!!))
        assertTrue(VersionKey.parse("1.2.3-rc.1+b")!! < VersionKey.parse("1.2.3")!!)
    }
}

// Port of src/web/token.rs tests, plus file-mode and remote-bind checks.
package rtok.web

import rtok.sys.createDirAll
import rtok.sys.fileMode
import rtok.sys.join
import rtok.sys.pid
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN_LEN = 64
private const val OWNER_RW = 0x180
private const val MODE_MASK = 0x1ff

class WebTokenTest {
    @Test
    fun tokensAreDistinctAndHex() {
        val a = WebToken.generate()
        val b = WebToken.generate()
        assertNotEquals(a, b)
        assertEquals(TOKEN_LEN, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun compareIsExact() {
        assertTrue(WebToken.matches("abc", "abc"))
        assertFalse(WebToken.matches("abc", "abd"))
        assertFalse(WebToken.matches("abc", "abcd"))
        assertFalse(WebToken.matches("abc", ""))
    }

    @Test
    fun headerWinsAndQueryIsTheFallback() {
        assertEquals("q", WebToken.presented(null, "x=1&token=q"))
        assertNull(WebToken.presented(null, null))
        assertEquals("hdr", WebToken.presented("Bearer hdr", "token=q"))
        assertNull(WebToken.presented("Basic zzz", "token=q"))
    }

    @Test
    fun issueWritesPrivateFileAndReadRoundTrips() {
        val home = join(tempDir(), "rtok-web-token-${pid()}")
        createDirAll(home)
        try {
            val first = WebToken.issue(home)
            assertEquals(first, WebToken.read(home))
            assertEquals(OWNER_RW, (fileMode(WebToken.path(home)) ?: 0) and MODE_MASK)
            val second = WebToken.issue(home)
            assertNotEquals(first, second)
            assertEquals(second, WebToken.read(home))
        } finally {
            removeDirAll(home)
        }
    }

    @Test
    fun loopbackAlwaysBindsRemoteNeedsOptIn() {
        for (h in listOf("localhost", "127.0.0.1", "127.1.2.3", "::1", "[::1]")) checkRemoteBind(h, false)
        assertFailsWith<RemoteBindRefused> { checkRemoteBind("0.0.0.0", false) }
        assertFailsWith<RemoteBindRefused> { checkRemoteBind("192.168.1.2", false) }
        checkRemoteBind("0.0.0.0", true)
    }
}

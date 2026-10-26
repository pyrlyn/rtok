// Port of crates/rtok-hook/src/lib.rs tests (unix branch).
package rtok.hook

import kotlin.test.*

private fun body(r: Request): ByteArray {
    val e = r.encode()
    var p = 0
    return readFrame { n -> e.copyOfRange(p, p + n).also { p += n } }
}

private fun env(vararg pairs: Pair<String, String>): (String) -> String? = { k -> pairs.firstOrNull { it.first == k }?.second }

class HookTest {
    @Test fun deadlineIsThreeSecondsUnlessTheVariableNamesMilliseconds() {
        assertEquals(DEADLINE_MS, deadline { null })
        assertEquals(250, deadline { "250" })
        assertEquals(DEADLINE_MS, deadline { "soon" })
    }

    @Test fun anEmptyRtokHomeFallsBackToTheUserHome() {
        assertEquals("/Users/me/.rtok", homeDirFrom("", "/Users/me"))
        assertEquals("/Users/me/.rtok", home(env("RTOK_HOME" to "", "HOME" to "/Users/me")))
    }

    @Test fun thePidIsOptionalAndOnlyItsExactFieldDecodes() {
        val old = Request("0.7.0", 7u, "SessionStart", "codex", "/w", "{}".encodeToByteArray())
        val new = old.copy(pid = 4242u)
        assertEquals(old, Request.decode(body(old)))
        assertEquals(new, Request.decode(body(new)))
        assertEquals(body(old).size + 8, body(new).size)
        assertNull(Request.decode(body(old) + le32(2u) + byteArrayOf(1, 2)))
        assertNull(Request.decode(body(new) + le32(0u)))
    }

    @Test fun aTruncatedRequestIsRefusedAndOnlyConfigVariablesMoveTheFingerprint() {
        val b = body(Request("0.7.0", 42u, "PreToolUse", "", "/w", "{}".encodeToByteArray()))
        assertNull(Request.decode(b.copyOf(b.size - 1)))
        val base = fingerprint(listOf("RTOK_HOME" to "/h", "HOME" to "/u"))
        assertEquals(base, fingerprint(listOf("HOME" to "/u", "PWD" to "/x", "RTOK_HOME" to "/h")))
        assertNotEquals(base, fingerprint(listOf("RTOK_HOME" to "/h", "HOME" to "/v")))
    }

    @Test fun homeIsRtoksOwnAndNeverRelative() {
        assertEquals("/h", home(env("RTOK_HOME" to "/h")))
        assertEquals("/u/x", home(env("RTOK_HOME" to "~/x", "HOME" to "/u")))
        assertEquals("/u/.rtok", home(env("RTOK_HOME" to "~/.rtok", "HOME" to "/u")))
        assertEquals("/u/.rtok", home(env("HOME" to "", "USERPROFILE" to "/u")))
        assertNull(home(env("RTOK_HOME" to "rel")))
        assertNull(home(env()))
    }

    @Test fun responsesRoundTrip() { // extra: wire response framing
        val f = encodeResponse("ok".encodeToByteArray())
        assertEquals("ok", decodeResponse(f.copyOfRange(4, f.size))!!.getOrNull()!!.decodeToString())
        val p = encodeResponse(null)
        assertNull(decodeResponse(p.copyOfRange(4, p.size))!!.getOrNull())
        assertNull(decodeResponse(byteArrayOf(1, 9)))
    }
}

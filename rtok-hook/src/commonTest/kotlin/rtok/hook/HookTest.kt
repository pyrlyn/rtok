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

    @Test fun aDeadlineBeyondTheLongRangeStaysPositive() {
        // A "never" value must not wrap to a negative limit (or overflow now() + limit in arm()),
        // which would fail every hook open at once.
        for (raw in listOf("18446744073709551615", "9223372036854775808", "9223372036854775807")) {
            val d = deadline { raw }
            assertEquals(MAX_DEADLINE_MS, d, raw)
        }
        assertEquals(MAX_DEADLINE_MS, deadline { MAX_DEADLINE_MS.toString() })
        assertEquals(MAX_DEADLINE_MS - 1, deadline { (MAX_DEADLINE_MS - 1).toString() })
        assertEquals(0, deadline { " 0 " })
        assertEquals(DEADLINE_MS, deadline { "-5" })
    }

    @Test fun anOversizedFrameIsRefusedBeforeItIsRead() {
        assertEquals(MAX_FRAME, frameLen(le32(MAX_FRAME.toUInt())))
        assertFailsWith<FrameException> { frameLen(le32(MAX_FRAME.toUInt() + 1u)) }
        assertFailsWith<FrameException> { frameLen(le32(UInt.MAX_VALUE)) }
        var asked = -1
        assertFailsWith<FrameException> { readFrame { n -> if (n == 4) le32(UInt.MAX_VALUE) else ByteArray(n).also { asked = n } } }
        assertEquals(-1, asked)
    }

    @Test fun invalidUtf8InATextFieldIsRefused() {
        val ok = body(Request("0.7.0", 1u, "Stop", "", "/w", byteArrayOf()))
        // version is the first field: replace its first byte with a lone continuation byte.
        val bad = ok.copyOf().also { it[4] = 0x80.toByte() }
        assertNull(Request.decode(bad))
        // stdin is raw bytes and may hold anything.
        val raw = Request("0.7.0", 1u, "Stop", "", "/w", byteArrayOf(0x80.toByte(), 0, -1))
        assertEquals(raw, Request.decode(body(raw)))
    }

    @Test fun theFingerprintCoversHostAndTelemetryVariables() {
        val base = fingerprint(emptyList())
        for (k in HOST_VARS + listOf("USERPROFILE", "OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_HEADERS", "RTOK_X")) {
            assertNotEquals(base, fingerprint(listOf(k to "v")), k)
        }
        assertEquals(base, fingerprint(listOf("OTEL_SERVICE_NAME" to "x", "PATH" to "/bin")))
        // Key and value are separated, so moving a byte between them changes the hash.
        assertNotEquals(fingerprint(listOf("RTOK_A" to "B")), fingerprint(listOf("RTOK_AB" to "")))
    }

    @Test fun fnvMatchesTheReferenceVectors() {
        assertEquals(0xcbf29ce484222325uL, fnv(byteArrayOf()))
        assertEquals(0xaf63dc4c8601ec8cuL, fnv("a".encodeToByteArray()))
        assertEquals(0x85944171f73967e8uL, fnv("foobar".encodeToByteArray()))
    }

    @Test fun tildeExpansion() {
        assertEquals("/u", expandWith("~", "/r", "/u"))
        assertEquals("~", expandWith("~", "/r", null))
        assertEquals("/u/a/b", expandWith("~/a//b/", "/r", "/u"))
        assertEquals("~/a", expandWith("~/a", "/r", null))
        assertEquals("/r/logs", expandWith("~/.rtok/logs", "/r", "/u"))
        assertEquals("/r/logs/x", expandWith("~\\.rtok\\logs\\x", "/r", "/u"))
        assertEquals("/r", expandWith("~/.rtok", "/r", "/u"))
        assertEquals("/u/.rtokx", expandWith("~/.rtokx", "/r", "/u"))
        assertEquals("~other/x", expandWith("~other/x", "/r", "/u"))
        assertEquals("rel/~/x", expandWith("rel/~/x", "/r", "/u"))
    }

    @Test fun theSocketPathMustFitSunPath() {
        assertEquals("/h/hook.sock", endpoint("/h"))
        assertEquals("/h/hook.sock", endpoint("/h/"))
        assertNull(endpoint("/" + "x".repeat(90)))
        assertNotNull(endpoint("/" + "x".repeat(87)))
    }
}

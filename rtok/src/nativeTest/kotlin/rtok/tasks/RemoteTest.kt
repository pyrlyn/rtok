@file:Suppress("MagicNumber", "EmptyFunctionBlock")

package rtok.tasks

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import rtok.store.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RemoteTest {
    private fun id(s: String) = TaskId.parse(s)

    @Test fun labelsAndTitlesRoundTrip() {
        val id = id("R2.1")
        assertEquals("rtok:R2.1", idLabel(id))
        assertEquals(id, labelId("rtok:r2.1"))
        assertNull(labelId("rtok:in-progress"))
        assertNull(labelId("rtokR2"))
        assertNull(labelId("bug"))
        assertEquals("R2.1. Ship it", issueTitle(id, " Ship it "))
        assertEquals("Ship it", taskTitle(id, "R2.1. Ship it"))
        assertEquals("Renamed by hand", taskTitle(id, "Renamed by hand"))
        assertEquals("Ж", taskTitle(id, "Ж"), "no failure on a short multibyte title")
        assertEquals(id("R7"), titleId("R7. Old"))
    }

    @Test fun tokensComeFromTheFirstSetVariableThenTheCli() {
        val env = { v: String ->
            when (v) {
                "GITHUB_TOKEN" -> "from-github"
                "GH_TOKEN" -> "  "
                else -> null
            }
        }
        assertEquals("from-github", tokenWith(env, listOf("GH_TOKEN", "GITHUB_TOKEN"), emptyList()))
        assertFailsWith<TaskException> { tokenWith({ null }, listOf("GH_TOKEN"), emptyList()) }
        // A CLI that is not installed is an error, not a crash.
        assertFailsWith<TaskException> { tokenWith({ null }, listOf("GH_TOKEN"), listOf("rtok-no-such-cli", "auth")) }
    }

    @Test fun rateLimitsWaitByRetryAfterThenReset() {
        fun h(vararg p: Pair<String, String>) = { n: String -> p.firstOrNull { it.first == n }?.second }
        assertEquals(7.seconds, rateWait(429, h("retry-after" to "7"), 0))
        val reset = h("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "130")
        assertEquals(30.seconds, rateWait(403, reset, 100))
        assertEquals(MAX_WAIT, rateWait(429, h(), 0))
        assertNull(rateWait(403, h(), 0), "a permission error")
        assertNull(rateWait(404, reset, 100))
    }

    @Test fun nextLinksAreReadFromTheLinkHeader() {
        assertEquals(
            "https://api.github.com/x?page=2",
            nextLink("""<https://api.github.com/x?page=2>; rel="next", <https://api.github.com/x?page=5>; rel="last""""),
        )
        assertNull(nextLink("""<https://api.github.com/x?page=1>; rel="prev""""))
    }

    @Test fun aLimitThatNeverLiftsEndsInAnErrorAndPagesStayOnTheHost() {
        val base = "http://mock.test"
        val fake = FakeHttp()
        val http = RemoteHttp("github", base, fake, emptyList(), Duration.ZERO)
        fake.on("POST", "/w") { status(429, "", "retry-after" to "0") }
        val err = assertFailsWith<TaskException> { http.send("POST", "/w", buildJsonObject {}) }
        assertTrue("rate limited" in err.message!!, err.message)
        assertEquals(ATTEMPTS, fake.hits("POST", "/w"))

        fake.on("GET", "/l", "page=1") { ok("[1]", "link" to "<$base/l?page=2>; rel=\"next\"") }
        fake.on("GET", "/l", "page=2") { ok("[2]", "link" to "<https://elsewhere.example/l?page=3>; rel=\"next\"") }
        assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2)), http.getAll("/l", listOf("page" to "1")))
        assertEquals(0, fake.calls.count { "elsewhere" in it.url })

        fake.on("GET", "/denied") { status(403, """{"message":"Resource not accessible"}""") }
        val denied = assertFailsWith<TaskException> { http.getAll("/denied", emptyList()) }
        assertTrue("Resource not accessible" in denied.message!!, denied.message)
    }

    @Test fun queriesAreEncodedAndWritesSendJson() {
        val fake = FakeHttp().on("PATCH", "/api/i/1") { ok("""{"n":1}""") }
        val http = RemoteHttp("gitlab", "http://h/api/", fake, listOf("authorization" to "Bearer t"), Duration.ZERO)
        http.send("PATCH", "/i/1", buildJsonObject { put("a", JsonPrimitive("b")) })
        val c = fake.calls.single()
        assertEquals("http://h/api/i/1", c.url)
        assertEquals("""{"a":"b"}""", c.body)
        assertTrue(c.headers.contains("content-type" to "application/json"))
        assertEquals("?labels=rtok%3AR1&state=all", queryString(listOf("labels" to "rtok:R1", "state" to "all")))
    }
}

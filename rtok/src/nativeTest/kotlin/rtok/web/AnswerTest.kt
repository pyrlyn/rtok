package rtok.web

import rtok.config.Config
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.readBytes
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val REQ = 3L

class AnswerTest {
    private fun live(tag: String): Pair<LiveConfig, String> {
        installDb()
        val dir = "${tempDir()}/rtok-answer-$tag-${platform.posix.getpid()}"
        removeDirAll(dir)
        createDirAll(dir)
        val c = Config.loadFrom(dir)
        return LiveConfig(c.copy(core = c.core.copy(dbPath = "$dir/rtok.db", archiveDir = "$dir/archive"))) to dir
    }

    @Test fun setFlipsAnAllowlistedPluginAndReloads() {
        val (l, dir) = live("set")
        try {
            assertTrue(l.value.plugins.guard.enabled)
            assertNull(Answer(l).answer("""{"set":{"key":"plugins.guard.enabled","value":false}}"""))
            assertTrue("enabled = false" in readBytes("$dir/config.toml").decodeToString())
            assertFalse(l.value.plugins.guard.enabled)
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun refusalsAndMalformedRequestsAreNotices() {
        val (l, dir) = live("refuse")
        try {
            val a = Answer(l)
            assertEquals("""{"req":7,"type":"message","text":"refused key core.enabled"}""", a.answer("""{"req":7,"set":{"key":"core.enabled","value":false}}"""))
            assertEquals("""{"type":"message","text":"refused key plugins.nope.enabled"}""", a.answer("""{"set":{"key":"plugins.nope.enabled","value":true}}"""))
            assertEquals("""{"type":"message","text":"set needs a string key and a bool value"}""", a.answer("""{"set":{"key":"plugins.guard.enabled","value":"no"}}"""))
            assertEquals("""{"type":"message","text":"graph needs a project"}""", a.answer("""{"graph":{}}"""))
            assertEquals("""{"type":"message","text":"unknown archive id: abc"}""", a.answer("""{"expand":"abc"}"""))
            assertNull(a.answer("""{"calls":{"subscribe":true}}"""))
            assertNull(a.answer("not json"))
            assertNull(a.answer("""{"other":1}"""))
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun tagReplyOnlyTouchesAnObjectFrame() {
        assertEquals("""{"req":3,"type":"x"}""", Answer.tagReply("""{"type":"x"}""", REQ))
        assertEquals("""{"type":"x"}""", Answer.tagReply("""{"type":"x"}""", null))
        assertEquals("[]", Answer.tagReply("[]", REQ))
    }
}

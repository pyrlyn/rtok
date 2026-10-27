package rtok.web

import rtok.config.Config
import rtok.plugin.Runtime
import rtok.store.Origin
import rtok.store.registerProject
import rtok.support.installDb
import rtok.sys.canonicalize
import rtok.sys.createDirAll
import rtok.sys.readBytes
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
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
        // `home` is transient: a data-class copy drops it, and `set` would then write into the cwd.
        val scoped = c.copy(core = c.core.copy(dbPath = "$dir/rtok.db", archiveDir = "$dir/archive")).also { it.home = dir }
        return LiveConfig(scoped) to dir
    }

    @Test fun setFlipsAnAllowlistedPluginAndReloads() {
        val (l, dir) = live("set")
        try {
            assertTrue(l.value.plugins.guard.enabled)
            assertNull(Answer(l).answer("""{"set":{"key":"plugins.guard.enabled","value":false}}"""))
            assertTrue(Regex("""\[plugins\.guard][^\[]*enabled = false""").containsMatchIn(readBytes("$dir/config.toml").decodeToString()))
            assertFalse(l.value.plugins.guard.enabled)
            assertEquals(dir, l.value.home)
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

    @Test fun projectRequestsWriteTheRegistryAndARemoveAsksFirst() {
        val (l, dir) = live("project")
        try {
            val root = canonicalize(dir)!! + "/proj"
            createDirAll(root)
            writeString("$root/lib.rs", "pub fn alpha() {}\n")
            Runtime.open(l.value, "test").use { it.store.registerProject(root, Origin.Manual) }
            val a = Answer(l)
            assertNull(a.answer("""{"project":{"action":"select","project":"$root"}}"""))
            val plan = a.answer("""{"req":2,"project":{"action":"remove","project":"$root"}}""")!!
            assertTrue(plan.startsWith("""{"req":2,"type":"project_plan","project":"$root","plan":"""), plan)
            Runtime.open(l.value, "test").use {
                assertEquals(
                    1,
                    rtok.plugins.graph.Projects
                        .rows(it)
                        .size,
                )
            }
            assertNull(a.answer("""{"project":{"action":"remove","project":"$root","confirmed":true}}"""))
            Runtime.open(l.value, "test").use {
                assertTrue(
                    rtok.plugins.graph.Projects
                        .rows(it)
                        .isEmpty(),
                )
            }
            assertTrue(readBytes("$root/lib.rs").isNotEmpty())
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun graphRequestsAnswerOrSayWhatIsMissing() {
        val (l, dir) = live("graph")
        try {
            val a = Answer(l)
            val notice = """{"type":"message","text":"unknown project request"}"""
            assertEquals(notice, a.answer("""{"project":{"action":"drop"}}"""))
            assertEquals(notice, a.answer("""{"project":{"action":"link","from":"1"}}"""))
            assertEquals("""{"type":"message","text":"diff needs a project"}""", a.answer("""{"diff":{"from":[]}}"""))
            assertEquals(
                """{"type":"message","text":"export needs a project, a level and a format"}""",
                a.answer("""{"export":{"project":"3","level":"all","format":"json"}}"""),
            )
            assertEquals("""{"type":"message","text":"import needs a name and the file's text"}""", a.answer("""{"import":{"name":"a.json"}}"""))
            assertTrue(a.answer("""{"import":{"name":"a.json","text":"{}"}}""")!!.startsWith("""{"type":"message","text":"a.json"""))
            assertTrue(a.answer("""{"graph":{"project":"nope"}}""")!!.startsWith("""{"type":"message","""))
        } finally {
            removeDirAll(dir)
        }
    }
}

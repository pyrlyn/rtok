package rtok.plugin

import rtok.plugin.sdk.*
import rtok.store.*
import rtok.support.*
import rtok.sys.*
import kotlin.test.*

class RuntimeTest {
    /** D25: an out-of-tree plugin implements the sdk's Plugin and the host takes it, because there is one interface. */
    @Test fun theTraitIsThePublishedOne() {
        val ext =
            object : Plugin {
                override fun manifest() = Manifest("ext", listOf(Surface.Mcp), false)

                override fun dashboardPage() = DashboardPage("Ext", "out of tree.", false)
            }
        mem("d25") { rt ->
            assertEquals("ext", ext.manifest().id)
            assertNull(ext.postTool(PostToolUse("t", kotlinx.serialization.json.JsonNull, kotlinx.serialization.json.JsonNull), Ctx(rt)))
        }
    }

    /** The host is every capability the sdk declares: a plugin gets it through `Ctx`. */
    @Test fun theRuntimeIsTheHost() =
        mem("host") { rt ->
            val cx = Ctx(rt, "agent-a")
            assertEquals("host", cx.session())
            assertEquals("agent-a", cx.agentId())
            cx.pluginStateSet("ext", "k", "v")
            assertEquals("v", rt.store.kvGet("plugin:ext:k"))
            assertEquals(
                true,
                cx
                    .pluginConfig(
                        "measure",
                        kotlinx.serialization.json.JsonObject
                            .serializer(),
                        kotlinx.serialization.json.JsonObject(emptyMap()),
                    )["enabled"]
                    ?.let { it.toString() == "true" },
            )
        }

    /** T154: a hook run from a linked worktree attributes its session to the main repository. */
    @Test fun aSessionInALinkedWorktreeIsAttributedToTheMainRepository() {
        val dir = tmpDir("t154-project")
        val (_, wt) = worktreeLayout(dir)
        mem("t154") { cx ->
            cx.cwd = wt
            cx.recordCall("hook", "hook", "SessionStart")
            val row = cx.store.sessionRow("t154")!!
            assertEquals("repo", row.second)
            assertEquals(wt, row.third)
            val seen = cx.store.sessionsByCwd()
            assertEquals(1, seen.size)
            assertEquals("t154", seen[0].id)
            assertEquals(wt, seen[0].cwd)
            assertTrue(seen[0].lastSeen > 0 && seen[0].endedAt == null)
        }
        removeDirAll(dir)
    }

    /** T644: a closed in-memory runtime leaves no directory behind. */
    @Test fun aClosedInMemoryRuntimeLeavesNoDirectory() {
        installDb()
        val cx = Runtime.inMemory("t644")
        val dir = parent(cx.config.log.path)!!
        assertTrue(isDir(dir))
        cx.close()
        assertFalse(exists(dir), dir)
    }

    @Test fun logSurvivesDbFailure() =
        mem("s") { cx ->
            val path = "${tempDir()}/rtok-log-db-fail-${pid()}.log"
            cx.config = cx.config.copy(log = cx.config.log.copy(path = path))
            cx.store.setQueryOnly()
            cx.log("error", "plugin", "read", "boom")
            assertTrue(exists(path), "file line written, row not")
            removeDirAll(path)
        }

    /** T127: a pointer only for the context that archived the bytes; the writer still dedups its own repeat. */
    @Test fun identicalResultIsScopedToTheWriterContext() =
        mem("t127") { cx ->
            val body = "repeated tool output ".repeat(50).encodeToByteArray()
            cx.putArchiveFor(body, "agent-a")
            assertNull(identicalResult(cx, "cmd", body, "agent-b"), "a different sub-agent never saw agent-a's body")
            assertNull(identicalResult(cx, "cmd", body, null), "the main window never saw agent-a's body either")
            assertNotNull(identicalResult(cx, "cmd", body, "agent-a"), "agent-a's own repeat still dedups")
        }

    @Test fun deferredMeasurementsLandOnFlush() =
        mem("defer") { cx ->
            cx.deferMeasurements()
            repeat(3) { cx.record(Measurement("measure", "k", 10u, 5u, 3u, 2u)) }
            assertEquals(0L, cx.store.countMeasurements())
            cx.flushMeasurements()
            assertEquals(3L, cx.store.countMeasurements())
        }

    @Test fun fitBudgetKeepsAPrefixWithinTheBudget() =
        mem("fit") { cx ->
            val text = "abcdefgh".repeat(100)
            assertEquals(text, fitBudget(cx, text, Class.Code, 10_000u))
            val cut = fitBudget(cx, text, Class.Code, 10u)
            assertTrue(text.startsWith(cut) && cx.estimate(cut, Class.Code) <= 10u && cut.isNotEmpty())
        }
}

/** An in-memory runtime closed (scratch dir removed) after [body]. */
private fun mem(
    session: String,
    body: (Runtime) -> Unit,
) {
    installDb()
    Runtime.inMemory(session).use(body)
}

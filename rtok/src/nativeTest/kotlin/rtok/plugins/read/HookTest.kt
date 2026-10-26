package rtok.plugins.read

import kotlinx.serialization.json.*
import rtok.HostPaths
import rtok.plugin.Runtime
import rtok.plugin.sdk.PreToolDecision
import rtok.plugin.sdk.PreToolUse
import rtok.plugins.read.ReadHook.WINDOW_TOOL_CALLS
import rtok.plugins.read.ReadHook.preTool
import rtok.store.*
import rtok.support.diskConfig
import rtok.sys.*
import kotlin.test.*
import kotlin.time.TimeSource

class HookTest {
    private val tail = "before Edit run native Read(limit=1) — it satisfies the edit gate"

    private fun ev(input: JsonObject) = PreToolUse("Read", input)

    private fun file(
        path: String,
        limit: Int? = null,
    ) = buildJsonObject {
        put("file_path", path)
        limit?.let { put("limit", it) }
    }

    private fun big(
        dir: String,
        name: String,
    ) = "$dir/$name".also { writeString(it, "x".repeat(100 * 1024)) }

    private fun hookRow(
        rt: Runtime,
        body: JsonObject,
        cap: Int = 65536,
    ) {
        val id = rt.recordCall("hook", "hook", (body["hook_event_name"] as? JsonPrimitive)?.content)
        rt.store.insertCallIo(id, body.toString().encodeToByteArray(), null, cap, null)
    }

    private fun post(
        tool: String,
        path: String,
    ) = buildJsonObject {
        put("hook_event_name", "PostToolUse")
        put("tool_name", tool)
        putJsonObject("tool_input") { put("file_path", path) }
    }

    private fun seedCache(
        rt: Runtime,
        path: String,
    ): String {
        val id = rt.ctx().putArchive("previous body".encodeToByteArray())
        rt.ctx().putReadCache(ReadCache.key(canonicalize(path)!!, "full", null), "h", id)
        return id
    }

    private fun reason(d: PreToolDecision?) = (d as? PreToolDecision.Deny)?.reason ?: fail("$d")

    @Test fun editedFileReadsOn() {
        val (rt, dir) = readRt("edit")
        rt.use { r ->
            val p = big(dir, "edit.txt")
            hookRow(r, post("Edit", p))
            assertNull(preTool(ev(file(p)), r.ctx()))
        }
    }

    @Test fun editedRereadAdvisesDiffWhenCached() {
        val (rt, dir) = readRt("delta-advice")
        rt.use { r ->
            val p = big(dir, "delta.txt")
            seedCache(r, p)
            hookRow(r, post("Edit", p))
            assertTrue("mode=diff" in reason(preTool(ev(file(p)), r.ctx())))
        }
    }

    /** Non-tool rows do not push an edit out of the window; the sixth finished tool call after it does. */
    @Test fun theWindowCountsToolCallsNotHookRows() {
        val (rt, dir) = readRt("window")
        rt.use { r ->
            val p = big(dir, "w.txt")
            hookRow(r, post("Write", p))
            repeat(12) { hookRow(r, buildJsonObject { put("hook_event_name", "UserPromptSubmit") }) }
            assertNull(preTool(ev(file(p)), r.ctx()), "still in window")
            repeat(WINDOW_TOOL_CALLS) {
                hookRow(
                    r,
                    buildJsonObject {
                        put("hook_event_name", "PreToolUse")
                        put("tool_name", "Bash")
                    },
                )
                hookRow(
                    r,
                    buildJsonObject {
                        put("hook_event_name", "PostToolUse")
                        put("tool_name", "Bash")
                    },
                )
            }
            assertNotNull(preTool(ev(file(p)), r.ctx()), "aged out")
        }
    }

    @Test fun hundredKbIsDenied() {
        val (rt, dir) = readRt("big")
        rt.use { r -> assertTrue("Read(limit=1)" in reason(preTool(ev(file(big(dir, "big.txt"))), r.ctx()))) }
    }

    /** T930: an image or PDF anywhere, and a text file outside the roots, pass native; an in-root one stays denied. */
    @Test fun aFileRtokReadCannotServePassesNative() {
        val (c, dir) = diskConfig("read-native-passthrough")
        Runtime.open(c, "native-passthrough").use { r ->
            val denied = { p: String -> preTool(ev(file(p)), r.ctx()) != null }
            val inside = "${cwd()}/build/t930-${pid()}.log"
            createDirAll("${cwd()}/build")
            writeString(inside, "x".repeat(100 * 1024))
            val insideDenied = denied(inside)
            removeFile(inside)
            assertTrue(insideDenied, "a large in-project file stays denied")
            assertFalse(denied(big(dir, "scratch.log")), "outside the roots")
            for (n in listOf("shot.png", "SHOT.PNG", "doc.pdf")) assertFalse(denied(big(dir, n)), n)
        }
    }

    private fun deniesOn(host: String): Pair<String, String> {
        val (c, dir) = diskConfig("read-deny-text-$host")
        val cfg = c.copy(hook = c.hook.copy(host = host), plugins = c.plugins.copy(read = c.plugins.read.copy(allowPaths = listOf(dir))))
        return Runtime.open(cfg, "deny-text-$host").use { r ->
            val p = big(dir, "big.txt")
            val first = reason(preTool(ev(file(p)), r.ctx()))
            val id = seedCache(r, p)
            hookRow(r, post("Edit", p))
            first to reason(preTool(ev(file(p)), r.ctx())).replace(id.take(8), "ID")
        }
    }

    @Test fun claudeDenyNamesTheToolAndHowToLoadIt() {
        val (first, delta) = deniesOn("claude")
        assertEquals("use mcp__rtok__read (ToolSearch select:mcp__rtok__read loads it); $tail", first)
        assertEquals("file changed since last read; use mcp__rtok__read(mode=diff) vs ID (ToolSearch select:mcp__rtok__read loads it)", delta)
    }

    @Test fun geminiDenyNamesTheToolWithoutALoadHint() {
        val (first, delta) = deniesOn("gemini")
        assertEquals("use mcp_rtok_read; $tail", first)
        assertEquals("file changed since last read; use mcp_rtok_read(mode=diff) vs ID", delta)
    }

    @Test fun anUnlistedHostKeepsTheGenericDeny() {
        val (first, delta) = deniesOn("copilot")
        assertEquals("use rtok read; $tail", first)
        assertEquals("file changed since last read; use rtok read(mode=diff) vs ID", delta)
    }

    @Test fun aRangedReadPassesUpToTheConfiguredCap() {
        val (rt, dir) = readRt("gate")
        rt.use { r ->
            val p = big(dir, "gate.txt")
            val denied = { l: Int? -> preTool(ev(file(p, l)), r.ctx()) != null }
            assertEquals(300u, r.config.plugins.read.rangeMaxLines)
            for (l in listOf(1, 5, 300)) assertFalse(denied(l), "$l")
            for (l in listOf(301, 2000, 0, null)) assertTrue(denied(l), "$l")
        }
    }

    @Test fun everyDenyWritesACostRow() {
        val (rt, dir) = readRt("deny-row")
        rt.use { r ->
            val p = big(dir, "d.txt")
            val rows = { r.store.listMeasurements("read") }
            assertNull(preTool(ev(file(p, 300)), r.ctx()))
            assertTrue(rows().isEmpty(), "a pass is not a deny")
            assertNotNull(preTool(ev(file(p, 301)), r.ctx()))
            val m = rows()[0]
            assertEquals("deny" to 0L, m.kind to m.beforeBytes)
            val (first, delta) = deniesOn("claude")
            assertEquals(first.encodeToByteArray().size.toLong(), m.afterBytes)
            assertEquals(0, m.estBefore)
            assertTrue(m.estAfter > 0)
            seedCache(r, p)
            hookRow(r, post("Edit", p))
            assertNotNull(preTool(ev(file(p, 301)), r.ctx()))
            val all = rows()
            assertEquals(2, all.size)
            assertTrue(all.all { it.kind == "deny" })
            assertEquals(
                delta
                    .replace("ID", "12345678")
                    .encodeToByteArray()
                    .size
                    .toLong(),
                all[1].afterBytes,
            )
        }
    }

    /** T55.13: Copilot's adapted `Read` carries `path`, not `file_path`. */
    @Test fun copilotPathKeyGetsTheReadAdvice() {
        val (rt, dir) = readRt("copilot-path")
        rt.use { r ->
            val p = big(dir, "copilot-big.txt")
            assertTrue(tail in reason(preTool(ev(buildJsonObject { put("path", p) }), r.ctx())))
        }
    }

    @Test fun twoKbIsSilent() {
        val (rt, dir) = readRt("small")
        rt.use { r ->
            writeString("$dir/small.txt", "x".repeat(2048))
            assertNull(preTool(ev(file("$dir/small.txt")), r.ctx()))
        }
    }

    @Test fun samePathRejectsSuffixFalsePositive() {
        assertFalse(HostPaths.sameSpelling("/repo/main.rs", "ain.rs"))
        assertTrue(HostPaths.sameSpelling("/repo/src/main.rs", "src/main.rs"))
        assertTrue(HostPaths.sameSpelling("src/main.rs", "/repo/src/main.rs"))
        assertFalse(HostPaths.sameSpelling("", "src/main.rs"))
    }

    /** T504: on case-sensitive hosts differently cased spellings stay different files. */
    @Test fun samePathFollowsThePlatformCaseRule() {
        assertFalse(HostPaths.sameSpelling("/Repo/Src/Main.rs", "/repo/src/main.rs"))
        assertFalse(HostPaths.sameSpelling("/Repo/Src/Main.rs", "src/MAIN.rs"))
    }

    /** A hook body the store could not keep comes back empty; the window fails open there. */
    @Test fun anUnreadableHookBodyAllowsTheRead() {
        val (rt, dir) = readRt("elided")
        rt.use { r ->
            val p = big(dir, "elided-big.txt")
            val body =
                buildJsonObject {
                    put("hook_event_name", "PostToolUse")
                    put("tool_name", "Write")
                    putJsonObject("tool_input") { put("file_path", p) }
                    put("pad", "x".repeat(70 * 1024))
                }
            val id = r.recordCall("hook", "hook", "PostToolUse")
            r.store.insertCallIo(id, body.toString().encodeToByteArray(), null, 65536, null)
            assertTrue(r.store.recentHookInputs(r.session, 10)[0].isEmpty(), "the body is elided, not stored")
            assertNull(preTool(ev(file(p)), r.ctx()))
        }
    }

    /** T202: 50 unrelated 60 KB PostToolUse rows; the window query fetches at most its size. */
    @Test fun hookPreToolReadStaysUnderBudgetWith50LargeRows() {
        val (rt, dir) = readRt("big-history")
        rt.use { r ->
            val p = big(dir, "target.txt")
            val pad = "x".repeat(60 * 1024)
            for (n in 0 until 50) {
                hookRow(
                    r,
                    buildJsonObject {
                        put("hook_event_name", "PostToolUse")
                        put("tool_name", "Write")
                        putJsonObject("tool_input") { put("file_path", "/repo/unrelated_$n.rs") }
                        put("pad", pad)
                    },
                    200_000,
                )
            }
            val start = TimeSource.Monotonic.markNow()
            assertNotNull(preTool(ev(file(p)), r.ctx()))
            assertTrue(start.elapsedNow().inWholeMilliseconds < 500)
            val rows = r.store.recentHookInputsForEvent(r.session, "PostToolUse", WINDOW_TOOL_CALLS.toLong())
            assertTrue(rows.size <= WINDOW_TOOL_CALLS)
            assertTrue(rows.sumOf { it.length } < 500 * 1024)
        }
    }
}

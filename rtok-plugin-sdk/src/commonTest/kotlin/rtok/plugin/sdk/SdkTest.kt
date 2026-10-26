package rtok.plugin.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.*

@Serializable private data class Cfg(val enabled: Boolean = false)

class SdkTest {
    @Test fun surfaceNamesAreTheConfigKeys() { assertEquals("hook", Surface.Hook.asStr()); assertEquals("cli", Surface.Cli.asStr()) }
    @Test fun aPageWithoutAMeasurementPathShowsNoStats() {
        val p = DashboardPage("Measure", "Reads what others recorded.", false); assertEquals("Measure", p.title); assertFalse(p.savesTokens); assertTrue(p.fields.isEmpty())
    }
    @Test fun configIsReadByDottedPathAndMissingIsDefault() {
        val host = MemoryHost(Json.parseToJsonElement("""{"plugins":{"mine":{"enabled":true}}}""")); val cx = Ctx(host)
        assertEquals(Cfg(true), cx.pluginConfig("mine", Cfg.serializer(), Cfg())); assertEquals(Cfg(), cx.pluginConfig("absent", Cfg.serializer(), Cfg()))
    }
    @Test fun anArchivedBlobComesBackUnderAStableId() {
        val h = MemoryHost(); val id = h.putArchive("the original output".encodeToByteArray())
        assertEquals(id, h.putArchive("the original output".encodeToByteArray())); assertEquals("the original output", h.getArchive(id)!!.decodeToString()); assertNull(h.getArchive("mem0"))
    }
    @Test fun distinctBodiesNeverShareAnId() {
        val h = MemoryHost(); val ab = h.putArchive("ab".encodeToByteArray()); val ac = h.putArchive("ac".encodeToByteArray())
        assertNotEquals(ab, ac); assertEquals("ab", h.getArchive(ab)!!.decodeToString()); assertEquals("ac", h.getArchive(ac)!!.decodeToString())
    }
    @Test fun anArchiveDecisionIsFrozenByItsFirstWriter() {
        val h = MemoryHost(); h.putArchiveDecision("t1", "mem1", "[archived mem1]"); h.putArchiveDecision("t1", "mem2", "[archived mem2]")
        val d = h.archiveDecision("t1")!!; assertEquals("[archived mem1]", d.pointer); assertFalse(d.expanded)
        assertEquals(1, h.markExpanded("mem1")); assertTrue(h.archiveDecision("t1")!!.expanded)
    }
    @Test fun terseExampleRecordsOneMeasurement() {
        val terse = object : Plugin {
            override fun manifest() = Manifest("terse", listOf(Surface.Hook), true)
            override fun dashboardPage() = DashboardPage("Terse", "Drops `-l` from `ls`.", true)
            override fun preTool(ev: PreToolUse, cx: Ctx): PreToolDecision? {
                val cmd = (ev.toolInput as? JsonObject)?.get("command")?.jsonPrimitive?.contentOrNull ?: return null
                if (ev.toolName != "Bash" || !cmd.startsWith("ls -l")) return null
                val short = cmd.replaceFirst("ls -l", "ls")
                cx.record(Measurement("terse", "rewrite", cmd.length.toULong(), short.length.toULong(), cx.estimate(cmd, Class.Code), cx.estimate(short, Class.Code)))
                return PreToolDecision.Rewrite(buildJsonObject { put("command", short) }, "terse: long listing is rarely what was wanted")
            }
        }
        val host = MemoryHost(); assertNotNull(terse.preTool(PreToolUse("Bash", buildJsonObject { put("command", "ls -la") }), Ctx(host))); assertEquals(1, host.recorded().size)
    }
    @Test fun wireRefWritesBack() {
        val wire = object : ToolResults { override fun toolResults(req: WireRequest) = listOf(ToolResultRef(req, listOf("messages", 0, "content"), 0, "t1")) }
        val req = WireRequest(wire, Json.parseToJsonElement("""{"messages":[{"content":"long"}]}"""))
        req.toolResults()[0].content = JsonPrimitive("short"); assertEquals("""{"messages":[{"content":"short"}]}""", req.body.toString())
    }
    @Test fun taskPrefixIsOneToEightLetters() { checkTaskPrefix("at"); assertFails { checkTaskPrefix("R2") }; assertFails { checkTaskPrefix("TOOLONGPX") } }
}

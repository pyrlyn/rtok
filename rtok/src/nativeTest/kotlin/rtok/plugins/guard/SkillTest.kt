// Port of the src/plugins/guard/skill.rs tests (10).
package rtok.plugins.guard

import kotlin.test.*
import kotlin.time.TimeSource
import kotlinx.serialization.json.*
import rtok.MIN_PREFIX
import rtok.plugin.sdk.*
import rtok.store.archiveIdsWithPrefix
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.createDirAll
import rtok.sys.rustLines
import rtok.sys.writeString

private fun body(sections: Int) = buildString {
    append("---\nname: big\ndescription: many sections\n---\n")
    for (i in 0 until sections) append("## Section $i\nFirst line of $i.\n```sh\n# not a heading\n```\n")
}

private fun load(cx: Ctx, skill: String) = noteLoad(PostToolUse("Skill", buildJsonObject { put("skill", skill) }, JsonNull), cx)

private fun rootOf(manifest: String, plugin: String, cwd: String?): String? {
    val file = "${tmpDir("skill-plugin-root")}/installed_plugins.json"
    writeString(file, manifest)
    return pluginRoot(file, plugin, cwd)
}

class SkillTest {
    @Test fun aRepeatLoadWarnsUntilTheNextCompaction() = withRuntime("skill-repeat") { r ->
        val cx = Ctx(r)
        assertNull(load(cx, "rtok-hub"), "first load")
        val warn = load(cx, "rtok-hub")!!
        assertTrue(warn.contains("skill rtok-hub was already loaded"), warn)
        assertNull(load(cx, "other"))
        assertNull(load(Ctx(r, "sub"), "rtok-hub"))
        forgetLoads(cx)
        assertNull(load(cx, "rtok-hub"), "after compaction")
        assertNotNull(load(cx, "rtok-hub"))
    }

    @Test fun smallBodiesAndHostKeysPass() = withRuntime("skill-pass") { r ->
        val cx = Ctx(r)
        assertNull(decide(cx, "s", "# T\n\nthree\nlines\n", 8192))
        val keyed = "---\nallowed-tools: Bash\n---\n${body(300)}"
        assertTrue(keyed.length > 8192)
        assertNull(decide(cx, "s", keyed, 8192))
        assertFalse(hostKeys(body(1)))
        assertTrue(hostKeys("---\nmodel:  haiku\n---\n# x"))
    }

    @Test fun hostKeysAreRecognisedWhateverTheirYamlSpelling() = withRuntime("skill-yaml") { r ->
        val cx = Ctx(r)
        val filler = body(300).substringAfter("---\n").substringAfter("---\n")
        for (fm in listOf("model: haiku", "\"model\": haiku", "'allowed-tools': Bash", "\"context\": fork", "'agent': Explore",
            "name: x\r\nmodel: haiku\r\n", "name: x\nallowed-tools:\n  - Bash\n", "name: x\n\"model\" :   haiku", "{model: haiku}",
            "name: [unclosed", "name: a\nname: b", "- just\n- a list")) {
            val skill = "---\n$fm\n---\n$filler"
            assertTrue(skill.length > 8192); assertTrue(hostKeys(skill), fm); assertNull(decide(cx, "s", skill, 8192), fm)
        }
        for (fm in listOf("name: x", "name: x\ndescription: \"pick the model and allowed-tools: later\"", "name: x\nmetadata:\n  model: nested\n",
            "\"name\": x\n'description': y", "")) {
            val skill = "---\n$fm\n---\n$filler"
            assertFalse(hostKeys(skill), fm); assertNotNull(decide(cx, "s", skill, 8192), fm)
        }
        assertFalse(hostKeys("# Notes\nmodel: not frontmatter\n$filler"))
    }

    @Test fun oversizedBodyIsDeniedWithMapAndPointerUnderBudget() = withRuntime("skill-deny") { r ->
        val cx = Ctx(r)
        val big = body(600) + "x".repeat(200_000)
        val t0 = TimeSource.Monotonic.markNow()
        val d = decide(cx, "big", big, 8192)
        val ms = t0.elapsedNow().inWholeMilliseconds
        val reason = (d as PreToolDecision.Deny).reason
        assertTrue(reason.startsWith("rtok kept the map of skill big ("), reason)
        assertTrue(reason.contains("## Section 0\n  First line of 0.\n"), reason)
        assertFalse(reason.contains("not a heading"))
        assertTrue(reason.encodeToByteArray().size <= 8192, "${reason.length}")
        val id = reason.substringAfter("expand: rtok expand ").substringBefore(']')
        assertEquals(MIN_PREFIX, id.length, id)
        assertTrue(reason.contains("[rtok $id · ${big.rustLines().size} lines ·"), reason)
        val full = r.store.archiveIdsWithPrefix(id, 2)
        assertEquals(1, full.size, "$full")
        assertContentEquals(big.encodeToByteArray(), r.getArchive(full[0]))
        assertTrue(ms < 100, "$ms ms (debug bound, like Rust's non-release gate)")
    }

    @Test fun resolvePrefersProjectThenUserAndReadsThePluginManifest() {
        val home = tmpDir("skill-resolve"); val proj = "$home/proj"
        val p = "$proj/.claude/skills/here/SKILL.md"
        createDirAll("$proj/.claude/skills/here"); writeString(p, "# here")
        createDirAll("$home/.claude/plugins")
        writeString("$home/.claude/plugins/installed_plugins.json", """{"version":2,"plugins":{"pony@market":[{"installPath":"/cache/pony/1.0"}]}}""")
        assertEquals(p, resolve("here", proj, home))
        assertEquals(skillMdPath(home, "there"), resolve("there", proj, home))
        assertEquals("/cache/pony/1.0/skills/tail/SKILL.md", resolve("pony:tail", null, home))
        assertNull(resolve("nope:tail", null, home)); assertNull(resolve("../etc", null, home)); assertNull(resolve("a/b", null, home))
    }

    @Test fun aPluginUnderTwoMarketplacesFallsOpen() = assertNull(rootOf("""{"version":2,"plugins":{
        "pony@a":[{"scope":"user","installPath":"/cache/a"}],
        "pony@b":[{"scope":"user","installPath":"/cache/b"}]}}""", "pony", null))

    @Test fun theInstallForTheSessionProjectBeatsTheUserOne() = assertEquals("/cache/here", rootOf("""{"version":2,"plugins":{"pony@a":[
        {"scope":"user","installPath":"/cache/user"},
        {"scope":"project","projectPath":"/work/other","installPath":"/cache/other"},
        {"scope":"project","projectPath":"/work/here","installPath":"/cache/here"}]}}""", "pony", "/work/here"))

    @Test fun theUserInstallIsUsedWhenNoProjectMatchesAndAmbiguityFallsOpen() {
        assertEquals("/cache/user", rootOf("""{"version":2,"plugins":{"pony@a":[
            {"scope":"project","projectPath":"/work/other","installPath":"/cache/other"},
            {"scope":"user","installPath":"/cache/user"}]}}""", "pony", "/work/here"))
        assertNull(rootOf("""{"version":2,"plugins":{"pony@a":[
            {"scope":"project","projectPath":"/work/x","installPath":"/cache/x"},
            {"scope":"local","projectPath":"/work/y","installPath":"/cache/y"}]}}""", "pony", "/work/here"))
        assertNull(rootOf("""{"version":2,"plugins":{"pony@a":[
            {"scope":"user","installPath":"/cache/1"},{"scope":"user","installPath":"/cache/2"}]}}""", "pony", null))
    }

    @Test fun aSoleInstallScopedToAnotherProjectFallsOpen() {
        val home = tmpDir("skill-foreign"); val plugin = "$home/private-a"; val skill = "$plugin/skills/big/SKILL.md"
        createDirAll("$plugin/skills/big"); writeString(skill, "x".repeat(200_000)); createDirAll("$home/.claude/plugins")
        fun manifest(entry: String) = writeString("$home/.claude/plugins/installed_plugins.json", """{"version":2,"plugins":{"pony@a":[$entry]}}""")
        for (foreign in listOf("""{"scope":"project","projectPath":"/A","installPath":"$plugin"}""", """{"scope":"local","projectPath":"/A","installPath":"$plugin"}""",
            """{"scope":"project","installPath":"$plugin"}""", """{"projectPath":"/A","installPath":"$plugin"}""")) {
            manifest(foreign); assertNull(resolve("pony:big", "/B", home), foreign)
        }
        manifest("""{"scope":"project","projectPath":"/B","installPath":"$plugin"}""")
        assertEquals(skill, resolve("pony:big", "/B", home))
    }

    @Test fun aSingleLegacyEntryWithoutScopeStillResolves() =
        assertEquals("/cache/pony", rootOf("""{"version":2,"plugins":{"pony@a":[{"installPath":"/cache/pony"}]}}""", "pony", "/work/here"))
}

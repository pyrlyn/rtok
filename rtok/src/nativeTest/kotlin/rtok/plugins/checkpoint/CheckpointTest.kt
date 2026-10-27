// Port of src/plugins/checkpoint.rs tests. Hook-driven tests (hooks::run) of the same flows call the
// plugin functions directly; the three that need the hook dispatcher wait for Phase 6.
package rtok.plugins.checkpoint

import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.plugin.pluginStateKey
import rtok.plugin.sdk.*
import rtok.store.*
import rtok.support.*
import rtok.sys.*
import snapshot.assertInlineSnapshot
import kotlin.test.*

private fun rt(
    session: String,
    cwd: String? = null,
): Runtime = installDb().let { Runtime.inMemory(session).also { r -> r.cwd = cwd } }

private fun entry(
    path: String,
    action: Action,
) = PathEntry(path, action)

private fun tool(
    name: String,
    input: JsonObject,
) = buildJsonObject {
    put("type", "assistant")
    put("cwd", "/r")
    putJsonObject("message") {
        putJsonArray("content") {
            addJsonObject {
                put("type", "tool_use")
                put("id", "t")
                put("name", name)
                put("input", input)
            }
        }
    }
}.toString()

private fun actionsOf(jsonl: String) = extract(jsonl).paths.map { it.path to it.action }

private fun transcript(
    dir: String,
    text: String,
) = "$dir/t.jsonl".also { writeString(it, text) }

class CheckpointTest {
    @Test fun lastPathsAreRootRelativeAndEmptyWithoutACheckpoint() =
        rt("last-paths").use { r ->
            val cx = Ctx(r)
            assertTrue(lastPaths(cx, "/r").isEmpty())
            val cp = Checkpoint(paths = listOf("/r/src/a.rs", "docs/b.md", "/else/c.rs").map { entry(it, Action.Read) })
            cx.upsertNote(null, "checkpoint:${cx.session()}", "compact", cp.render())
            assertEquals(listOf("src/a.rs", "docs/b.md", "/else/c.rs"), lastPaths(cx, "/r"))
        }

    @Test fun fixtureHasThreePathsAndCompactInjectsUnderBudget() {
        val cp = extract(FIXTURE)
        assertEquals(listOf("src/a.rs", "src/b.rs", "src/c.rs"), cp.paths.map { it.path })
        val path = transcript(tmpDir("t25"), FIXTURE)
        rt("t25").use { r ->
            save(path, Ctx(r))
            val body = assertNotNull(r.store.latestNote("checkpoint:t25"))
            val text = assertNotNull(offer(Ctx(r))).text
            for (p in listOf("src/a.rs", "src/b.rs", "src/c.rs")) assertTrue(p in body && p in text, "$body\n$text")
            assertTrue(r.estimate(text, Class.Prose) <= r.config.plugins.memory.checkpointTokens)
        }
    }

    @Test fun injectedSkillBodiesAreListedNotQuoted() {
        val (body, lines) = skillFixture()
        val cp = extract(lines)
        assertEquals(2, cp.skills.size, "${cp.skills}")
        assertEquals("pixel" to body.encodeToByteArray().size.toULong(), cp.skills[0])
        assertEquals("ponytail", cp.skills[1].first)
        assertEquals(listOf("make it blue"), cp.prompts)
        val text = cp.render()
        assertTrue("skills loaded before compaction: pixel (5.0 KB), ponytail (0.1 KB) — re-invoke only what the next step needs\n" in text, text)
        assertFalse("xxxx" in text)
    }

    @Test fun hostInjectedRecordsAreNotPrompts() {
        val cp = extract(HOST_FIXTURE)
        assertEquals(listOf("first typed", "second typed", "third typed"), cp.prompts)
        assertEquals(3uL to 10uL, cp.typed to cp.skipped, "T419 counts every record")
        val text = cp.render()
        for (noise in listOf("task-notification", "agent-message", "continued from", "cut off", "ci-monitor", "local-command", "interrupted", "system-reminder", "host context", "task_1", "unclosed")) {
            assertFalse(noise in text, "$noise leaked into\n$text")
        }
    }

    @Test fun promptWindowCountsOnlyTypedPrompts() {
        val lines =
            (0 until 25)
                .flatMap { i ->
                    listOf(
                        """{"type":"user","origin":{"kind":"human"},"message":{"content":"typed $i"}}""",
                        """{"type":"user","origin":{"kind":"task-notification"},"message":{"content":"<task-notification>$i</task-notification>"}}""",
                    )
                }.joinToString("\n")
        val cp = extract(lines)
        assertEquals((5 until 25).map { "typed $it" }, cp.prompts)
        assertEquals(25uL to 25uL, cp.typed to cp.skipped)
    }

    @Test fun countsNeverReachTheRender() {
        val cp = extract(HOST_FIXTURE)
        assertEquals(cp.render(), cp.copy(typed = 0u, skipped = 0u).render())
    }

    @Test fun toolResultRecordsCountAsNeither() {
        val cp =
            extract(
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}
{"type":"user","message":{"content":""}}""",
            )
        assertEquals(0uL to 0uL, cp.typed to cp.skipped)
    }

    @Test fun promptCountsTakeEachSessionsLargestRow() {
        assertEquals(
            6uL to 4uL,
            promptCounts(
                listOf(
                    "plugin:memory:checkpoint:s1" to """{"typed":2,"skipped":3}""",
                    "plugin:memory:session:s1" to """{"typed":5,"skipped":4}""",
                    "plugin:memory:session:s2" to """{"typed":1,"skipped":0}""",
                    "plugin:memory:session:s3" to "not json",
                    "plugin:memory:other" to """{"typed":99,"skipped":99}""",
                ),
            ),
        )
        val huge = """{"typed":${ULong.MAX_VALUE},"skipped":1}"""
        assertEquals(ULong.MAX_VALUE to 2uL, promptCounts(listOf("plugin:memory:session:a" to huge, "plugin:memory:session:b" to huge)))
        assertEquals(0uL to 0uL, promptCounts(emptyList()))
    }

    @Test fun changedFilesAreListedBeforeReadOnes() {
        val cp = extract(ACTIONS)
        assertInlineSnapshot(
            cp.render(),
            """
            checkpoint
            - fix a, rewrite b
            path /r/src/b.rs (edited)
            path /r/src/old.rs (edited)
            path /r/src/new.rs (created)
            path /r/src/c.rs (deleted)
            path /r/src/gone.rs (deleted)
            path /r/src/a.rs (read 10-59)
            """,
        )
        assertEquals('\n', cp.render().last(), "ends with one newline")
        assertEquals(cp.render(), extract(ACTIONS).render(), "byte-stable")
    }

    @Test fun toolNamesMapToActionsAndAChangeIsNeverDowngraded() {
        fun p(n: String) = buildJsonObject { put("file_path", "/r/$n") }
        val got =
            actionsOf(
                listOf(
                    tool("Read", p("read")),
                    tool("Grep", p("grep")),
                    tool("Edit", p("edit")),
                    tool("MultiEdit", p("multi")),
                    tool("Read", p("edit")),
                    tool("Write", p("made")),
                    tool("Edit", p("made")),
                    tool("Edit", p("gone")),
                    tool("Bash", buildJsonObject { put("command", "rm gone") }),
                    tool("Read", p("gone")),
                ).joinToString("\n"),
            )

        fun want(n: String) = got.first { it.first == "/r/$n" }.second
        assertEquals(Action.Read, want("read"))
        assertEquals(Action.Read, want("grep"))
        assertEquals(Action.Edited, want("edit"))
        assertEquals(Action.Edited, want("multi"))
        assertEquals(Action.Deleted, want("gone"))
        assertEquals(Action.Edited, want("made"))
    }

    @Test fun aWriteIsCreatedOnlyWhenItsResultSaysSo() {
        fun write(
            id: String,
            p: String,
        ) = """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"$id","name":"Write","input":{"file_path":"$p"}}]}}"""

        fun result(
            id: String,
            content: JsonElement,
        ) = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", content)
                    }
                }
            }
        }.toString()
        val lines =
            listOf(
                write("a", "/r/by-text"),
                result("a", JsonPrimitive("File created successfully at: /r/by-text")),
                write("b", "/r/by-blocks"),
                result("b", Json.parseToJsonElement("""[{"type":"text","text":"File created successfully at: x"}]""")),
                write("c", "/r/overwritten"),
                result("c", JsonPrimitive("The file /r/overwritten has been updated successfully.")),
                result("zzz", JsonPrimitive("File created successfully at: /r/forged")),
            ).joinToString("\n")
        assertEquals(listOf("/r/by-blocks" to Action.Created, "/r/by-text" to Action.Created, "/r/overwritten" to Action.Edited), actionsOf(lines).sortedBy { it.first })
    }

    @Test fun readRangesFollowTheLastRangedRead() {
        fun read(extra: String) = tool("Read", Json.parseToJsonElement("""{"file_path":"/r/a"${if (extra.isEmpty()) "" else ",$extra"}}""").jsonObject)

        fun lines(vararg l: String) = extract(l.joinToString("\n")).paths[0].lines
        assertEquals(Lines(5u, 7u), lines(read(""""offset":5,"limit":3""")))
        assertEquals(Lines(1u, 4u), lines(read(""""limit":4""")))
        assertEquals(Lines(9u, null), lines(read(""""offset":9""")))
        assertNull(lines(read(""""offset":5"""), read("")), "a whole-file read supersedes the range")
    }

    @Test fun bashRemovalsAreReadConservatively() {
        fun rm(c: String) = removedPaths(c, "/r/sub")
        assertEquals(listOf("/r/sub/a.rs", "/r/b.rs", "/abs/c.rs"), rm("rm a.rs ../b.rs /abs/c.rs"))
        assertEquals(listOf("/r/sub/-odd", "/r/sub/dir"), rm("rm -rf -- -odd dir/"))
        assertEquals(listOf("/r/sub/a.rs", "/r/sub/b.rs"), rm("echo hi; rm a.rs; git rm -f b.rs && ls c.rs"))
        assertEquals(listOf("/abs/b.rs"), rm("cd x && rm a.rs /abs/b.rs"), "after a cd only absolute paths are known")
        assertTrue(rm("rm *.rs").isEmpty() && rm("rm \$F ~/x").isEmpty())
        assertTrue(rm("git rm --cached a.rs").isEmpty(), "the file stays on disk")
        assertTrue(rm("git rm -n a.rs").isEmpty() && rm("echo rm a.rs").isEmpty())
        assertTrue(rm("rm 'unterminated").isEmpty() && rm("warm a.rs").isEmpty())
        assertEquals(listOf("a.rs"), removedPaths("rm a.rs", null))
    }

    @Test fun oldRowsDecodeAsReads() {
        for ((row, want) in listOf(
            "src/a.rs" to PathEntry("src/a.rs", Action.Read),
            "src/a (copy).rs" to PathEntry("src/a (copy).rs", Action.Read),
            "src/a (note)" to PathEntry("src/a (note)", Action.Read),
            "src/a.rs (edited)" to PathEntry("src/a.rs", Action.Edited),
            "a (b) (created)" to PathEntry("a (b)", Action.Created),
            "a.rs (deleted)" to PathEntry("a.rs", Action.Deleted),
            "a.rs (read)" to PathEntry("a.rs", Action.Read),
            "a.rs (read 10-59)" to PathEntry("a.rs", Action.Read, Lines(10u, 59u)),
            "a.rs (read 10-)" to PathEntry("a.rs", Action.Read, Lines(10u, null)),
            "a.rs (read x-y)" to PathEntry("a.rs", Action.Read),
        )) {
            assertEquals(want, PathEntry.parse(row), row)
        }
        rt("t375-old-rows").use { r ->
            val cx = Ctx(r)
            cx.upsertNote(null, "checkpoint:${cx.session()}", "compact", "checkpoint\n- go\npath /r/src/a.rs\npath /r/src/b.rs (edited)\npath /r/src/c.rs (deleted)\npath docs/d.md (read 3-9)\n")
            assertEquals(listOf("src/a.rs", "src/b.rs", "docs/d.md"), lastPaths(cx, "/r"))
        }
    }

    private fun both(content: String) =
        Extraction(true).apply { content.rustLines().forEach(::line) }.finish() to
            Extraction(false).apply { content.rustLines().forEach(::line) }.finish()

    @Test fun prefilterIsFormattingIndependent() {
        for (line in listOf(
            """{"type":"user","message":{"content":"ship the patch"}}""",
            """{"type": "user", "message": {"content": "ship the patch"}}""",
            """{"message":{"content":"ship the patch"},"type" : "user"}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"},{"type":"text","text":"ship the patch"}]}}""",
            """{"type": "user", "message": {"content": [{"tool_use_id": "t1", "type": "tool_result", "content": "ok"}, {"text": "ship the patch", "type": "text"}]}}""",
            """{"typ\u0065":"user","message":{"content":"ship the patch"}}""".replace("\u0065", "\\u0065"),
            """{"type":"user","message":{"content":"ship the patch"},"tool_use_id":"t1"}""",
        )) {
            val (f, u) = both(line)
            assertEquals(listOf("ship the patch"), f.prompts, line)
            assertEquals(u, f, line)
        }
        assertTrue(worthParsing("""{"type": "assistant", "cwd": "/r", "message": {"content": [{"type": "tool_use", "id": "t6", "name": "Bash", "input": {"command": "rm src/gone.rs"}}]}}"""))
        assertTrue(worthParsing("""{"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t4", "content": "File created successfully at: /r/src/new.rs"}]}, "toolUseResult": {"type": "create"}}"""))
        assertTrue(extract("""{"type": "user", "isMeta": true, "message": {"content": "Your response above was cut off."}}""").prompts.isEmpty())
        for (echo in listOf(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":"ls output"}]}}""",
            """{"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t", "content": "ls output"}]}}""",
        )) {
            assertFalse(worthParsing(echo), echo)
        }
    }

    @Test fun prefilterKeepsTheActionLines() {
        for (line in ACTIONS.rustLines().drop(1)) assertEquals("has been updated" !in line, worthParsing(line), line)
        assertFalse(worthParsing("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":"ls output"}]}}"""))
        assertFalse(worthParsing("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}"""))
    }

    @Test fun stripRemindersCutsEveryBlock() {
        assertEquals("a", stripReminders("a"))
        assertEquals("ab", stripReminders("<system-reminder>x</system-reminder>a<system-reminder>y</system-reminder>b"))
        assertEquals("a", stripReminders("a<system-reminder>never closed"))
        assertEquals("a</system-reminder>b", stripReminders("a</system-reminder>b"))
    }

    @Test fun errorsKeepTheLastEight() {
        val cp = extract((0 until 12).joinToString("\n") { """{"type":"assistant","message":{"content":"Error $it"}}""" })
        assertEquals(8, cp.errors.size)
        assertEquals("Error 4", cp.errors[0])
        assertEquals("Error 11", cp.errors[7])
    }

    @Test fun threeArchivedResultsYieldIdLinesOnRestore() {
        val path = transcript(tmpDir("t582"), FIXTURE)
        rt("t582").use { r ->
            val ids =
                listOf("one-result", "two-result", "three-result")
                    .mapIndexed { i, b ->
                        r.store.putArchive("t582", b.encodeToByteArray(), r.config.core.archiveDir).also { r.store.putArchiveDecision("tu$i", it, "t582", "ptr") }
                    }.reversed()
            save(path, Ctx(r))
            val body = assertNotNull(r.store.latestNote("checkpoint:t582"))
            ids.forEach { id -> assertTrue(body.rustLines().any { it.startsWith("id $id - ") }, "missing $id in $body") }
            assertEquals(3, body.rustLines().count { it.startsWith("id ") }, body)
            val text = assertNotNull(offer(Ctx(r))).text
            ids.forEach { assertTrue("id $it - " in text, text) }
        }
    }

    @Test fun sessionEndNoteAndStartupRecall() {
        val repo = "${tmpDir("t712")}/myproj"
        createDirAll("$repo/.git")
        val path = transcript(repo, FIXTURE)
        rt("t712", repo).use { r ->
            saveSession(path, Ctx(r))
            val body = assertNotNull(r.store.latestSessionNote("myproj"))
            assertTrue(body.startsWith("checkpoint\n"), body)
            listOf("src/a.rs", "src/b.rs", "src/c.rs").forEach { assertTrue(it in body, body) }
            val cp = extract(FIXTURE)
            assertEquals(
                Json.parseToJsonElement("""{"typed":${cp.typed},"skipped":${cp.skipped}}"""),
                Json.parseToJsonElement(assertNotNull(r.store.kvGet(pluginStateKey("memory", "session:t712")))),
            )
            // Runtime.inMemory owns its store, so the next session's restore reads the same one.
            val db = r.store
            val on1 = assertNotNull(offerSession(Ctx(r))).text
            val on2 = assertNotNull(offerSession(Ctx(r))).text
            assertEquals(on1, on2, "byte-stable for an unchanged store")
            listOf("src/a.rs", "src/b.rs", "src/c.rs").forEach { assertTrue(it in on1, on1) }
            assertTrue(r.estimate(on1, Class.Prose) <= r.config.plugins.memory.checkpointTokens, on1)
            assertEquals(2, db.listMeasurements("memory").count { it.kind == "handoff" }, "one measurement per startup restore")
        }
    }

    @Test fun privateTextNeverReachesAStoredOrRestoredCheckpoint() {
        val long = "z".repeat(400)

        fun user(c: String) =
            buildJsonObject {
                put("type", "user")
                putJsonObject("message") { put("content", c) }
            }.toString()
        val lines =
            listOf(
                user("use <private>PRIVATE_SENTINEL</private> briefly"),
                user("mixed <PrIvAtE>\nPRIVATE_SENTINEL\nmultiline</pRiVaTe> case"),
                user("long <private>$long PRIVATE_SENTINEL</private> tail"),
                buildJsonObject {
                    put("type", "assistant")
                    putJsonObject("message") {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "tool_result")
                                put("content", "error: <private>$long PRIVATE_SENTINEL</private>")
                            }
                        }
                    }
                }.toString(),
                user("plain prompt stays"),
            ).joinToString("\n")
        val cp = extract(lines)
        assertFalse("PRIVATE_SENTINEL" in cp.render(), cp.render())
        assertTrue("plain prompt stays" in cp.render())
        val repo = "${tmpDir("t548")}/privproj"
        createDirAll("$repo/.git")
        val path = transcript(repo, lines)
        rt("t548", repo).use { r ->
            save(path, Ctx(r))
            saveSession(path, Ctx(r))
            for (note in listOf(assertNotNull(r.store.latestNote("checkpoint:t548")), assertNotNull(r.store.latestSessionNote("privproj")))) {
                assertFalse("PRIVATE_SENTINEL" in note, note)
                assertTrue("plain prompt stays" in note, note)
            }
        }
        rt("t548-legacy").use { r ->
            val cx = Ctx(r)
            cx.upsertNote("rtok", "checkpoint:${cx.session()}", "compact", "checkpoint\n- old <private>PRIVATE_SENTINEL</private> row\n")
            val text = assertNotNull(offer(cx)).text
            assertFalse("PRIVATE_SENTINEL" in text, text)
        }
    }

    @Test fun handoffProjectIsOneNameAcrossWorktreesAndLegacyIsPerCheckout() {
        val (main, wt) = worktreeLayout(tmpDir("t660"))

        fun nameAt(cwd: String) = rt("t660-name", cwd).use { r -> Ctx(r).let { projectOf(it) to legacyProjectOf(it) } }
        val m = nameAt(main)
        val w = nameAt(wt)
        assertEquals(m.first, w.first)
        assertEquals("repo", m.first)
        assertEquals("repo", m.second)
        assertEquals("repo-t1", w.second)
    }

    @Test fun offerFitsCheckpointTokens() =
        rt("cap").use { r ->
            val cx = Ctx(r)
            val cap =
                r.config.plugins.memory.checkpointTokens
                    .coerceAtLeast(1u)
            cx.upsertNote("rtok", "checkpoint:${cx.session()}", "compact", "checkpoint\n".repeat(4000))
            val inj = assertNotNull(offer(cx))
            assertTrue(r.estimate(inj.text, Class.Prose) <= cap)
            assertTrue(inj.text.startsWith("checkpoint\n"))
            var many = Checkpoint(prompts = listOf("x".repeat(80)))
            for (i in 0 until 200) {
                val next = many.copy(ids = many.ids + "id${i.toString().padStart(4, '0')}", idMeta = many.idMeta + ("Read" to 9999uL))
                if (r.estimate(next.render(), Class.Prose) > cap) break
                many = next
            }
            assertTrue(many.ids.isNotEmpty())
            assertTrue(r.estimate(many.render(), Class.Prose) <= cap)
            cx.upsertNote("rtok", "checkpoint:${cx.session()}", "compact", many.render())
            assertTrue(r.estimate(assertNotNull(offer(cx)).text, Class.Prose) <= cap)
        }

    @Test fun streamedAndInMemoryExtractionAgreeOnALargeTranscript() {
        val content = bigTranscript(5 * 1024 * 1024)
        val path = transcript(tmpDir("t203"), content)
        val expected = extract(content)
        assertTrue(expected.paths.isNotEmpty() && expected.errors.isNotEmpty())
        assertEquals(expected, extractPath(path))
    }

    @Test fun prefilterIsATrueSupersetOnFixturesAndALargeTranscript() {
        for ((name, content) in listOf(
            "FIXTURE" to FIXTURE,
            "HOST_FIXTURE" to HOST_FIXTURE,
            "ACTIONS" to ACTIONS,
            "skill fixture" to skillFixture().second,
            "2 MB transcript" to bigTranscript(2 * 1024 * 1024),
        )) {
            val (f, u) = both(content)
            assertEquals(u, f, "prefilter dropped something on $name")
        }
    }

    @Test fun shlexSplitsLikeTheRustCrate() {
        assertEquals(listOf("a", "b c", "d\$e", "f\\g", "h"), Shlex.split("a 'b c' \"d\\\$e\" \"f\\g\" h # comment"))
        assertNull(Shlex.split("rm 'x"))
        assertNull(Shlex.split("a\\"))
    }

    @Test fun kibRoundsHalfToEvenLikeRustFormatting() {
        assertEquals(listOf("0.2", "0.8", "1.2", "1.8", "0.0", "5.0"), listOf(256uL, 768uL, 1280uL, 1792uL, 51uL, 5120uL).map(::kib1))
    }
}

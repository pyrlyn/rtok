// Transcript fixtures of src/plugins/checkpoint.rs tests.
package rtok.plugins.checkpoint

import kotlinx.serialization.json.*

internal const val FIXTURE = """{"type":"user","message":{"role":"user","content":"edit the three files"}}
{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"src/a.rs"}},{"type":"tool_use","name":"Read","input":{"file_path":"src/b.rs"}},{"type":"tool_use","name":"Read","input":{"file_path":"src/c.rs"}}]}}
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"still failing with error: boom"}]}}
"""

internal const val HOST_FIXTURE = """{"type":"user","origin":{"kind":"human"},"message":{"content":"first typed"}}
{"type":"user","origin":{"kind":"task-notification","producer":"session-task"},"message":{"content":"<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n</task-notification>"}}
{"type":"user","isMeta":true,"origin":{"kind":"peer","from":"a1"},"message":{"content":[{"type":"text","text":"Another Claude session sent a message:\n<agent-message from=\"a1\">done</agent-message>"}]}}
{"type":"user","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"message":{"content":"This session is being continued from a previous conversation."}}
{"type":"user","isMeta":true,"message":{"content":"Your response above was cut off."}}
{"type":"user","message":{"content":"<ci-monitor-event>\"Auto-fix\" is on</ci-monitor-event>"}}
{"type":"user","message":{"content":"<local-command-stdout>ok</local-command-stdout>"}}
{"type":"user","isMeta":true,"message":{"content":"<local-command-caveat>Caveat</local-command-caveat>"}}
{"type":"user","message":{"content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}
{"type":"user","message":{"content":"<task-notification>\n<task-id>old</task-id>\n</task-notification>"}}
{"type":"user","message":{"content":"<system-reminder>\nonly host context\n</system-reminder>"}}
{"type":"user","origin":{"kind":"human"},"message":{"content":[{"type":"text","text":"<system-reminder>\nThe user started task_1.\n</system-reminder>\nsecond typed"}]}}
{"type":"user","origin":{"kind":"human"},"message":{"content":"third <system-reminder>a</system-reminder>typed<system-reminder>\nunclosed tail"}}
"""

internal const val ACTIONS = """{"type":"user","message":{"role":"user","content":"fix a, rewrite b"}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/r/src/a.rs","offset":10,"limit":50}},{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/r/src/b.rs"}}]}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"/r/src/b.rs","old_string":"x","new_string":"y"}}]}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t4","name":"Write","input":{"file_path":"/r/src/new.rs","content":"z"}},{"type":"tool_use","id":"t5","name":"Write","input":{"file_path":"/r/src/old.rs","content":"z"}}]}}
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t4","content":"File created successfully at: /r/src/new.rs"}]},"toolUseResult":{"type":"create","filePath":"/r/src/new.rs"}}
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t5","content":"The file /r/src/old.rs has been updated successfully."}]},"toolUseResult":{"filePath":"/r/src/old.rs"}}
{"type":"assistant","cwd":"/r","message":{"content":[{"type":"tool_use","id":"t6","name":"Bash","input":{"command":"rm -f src/gone.rs && git rm src/c.rs"}}]}}
"""

internal fun skillFixture(): Pair<String, String> {
    val body = "Base directory for this skill: /home/u/.claude/skills/pixel\n\n# Pixel\n" + "x".repeat(5000)

    fun meta(
        id: String,
        text: String,
    ) = buildJsonObject {
        put("type", "user")
        put("isMeta", true)
        put("sourceToolUseID", id)
        putJsonObject("message") {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }
    }.toString()
    return body to
        listOf(
            """{"type":"user","message":{"content":"make it blue"}}""",
            meta("toolu_1", body),
            meta("toolu_2", "Base directory for this skill: C:\\u\\.claude\\plugins\\cache\\p\\1.0\\skills\\ponytail\n\n# P"),
            """{"type":"user","isMeta":true,"message":{"content":"Base directory for this skill: /x/y"}}""",
        ).joinToString("\n")
}

private fun obj(s: String) = Json.parseToJsonElement(s)

/** A transcript of about [targetBytes]: mostly tool output and prose, a prompt/path/error every 500 records. */
internal fun bigTranscript(targetBytes: Int): String {
    val toolOutput = "line of bash output ".repeat(6)
    val prose = "lorem ipsum dolor sit amet consectetur adipiscing elit ".repeat(3)
    val s = StringBuilder()
    var i = 0
    while (s.length < targetBytes) {
        val lines =
            when {
                i % 500 == 0 -> {
                    listOf(
                        buildJsonObject {
                            put("type", "user")
                            putJsonObject("message") { put("content", "prompt $i") }
                        },
                        obj("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"src/file$i.rs"}}]}}"""),
                        obj("""{"type":"user","message":{"content":[{"type":"text","text":"still failing with error: boom $i"}]}}"""),
                    )
                }

                i % 2 == 0 -> {
                    listOf(
                        buildJsonObject {
                            put("type", "user")
                            putJsonObject("message") {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", "toolu_$i")
                                        put("content", toolOutput)
                                    }
                                }
                            }
                        },
                    )
                }

                else -> {
                    listOf(
                        buildJsonObject {
                            put("type", "assistant")
                            putJsonObject("message") {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", prose)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        lines.forEach { s.append(it.toString()).append('\n') }
        i++
    }
    return s.toString()
}

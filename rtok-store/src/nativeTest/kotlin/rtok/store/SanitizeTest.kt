package rtok.store

import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*

class SanitizeTest {
    private val textCases =
        listOf(
            "\u001b[31mred\u001b[0m" to "red",
            "a\u001b[2K\u001b[1Gb" to "ab",
            "\u001b]8;;https://x\u0007link\u001b]8;;\u0007" to "link",
            "\u001b]8;;https://x\u001b\\link\u001b]8;;\u001b\\" to "link",
            "a\u001b=b" to "ab",
            "\u001bé" to "é",
            "a\u001b[" to "a",
            "a\u001b[ёж" to "a",
            "a\u0007b\u0000c\u0008d\u007fe" to "abcde",
            "a\tb" to "a\tb",
            "a\r\nb\r\n" to "a\nb\n",
            "a\rb" to "a\nb",
            "a\u200bb\u2060c\ufeff" to "abc",
            "👩\u200d💻" to "👩\u200d💻",
            "a  \nb\t\n" to "a\nb\n",
            "    fn x() {\n\tlet y;\n}" to "    fn x() {\n\tlet y;\n}",
            "a\n\n\n\n\nb" to "a\n\nb",
            "a\n  \n \t\n\nb" to "a\n\nb",
            "a\n\nb" to "a\n\nb",
            "<system-reminder>\nYou are in a worktree.\n</system-reminder>\n\n\nfix the test" to "fix the test",
            "<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n</task-notification>" to "",
            "<ci-monitor-event>pr 7 green</ci-monitor-event>\nok" to "ok",
            "<local-command-caveat>Caveat: x</local-command-caveat>\nls" to "ls",
            "a <system-reminder>x</system-reminder> b" to "a  b",
            "<system-reminder>1</system-reminder>\nmid\n<system-reminder>2</system-reminder>\nend" to "mid\nend",
            "<system-reminder>x</task-notification>" to "<system-reminder>x</task-notification>",
            "<system-reminder> x" to "<system-reminder> x",
            "<task-id>b1</task-id>" to "<task-id>b1</task-id>",
            "<system-\u001b[0mreminder>hidden</system-reminder>\nvisible" to "visible",
            "<system-\u200breminder>hidden</system-reminder>\nvisible" to "visible",
            "<system-reminder>hidden</system\ufeff-reminder>\nvisible" to "visible",
            "<system-<task-notification>x</task-notification>reminder>hidden</system-reminder>\nvisible" to "visible",
            "<system-reminder>a<system-reminder>b</system-<task-notification>x</task-notification>reminder>\nvisible" to "visible",
            "<system-\u200breminder> x" to "<system-reminder> x",
        )

    @Test fun textDropsEachKindOfNoise() =
        textCases.forEach { (input, want) ->
            val got = Sanitize.text(input)
            assertEquals(want, got, input)
            assertEquals(got, Sanitize.text(got), "twice: $input")
        }

    @Test fun terminalNoiseTouchesNothingElse() =
        listOf(
            "\u001b[31mred\u001b[0m \u001b]8;;u\u0007l\u001b]8;;\u0007" to "red l",
            "a\u0007b\u0000c\u200bd\ufeffe\u2060" to "abcde",
            "a  \r\n\r\n\n\n\tb \t\n" to "a  \r\n\r\n\n\n\tb \t\n",
            "<system-reminder>\n\u001b[1mx\u001b[0m\n</system-reminder>\n\n\n" to "<system-reminder>\nx\n</system-reminder>\n\n\n",
            "👩\u200d💻" to "👩\u200d💻",
        ).forEach { (input, want) ->
            val got = Sanitize.terminalNoise(input)
            assertEquals(want, got)
            assertEquals(got, Sanitize.terminalNoise(got))
        }

    @Test fun cleanTextIsTheSameInstance() {
        val s = "plain text\n\nwith one blank line and\ttabs"
        assertSame(s, Sanitize.text(s))
        val t = "a  \r\nb\t"
        assertSame(t, Sanitize.terminalNoise(t))
    }

    @Test fun jsonStringsAreCleanedAndEverythingElseKept() {
        val raw = """{"tool_input":{"command":"ls  \r\n"},"n":1.5,"ok":true,"none":null,"list":["\u001b[1mA\u001b[0m",2],"key  \u0007":"v"}"""
        val got = Json.parseToJsonElement(Sanitize.body(raw.encodeToByteArray()).decodeToString())
        val want =
            buildJsonObject {
                put("tool_input", buildJsonObject { put("command", "ls\n") })
                put("n", 1.5)
                put("ok", true)
                put("none", JsonNull)
                put(
                    "list",
                    buildJsonArray {
                        add("A")
                        add(2)
                    },
                )
                put("key  \u0007", "v")
            }
        assertEquals(want, got)
    }

    @Test fun aCleanJsonBodyKeepsItsBytes() {
        val raw = """{ "z": "a", "a": [1, 2] }""".encodeToByteArray()
        assertSame(raw, Sanitize.body(raw))
    }

    @Test fun plainTextBodiesAreCleanedAsText() = assertEquals("ok\n", Sanitize.body("\u001b[32mok\u001b[0m  \r\n".encodeToByteArray()).decodeToString())

    @Test fun invalidUtf8IsKeptAsIs() {
        val raw = byteArrayOf(0xff.toByte(), 0x1b, '['.code.toByte(), '3'.code.toByte(), '1'.code.toByte(), 'm'.code.toByte(), 0xfe.toByte())
        assertSame(raw, Sanitize.body(raw))
    }

    @Test fun aRealHookPromptLosesItsWrapper() {
        val raw = """{"session_id":"s1","hook_event_name":"UserPromptSubmit","prompt":"<system-reminder>\nYou are operating in a git worktree.\n</system-reminder>\n\n\nIn rtok, run the tests  "}"""
        val got = Json.parseToJsonElement(Sanitize.body(raw.encodeToByteArray()).decodeToString()).jsonObject
        assertEquals("In rtok, run the tests", got["prompt"]!!.jsonPrimitive.content)
        assertEquals("s1", got["session_id"]!!.jsonPrimitive.content)
    }

    @Test fun jsonBodiesReachTheCanonicalFormInOnePass() {
        val once = Sanitize.body("""{"prompt":"<system-\u001b[0mreminder>hidden</system-reminder>\nvisible"}""".encodeToByteArray())
        assertEquals("""{"prompt":"visible"}""", once.decodeToString())
        assertSame(once, Sanitize.body(once))
    }

    private val wrapper = Regex("(?s)(?:" + listOf("system-reminder", "task-notification", "ci-monitor-event", "local-command-caveat").joinToString("|") { "<$it>.*?</$it>" } + ")\n*")

    private fun byFixedPoint(s: String): String {
        // Inputs here carry no `\r`, so `characters(s, true)` is `terminalNoise(s)`.
        var cur = Sanitize.terminalNoise(s)
        while (true) {
            val next = wrapper.replace(cur, "")
            if (next == cur) return wsOnly(cur)
            cur = next
        }
    }

    private fun wsOnly(s: String): String {
        val out = StringBuilder()
        var newlines = 0
        s.split('\n').forEachIndexed { n, raw ->
            if (n > 0) {
                newlines++
                if (newlines <= 2) out.append('\n')
            }
            val l = raw.trimEnd(' ', '\t')
            if (l.isNotEmpty()) {
                newlines = 0
                out.append(l)
            }
        }
        return out.toString()
    }

    @Test fun aJoinedOpenerDoesNotPairWithALaterCloser() {
        val s = "<system-<ci-monitor-event></ci-monitor-event>reminder><system-reminder></system-reminder>"
        assertEquals("<system-reminder>", byFixedPoint(s))
        assertEquals("<system-reminder>", Sanitize.text(s))
    }

    @Test fun wrappersAgreeWithTheFixedPointOnRandomInput() {
        val parts =
            listOf(
                "<system-",
                "reminder>",
                "</system-",
                "<task-notification>",
                "</task-notification>",
                "<system-reminder>",
                "</system-reminder>",
                "\u200b",
                "\u001b[0m",
                "\n",
                "x",
                " ",
                "<ci-monitor-event>",
                "</ci-monitor-event>",
            )
        val rnd = Random(7)
        repeat(2000) {
            val s = (0 until rnd.nextInt(24)).joinToString("") { parts[rnd.nextInt(parts.size)] }
            val got = Sanitize.text(s)
            assertEquals(byFixedPoint(s), got, s)
            assertEquals(got, Sanitize.text(got))
        }
    }
}

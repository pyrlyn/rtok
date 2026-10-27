// Port of the per-fold tests of src/measure/stats.rs: whole/denied/delta/repeat reads, Grep answers, re-expands, the
// archive replay and Bash families.
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.measure.stats

import rtok.MIN_PREFIX
import rtok.measure.estTokens
import rtok.measure.utf8Len
import rtok.plugins.guard.GrepSymbol
import rtok.plugins.read.ReadHook
import rtok.shortId
import rtok.sys.createDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsFoldTest {
    /** T136: of five Reads only the two whole, large, outline-able ones count; the edited one is split out. */
    @Test fun readWholeCountsLargeUnrangedReadsOfOutlinedFiles() {
        val big = "x".repeat(40_000)
        val lines =
            listOf(
                toolCall("t1", "Read", """{"file_path":"src/a.rs"}"""),
                toolResult("t1", big),
                toolCall("t2", "Read", """{"file_path":"src/b.rs"}"""),
                toolResult("t2", big),
                toolCall("t3", "Edit", """{"file_path":"src/b.rs","old_string":"x","new_string":"y"}"""),
                toolCall("t4", "Read", """{"file_path":"src/c.rs","limit":50}"""),
                toolResult("t4", big),
                toolCall("t5", "Read", """{"file_path":"notes.txt"}"""),
                toolResult("t5", big),
                toolCall("t6", "Read", """{"file_path":"src/small.rs"}"""),
                toolResult("t6", "fn main() {}"),
            )
        val r = writeAndCollect(statsDir(), "w.jsonl", lines)
        val d = r.readWhole
        assertEquals(listOf(2L, 80_000L, 1L, 40_000L), listOf(d.calls, d.bytes, d.edited, d.editedBytes))
        assertEquals(160_012, d.readBytes)
        val table = r.toTable()
        assertTrue(
            "read whole calls 2  bytes 80000  50.0% of Read  50.0% of results  not edited 1 bytes 25.0% of results  edited after 1" in table,
            table,
        )
    }

    /** T383: denied Reads, classed by what the agent did within the next three calls. */
    @Test fun readDenyCountsDeniesAndWhatFollowed() {
        val deny = "PreToolUse:Read hook error: ${ReadHook.REASON}"
        val lines =
            listOf(
                toolCall("t1", "Read", """{"file_path":"src/a.rs"}"""),
                toolResult("t1", deny),
                toolCall("t2", "mcp__rtok__read", """{"path":"/proj/src/a.rs"}"""),
                toolResult("t2", "body"),
                toolCall("t3", "Read", """{"file_path":"src/b.rs"}"""),
                toolResult("t3", deny),
                toolCall("t4", "Read", """{"file_path":"src/b.rs","limit":50}"""),
                toolResult("t4", "body"),
                toolCall("t5", "Read", """{"file_path":"src/c.rs"}"""),
                toolResult("t5", deny),
                toolCall("t6", "ToolSearch", """{"query":"select:mcp__rtok__read"}"""),
                toolCall("t7", "Bash", """{"command":"ls"}"""),
                toolCall("t8", "Bash", """{"command":"ls"}"""),
                toolCall("t9", "mcp__rtok__read", """{"path":"src/c.rs"}"""),
                toolResult("t9", "body"),
                toolCall("t10", "Read", """{"file_path":"src/d.rs"}"""),
                toolResult("t10", "fine"),
            )
        val r = writeAndCollect(statsDir(), "deny.jsonl", lines)
        val d = r.readDeny
        val tokens = estTokens(utf8Len(deny))
        assertEquals(listOf(3L, 3 * tokens, 1L, 1L, 1L), listOf(d.calls, d.tokens, d.thenMcp, d.thenNative, d.thenToolsearch))
        assertTrue(isReadDeny("use rtok read; before Edit run native Read(limit=1) — it satisfies the edit gate"))
        assertTrue(isReadDeny("file changed since last read; use mcp__rtok__read(mode=diff) vs ab12cd34"))
        assertTrue(hasRtokMarker("use mcp__rtok__read (ToolSearch select:mcp__rtok__read loads it); ${ReadHook.REASON}"))
        val table = r.toTable()
        assertTrue("read deny calls 3  tokens ${3 * tokens}  then rtok read 1  then native Read 1  then ToolSearch 1" in table, table)
        assertAbsentWhenEmpty("read deny", "read_deny")
    }

    /** T369.1: answered Greps, followed by a read of a listed file or a same-name search within three calls. */
    @Test fun grepSymbolCountsAnswersAndSameNameFollowUps() {
        fun answer(
            name: String,
            path: String,
        ) = "Grep `$name` ${GrepSymbol.ANSWERED}: 1 def, 2 refs (`callers`)\n$path::$name#function@1 $path:1 function\npub fn $name() {}\n"
        val lib = answer("parse_since", "src/lib.rs")
        val other = answer("other_fn", "src/b.rs")
        val lines =
            listOf(
                toolCall("t1", "Grep", """{"pattern":"fn parse_since"}"""),
                toolResult("t1", lib),
                toolCall("t2", "Read", """{"file_path":"/proj/src/lib.rs"}"""),
                toolCall("t3", "Grep", """{"pattern":"\\bparse_since\\("}"""),
                toolResult("t3", lib),
                toolCall("t4", "Bash", """{"command":"ls"}"""),
                toolCall("t5", "Bash", """{"command":"rg -n parse_since src"}"""),
                toolCall("t6", "Grep", """{"pattern":"other_fn"}"""),
                toolResult("t6", other),
                toolCall("t7", "mcp__rtok__read", """{"path":"/proj/src/c.rs"}"""),
                toolCall("t8", "Bash", """{"command":"echo other_fn"}"""),
                toolCall("t9", "Bash", """{"command":"ls"}"""),
                toolCall("t10", "Grep", """{"pattern":"other_fn"}"""),
                toolResult("t10", "src/b.rs:1:pub fn other_fn() {}"),
            )
        val r = writeAndCollect(statsDir(), "grep.jsonl", lines)
        val g = r.grepSymbol
        val tokens = 2 * estTokens(utf8Len(lib)) + estTokens(utf8Len(other))
        assertEquals(Triple(3L, tokens, 2L), Triple(g.calls, g.tokens, g.followed))
        val table = r.toTable()
        assertTrue("grep symbol answers 3  tokens $tokens  then same-name Grep/Read 2 (66.7%)" in table, table)
        assertAbsentWhenEmpty("grep symbol", "grep_symbol")
    }

    @Test fun readDeltaCountsRereadAfterEditNotUnchangedReread() {
        val lines =
            listOf(
                toolCall("t1", "Read", """{"file_path":"src/a.rs"}"""),
                toolResult("t1", "0123456789"),
                toolCall("t2", "Edit", """{"file_path":"/proj/src/a.rs","old_string":"x","new_string":"y"}"""),
                toolCall("t3", "Read", """{"file_path":"src/a.rs"}"""),
                toolResult("t3", "01234567890123456789"),
                toolCall("t4", "Read", """{"file_path":"src/a.rs"}"""),
                toolResult("t4", "same"),
                toolCall("t5", "Read", """{"file_path":"/b.rs"}"""),
                toolResult("t5", "bbbb"),
            )
        val r = writeAndCollect(statsDir(), "d.jsonl", lines)
        val d = r.readDelta
        assertEquals(Triple(1L, 20L, 38L), Triple(d.calls, d.bytes, d.readBytes))
        assertTrue("read delta calls 1  bytes 20  of Read bytes 38  52.6%" in r.toTable(), r.toTable())
    }

    @Test fun repeatCountsIdenticalBodiesFromDifferentTools() {
        val lines =
            listOf(
                toolCall("t1", "Bash", """{"command":"cat a"}"""),
                toolResult("t1", "01234567890123456789"),
                toolCall("t2", "Read", """{"file_path":"a"}"""),
                toolResult("t2", "01234567890123456789"),
                toolCall("t3", "Bash", """{"command":"head -1000 a"}"""),
                toolResult("t3", "different-output"),
            )
        val r = writeAndCollect(statsDir(), "r.jsonl", lines)
        val d = r.repeat
        assertEquals(Triple(1L, 20L, 56L), Triple(d.calls, d.bytes, d.resultBytes))
        assertTrue("repeat calls 1  bytes 20  of result bytes 56  35.7%" in r.toTable(), r.toTable())
    }

    /** T179: one repeat native Read per class, across three sessions and a sub-agent. */
    @Test fun repeatReadsClassifyEachKindOnce() {
        val dir = statsDir()

        fun pair(
            id: String,
            input: String,
            body: String,
        ) = listOf(toolCall(id, "Read", input), toolResult(id, body))

        fun write(
            path: String,
            lines: List<String>,
        ) = writeString(path, lines.joinToString("") { "$it\n" })
        val firedBody = "[archived aa11: 1 lines · 1 tokens · expand(aa11)]"
        write(
            "$dir/sessA.jsonl",
            pair("t1", """{"file_path":"fA.rs"}""", "base") + pair("t2", """{"file_path":"fA.rs"}""", firedBody) +
                pair("t3", """{"file_path":"gA.rs"}""", "base-g") + listOf(toolCall("t4", "Edit", """{"file_path":"gA.rs"}""")) +
                pair("t5", """{"file_path":"gA.rs"}""", "chg") + pair("t6", """{"file_path":"hA.rs","limit":100}""", "bh") +
                pair("t7", """{"file_path":"hA.rs","limit":5}""", "rng") + pair("t8", """{"file_path":"iA.rs"}""", "base-i") +
                pair("t9", """{"file_path":"iA.rs"}""", "dec"),
        )
        write("$dir/sessB.jsonl", pair("u1", """{"file_path":"kB.rs"}""", "base-k") + pair("u2", """{"file_path":"kB.rs"}""", "abs"))
        write("$dir/sessC.jsonl", pair("v1", """{"file_path":"pC.rs"}""", "par") + listOf(toolCall("v2", "Agent", "{}")))
        val subs = "$dir/sessC/subagents"
        createDirAll(subs)
        write("$subs/agent-1.jsonl", pair("w1", """{"file_path":"pC.rs"}""", "sub"))
        writeString("$subs/agent-1.meta.json", """{"agentType":"explore","model":"haiku","toolUseId":"v2"}""")
        val r = collect(dir, cutoff(), "", defaultReplay())
        val d = r.repeatReads
        for ((got, want) in listOf(d.fired to utf8Len(firedBody), d.changed to 3L, d.ranged to 3L, d.declined to 3L, d.hookAbsent to 3L, d.subagent to 3L)) {
            assertEquals(1L to want, got.calls to got.bytes, d.toString())
        }
        assertTrue("repeat reads  total 6" in r.toTable())
    }

    @Test fun bashTouchesNeedsAWriteSignalNotJustANamedRead() {
        val cases =
            listOf(
                "cat a.rs" to false,
                "grep foo a.rs" to false,
                "warm-cache a.rs" to false,
                "sed -i '' 's/x/y/' a.rs" to true,
                "echo hi > a.rs" to true,
                "git checkout -- ." to true,
            )
        for ((cmd, want) in cases) assertEquals(want, bashTouches(cmd, "a.rs"), cmd)
    }

    @Test fun expandRightAfterCountsExpandsOfShownIdsOnly() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val shown = "x\n… 23 lines omitted (expand $a)"
        val lines =
            listOf(
                toolCall("t1", "Bash", """{"command":"grep -A3 x f"}"""),
                toolResult("t1", shown),
                toolCall("t2", "Bash", """{"command":"rtok expand $a --lines 1-9"}"""),
                toolResult("t2", "0123456789"),
                toolCall("t3", "mcp__rtok__expand", """{"id":"$a"}"""),
                toolResult("t3", "01234"),
                toolCall("t4", "mcp__rtok__expand", """{"id":"$b"}"""),
                toolResult("t4", "never shown"),
            )
        val r = writeAndCollect(statsDir(), "e.jsonl", lines)
        val d = r.expandAfter
        val want = utf8Len(shown)
        assertEquals(Triple(2L, 15L, want), Triple(d.calls, d.bytes, d.shownBytes))
        assertTrue("expand right after  calls 2  bytes 15  shown bytes $want" in r.toTable(), r.toTable())
    }

    /** T911: pointers print 8 hex chars; a later full-id expand matches the short one. */
    @Test fun expandRightAfterCountsTheEightCharIdPointersPrint() {
        val full = "ab".repeat(32)
        val short = shortId(full)
        assertEquals(MIN_PREFIX, short.length)
        val other = "cd".repeat(32)
        val tiny = "abc1234"
        val shown = "[rtok $short · 9 lines]"
        val lines =
            listOf(
                toolCall("t1", "Bash", """{"command":"ls"}"""),
                toolResult("t1", shown),
                toolCall("t2", "Bash", """{"command":"rtok expand $short"}"""),
                toolResult("t2", "restored"),
                toolCall("t3", "mcp__rtok__expand", """{"id":"$full"}"""),
                toolResult("t3", "more"),
                toolCall("t4", "Bash", """{"command":"cat f"}"""),
                toolResult("t4", "… 1 lines omitted (expand $tiny)"),
                toolCall("t5", "Bash", """{"command":"rtok expand $tiny"}"""),
                toolResult("t5", "too-short"),
                toolCall("t6", "mcp__rtok__expand", """{"id":"$other"}"""),
                toolResult("t6", "other"),
            )
        val d = writeAndCollect(statsDir(), "e.jsonl", lines).expandAfter
        assertEquals(Triple(2L, 12L, utf8Len(shown)), Triple(d.calls, d.bytes, d.shownBytes))
    }

    private val rp = Replay(keepTurns = 2, minTokens = 100, headLines = 1, tailLines = 1)
    private val content = "x".repeat(50) + "\n" + "y".repeat(500) + "\n" + "z".repeat(50)

    @Test fun archiveReplayKeepsYoungTurnsAndShrinksOldOnes() {
        val tokens = estTokens(utf8Len(content))
        assertEquals(tokens * 2, replayCtt(content, tokens, 2, rp))
        assertEquals(50, replayCtt(content, 10, 5, rp))
        val after = replayCtt(content, tokens, 5, rp)
        assertEquals(tokens * 2 + estTokens(51 + 51 + 64) * 3, after)
        assertTrue(after < tokens * 5)
    }

    @Test fun archiveReplayNeverDoublesShortBodies() {
        val single = "y".repeat(600)
        val tokens = estTokens(600)
        assertTrue(replayCtt(single, tokens, 5, rp) <= tokens * 5)
        val t2 = estTokens(utf8Len(content))
        assertEquals(t2 * 2 + estTokens(51 + 51 + 64) * 3, replayCtt(content, t2, 5, rp))
    }

    @Test fun bashFamilyStripsCdAndEnv() {
        assertEquals("git", bashFamily("cd /tmp && git status"))
        assertEquals("grep", bashFamily("FOO=1 grep x"))
        assertEquals("sed", bashFamily("sed -n 1p"))
        assertEquals("git", bashFamily("C:\\Git\\cmd\\git.exe status"))
        assertEquals("cargo", bashFamily("cargo.exe test"))
    }

    @Test fun bashFamilySeesThroughMiseExec() {
        assertEquals("cargo", bashFamily("mise exec -- cargo test"))
        assertEquals("git", bashFamily("mise x node@20 -- git status"))
        assertEquals("cargo", bashFamily("just --command cargo test"))
        assertEquals("cargo", bashFamily("cd /tmp && mise exec -- cargo test"))
        assertEquals("mise", bashFamily("mise run test"))
        assertEquals("just", bashFamily("just check"))
    }

    @Test fun bashFamilyStripsQuotedCdPaths() {
        assertEquals("git", bashFamily("cd 'My Documents' && git status"))
        assertEquals("npm", bashFamily("cd \"C:\\Program Files\\App\" && npm test"))
        assertEquals("cargo", bashFamily("cd ~/'My Documents'/src && cargo build"))
        assertEquals("rg", bashFamily("FOO=\"a b\" cd 'x y' && rg z"))
        assertEquals("sed", bashFamily("cd\t'a b'\t&& sed -n 1p"))
        assertEquals("cd", bashFamily("cd 'unterminated && git status"))
        assertEquals("FOO='x", bashFamily("FOO='x cd y && git status"))
        assertEquals("cdx", bashFamily("cdx && git status"))
    }

    @Test fun serdeFloatsAndFixedDecimals() {
        assertEquals("0.0", SerdeJson.ryu(0.0))
        assertEquals("12345678.9", SerdeJson.ryu(12345678.9))
        assertEquals("1e16", SerdeJson.ryu(1e16))
        assertEquals("1.5e-7", SerdeJson.ryu(1.5e-7))
        assertEquals("0.0001", SerdeJson.ryu(0.0001))
        assertEquals("16.875", SerdeJson.ryu(16.875))
        assertEquals("52.6", fixed(100.0 * 20 / 38, 1))
        assertEquals("11.60", fixed(11.6, 2))
    }
}

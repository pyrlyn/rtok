package rtok.plugins.graph

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import rtok.sys.PipedChild
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.pid
import rtok.sys.readBytes
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class LspTest {
    private val budget = 100L

    /** A scripted peer: `sh -c script sh arg`; every script ends on its own. */
    private fun peer(
        script: String,
        arg: String,
    ) = LspSession(PipedChild.spawn("/bin/sh", listOf("-c", script, "sh", arg)), "", "")

    private fun framed(v: JsonElement) = LspWire.frame(v).decodeToString()

    private fun reply(id: Long) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("result") { put("ok", true) }
        }

    private fun note(method: String) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            putJsonObject("params") {}
        }

    private fun expectTimeout(
        s: LspSession,
        params: JsonElement = buildJsonObject {},
    ) {
        val t0 = TimeSource.Monotonic.markNow()
        val e = assertFails { s.requestWithin("textDocument/documentSymbol", params, budget) }
        assertContains(e.message.orEmpty(), "timeout")
        assertTrue(Capability.serverBroke(e), "a timeout must reach the fallback path")
        assertTrue(t0.elapsedNow() < 1800.milliseconds, "${t0.elapsedNow()}")
        s.close()
    }

    @Test fun kindNameFollowsTheLspSymbolKindNumbers() {
        for ((k, n) in listOf(5L to "class", 6L to "method", 10L to "enum", 11L to "interface", 12L to "function", 13L to "variable", 23L to "struct", 99L to "symbol")) {
            assertEquals(n, LspWire.kindName(k), "SymbolKind $k")
        }
    }

    @Test fun dartPubspecRootPicksDartLanguageServer() {
        val dir = join(tempDir(), "rtok-lsp-dart-${pid()}")
        removeDirAll(dir)
        createDirAll(join(dir, "lib"))
        writeString(join(dir, "pubspec.yaml"), "name: dart_gate\n")
        val main = join(dir, "lib/main.dart")
        writeString(main, "void main() {}\n")
        assertEquals(LspServer("dart", listOf("language-server")), Lsp.pick(dir))
        assertEquals(dir, Lsp.workspaceOf(main))
        removeDirAll(dir)
    }

    @Test fun shutdownReapsTheCachedServerAndItsStderrFile() {
        val child = PipedChild.spawn("cat", emptyList())
        val err = join(tempDir(), "rtok-lsp-test-${child.pid}.stderr")
        writeString(err, "")
        LspSession.install("test", LspSession(child, "", err))
        LspSession.shutdown()
        assertNotNull(child.tryWait(), "server ${child.pid} still running")
        assertFalse(exists(err))
    }

    @Test fun aSilentPeerTimesOutInsteadOfBlockingOnThePipe() = expectTimeout(peer("sleep 3", ""))

    @Test fun aReplyAfterTheBudgetIsATimeoutNotASuccess() = expectTimeout(peer("sleep 1.5; printf '%s' \"\$1\"", framed(reply(1))))

    @Test fun aPartialFrameDoesNotHoldTheRequestPastTheBudget() {
        val whole = framed(reply(1))
        expectTimeout(peer("printf '%s' \"\$1\"; sleep 3", whole.dropLast(6)))
    }

    @Test fun aHeaderWithoutABodyDoesNotHoldTheRequest() = expectTimeout(peer("printf 'Content-Length: 40\\r\\n'; sleep 3", ""))

    @Test fun notificationsDoNotExtendTheBudget() = expectTimeout(peer("i=0; while [ \$i -lt 300 ]; do printf '%s' \"\$1\"; sleep 0.01; i=\$((i+1)); done", framed(note("\$/progress"))))

    @Test fun aPeerThatStopsReadingCannotBlockTheWrite() = expectTimeout(peer("sleep 3", ""), buildJsonObject { put("pad", "x".repeat(1 shl 20)) })

    @Test fun aTimelyReplyAfterANotificationSucceeds() {
        val s = peer("printf '%s' \"\$1\"; sleep 3", framed(note("window/logMessage")) + framed(reply(1)))
        assertEquals(buildJsonObject { put("ok", true) }, s.requestWithin("workspace/symbol", buildJsonObject {}, 2000))
        s.close()
    }

    @Test fun anExpiredSessionIsReapedAndRefusesFurtherRequests() {
        val s = peer("sleep 0.5; printf '%s' \"\$1\"", framed(reply(1)))
        assertFails { s.requestWithin("textDocument/documentSymbol", buildJsonObject {}, budget) }
        assertNotNull(s.child.tryWait(), "server still running")
        platform.posix.usleep(700_000u)
        assertFails { s.requestWithin("workspace/symbol", buildJsonObject {}, 1000) }
        s.close()
    }

    /** A peer that appends everything the session sends to `log`. */
    private fun recording(dir: String): Pair<LspSession, String> {
        val log = join(dir, "peer.log")
        return peer("cat > \"\$1\"", log) to log
    }

    private fun received(
        log: String,
        n: Int,
    ): List<Pair<String, JsonElement?>> {
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (true) {
            val got = LspWire.frames(runCatching { readBytes(log) }.getOrDefault(ByteArray(0))).map { it["method"].str.orEmpty() to it["params"] }
            if (got.size >= n || deadline.hasPassedNow()) return got
            platform.posix.usleep(20_000u)
        }
    }

    private fun docDir(name: String): String {
        val dir = join(tempDir(), "rtok-lsp-doc-$name-${pid()}")
        removeDirAll(dir)
        createDirAll(dir)
        return dir
    }

    private fun textOf(n: Pair<String, JsonElement?>) = n.second["textDocument"]["text"].str ?: n.second["contentChanges"][0]["text"].str.orEmpty()

    @Test fun anEditedFileReachesASyncingServerAsAFullChange() {
        val dir = docDir("change")
        val file = join(dir, "a.rs")
        writeString(file, "fn old_symbol() {}\n")
        val (s, log) = recording(dir)
        s.syncChange = 1
        val uri = LspWire.fileUri(file)
        s.didOpen(uri)
        writeString(file, "fn new_symbol() {}\n")
        s.didOpen(uri)
        val got = received(log, 2)
        assertEquals(2, got.size, "$got")
        assertEquals("textDocument/didOpen", got[0].first)
        assertEquals("fn old_symbol() {}\n", textOf(got[0]))
        assertEquals("textDocument/didChange", got[1].first)
        assertEquals(
            buildJsonObject {
                put("uri", uri)
                put("version", 2)
            },
            got[1].second["textDocument"],
        )
        assertEquals("fn new_symbol() {}\n", textOf(got[1]))
        s.close()
    }

    @Test fun aServerWithoutChangeSyncGetsACloseAndAReopen() {
        val dir = docDir("reopen")
        val file = join(dir, "a.rs")
        writeString(file, "fn one() {}\n")
        val (s, log) = recording(dir)
        val uri = LspWire.fileUri(file)
        s.didOpen(uri)
        writeString(file, "fn two() {}\n")
        s.didOpen(uri)
        val got = received(log, 3)
        assertEquals(listOf("textDocument/didOpen", "textDocument/didClose", "textDocument/didOpen"), got.map { it.first })
        assertEquals(JsonPrimitive(2), got[2].second["textDocument"]["version"])
        assertEquals("fn two() {}\n", textOf(got[2]))
        s.close()
    }

    @Test fun anUnchangedFileIsNotSentTwice() {
        val dir = docDir("same")
        val file = join(dir, "a.rs")
        writeString(file, "fn same() {}\n")
        val (s, log) = recording(dir)
        s.syncChange = 1
        val uri = LspWire.fileUri(file)
        s.didOpen(uri)
        s.didOpen(uri)
        s.refreshOpen()
        s.notify("probe", buildJsonObject {})
        assertEquals(listOf("textDocument/didOpen", "probe"), received(log, 2).map { it.first })
        s.close()
    }

    @Test fun consecutiveEditsWithUnicodeCountVersionsUp() {
        val dir = docDir("unicode")
        val file = join(dir, "a.rs")
        val (s, log) = recording(dir)
        s.syncChange = 2
        val uri = LspWire.fileUri(file)
        val texts = listOf("fn привет() {}\n", "// \uD83E\uDD80\nfn crab() {}\n", "fn plain() {}\n")
        for (t in texts) {
            writeString(file, t)
            s.didOpen(uri)
        }
        val got = received(log, 3)
        assertEquals(3, got.size, "$got")
        texts.forEachIndexed { i, t ->
            assertEquals(t, textOf(got[i]))
            assertEquals(JsonPrimitive(i + 1), got[i].second["textDocument"]["version"])
        }
        s.close()
    }

    @Test fun aDeletedFileIsClosedAndARecreatedOneIsOpenedAgain() {
        val dir = docDir("delete")
        val file = join(dir, "a.rs")
        writeString(file, "fn gone() {}\n")
        val (s, log) = recording(dir)
        s.syncChange = 1
        val uri = LspWire.fileUri(file)
        s.didOpen(uri)
        removeFile(file)
        s.didOpen(uri)
        writeString(file, "fn back() {}\n")
        s.didOpen(uri)
        val got = received(log, 3)
        assertEquals(listOf("textDocument/didOpen", "textDocument/didClose", "textDocument/didOpen"), got.map { it.first })
        assertEquals(JsonPrimitive(1), got[2].second["textDocument"]["version"])
        assertEquals("fn back() {}\n", textOf(got[2]))
        s.close()
    }

    @Test fun refreshOpenResyncsDocumentsThisQueryDoesNotTouch() {
        val dir = docDir("refresh")
        val a = join(dir, "a.rs")
        val b = join(dir, "b.rs")
        writeString(a, "fn a() {}\n")
        writeString(b, "fn b() {}\n")
        val (s, log) = recording(dir)
        s.syncChange = 1
        s.didOpen(LspWire.fileUri(a))
        s.didOpen(LspWire.fileUri(b))
        writeString(a, "fn a2() {}\n")
        s.refreshOpen()
        val got = received(log, 3)
        assertEquals(3, got.size, "$got")
        assertEquals("textDocument/didChange", got[2].first)
        assertEquals("fn a2() {}\n", textOf(got[2]))
        s.close()
    }

    @Test fun aFailedNotificationDoesNotMarkTheDocumentSynchronized() {
        val dir = docDir("failed")
        val file = join(dir, "a.rs")
        writeString(file, "fn x() {}\n")
        val (s, log) = recording(dir)
        val live = s.sink
        s.sink = { false }
        val uri = LspWire.fileUri(file)
        assertFails { s.didOpen(uri) }
        s.sink = live
        s.didOpen(uri)
        assertEquals("textDocument/didOpen", received(log, 1)[0].first)
        s.close()
    }

    @Test fun theSyncKindIsReadFromTheInitializeResult() {
        val cases =
            listOf(
                """{"textDocumentSync": 1}""" to 1,
                """{"textDocumentSync": {"openClose": true, "change": 2}}""" to 2,
                """{"textDocumentSync": {"openClose": true}}""" to 0,
                """{"textDocumentSync": 0}""" to 0,
                "{}" to 0,
            )
        for ((caps, kind) in cases) {
            assertEquals(
                kind,
                LspWire.syncKind(
                    kotlinx.serialization.json.Json
                        .parseToJsonElement("""{"capabilities": $caps}"""),
                ),
                caps,
            )
        }
    }

    @Test fun droppingASessionWhosePeerStoppedReadingDoesNotWaitForIt() {
        val s = peer("sleep 3", "")
        val t0 = TimeSource.Monotonic.markNow()
        s.notify("textDocument/didOpen", buildJsonObject { put("pad", "x".repeat(1 shl 20)) })
        s.close()
        assertTrue(t0.elapsedNow() < 1800.milliseconds, "${t0.elapsedNow()}")
    }

    @Test fun fileUriUsesForwardSlashes() {
        val dir = join(tempDir(), "rtok-uri-${pid()}")
        createDirAll(dir)
        val uri = LspWire.fileUri(dir)
        removeDirAll(dir)
        assertTrue(uri.startsWith("file://"), uri)
        assertFalse('\\' in uri, uri)
        assertTrue(LspWire.pathFromFileUri(uri).isNotEmpty())
    }

    @Test fun pathFromFileUriKeepsUnixRoot() = assertEquals("/tmp/a", LspWire.pathFromFileUri("file:///tmp/a"))

    @Test fun fileUriEncodesSpaces() {
        val encoded = LspWire.percentEncodePath("C:/Users/Ivan Tuhai/proj/a.rs")
        assertContains(encoded, "%20")
        assertFalse(' ' in encoded)
        assertTrue(encoded.startsWith("C:"), encoded)
        assertContains(LspWire.pathFromFileUri("file:///$encoded"), "Ivan Tuhai")
    }

    @Test fun pathFromFileUriDecodesPercent20() = assertEquals("/tmp/My Docs/a.rs", LspWire.pathFromFileUri("file:///tmp/My%20Docs/a.rs"))

    @Test fun fileUriEncodesHashAndQuestionInPath() {
        val encoded = LspWire.percentEncodePath("C:/tmp/a#b?.rs")
        assertContains(encoded, "%23")
        assertContains(encoded, "%3F")
        assertContains(LspWire.pathFromFileUri("file:///$encoded"), "a#b?.rs")
    }

    @Test fun miseShimIsNotAnInstalledServer() {
        assertTrue(Lsp.isMiseShim("/home/runner/.local/share/mise/shims/rust-analyzer"))
        assertFalse(Lsp.isMiseShim("/home/runner/.rustup/toolchains/stable/bin/rust-analyzer"))
    }

    @Test fun rustupProxyMatchesTheRustupBinarySize() {
        val dir = join(tempDir(), "rtok-ra-proxy-${pid()}")
        removeDirAll(dir)
        createDirAll(dir)
        val analyzer = join(dir, "rust-analyzer")
        writeString(join(dir, "rustup"), "proxy")
        writeString(analyzer, "proxy")
        assertTrue(Lsp.isRustupProxy(analyzer))
        writeString(analyzer, "a real rust-analyzer binary")
        assertFalse(Lsp.isRustupProxy(analyzer))
        removeDirAll(dir)
    }
}

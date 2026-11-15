package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import rtok.sys.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private fun pluginRow(
    out: String,
    id: String,
) = out.lines().first { it.startsWith("$id ") }

class CliE2eSpec :
    FunSpec({
        context("binary basics") {
            test("rtok run cat <file> prints the file (release build once failed: Unexpected receiver type: kotlin.Char)") {
                Sandbox("run-cat").use { s ->
                    writeString("${s.root}/c.txt", "a\nb\n")
                    s.rtok("run", "cat", "${s.root}/c.txt").also {
                        it.err shouldBe ""
                        it.code shouldBe 0
                        it.out shouldBe "a\nb\n"
                    }
                }
            }
            test("--version prints the crate version and exits 0") {
                Sandbox("version").use { s ->
                    s.rtok("--version").also {
                        it.code shouldBe 0
                        it.out shouldStartWith "rtok 0."
                        it.err shouldBe ""
                    }
                }
            }
            test("no subcommand prints usage and exits 0; an unknown one exits 2") {
                Sandbox("usage").use { s ->
                    s.rtok("--help").also {
                        it.code shouldBe 0
                        it.out shouldContain "plugins"
                    }
                    s.rtok("frobnicate").also {
                        it.code shouldBe 2
                        it.out shouldBe ""
                        it.err shouldContain "frobnicate"
                    }
                }
            }
            test("an unknown option and a missing argument are usage errors") {
                Sandbox("badopt").use { s ->
                    s.rtok("plugins", "--nope").also {
                        it.code shouldBe 2
                        it.err shouldContain "--nope"
                    }
                    s.rtok("config", "set", "only-key").also {
                        it.code shouldBe 2
                        it.err shouldContain "value"
                    }
                }
            }
        }

        context("config files on disk") {
            test("the first command creates the reference config under RTOK_HOME") {
                Sandbox("init").use { s ->
                    s.rtok("plugins").code shouldBe 0
                    exists("${s.home}/config.toml") shouldBe true
                    s.rtok("config", "path").out shouldBe "${s.home}/config.toml\n"
                }
            }
            test("config set persists, plugins reads it back, and the file stays valid") {
                Sandbox("set").use { s ->
                    s.rtok("config", "set", "plugins.measure.enabled", "false").code shouldBe 0
                    pluginRow(s.rtok("plugins").out, "measure") shouldBe "measure   off       cli,proxy"
                    s.rtok("config", "validate").also {
                        it.code shouldBe 0
                        it.err shouldBe ""
                        it.out shouldContain "plugins.measure.enabled = false (default true)"
                        it.out
                            .lines()
                            .filter(String::isNotEmpty)
                            .last() shouldBe "ok ${s.home}/config.toml"
                    }
                }
            }
            test("--config points every command at another file") {
                Sandbox("flag").use { s ->
                    writeString("${s.root}/alt.toml", "[plugins.proxy]\nenabled = false\n")
                    pluginRow(s.rtok("--config", "${s.root}/alt.toml", "plugins").out, "proxy") shouldBe "proxy     off       proxy"
                    pluginRow(s.rtok("plugins").out, "proxy") shouldBe "proxy     on        proxy"
                }
            }
            test("invalid input: a bad value, an unknown key, a broken file") {
                Sandbox("invalid").use { s ->
                    s.rtok("config", "set", "plugins.measure.enabled", "maybe").also {
                        it.code shouldBe 1
                        it.err shouldStartWith "Error: "
                    }
                    s.rtok("config", "set", "core.nope", "1").also {
                        it.code shouldBe 1
                        it.err shouldContain "unknown key"
                    }
                    writeString("${s.home}/config.toml", "[core\n")
                    s.rtok("config", "validate").also {
                        it.code shouldBe 1
                        it.err shouldNotContain "Exception"
                    }
                    s.rtok("plugins").also {
                        it.code shouldBe 1
                        it.err shouldStartWith "Error: "
                    }
                }
            }
            test("config validate writes each problem to the log file") {
                Sandbox("log").use { s ->
                    s.rtok("plugins")
                    writeString("${s.root}/bad.toml", "[core]\nbogus = 1\n")
                    s.rtok("config", "validate", "${s.root}/bad.toml").code shouldBe 1
                    readToString("${s.home}/logs/rtok.log") shouldContain "bogus"
                }
            }
        }

        context("project root discovery with real git repositories") {
            test("a project .rtok.toml applies from any subdirectory of the checkout") {
                Sandbox("project").use { s ->
                    val repo = s.repo("${s.root}/app")
                    writeString("$repo/.rtok.toml", "[plugins.measure]\nenabled = false\n")
                    createDirAll("$repo/src/deep")
                    pluginRow(s.rtok("plugins", cwd = "$repo/src/deep").out, "measure") shouldBe "measure   off       cli,proxy"
                    pluginRow(s.rtok("plugins", cwd = s.root).out, "measure") shouldBe "measure   on        cli,proxy"
                }
            }
            test("a linked worktree reads its own checkout's .rtok.toml") {
                Sandbox("worktree").use { s ->
                    val repo = s.repo("${s.root}/main")
                    s.git(repo, "worktree", "add", "-q", "${s.root}/wt", "-b", "feature")
                    writeString("${s.root}/wt/.rtok.toml", "[plugins.proxy]\nenabled = false\n")
                    pluginRow(s.rtok("plugins", cwd = "${s.root}/wt").out, "proxy") shouldBe "proxy     off       proxy"
                    pluginRow(s.rtok("plugins", cwd = repo).out, "proxy") shouldBe "proxy     on        proxy"
                }
            }
            test("a submodule is its own project") {
                Sandbox("submodule").use { s ->
                    val dep = s.repo("${s.root}/dep-src")
                    val repo = s.repo("${s.root}/super")
                    s.git(repo, "submodule", "add", "-q", dep, "vendor/dep")
                    writeString("$repo/.rtok.toml", "[plugins.measure]\nenabled = false\n")
                    pluginRow(s.rtok("plugins", cwd = "$repo/vendor/dep").out, "measure") shouldBe "measure   on        cli,proxy"
                    pluginRow(s.rtok("plugins", cwd = repo).out, "measure") shouldBe "measure   off       cli,proxy"
                }
            }
        }
        context("mcp -- <server> wrapper") {
            // A foreign stdio server: answers `tools/call` with 200 lines, echoes every other request, exits 3.
            val server =
                "import json,sys\n" +
                    "for l in sys.stdin:\n" +
                    "    m=json.loads(l)\n" +
                    "    r={'content':[{'type':'text','text':''.join('line %d\\n'%i for i in range(200))}]} if m.get('method')=='tools/call' else {}\n" +
                    "    print(json.dumps({'jsonrpc':'2.0','id':m['id'],'result':r}),flush=True)\n" +
                    "sys.exit(3)\n"
            test("long tool results are cut with an expand trailer, others pass byte-exact, the exit code is the server's") {
                Sandbox("mcp-wrap").use { s ->
                    writeString("${s.root}/srv.py", server)
                    val input =
                        """{"jsonrpc":"2.0","id":1,"method":"ping"}""" + "\n" +
                            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list"}}""" + "\n"
                    s.rtok("mcp", "--", "python3", "${s.root}/srv.py", stdin = input).also {
                        it.code shouldBe 3
                        val lines = it.out.lines()
                        lines[0] shouldBe """{"jsonrpc": "2.0", "id": 1, "result": {}}"""
                        lines[1] shouldContain "expand: rtok expand "
                        lines[1] shouldNotContain "line 150"
                    }
                }
            }

            // Logs every method it is sent (`response` for an answer) and exits 1 when the Python expression [on]
            // holds for a message `m`; [silent] leaves the request it dies on unanswered, [last] is Python run
            // right before it dies.
            fun crashing(
                on: String,
                silent: Boolean = false,
                last: String = "pass",
            ) = "import json,os,sys\n" +
                "mark,log=sys.argv[1],sys.argv[2]\n" +
                "for l in sys.stdin:\n" +
                "    m=json.loads(l)\n" +
                "    open(log,'a').write(m.get('method','response')+'\\n')\n" +
                "    if 'method' not in m: continue\n" +
                "    die=$on\n" +
                "    if 'id' in m and not (die and ${if (silent) "True" else "False"}):\n" +
                "        print(json.dumps({'jsonrpc':'2.0','id':m['id'],'result':{}}),flush=True)\n" +
                "    if die:\n" +
                "        $last\n" +
                "        open(mark,'w').close(); sys.exit(1)\n"
            val firstToolCall = "m['method']=='tools/call' and not os.path.exists(mark)"
            val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

            fun call(id: Int) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"x"}}"""

            fun answer(id: Int) = """{"jsonrpc": "2.0", "id": $id, "result": {}}"""

            fun print(vararg frames: String) = "printf '%s\\n' " + frames.joinToString(" ") { q(it) }

            // Shell that returns once [file] exists, after 10 s at the latest.
            fun until(file: String) = "i=0; while [ ! -e ${q(file)} ] && [ \$i -lt 200 ]; do sleep 0.05; i=\$((i+1)); done"

            fun Sandbox.mark() = "$root/mark"

            fun Sandbox.methods() = readToString("$root/methods.log").lines().filter(String::isNotEmpty)

            // The wrapped server as a shell command, with [script] as its source and [mcp] as the `[mcp]` keys.
            fun Sandbox.wrap(
                script: String,
                mcp: String,
            ): String {
                writeString("$root/srv.py", script)
                writeString("$home/config.toml", "[mcp]\n$mcp\n")
                return listOf(RTOK, "mcp", "--", "python3", "$root/srv.py", mark(), "$root/methods.log").joinToString(" ") { q(it) }
            }
            test("a server that crashes under a connected client is restarted and handed the client's handshake") {
                Sandbox("mcp-wrap-restart").use { s ->
                    val wrap = s.wrap(crashing(firstToolCall, silent = true), "restart_interval_ms = 50")
                    s.shell("{ ${print(initialize, initialized, call(2))}; sleep 1; ${print(call(3))}; } | $wrap").also {
                        it.code shouldBe 0
                        it.out.lines().filter(String::isNotEmpty) shouldBe
                            listOf(
                                answer(1),
                                """{"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"MCP server python3 exited with code 1"}}""",
                                answer(3),
                            )
                        it.err shouldContain "python3 exited with code 1; restarting in 50 ms (1/6)"
                    }
                    s.methods() shouldBe listOf("initialize", "notifications/initialized", "tools/call", "initialize", "notifications/initialized", "tools/call")
                }
            }
            test("frames the client writes while the server is down reach the new server once, after the handshake") {
                Sandbox("mcp-wrap-held").use { s ->
                    // Dies between its `initialize` answer and the client's `notifications/initialized`.
                    val wrap = s.wrap(crashing("m['method']=='initialize' and not os.path.exists(mark)"), "restart_interval_ms = 500")
                    val feed = "${print(initialize)}; ${until(s.mark())}; sleep 0.2; ${print(initialized, call(2))}; sleep 1"
                    s.shell("{ $feed; } | $wrap").also {
                        it.code shouldBe 0
                        it.out.lines().filter(String::isNotEmpty) shouldBe listOf(answer(1), answer(2))
                    }
                    s.methods() shouldBe listOf("initialize", "initialize", "notifications/initialized", "tools/call")
                }
            }
            test("what a dead server asked is cancelled, its torn frame and the client's late answer go nowhere") {
                Sandbox("mcp-wrap-orphan").use { s ->
                    // Asks the client for its roots, starts a header-framed frame and dies inside it.
                    val ask = """{"jsonrpc": "2.0", "id": 7, "method": "roots/list"}"""
                    val last = "sys.stdout.write('$ask\\nContent-Length: 100\\r\\n\\r\\n{\"torn');sys.stdout.flush()"
                    val wrap = s.wrap(crashing(firstToolCall, silent = true, last = last), "restart_interval_ms = 500")
                    val late = """{"jsonrpc":"2.0","id":7,"result":{"roots":[]}}"""
                    val feed = "${print(initialize, initialized, call(2))}; ${until(s.mark())}; sleep 0.2; ${print(late, call(3))}; sleep 1"
                    s.shell("{ $feed; } | $wrap").also {
                        it.code shouldBe 0
                        val gone = "MCP server python3 exited with code 1"
                        it.out.lines().filter(String::isNotEmpty) shouldBe
                            listOf(
                                answer(1),
                                ask,
                                """{"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"$gone"}}""",
                                """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7,"reason":"$gone"}}""",
                                answer(3),
                            )
                    }
                    s.methods() shouldBe listOf("initialize", "notifications/initialized", "tools/call", "initialize", "notifications/initialized", "tools/call")
                }
            }
            test("a server that dies after every handshake runs out of restart attempts") {
                Sandbox("mcp-wrap-give-up").use { s ->
                    val wrap = s.wrap(crashing("m['method']=='notifications/initialized'"), "restart_attempts = 2\nrestart_interval_ms = 20")
                    val done = "${s.root}/done"
                    // stdin stays open until rtok is gone: it has to leave on its own, not on the client's hang-up.
                    s.shell("{ ${print(initialize, initialized)}; ${until(done)}; } | { $wrap; code=\$?; : >${q(done)}; exit \$code; }").also {
                        it.code shouldBe 1
                        it.out.lines().filter(String::isNotEmpty) shouldBe listOf(answer(1))
                        it.err shouldContain "python3 exited with code 1; giving up after 2 restarts"
                    }
                    s.methods() shouldBe List(3) { listOf("initialize", "notifications/initialized") }.flatten()
                }
            }
            test("the client hanging up during the restart wait ends the wrapper at once") {
                Sandbox("mcp-wrap-hang-up").use { s ->
                    val wrap = s.wrap(crashing(firstToolCall, silent = true), "restart_interval_ms = 60000")
                    val started = TimeSource.Monotonic.markNow()
                    // Hangs up a second after the server died, by when rtok is waiting to restart it.
                    s.shell("{ ${print(initialize, initialized, call(2))}; ${until(s.mark())}; sleep 1; } | $wrap").also {
                        it.code shouldBe 1
                        it.err shouldContain "python3 exited with code 1; restarting in 60000 ms (1/6)"
                    }
                    (started.elapsedNow() < 30.seconds) shouldBe true
                    s.methods() shouldBe listOf("initialize", "notifications/initialized", "tools/call")
                }
            }
        }
    })

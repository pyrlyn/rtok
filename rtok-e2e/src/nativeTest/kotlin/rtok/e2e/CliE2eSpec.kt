package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import rtok.sys.*

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
            test("a server that crashes under a connected client is restarted and handed the client's handshake") {
                // Dies on its first `tools/call` (the marker file remembers it), logs every method it is sent.
                val crashing =
                    "import json,os,sys\n" +
                        "mark,log=sys.argv[1],sys.argv[2]\n" +
                        "for l in sys.stdin:\n" +
                        "    m=json.loads(l)\n" +
                        "    open(log,'a').write(m['method']+'\\n')\n" +
                        "    if 'id' not in m: continue\n" +
                        "    if m['method']=='tools/call' and not os.path.exists(mark):\n" +
                        "        open(mark,'w').close(); sys.exit(1)\n" +
                        "    print(json.dumps({'jsonrpc':'2.0','id':m['id'],'result':{}}),flush=True)\n"
                Sandbox("mcp-wrap-restart").use { s ->
                    writeString("${s.root}/srv.py", crashing)
                    writeString("${s.home}/config.toml", "[mcp]\nrestart_interval_ms = 50\n")
                    val before =
                        listOf(
                            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
                            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"x"}}""",
                        ).joinToString(" ") { q(it) }
                    val after = q("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"x"}}""")
                    val log = "${s.root}/methods.log"
                    val wrap = listOf(RTOK, "mcp", "--", "python3", "${s.root}/srv.py", "${s.root}/mark", log).joinToString(" ") { q(it) }
                    s.shell("{ printf '%s\\n' $before; sleep 1; printf '%s\\n' $after; } | $wrap").also {
                        it.code shouldBe 0
                        it.out.lines().filter(String::isNotEmpty) shouldBe
                            listOf(
                                """{"jsonrpc": "2.0", "id": 1, "result": {}}""",
                                """{"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"MCP server python3 exited with code 1"}}""",
                                """{"jsonrpc": "2.0", "id": 3, "result": {}}""",
                            )
                        it.err shouldContain "python3 exited with code 1; restarting in 50 ms (1/6)"
                    }
                    readToString(log).lines().filter(String::isNotEmpty) shouldBe
                        listOf("initialize", "notifications/initialized", "tools/call", "initialize", "notifications/initialized", "tools/call")
                }
            }
        }
    })

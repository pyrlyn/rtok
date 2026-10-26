package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.*
import rtok.sys.*

class GraphCliSpec :
    FunSpec({
        tags(Cli)

        fun git(
            dir: String,
            vararg args: String,
        ) {
            val (c, _) = ProgressRunner("", "git", listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args).quiet().runCapped(1L shl 20)
            check(c.exitCode == 0) { c.stderr.decodeToString() }
        }

        /** A committed project `p` with `caller` calling `callee`, inside a fresh RTOK_HOME. */
        fun withProject(
            tag: String,
            body: (String) -> Unit,
        ) = withHome(tag) { home ->
            val p = "$home/p"
            createDirAll(p)
            writeString("$p/lib.rs", "fn callee() {}\nfn caller() {\n    callee();\n}\n")
            git(p, "init", "-q", "-b", "main")
            git(p, "add", "-A")
            git(p, "commit", "-q", "-m", "init")
            body(canonicalize(p)!!)
        }

        test("index, status and impact answer for a path or --project") {
            withProject("graph-index") { p ->
                cli("graph", "index", p).also {
                    it.code shouldBe 0
                    it.out shouldStartWith "indexed 1 files"
                }
                val st = Json.parseToJsonElement(cli("graph", "status", "--json", p).out).jsonObject
                st["files"]!!.jsonPrimitive.int shouldBe 1
                cli("graph", "add-nothing").code shouldBe ExitCode.USAGE
                cli("graph", "impact", "callee", p).out shouldContain "caller"
                cli("graph", "projects", "add", p).code shouldBe 0
                cli("graph", "projects").out shouldContain p
                cli("graph", "status", "--project", p, p).code shouldBe ExitCode.USAGE
                cli("graph", "status", "--project", p).code shouldBe 0
            }
        }

        test("diff and review read git, export draws an svg") {
            withProject("graph-diff") { p ->
                cli("graph", "projects", "add", p).code shouldBe 0
                writeString("$p/lib.rs", "fn callee(x: u8) {}\nfn caller() {\n    callee();\n}\n")
                cli("graph", "diff", "--project", p).also {
                    it.code shouldBe 0
                    it.out shouldStartWith "graph diff HEAD -> working (tags)"
                    it.out shouldContain "[signature] 1 callers"
                }
                cli("graph", "diff", "--project", p, "--from", "HEAD", "--from", "main").err shouldContain "twice"
                cli("graph", "review", "--project", p, "--since", "HEAD").out shouldStartWith "review: "
                cli("graph", "review", "--project", p, "--since", "HEAD", "--staged").code shouldBe ExitCode.USAGE
                cli("graph", "export", "--project", p, "--format", "svg").out shouldStartWith "<svg "
                cli("graph", "export", "--project", p, "--format", "png").code shouldBe ExitCode.USAGE
                cli("graph", "export", "--project", p, "--format", "svg", "--scale", "2").err shouldContain "--scale is for --format png"
                val out = "$p/../g.json"
                cli("graph", "export", "--project", p, "-o", out).out shouldStartWith "wrote "
                readToString(out) shouldContain "rtok.graph.v1"
            }
        }
    })

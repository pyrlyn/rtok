package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import rtok.sys.*

class McpPingE2eSpec :
    FunSpec({
        context("rtok mcp ping") {
            test("server-only: spawns the configured rtok with --host and gets the ping reply") {
                Sandbox("mcp-ping").use { s ->
                    createDirAll("${s.user}/.codeium/windsurf")
                    writeString(
                        "${s.user}/.codeium/windsurf/mcp_config.json",
                        """{"mcpServers":{"rtok":{"command":"$RTOK","args":["mcp","--host","windsurf"]}}}""",
                    )
                    s.rtok("mcp", "ping", "windsurf", "--timeout", "30").also {
                        it.code shouldBe 0
                        it.out shouldContain "Checked the configured server, not whether Windsurf loaded it: MCP Windsurf жив"
                    }
                    s.rtok("mcp", "ping", "windsurf", "--json", "--timeout", "30").out shouldContain "\"ok\":true"
                }
            }
            test("an unknown host is an error and nothing installed is exit 1") {
                Sandbox("mcp-ping-none").use { s ->
                    s.rtok("mcp", "ping", "nosuch").also {
                        it.code shouldBe 1
                        it.err shouldContain "unknown host: nosuch"
                    }
                    s.rtok("mcp", "ping").also {
                        it.code shouldBe 1
                        it.err shouldContain "no host has rtok MCP installed"
                    }
                }
            }
        }
    })

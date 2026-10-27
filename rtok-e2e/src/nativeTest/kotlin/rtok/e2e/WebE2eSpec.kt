package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val SCRIPT = """
"${'$'}RTOK" web --host 127.0.0.1 --port 0 2>"${'$'}LOG" &
pid=${'$'}!
for i in ${'$'}(seq 100); do grep -q 'ws://' "${'$'}LOG" 2>/dev/null && break; sleep 0.1; done
addr=${'$'}(sed -n 's|^rtok web http://\([^ ]*\).*|\1|p' "${'$'}LOG")
tok=${'$'}(cat "${'$'}RTOK_HOME/web-token")
echo "health=${'$'}(curl -s "http://${'$'}addr/health")"
echo "noauth=${'$'}(curl -s -o /dev/null -w '%{http_code}' "http://${'$'}addr/auth")"
echo "auth=${'$'}(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${'$'}tok" "http://${'$'}addr/auth")"
echo "ws=${'$'}(curl -s -o /dev/null -w '%{http_code}' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "http://${'$'}addr/ws")"
echo "page=${'$'}(curl -s -o /dev/null -w '%{http_code}' "http://${'$'}addr/")"
kill ${'$'}pid
cat "${'$'}LOG"
"""

class WebE2eSpec :
    FunSpec({
        test("rtok web --port 0 serves /health, gates /auth on the token and has no UI yet") {
            Sandbox("web").use { s ->
                val env = mapOf("RTOK_HOME" to s.home, "HOME" to s.user, "RTOK" to RTOK, "LOG" to "${s.root}/web.log", "NO_COLOR" to "1")
                val p = sh(listOf("sh", "-c", SCRIPT), s.root, env)
                p.code shouldBe 0
                p.out shouldContain "health={\"host\":\"127.0.0.1\",\"ok\":true,\"port\":"
                p.out shouldContain "noauth=401"
                p.out shouldContain "auth=204"
                p.out shouldContain "page=503"
                p.out shouldContain "ws=401"
                p.out shouldContain "rtok web: /ws needs the token in "
                p.out shouldContain "built without the SPA"
            }
        }
        test("a non-loopback bind needs the opt-in") {
            Sandbox("web-remote").use { s ->
                s.rtok("web", "--host", "0.0.0.0", "--port", "0").also {
                    it.code shouldBe 1
                    it.err shouldContain "refusing to bind 0.0.0.0"
                }
            }
        }
    })

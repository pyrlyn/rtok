package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

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
echo "page=${'$'}(curl -s -o /dev/null -w '%{http_code}' "http://${'$'}addr/overview")"
echo "index=${'$'}(curl -s "http://${'$'}addr/" | grep -c 'src="/app.js"')"
echo "csp=${'$'}(curl -s -D - -o /dev/null "http://${'$'}addr/" | grep -ci "wasm-unsafe-eval")"
echo "bundle=${'$'}(curl -s -o /dev/null -w '%{http_code} %{content_type}' "http://${'$'}addr/app.js")"
for w in ${'$'}(curl -s "http://${'$'}addr/app.js" | grep -o '[0-9a-f]\{20\}\.wasm' | sort -u); do
  echo "wasm=${'$'}(curl -s -o "${'$'}LOG.wasm" -w '%{http_code} %{content_type}' "http://${'$'}addr/${'$'}w") magic=${'$'}(head -c 4 "${'$'}LOG.wasm" | od -An -tx1 | tr -d ' ')"
done
kill ${'$'}pid
cat "${'$'}LOG"
"""

class WebE2eSpec :
    FunSpec({
        test("rtok web --port 0 serves /health, gates /auth on the token and serves the embedded Compose UI") {
            Sandbox("web").use { s ->
                val env = mapOf("RTOK_HOME" to s.home, "HOME" to s.user, "RTOK" to RTOK, "LOG" to "${s.root}/web.log", "NO_COLOR" to "1")
                val p = sh(listOf("sh", "-c", SCRIPT), s.root, env)
                p.code shouldBe 0
                p.out shouldContain "health={\"host\":\"127.0.0.1\",\"ok\":true,\"port\":"
                p.out shouldContain "noauth=401"
                p.out shouldContain "auth=204"
                p.out shouldContain "page=200"
                p.out shouldContain "index=1"
                p.out shouldContain "csp=1"
                p.out shouldContain "bundle=200 text/javascript"
                // The Compose bundle's .wasm modules are embedded as base64 and served as the original bytes.
                p.out shouldContain "wasm=200 application/wasm magic=0061736d"
                p.out shouldNotContain "wasm=404"
                p.out shouldContain "ws=401"
                p.out shouldContain "rtok web: /ws needs the token in "
                p.out shouldNotContain "built without the SPA"
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

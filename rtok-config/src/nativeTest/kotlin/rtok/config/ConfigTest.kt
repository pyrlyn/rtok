// Port of crates/rtok-config/src/lib.rs tests.
package rtok.config

import kotlinx.serialization.json.*
import rtok.hook.expandWith
import rtok.hook.homeDirFrom
import rtok.hook.userHomeFrom
import rtok.sys.*
import kotlin.test.*

internal fun parse(s: String): Config = Fig(listOf(Layer("default", Config().toJson()), Layer("user", Toml.parse(s)))).extract()
private fun parseFile(s: String): Config = decodeConfig(Toml.parse(s)) // Figment::from(Toml::string) — no defaults layer
internal fun tmpDir(name: String): String { val d = "${tempDir()}/rtok-cfg-$name-${pid()}"; removeDirAll(d); return d }

private fun tildeLeaves(cfg: Config): List<String> {
    val out = ArrayList<String>()
    fun walk(v: JsonElement, key: String) { when (v) {
        is JsonPrimitive -> if (v.isString && v.content.startsWith("~")) out += "$key = ${v.content}"
        is JsonObject -> v.forEach { (k, x) -> walk(x, "$key.$k") }
        is JsonArray -> v.forEach { walk(it, key) }
    } }
    walk(cfg.toJson(), ""); return out
}

private fun uncommentDocumented(src: String): String = buildString {
    for (line in src.lines().let { if (src.endsWith("\n")) it.dropLast(1) else it }) {
        if (line.startsWith("# ")) {
            val rest = line.substring(2); val code = rest.substringBefore('#').trim()
            val assignment = code.contains('=') && code.substringBefore('=').trim().let { k -> k.isNotEmpty() && k.all { it.isLetterOrDigit() && it.code < 128 || it in "_-\"." } }
            val mapTable = code.startsWith("[bench.configs]") || code.startsWith("[stats.prices.")
            if (assignment || mapTable) { append(rest).append('\n'); continue }
        }
        append(line).append('\n')
    }
}

class ConfigTest {
    @Test fun usageDirsFollowAHostsRelocationVariableUntilTheFileNamesOne() {
        val env = { k: String -> when (k) { "XDG_DATA_HOME" -> "/data"; "COPILOT_HOME" -> "/cop"; "GEMINI_CLI_HOME" -> "relative/is/ignored"; "PI_CODING_AGENT_DIR" -> "/pi"; "KIMI_CODE_HOME" -> "/kimi"; else -> null } }
        val d = UsageDirs(kilo = listOf("/mine")).followEnv(env)
        assertEquals(listOf("/data/opencode"), d.opencode); assertEquals(listOf("/cop/session-state"), d.copilot)
        assertEquals(listOf("/mine"), d.kilo); assertEquals(listOf("~/.gemini/tmp"), d.gemini); assertEquals(listOf("~/.factory/sessions"), d.droid)
        assertEquals(listOf("/pi/sessions"), d.pi); assertEquals(listOf("/kimi/sessions"), d.kimi); assertEquals(listOf("~/.grok/sessions"), d.grok)
        val n = UsageDirs().followEnv { k -> when (k) { "PI_CODING_AGENT_SESSION_DIR" -> "/s"; "PI_CODING_AGENT_DIR" -> "/pi"; else -> null } }
        assertEquals(listOf("/s"), n.pi)
    }
    @Test fun otelIsOffUntilAnEndpointResolves() {
        val o = Otel(); assertNull(o.resolveWith { null })
        val env = { k: String -> when (k) { "OTEL_EXPORTER_OTLP_ENDPOINT" -> "http://localhost:4318/"; "OTEL_EXPORTER_OTLP_HEADERS" -> "a=1, b=x=y"; else -> null } }
        val e = o.resolveWith(env)!!; assertEquals("http://localhost:4318", e.url); assertEquals(listOf("a" to "1", "b" to "x=y"), e.headers)
        val e2 = Otel(endpoint = "https://otel.example/", headers = "signoz-ingestion-key=k").resolveWith(env)!!
        assertEquals("https://otel.example", e2.url); assertEquals(listOf("signoz-ingestion-key" to "k"), e2.headers)
    }
    @Test fun defaultTomlIsTheDefaults() {
        for ((n, line) in DEFAULT_TOML.lines().withIndex()) { val t = line.trim(); assertTrue(t.isEmpty() || t.startsWith("#") || t.startsWith("["), "line ${n + 1} pins a default: $line") }
        val parsed = parseFile(DEFAULT_TOML)
        assertEquals(Config(), parsed, "default.toml drifted from Config()")
        assertEquals(parsed, parseFile(""))
        assertEquals(emptyList(), Validate.issuesIn("config.toml", DEFAULT_TOML))
        val live = uncommentDocumented(DEFAULT_TOML)
        assertEquals(parsed, parseFile(live), "a commented default does not match Config()")
        assertEquals(emptyList(), Validate.pinnedNotesIn("config.toml", live))
    }
    @Test fun everyCatalogueIdIsAnswered() {
        val cfg = Config(); for ((id, on) in CATALOGUE) assertEquals(on, cfg.pluginEnabled(id, !on), id)
        assertTrue(cfg.pluginEnabled("external", true)); assertFalse(cfg.pluginEnabled("external", false))
    }
    @Test fun setPluginEnabledFlipsEveryCatalogueId() {
        var cfg = Config(); for ((id, on) in CATALOGUE) { cfg = cfg.withPluginEnabled(id, !on); assertEquals(!on, cfg.pluginEnabled(id, on), id) }
        assertTrue(cfg.withPluginEnabled("external", false).pluginEnabled("external", true))
    }
    @Test fun createsReferenceFileAndBudgetIs800() {
        val home = tmpDir("create"); val cfg = Config.loadFrom(home)
        assertEquals(DEFAULT_TOML, readToString(Config.pathFor(home)))
        assertEquals(800u, cfg.plugins.inject.budgetTokens); assertEquals("$home/rtok.db", cfg.core.dbPath); assertEquals("$home/archive", cfg.core.archiveDir)
        assertTrue(cfg.pluginEnabled("toon", true)); removeDirAll(home)
    }
    @Test fun initRefusesToClobberWithoutForce() {
        val home = tmpDir("force"); Config.init(home, false); writeString(Config.pathFor(home), "[core]\n")
        assertFails { Config.init(home, false) }; Config.init(home, true); assertEquals(DEFAULT_TOML, readToString(Config.pathFor(home))); removeDirAll(home)
    }
    @Test fun partialFileKeepsDefaults() {
        val cfg = parse("[plugins.cmd]\nrewrite = false\n[estimator]\ncode = 4.0\n")
        assertEquals(800u, cfg.plugins.inject.budgetTokens); assertEquals(4.0f, cfg.estimator.code); assertEquals(4.2f, cfg.estimator.prose)
        assertFalse(cfg.plugins.cmd.rewrite); assertTrue(cfg.plugins.cmd.enabled)
    }
    @Test fun toolsRewriteDefaultsOverlayAndUnknownKey() {
        val d = ToolsRewrite(); assertFalse(d.enabled); assertEquals(60u, d.maxDescriptionTokens); assertTrue(d.allow.isEmpty() && d.deny.isEmpty())
        val cfg = parse("[proxy.tools_rewrite]\nenabled = true\nmax_description_tokens = 40\ndeny = [\"Bash\"]\n")
        assertTrue(cfg.proxy.toolsRewrite.enabled); assertEquals(40u, cfg.proxy.toolsRewrite.maxDescriptionTokens); assertEquals(listOf("Bash"), cfg.proxy.toolsRewrite.deny)
        assertTrue(assertFailsWith<ConfigException> { parse("[proxy.tools_rewrite]\nbogus = true\n") }.message!!.contains("bogus"))
    }
    @Test fun semanticCacheDefaultsOverlayAndUnknownKey() {
        val d = SemanticCache(); assertFalse(d.enabled); assertEquals(0.99f, d.threshold); assertEquals(300uL, d.ttlS); assertEquals(1u, d.maxMessages)
        assertTrue(d.requireEmptyTools); assertEquals("hash", d.embedBackend); assertTrue(d.cacheByModel && d.cacheByProvider)
        val cfg = parse("[plugins.proxy.semantic_cache]\nenabled = true\nthreshold = 0.95\n")
        assertTrue(cfg.plugins.proxy.semanticCache.enabled); assertEquals(0.95f, cfg.plugins.proxy.semanticCache.threshold)
        assertTrue(assertFailsWith<ConfigException> { parse("[plugins.proxy.semantic_cache]\nbogus = true\n") }.message!!.contains("bogus"))
    }
    @Test fun readLanguagesIsAcceptedOnlyForCompatibility() {
        assertEquals(listOf("rust", "ts"), parse("[plugins.read]\nlanguages = [\"rust\", \"ts\"]\n").plugins.read.languages)
        assertTrue(assertFailsWith<ConfigException> { parse("[plugins.read]\nlangauges = [\"rust\"]\n") }.message!!.contains("langauges"))
    }
    @Test fun unknownKeyIsAnError() {
        assertTrue(assertFailsWith<ConfigException> { parse("[proxy]\nprot = 1\n") }.message!!.contains("prot"))
        assertFails { parse("[nope]\nx = 1\n") }; assertFails { parse("[plugins.compress]\nunknown = true\n") }
        assertTrue(assertFailsWith<ConfigException> { parse("[plugins.memory.embed]\nprot = 1\n") }.message!!.contains("prot"))
        assertFails { parse("[plugins.archive]\nno_such = 1\n") }
    }
    @Test fun compressDefaultsOnAndOverlaysTurnOff() {
        assertTrue(Config().plugins.compress.enabled); assertFalse(parse("[plugins.compress]\nenabled = false\n").plugins.compress.enabled)
        val home = tmpDir("compress-env"); createDirAll(home); writeString("$home/.env", "RTOK_PLUGINS_COMPRESS_ENABLED=false\n")
        writeString("$home/config.toml", "")
        assertFalse(Layers.load(home, "$home/config.toml", null).plugins.compress.enabled)
        val row = Layers.entries(Layers.figment(home, "$home/config.toml", null)).first { it.first == "plugins.compress.enabled" }
        assertEquals("false", row.second); assertEquals("dotenv", row.third); removeDirAll(home)
    }
    @Test fun memoryEmbedDefaultsOff() { val e = Config().plugins.memory.embed; assertFalse(e.enabled); assertEquals("local", e.provider); assertEquals("all-MiniLM-L6-v2", e.model); assertEquals(384u, e.dimensions); assertTrue(e.hybrid) }
    @Test fun memoryEmbedEnabledFromFile() = assertTrue(parse("[plugins.memory.embed]\nenabled = true\n").plugins.memory.embed.enabled)
    @Test fun graphBackendDefaultsToTags() { assertEquals("tags", Config().plugins.graph.backend); assertEquals(0u, Config().plugins.graph.mapTokens) }
    @Test fun graphBackendOverlayFromToml() = assertEquals("lsp", parse("[plugins.graph]\nbackend = \"lsp\"\n").plugins.graph.backend)
    @Test fun graphAutoBackendKeys() {
        val g = Config().plugins.graph; assertEquals(40_000u, g.lspTimeoutMs); assertEquals(0, g.backendByLanguage.size)
        val toml = "[plugins.graph]\nbackend = \"auto\"\nlsp_timeout_ms = 500\n[plugins.graph.backend_by_language]\ngo = \"tags\"\njava = \"text\"\n"
        val cfg = parse(toml); assertEquals("auto", cfg.plugins.graph.backend); assertEquals(500u, cfg.plugins.graph.lspTimeoutMs); assertEquals("tags", cfg.plugins.graph.backendByLanguage["go"])
        val dir = tmpDir("auto-keys"); createDirAll(dir); val path = "$dir/c.toml"
        writeString(path, toml); assertEquals(emptyList(), Validate.issues(path))
        writeString(path, "[plugins.graph]\nbackend = \"text\"\n"); assertEquals(emptyList(), Validate.issues(path))
        for (bad in listOf("[plugins.graph]\nbackend = \"grep\"\n", "[plugins.graph.backend_by_language]\ngo = \"rg\"\n")) {
            writeString(path, bad); val errs = Validate.issues(path); assertTrue(errs.any { it.contains("plugins.graph.backend") }, "$bad: $errs")
        }
        removeDirAll(dir)
    }
    @Test fun graphBackendUnknownKeyIsAnError() { assertFails { parse("[plugins.graph]\nback_end = \"lsp\"\n") } }
    @Test fun archiveTiersDefaultsAndOverlays() {
        assertFalse(Config().plugins.archive.tiers); assertTrue(parse("[plugins.archive]\ntiers = true\n").plugins.archive.tiers)
        assertTrue("plugins.archive.tiers" in Layers.leafKeys())
    }
    @Test fun defaultExpandsEveryPathbuf() = assertEquals(emptyList(), tildeLeaves(Config().finish("/tmp/rtok-tilde-default")))
    @Test fun tildeExpandsForEveryPathKey() {
        for (toml in listOf("[report]\nout = \"~/rtok-report.md\"\n", "[bench]\ntasks = \"~/bench/tasks.toml\"\n", "[bench.configs]\ncustom = \"~/bench/rtok.json\"\n",
            "[plugins.read]\nallow_paths = [\"~/src\"]\n", "[plugins.wasm]\ndir = \"~/plugins\"\n", "[doctor]\nmcp_json = \"~/.mcp.json\"\n", "[worktree]\nroot = \"~/worktrees\"\n"))
            assertEquals(emptyList(), tildeLeaves(parse(toml).finish("/tmp/rtok-tilde-keys")), toml)
    }
    @Test fun userHomePrefersHomeThenUserprofile() {
        assertEquals("/Users/me", userHomeFrom("/Users/me", "C:\\Users\\me")); assertEquals("C:\\Users\\Example", userHomeFrom(null, "C:\\Users\\Example"))
        assertEquals("C:\\Users\\Example", userHomeFrom("", "C:\\Users\\Example")); assertNull(userHomeFrom(null, null)); assertNull(userHomeFrom("", ""))
    }
    @Test fun rtokHomeExpandsALiteralTilde() {
        for ((env, want) in listOf(null to "/Users/me/.rtok", "/srv/rtok" to "/srv/rtok", "~/.rtok" to "/Users/me/.rtok", "~/state/rtok" to "/Users/me/state/rtok"))
            assertEquals(want, homeDirFrom(env, "/Users/me"))
    }
    @Test fun homeDirAbsoluteNeverRelative() {
        assertEquals("/tmp/rtok-fallback/.rtok", homeDirAbsolute(null, null, { "/cwd" }, { "/tmp/rtok-fallback/.rtok" }))
        assertEquals("/repo/target/tmp/case", homeDirAbsolute("target/tmp/case", null, { "/repo" }, { "/tmp/rtok-fallback/.rtok" }))
        val abs = "${tempDir()}/srv-rtok"
        assertEquals(abs, homeDirAbsolute(abs, null, { error("cwd should not be read") }, { error("fallback should not run") }))
        assertEquals("/repo/rel-home/.rtok", homeDirAbsolute(null, "rel-home", { "/repo" }, { "/tmp/rtok-fallback/.rtok" }))
        assertEquals("/tmp/rtok-fallback/.rtok", homeDirAbsolute("", null, { "/cwd" }, { "/tmp/rtok-fallback/.rtok" }))
    }
    @Test fun expandCoversBareTildeAndRtokHomeDir() {
        val home = "/tmp/rtok-home"; val u = envUserHome()
        assertEquals(home, expandWith("~/.rtok", home, u)); assertEquals(home, expandWith("~/.rtok/", home, u)); assertEquals("$home/db", expandWith("~/.rtok/db", home, u))
        if (u != null) { assertEquals(u, expandWith("~", home, u)); assertEquals("$u/.claude/settings.json", expandWith("~/.claude/settings.json", home, u)) }
    }
    @Test fun expandWithUserprofileResolvesCursorAndClaudePaths() {
        // Windows-shaped profile on a POSIX host: joined with '/', like Path::join on Unix.
        val rtok = "/tmp/rtok-home"; val profile = "/Users/Example"
        assertEquals("$profile/.cursor/hooks.json", expandWith("~/.cursor/hooks.json", rtok, profile))
        assertEquals("$profile/.cursor/mcp.json", expandWith("~/.cursor/mcp.json", rtok, profile))
        assertEquals("$profile/.claude/settings.json", expandWith("~/.claude/settings.json", rtok, profile))
        assertEquals("$profile/.claude.json", expandWith("~/.claude.json", rtok, profile))
        assertEquals("~/.cursor/hooks.json", expandWith("~/.cursor/hooks.json", rtok, null))
        assertEquals("$profile/.cursor/hooks.json", expandWith("~\\.cursor\\hooks.json", rtok, profile))
        assertEquals("$rtok/db", expandWith("~\\.rtok\\db", rtok, profile)); assertEquals("$rtok/db", expandWith("~/.rtok\\db", rtok, profile))
    }
    @Test fun legacyBudgetKeyMigrates() {
        val cfg = parse("[core]\ninject_budget_tokens = 250\n").finish("/tmp/rtok-legacy")
        assertEquals(250u, cfg.plugins.inject.budgetTokens); assertNull(cfg.core.injectBudgetTokens)
    }
    @Test fun legacyCoreLogKeysMigrateIntoLog() {
        val cfg = parse("[core]\nlog_file = \"/tmp/old.log\"\nlog_level = \"debug\"\nlog_to_db = false\n").finish("/tmp/rtok-legacy-log")
        assertEquals("/tmp/old.log", cfg.log.path); assertEquals("debug", cfg.log.level); assertFalse(cfg.log.toDb)
        assertNull(cfg.core.logFile); assertNull(cfg.core.logLevel); assertNull(cfg.core.logToDb)
    }
    @Test fun validateRejectsLegacyCoreLogKeys() {
        val dir = tmpDir("val-log"); createDirAll(dir); val path = "$dir/bad.toml"
        writeString(path, "[core]\nlog_file = \"/tmp/x.log\"\nlog_level = \"debug\"\nlog_to_db = false\n")
        val j = Validate.issues(path).joinToString("\n"); assertTrue(j.contains("log_file") || j.contains("log_level") || j.contains("log_to_db"), j); removeDirAll(dir)
    }
    @Test fun wasmDisabledByDefault() = assertFalse(Config().plugins.wasm.enabled)
    @Test fun wasmOverlayEnables() = assertTrue(parse("[plugins.wasm]\nenabled = true\n").plugins.wasm.enabled)
    @Test fun wasmUnknownKeyIsDenied() = assertTrue(assertFailsWith<ConfigException> { parse("[plugins.wasm]\nfuel_per_call = 1\n") }.message!!.contains("fuel_per_call"))
}

// Port of crates/rtok-config/src/validate.rs tests.
package rtok.config

import kotlinx.cinterop.*
import kotlinx.serialization.json.*
import platform.posix.stat
import rtok.config.Validate.issues
import rtok.config.Validate.set
import rtok.sys.*
import kotlin.test.*
import kotlinx.serialization.json.*

private fun tmp(name: String): String { val d = "${tempDir()}/rtok-val-$name-${pid()}"; removeDirAll(d); createDirAll(d); return d }
private fun fails(block: () -> Unit): Throwable = assertFails(block)
@OptIn(ExperimentalForeignApi::class)
private fun ino(p: String): ULong = memScoped { val s = alloc<stat>(); stat(p, s.ptr); s.st_ino }

class ValidateTest {
    @Test fun setRefusesValuesTypedExtractionRejects() {
        val home = tmp("typed"); val path = Config.initMaybe(home, null, false, false).first; val before = readToString(path)
        for ((key, raw, want) in listOf(Triple("stats.prices", "42", "stats.prices"), Triple("stats.prices.custom", "\"x\"", "stats.prices.custom"),
            Triple("plugins.read.allow_paths", "[1]", "plugins.read.allow_paths"), Triple("plugins.read.allow_paths", "[\"a\", 2]", "plugins.read.allow_paths"),
            Triple("bench.configs.mine", "7", "bench.configs.mine"))) {
            for (dry in listOf(true, false)) { val e = fails { set(home, key, raw, dry) }.message.orEmpty(); assertTrue(e.contains(want), "$key=$raw dry=$dry: $e") }
            assertEquals(before, readToString(path), "$key=$raw")
        }
        Config.loadFrom(home); removeDirAll(home)
    }
    @Test fun setKeepsFreeFormMapsAndArraysUsable() {
        val home = tmp("typed-ok"); val path = Config.initMaybe(home, null, false, false).first
        writeString(path, "# keep me\n[proxy]\nport = 8790 # port\n")
        for ((k, r) in listOf("stats.prices.my-model" to "{ input = 1.0, output = 2.0 }", "bench.configs.mine" to "\"/tmp/x\"", "plugins.graph.extensions.zig" to "\"c\"",
            "plugins.read.allow_paths" to "[]", "plugins.read.allow_paths" to "[\"/tmp\"]")) set(home, k, r, false)
        val t = readToString(path); assertFalse(t.contains("#"), t) // config set drops comments (ktoml encoder)
        assertEquals(8790L, Toml.parse(t)["proxy"]!!.jsonObject["port"]!!.jsonPrimitive.long, t); Config.loadFrom(home); removeDirAll(home)
    }
    @Test fun validateReportsTheTypedErrorWithKeyAndLine() {
        val e = Validate.issuesIn("c.toml", "[stats]\nprices = 42\n"); assertEquals(1, e.size, "$e"); assertTrue(e[0].startsWith("c.toml:2: stats.prices: "), "$e")
        assertTrue(Validate.issuesIn("c.toml", "[plugins.read]\nallow_paths = [1]\n")[0].startsWith("c.toml:2: plugins.read.allow_paths"))
    }
    @Test fun setLeavesMultilineStringPayloadsUntouched() {
        for (q in listOf("'''", "\"\"\"")) for (eol in listOf("\n", "\r\n")) {
            val home = tmp("payload"); val path = Config.initMaybe(home, null, false, false).first
            val p0 = "$q\n[proxy]\n# port = 12\n# [x] = 1\n$q"; val body = "[otel]\nservice_name = $p0\n[proxy]\n# port = 8790\n"
            writeString(path, body.replace("\n", eol)); set(home, "proxy.port", "9000", false); val a = readToString(path)
            val tree = Toml.parse(a); assertEquals(p0.removePrefix(q).removeSuffix(q).removePrefix("\n"), tree["otel"]!!.jsonObject["service_name"]!!.jsonPrimitive.content, a)
            assertEquals(9000L, tree["proxy"]!!.jsonObject["port"]!!.jsonPrimitive.long, a); removeDirAll(home)
        }
    }
    @Test fun setReplacesTheFileAtomically() {
        val home = tmp("atomic"); val path = Config.initMaybe(home, null, false, false).first; val i = ino(path)
        set(home, "proxy.port", "9999", false); assertNotEquals(i, ino(path)); assertTrue(readToString(path).contains("9999")); removeDirAll(home)
    }
    @Test fun aTspinValueOutsideTheSetIsRejectedByName() {
        val d = tmp("tspin"); val p = "$d/c.toml"; writeString(p, "[log]\ntspin = \"sometimes\"\n")
        assertTrue(issues(p).any { it.contains("log.tspin") && it.contains("auto | always | off") }, "${issues(p)}")
        for (ok in listOf("auto", "always", "off")) { writeString(p, "[log]\ntspin = \"$ok\"\n"); assertTrue(issues(p).isEmpty(), ok) }; removeDirAll(d)
    }
    @Test fun agentsJunkValuesAreCheckedByName() {
        val d = tmp("agents-junk"); val p = "$d/c.toml"; fun errs(b: String): List<String> { writeString(p, "[agents.junk]\n$b\n"); return issues(p) }
        for ((b, w) in listOf("keep_logs_days = -1" to "agents.junk.keep_logs_days must be ≥ 0", "keep_logs_days = 2.5" to "agents.junk.keep_logs_days: expected number",
            "keep_logs_days = \"abc\"" to "agents.junk.keep_logs_days: expected number", "keep_logs_days = 3651" to "agents.junk.keep_logs_days must be ≤ 3650",
            "stale_session_days = 3651" to "agents.junk.stale_session_days must be ≤ 3650", "stale_session_days = -1" to "agents.junk.stale_session_days must be ≥ 0",
            "stale_session_days = 2.5" to "agents.junk.stale_session_days: expected number", "stale_worktree_days = 3651" to "agents.junk.stale_worktree_days must be ≤ 3650",
            "crash_dump_min_age_days = 3651" to "agents.junk.crash_dump_min_age_days must be ≤ 3650", "temp_min_age_hours = 87601" to "agents.junk.temp_min_age_hours must be ≤ 87600",
            "exclude = [\"[\"]" to "agents.junk.exclude[0]", "exclude = [1]" to "agents.junk.exclude[0]: expected string",
            "extra = [{ host = \"nope\", kind = \"cache\", path = \"/x\" }]" to "agents.junk.extra[0].host",
            "extra = [{ host = \"cursor\", kind = \"sessions\", path = \"/x\" }]" to "agents.junk.extra[0].kind must be one of cache, temp, logs, crash-dumps",
            "extra = [{ host = \"rtok\", kind = \"temp\" }]" to "agents.junk.extra[0].path",
            "extra = [{ host = \"rtok\", kind = \"temp\", path = \"/x\", why = 1 }]" to "unknown key: agents.junk.extra[0].why",
            "extra = [\"/x\"]" to "agents.junk.extra[0]: expected table")) { val g = errs(b); assertTrue(g.any { it.contains(w) }, "$b: $g") }
        assertEquals(emptyList(), errs("keep_logs_days = 0\nexclude = [\"~/keep/**\"]\n\n[[agents.junk.extra]]\nhost = \"cursor\"\nkind = \"cache\"\npath = \"~/c\""))
        writeString("$d/config.toml", "[agents.junk]\nkeep_logs_days = \"abc\"\n")
        assertTrue(fails { Config.loadFrom(d) }.message.orEmpty().contains("keep_logs_days")); removeDirAll(d)
    }
    @Test fun tasksAdapterAndPrefixAreChecked() {
        val d = tmp("tasks"); val p = "$d/c.toml"
        for ((b, k) in listOf("adapter = \"jira\"" to "tasks.adapter", "[tasks.disk]\nlayout = \"tree\"" to "tasks.disk.layout", "prefix = \"R2\"" to "tasks.prefix", "prefix = \"TOOLONGPX\"" to "tasks.prefix")) {
            writeString(p, "[tasks]\n$b\n"); assertTrue(issues(p).any { it.contains(k) }, "$b: ${issues(p)}") }
        writeString(p, "[tasks]\nadapter = \"gitlab\"\nprefix = \"at\"\n[tasks.gitlab]\nproject = \"g/n\"\n"); assertTrue(issues(p).isEmpty(), "${issues(p)}"); removeDirAll(d)
    }
    @Test fun aReadCapBelowTheArchiveMarkerIsRejected() {
        val d = tmp("readcap"); val p = "$d/c.toml"; writeString(p, "[plugins.read]\nmax_chars = 50\n"); assertTrue(issues(p).any { it.contains("plugins.read.max_chars") })
        writeString(p, "[plugins.read]\nmax_chars = 100\n"); assertTrue(issues(p).isEmpty()); removeDirAll(d)
    }
    @Test fun logLevelTypoAndHookPathKillingBoundsAreRejected() {
        val d = tmp("logbounds"); val p = "$d/c.toml"
        for ((b, k) in listOf("[log]\nlevel = \"verbose\"\n" to "log.level", "[log]\nmax_bytes = 0\n" to "log.max_bytes", "[log]\nfiles = 100\n" to "log.files")) { writeString(p, b); assertTrue(issues(p).any { it.contains(k) }, b) }
        writeString(p, "[log]\nlevel = \"Warn\"\nmax_bytes = 1024\nfiles = 20\n"); assertTrue(issues(p).isEmpty(), "${issues(p)}"); removeDirAll(d)
    }
    @Test fun aLaneUpstreamMustBeAnHttpUrl() {
        val d = tmp("laneup"); val p = "$d/c.toml"
        for (b in listOf("localhost:8080", "ftp://mirror", "not a url")) { writeString(p, "[proxy.lanes.bulk]\nupstream = \"$b\"\n"); assertTrue(issues(p).any { it.contains("proxy.lanes.bulk.upstream") }, b) }
        for (g in listOf("", "http://127.0.0.1:4000", "https://gateway.example/v1")) { writeString(p, "[proxy.lanes.bulk]\nupstream = \"$g\"\n"); assertTrue(issues(p).isEmpty(), "$g ${issues(p)}") }
        removeDirAll(d)
    }
    @Test fun port70000NamesTheLine() { val d = tmp("port"); val p = "$d/bad.toml"; writeString(p, "[proxy]\nport = 70000\n"); assertTrue(issues(p).any { it.contains(":2:") && it.contains("proxy.port") }, "${issues(p)}"); removeDirAll(d) }
    @Test fun pluginEnabledIsValidated() {
        val d = tmp("plug"); val p = "$d/bad.toml"; writeString(p, "[plugins.cmd]\nenabled = \"yes\"\n[plugins.nope]\nenabled = true\n"); val e = issues(p)
        assertTrue(e.any { it.contains("plugins.cmd.enabled") && it.contains("bool") }, "$e"); assertTrue(e.any { it.contains("unknown key: plugins.nope") }, "$e"); removeDirAll(d)
    }
    @Test fun theReportedLineIsTheOffendingOne() {
        val d = tmp("line"); val p = "$d/bad.toml"; writeString(p, "[plugins.measure]\nenabled = true\n[plugins.cmd]\nenabled = \"yes\"\n")
        assertTrue(issues(p).any { it.contains(":4:") && it.contains("plugins.cmd.enabled") }, "${issues(p)}"); removeDirAll(d)
    }
    @Test fun aFloatIsNotANumberForAnIntegerKey() { val d = tmp("float"); val p = "$d/bad.toml"; writeString(p, "[proxy]\nport = 8790.5\n"); assertTrue(issues(p).any { it.contains("proxy.port") }); removeDirAll(d) }
    @Test fun setThroughAScalarReportsInsteadOfPanicking() {
        val home = tmp("scalar"); Config.init(home, false); val before = readToString(Config.pathFor(home))
        val e = fails { set(home, "proxy.port.foo", "1", false) }; assertTrue(e.message.orEmpty().contains("not a table"), "$e"); assertEquals(before, readToString(Config.pathFor(home))); removeDirAll(home)
    }
    @Test fun setRefusesAValueTheLoaderWouldReject() {
        val home = tmp("setbad"); Config.init(home, false); val before = readToString(Config.pathFor(home))
        fails { set(home, "plugins.nope.enabled", "true", false) }; fails { set(home, "plugins.cmd.enabled", "yes", false) }; assertEquals(before, readToString(Config.pathFor(home)))
        set(home, "plugins.cmd.enabled", "false", false); assertFalse(Config.loadFrom(home).pluginEnabled("cmd", true)); removeDirAll(home)
    }
    @Test fun aMalformedSinceWindowIsRejected() {
        for (table in listOf("stats", "report")) {
            val key = "$table.since"; val d = tmp("$table-since"); val p = "$d/c.toml"
            for (b in listOf("7x", "d", "-1d", "")) { writeString(p, "[$table]\nsince = \"$b\"\n"); assertTrue(issues(p).any { it.contains(key) && it.contains("c.toml:2") }, "$key=$b ${issues(p)}") }
            for (ok in listOf("30d", "12h", "7")) { writeString(p, "[$table]\nsince = \"$ok\"\n"); assertTrue(issues(p).isEmpty(), "$key=$ok") }
            val home = tmp("$table-since-set"); Config.init(home, false); val before = readToString(Config.pathFor(home))
            fails { set(home, key, "7x", false) }; assertEquals(before, readToString(Config.pathFor(home))); set(home, key, "12h", false); removeDirAll(d); removeDirAll(home)
        }
    }
    @Test fun layeredValuesAreCheckedAndNameTheirLayer() {
        val e = Validate.layeredIssues(listOf(Triple("proxy.port", JsonPrimitive(70000), "env"), Triple("plugins.read.delta_max_ratio", JsonPrimitive(5.0), "project"),
            Triple("log.level", JsonPrimitive("debug"), "env"), Triple("proxy.port", JsonPrimitive(0), "user"), Triple("log.path", JsonPrimitive("123"), "dotenv")))
        assertEquals(2, e.size, "$e"); assertTrue(e.any { it.startsWith("env: proxy.port out of range") }, "$e"); assertTrue(e.any { it.startsWith("project: plugins.read.delta_max_ratio") }, "$e")
    }
    @Test fun unitRatioKeysAndEmbedBackendAreRangeChecked() {
        val d = tmp("ratio"); val p = "$d/c.toml"; val sc = "plugins.proxy.semantic_cache"
        for ((t, k, b) in listOf(Triple(sc, "threshold", "-1"), Triple(sc, "threshold", "0"), Triple(sc, "threshold", "5"), Triple(sc, "threshold", "1.5"),
            Triple("plugins.read", "delta_max_ratio", "-3"), Triple("plugins.read", "delta_max_ratio", "0.0"), Triple(sc, "embed_backend", "\"openai\""))) {
            writeString(p, "[$t]\n$k = $b\n"); assertTrue(issues(p).any { it.contains(k) }, "$k=$b ${issues(p)}") }
        for ((t, k, ok) in listOf(Triple(sc, "threshold", "0.99"), Triple(sc, "threshold", "1"), Triple("plugins.read", "delta_max_ratio", "0.6"), Triple(sc, "embed_backend", "\"hash\""))) {
            writeString(p, "[$t]\n$k = $ok\n"); assertTrue(issues(p).isEmpty(), "$k=$ok ${issues(p)}") }
        val home = tmp("ratio-set"); Config.init(home, false); val before = readToString(Config.pathFor(home))
        for ((k, b) in listOf("$sc.threshold" to "-1", "$sc.threshold" to "0", "$sc.threshold" to "5", "plugins.read.delta_max_ratio" to "-3", "$sc.embed_backend" to "openai")) fails { set(home, k, b, false) }
        assertEquals(before, readToString(Config.pathFor(home))); removeDirAll(d); removeDirAll(home)
    }
    @Test fun flexPolicyValuesAreChecked() {
        val d = tmp("flex"); val p = "$d/c.toml"
        for ((k, b) in listOf("on_429" to "\"retry\"", "retries" to "6")) { writeString(p, "[proxy.flex]\n$k = $b\n"); assertTrue(issues(p).any { it.contains(k) }, "$k=$b") }
        for ((k, ok) in listOf("on_429" to "\"backoff\"", "on_429" to "\"default\"", "retries" to "5")) { writeString(p, "[proxy.flex]\n$k = $ok\n"); assertTrue(issues(p).isEmpty(), "$k=$ok ${issues(p)}") }
        removeDirAll(d)
    }
    // Differs from Rust (toml_edit): `config set` re-encodes the file, so comments and padding are dropped.
    private fun setDropsCommentsKeepsValue(key: String, raw: String, expected: JsonPrimitive) {
        val home = tmp("set"); Config.init(home, false); set(home, key, raw, false); val t = readToString(Config.pathFor(home))
        assertFalse(t.contains("#"), t); val (table, leaf) = key.split('.')
        assertEquals(expected, Toml.parse(t)[table]!!.jsonObject[leaf], t); Config.loadFrom(home); removeDirAll(home)
    }
    @Test fun setDropsCommentsAndWritesThePort() = setDropsCommentsKeepsValue("proxy.port", "8791", JsonPrimitive(8791L))
    @Test fun setDropsCommentsAndWritesTheMode() = setDropsCommentsKeepsValue("proxy.mode", "compress", JsonPrimitive("compress"))
    @Test fun anInlineTableIsATable() {
        val d = tmp("inline"); val p = "$d/c.toml"; writeString(p, "proxy = { port = 2 }\n"); assertTrue(issues(p).isEmpty(), "${issues(p)}")
        writeString(p, "proxy = { port = 70000 }\n"); assertTrue(issues(p).any { it.contains("proxy.port") }); removeDirAll(d)
    }
    @Test fun aNegativeNumberIsRejectedForAnUnsignedKey() {
        val d = tmp("negative"); val p = "$d/c.toml"; writeString(p, "[core]\nretain_calls_days = -1\n")
        assertTrue(issues(p).any { it.contains("core.retain_calls_days") && it.contains("≥ 0") }, "${issues(p)}"); removeDirAll(d)
    }
    @Test fun configSubcommandsHonourConfigFlag() {
        val home = tmp("cfg-flag"); val ci = "$home/ci.toml"; writeString(ci, "[proxy]\nport = 1111\n")
        assertEquals(ci, Config.userPath(home, ci)); Validate.setWith(home, ci, "proxy.port", "2222", false)
        assertTrue(readToString(ci).contains("2222")); assertFalse(exists(Config.pathFor(home)))
        assertEquals(2222.toUShort(), Layers.load(home, ci, null).proxy.port); assertTrue(issues(ci).isEmpty(), "${issues(ci)}"); removeDirAll(home)
    }
}

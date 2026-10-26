package rtok.store

import rtok.db.*
import rtok.sys.*

/** Every test binary installs the app's SQLite adapter before any test runs. */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val installDriver = Db.installIfAbsent(rtok.db.room.RoomDbDriver)

private var nextTmp = 0

/** Port of support.rs `tmp_dir`: a fresh, empty directory under `$TMPDIR`. */
fun tmpDir(tag: String): String {
    val d = "${tempDir()}/rtok-$tag-${pid()}-${nextTmp++}"
    removeDirAll(d)
    createDirAll(d)
    return d
}

/** One proxy session, two api_request calls, usage for `anthropic` and `openai_chat`. */
fun seedTwoApis(store: Store) {
    store.upsertSession("s1", null, null, null, "proxy")
    val id1 = store.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/messages")
    val id2 = store.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/chat/completions")
    store.insertUsage("s1", "m", "anthropic", 10, 1, 2, 3, id1)
    store.insertUsage("s1", "m", "openai_chat", 20, 0, 5, 4, id2)
}

/** A raw second connection to `path` (diesel `SqliteConnection::establish`). */
fun rawDb(path: String): Db = Db.open(path)

fun meas(
    plugin: String,
    kind: String,
    before: ULong,
    after: ULong,
    estBefore: UInt,
    estAfter: UInt,
    refId: String? = null,
    callId: Int? = null,
) = rtok.plugin.sdk.Measurement(plugin, kind, before, after, estBefore, estAfter, refId, callId)

/** Run `n` copies of `body(i)` on their own threads; returns each one's error message, if any. */
fun parallel(
    n: Int,
    body: (Int) -> Unit,
): List<String> {
    val workers =
        (0 until n).map {
            kotlin.native.concurrent.Worker
                .start()
        }
    val futures =
        workers.mapIndexed { i, w ->
            w.execute(kotlin.native.concurrent.TransferMode.SAFE, { i to body }) { (k, f) ->
                try {
                    f(k)
                    null
                } catch (e: Throwable) {
                    e.message ?: e.toString()
                }
            }
        }
    val errs = futures.mapNotNull { it.result }
    workers.forEach { it.requestTermination().result }
    return errs
}

fun hookCallWithBody(
    store: Store,
    kind: String,
    body: ByteArray,
    ageDays: Long,
): Int {
    val id = store.insertCall("s", "hook", kind, null, null, null, null, null)
    store.insertCallIo(id, body, body, body.size + 1, null)
    store.setCallTs(id, unixNow() - ageDays * 86_400)
    return id
}

// ---- symbol fixtures (symbols_tests.rs helpers), shared by the symbol, project and retention tests ----
fun symRow(
    name: String,
    line: Int,
    isDef: Boolean,
) = rtok.plugin.sdk.SymbolRow(name, "function", line, isDef, line, "")

fun symImport(
    name: String,
    line: Int,
    spec: String = "",
) = rtok.plugin.sdk.SymbolRow(name, "import", line, false, line, spec)

fun symRef(
    name: String,
    line: Int,
    scope: String,
) = rtok.plugin.sdk.SymbolRow(name, "function", line, false, line, scope)

fun Store.index(
    root: String,
    path: String,
    vararg rows: rtok.plugin.sdk.SymbolRow,
) = replaceSymbols(root, path, "s", 0L to 0L, rows.toList())

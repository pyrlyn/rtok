package rtok.store

import rtok.sys.*

private var nextTmp = 0
/** Port of support.rs `tmp_dir`: a fresh, empty directory under `$TMPDIR`. */
fun tmpDir(tag: String): String {
    val d = "${tempDir()}/rtok-$tag-${pid()}-${nextTmp++}"
    removeDirAll(d); createDirAll(d); return d
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

fun meas(plugin: String, kind: String, before: ULong, after: ULong, estBefore: UInt, estAfter: UInt, refId: String? = null, callId: Int? = null) =
    rtok.plugin.sdk.Measurement(plugin, kind, before, after, estBefore, estAfter, refId, callId)

/** Run `n` copies of `body(i)` on their own threads; returns each one's error message, if any. */
fun parallel(n: Int, body: (Int) -> Unit): List<String> {
    val workers = (0 until n).map { kotlin.native.concurrent.Worker.start() }
    val futures = workers.mapIndexed { i, w ->
        w.execute(kotlin.native.concurrent.TransferMode.SAFE, { i to body }) { (k, f) -> try { f(k); null } catch (e: Throwable) { e.message ?: e.toString() } }
    }
    val errs = futures.mapNotNull { it.result }
    workers.forEach { it.requestTermination().result }
    return errs
}

fun hookCallWithBody(store: Store, kind: String, body: ByteArray, ageDays: Long): Int {
    val id = store.insertCall("s", "hook", kind, null, null, null, null, null)
    store.insertCallIo(id, body, body, body.size + 1, null)
    store.setCallTs(id, unixNow() - ageDays * 86_400); return id
}

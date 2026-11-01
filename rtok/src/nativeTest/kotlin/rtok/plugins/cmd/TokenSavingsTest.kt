package rtok.plugins.cmd

import rtok.config.Config
import rtok.estimate
import rtok.plugin.sdk.Class
import rtok.store.MeasRow
import rtok.sys.readBytes
import rtok.sys.removeDirAll
import kotlin.test.*

// The formatter floors in FormattersTest stop before the trailer, the fallbacks and the ledger; these go through
// the path a real run takes, so a saving that only exists on paper fails here.

/** One golden fixture through `emitFilteredTo`, on its own store so equal fixtures do not dedup each other. */
private class Emitted(
    val name: String,
    val body: ByteArray,
    val printed: ByteArray,
    val rows: List<MeasRow>,
    val archive: List<ByteArray>,
    val cfg: Config,
) {
    val row get() = rows.single()
}

private fun emit(
    tag: String,
    argv: List<String>,
    bodies: List<ByteArray>,
    exit: Int = 0,
): Emitted {
    val (c, dir) = cmdConfig("savings-$tag")
    val printed = ArrayList<Byte>()
    bodies.forEach { emitFilteredTo(c, argv, it, exit, null) { b -> printed.addAll(b.toList()) } }
    return Emitted(tag, bodies.last(), printed.toByteArray(), cmdRows(c), archived(c).map(::readBytes), c).also { removeDirAll(dir) }
}

private val corpus by lazy {
    goldenInputs().map { it to golden(it) }.filter { (_, g) -> g.argv.isNotEmpty() }.map { (f, g) ->
        emit(f.removeSuffix(".in"), g.argv, listOf(g.output.encodeToByteArray()), g.exit)
    }
}

private fun visibleTokens(
    e: Emitted,
    bytes: ByteArray,
) = estimate(Bounded.hostVisiblePrefix(bytes.decodeToString()), Class.Code, e.cfg.estimator)

class TokenSavingsTest {
    @Test fun theCorpusSavesTheFloorOverall() {
        assertTrue(corpus.size >= MIN_FIXTURES, "corpus shrank to ${corpus.size}")
        val before = corpus.sumOf { it.row.estBefore.toLong() }
        val after = corpus.sumOf { it.row.estAfter.toLong() }
        val pct = (before - after) * 100 / before
        println("token savings: $before -> $after tok, $pct% over ${corpus.size} fixtures")
        assertTrue(pct >= CORPUS_FLOOR, "saved $pct%, floor $CORPUS_FLOOR%")
    }

    @Test fun noFixtureCostsMoreThanTheRawOutput() {
        for (e in corpus) {
            assertTrue(e.printed.size <= e.body.size, "${e.name}: printed ${e.printed.size} of ${e.body.size} bytes")
            assertTrue(e.row.estAfter <= e.row.estBefore, "${e.name}: ${e.row}")
        }
    }

    @Test fun theLedgerClaimsWhatWasReallyPrinted() {
        for (e in corpus) {
            assertEquals(e.body.size.toLong(), e.row.beforeBytes, e.name)
            assertEquals(e.printed.size.toLong(), e.row.afterBytes, e.name)
            assertEquals(visibleTokens(e, e.body), e.row.estBefore, e.name)
            assertEquals(visibleTokens(e, e.printed), e.row.estAfter, e.name)
        }
    }

    @Test fun whatAPointerPromisesCanBeRecovered() {
        val pointed = corpus.filter { it.row.refId != null }
        assertTrue(pointed.size >= MIN_POINTED, "only ${pointed.size} fixtures archived")
        for (e in pointed) assertTrue(e.archive.any { it.contentEquals(e.body) }, "${e.name}: original is not in the archive")
    }

    @Test fun outputNothingCanShrinkCostsNothingExtra() {
        for (argv in listOf(listOf("git", "status"), listOf("cargo", "test"), listOf("ls"), listOf("some-unknown-tool"))) {
            for (n in FLAT_LINES) {
                val body = (0 until n).joinToString("") { "${(it * 2654435761L) % 1000003}-$it\n" }.encodeToByteArray()
                val e = emit("flat-${argv[0]}-$n", argv, listOf(body))
                assertTrue(e.printed.size <= body.size, "${e.name}: printed ${e.printed.size} of ${body.size} bytes")
                assertTrue(e.row.estAfter <= e.row.estBefore, "${e.name}: ${e.row}")
            }
        }
    }

    @Test fun aRepeatedOutputCostsAlmostNothing() {
        val body = numbered(400).encodeToByteArray()
        val rows = emit("repeat", listOf("cat", "notes.txt"), listOf(body, body)).rows
        assertEquals(listOf(false, true), rows.map { it.kind == "dedup" }, rows.toString())
        assertTrue(rows[1].estAfter * DEDUP_RATIO <= rows[1].estBefore, rows[1].toString())
    }

    private companion object {
        // Just under the measured 73%: a real regression trips it, a one-fixture wobble does not.
        const val CORPUS_FLOOR = 70
        const val MIN_FIXTURES = 90
        const val MIN_POINTED = 10
        const val DEDUP_RATIO = 20

        // Both sides of the 40-line trailer threshold.
        val FLAT_LINES = listOf(1, 2, 39, 40, 41, 200)
    }
}

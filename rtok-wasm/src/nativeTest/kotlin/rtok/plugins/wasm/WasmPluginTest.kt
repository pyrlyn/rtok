package rtok.plugins.wasm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonObject
import rtok.log.FileLog
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.MemoryHost
import rtok.plugin.sdk.Plugin
import rtok.wasm.WasmException
import rtok.wasm.chasm.ChasmRuntime
import rtok.wasm.guestSlice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class WasmPluginTest {
    private val fixtures = platform.posix.getenv("RTOK_WASM_FIXTURES")!!.toKString()
    private val tmp = "/tmp/rtok-wasm-test-${platform.posix.getpid()}"
    private val env = WasmHostEnv({ t, _ -> (t.length + 3) / 4 }, FileLog("$tmp/rtok.log", 1 shl 20, 2, "info"), {})

    // Port of `from_plugins_loads_demo_and_records_measurement` (registry half lives in :rtok RegistryTest).
    @Test fun loadsDemoAndRecordsMeasurement() {
        val p = WasmPlugin.load("$fixtures/wasm-demo.wasm", ChasmRuntime, env)
        assertEquals("wasm-demo", p.manifest().id)
        assertEquals(listOf("demo"), p.mcpTools().map { it.name })
        val host = MemoryHost()
        val text = p.invokeMcp("demo", JsonObject(emptyMap()), Ctx(host))
        assertTrue("ok" in text)
        val rows = host.recorded()
        assertEquals(1, rows.size)
        assertEquals("wasm-demo", rows[0].plugin)
        assertEquals("demo", rows[0].kind)
        assertTrue(rows[0].beforeBytes >= rows[0].afterBytes)
        assertTrue(rows[0].estBefore >= rows[0].estAfter)
    }

    @Test fun appendSkipsBadFilesAndHonoursConfig() {
        platform.posix.mkdir(tmp, 0x1c0u)
        rtok.sys.writeBytes("$tmp/wasm-demo.wasm", rtok.sys.readBytes("$fixtures/wasm-demo.wasm"))
        rtok.sys.writeBytes("$tmp/broken.wasm", byteArrayOf(1, 2, 3))
        rtok.sys.writeBytes("$tmp/readme.txt", byteArrayOf(1))
        val errors = mutableListOf<String>()
        val out = appendWasm(emptyList<Pair<Plugin, Boolean>>(), tmp, ChasmRuntime, env, { _, d -> d }, { errors += it })
        assertEquals(listOf("wasm-demo" to true), out.map { it.first.manifest().id to it.second })
        assertEquals(1, errors.size)
        assertTrue("broken.wasm" in errors[0])
        assertTrue(appendWasm(emptyList(), "$tmp/missing", ChasmRuntime, env, { _, d -> d }, {}).isEmpty())
    }

    // Port of `guest_slice_rejects_what_used_to_panic`.
    @Test fun guestSliceRejectsWhatUsedToPanic() {
        val data = ByteArray(16) { 7 }
        assertFailsWith<WasmException> { guestSlice(data, -1, 2) }
        assertFailsWith<WasmException> { guestSlice(data, 0, -1) }
        assertFailsWith<WasmException> { guestSlice(data, Int.MAX_VALUE, Int.MAX_VALUE) }
        assertFailsWith<WasmException> { guestSlice(data, 8, 9) }
        assertContentEquals(ByteArray(8) { 7 }, guestSlice(data, 8, 8))
        assertTrue(guestSlice(data, 16, 0).isEmpty())
    }

    // Port of `endless_guest_runs_out_of_fuel`: (memory (export "memory") 1)
    // (func (export "rtok_manifest") (result i64) (loop $l (br $l)) (i64.const 0)).
    @Test fun endlessGuestRunsOutOfFuel() {
        val m =
            intArrayOf(
                0,
                0x61,
                0x73,
                0x6d,
                1,
                0,
                0,
                0,
                1,
                5,
                1,
                0x60,
                0,
                1,
                0x7e,
                3,
                2,
                1,
                0,
                5,
                3,
                1,
                0,
                1,
                7,
                0x1a,
                2,
                6,
                *"memory".map { it.code }.toIntArray(),
                2,
                0,
                13,
                *"rtok_manifest".map { it.code }.toIntArray(),
                0,
                0,
                0x0a,
                0x0b,
                1,
                9,
                0,
                3,
                0x40,
                0x0c,
                0,
                0x0b,
                0x42,
                0,
                0x0b,
            )
        platform.posix.mkdir(tmp, 0x1c0u)
        rtok.sys.writeBytes("$tmp/spin.wasm", ByteArray(m.size) { m[it].toByte() })
        assertFailsWith<WasmException> { WasmPlugin.loadWithFuel("$tmp/spin.wasm", ChasmRuntime, env, 10_000) }
    }

    @Test fun unpackSplitsPointerAndLength() {
        assertEquals(16 to 4, unpackI64((4L shl 32) or 16L))
        assertEquals(-1 to 0, unpackI64(0xffff_ffffL))
    }
}

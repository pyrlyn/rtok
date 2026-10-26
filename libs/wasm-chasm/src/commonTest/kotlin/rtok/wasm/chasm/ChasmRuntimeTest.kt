package rtok.wasm.chasm

import rtok.wasm.WasmException
import rtok.wasm.WasmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ChasmRuntimeTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private val header = intArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0, 0, 0)

    // (func (export "add") (param i32 i32) (result i32) local.get 0 local.get 1 i32.add)
    private val add = bytes(*header, 1, 7, 1, 0x60, 2, 0x7f, 0x7f, 1, 0x7f, 3, 2, 1, 0,
        7, 7, 1, 3, 'a'.code, 'd'.code, 'd'.code, 0, 0, 0x0a, 9, 1, 7, 0, 0x20, 0, 0x20, 1, 0x6a, 0x0b)

    // (func (export "spin") (loop br 0))
    private val spin = bytes(*header, 1, 4, 1, 0x60, 0, 0, 3, 2, 1, 0,
        7, 8, 1, 4, 's'.code, 'p'.code, 'i'.code, 'n'.code, 0, 0, 0x0a, 9, 1, 7, 0, 3, 0x40, 0x0c, 0, 0x0b, 0x0b)

    @Test fun callsAnExport() {
        val i = ChasmRuntime.instantiate(add, emptyList())
        assertEquals(listOf<WasmValue>(WasmValue.I32(5)), i.call("add", listOf(WasmValue.I32(2), WasmValue.I32(3))))
        assertTrue(i.hasExport("add")); assertTrue(!i.hasExport("nope"))
    }

    @Test fun rejectsGarbage() { assertFailsWith<WasmException> { ChasmRuntime.instantiate(bytes(1, 2, 3), emptyList()) } }

    // Mirrors Rust `endless_guest_runs_out_of_fuel`.
    @Test fun endlessGuestRunsOutOfFuel() {
        val i = ChasmRuntime.instantiate(spin, emptyList(), fuel = 1_000_000)
        val t = measureTime { assertFailsWith<WasmException> { i.call("spin") } }
        println("chasm: 1M fuel exhausted in $t")
        i.setFuel(1_000_000) // refuelled instance traps again rather than hanging
        assertFailsWith<WasmException> { i.call("spin") }
    }
}

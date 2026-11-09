# WASM plugins

Port of `src/plugins/wasm.rs`. Out-of-tree plugins compiled to WebAssembly are loaded from a directory
and join the plugin registry like built-in ones (MCP, proxy and CLI surfaces; never `hook`).

## How it works
- `pyrlyn.wasm:wasm` is the engine interface (`WasmRuntime`, `WasmInstance`, `WasmMemory`,
  `HostFunction`, fuel), shared with Octo in the kotlin-wasm package (an included build at
  `../../packages/kotlin-wasm` or `KOTLIN_WASM_DIR`). `pyrlyn.wasm:wasm-chasm` is the only code that sees the third-party engine,
  [Chasm](https://github.com/CharlieTap/chasm) 2.3.0 (pure Kotlin Multiplatform interpreter).
- `:rtok-wasm` is the host: `WasmPlugin.load` instantiates the module with the host imports
  `env.rtok_estimate`, `env.rtok_record_measurement`, `env.rtok_log`, reads the JSON manifest from
  `rtok_manifest`, the tool list from `rtok_mcp_tools`, and runs `rtok_on_mcp_tool`. Guest strings are
  packed `(ptr, len)` i64 values, low half pointer. Same ABI as Rust, so the same `.wasm` files work.
- Every call (instantiation included) is metered: `FUEL` = 100 000 000 units per call. An endless guest
  traps instead of hanging `rtok mcp`. Chasm counts fuel per instruction-ish step like wasmi.
- `rtok/src/wasmPlugins/kotlin/rtok/plugins/WasmPlugins.kt` wires it into `Registry.fromPlugins`.

## Build flag
WASM plugins are opt-in at build time: `-Prtok.wasm=true` (default `false` in `gradle.properties`) includes
`:rtok-wasm` and the kotlin-wasm build and links Chasm into `:rtok`. Kotlin has no conditional compilation, so the
flag picks a source dir: `src/wasmPlugins` or the stub `src/noWasmPlugins`. Without the flag
`[plugins.wasm] enabled = true` warns on stderr and in the log (`warn wasm load ...`) and loads nothing.

## Enable / disable
In a build with the flag:
```toml
[plugins.wasm]
enabled = true            # default false
dir = "~/.rtok/plugins"   # every *.wasm here is loaded
```
Each plugin can then be switched with `[plugins] <id> = false` like any other. A file that fails to
load is reported on stderr and in the log (`error wasm load ...`) and skipped.

## Test
`./gradlew wasmTest -Prtok.wasm=true` (`:rtok-wasm`, 5 tests) and the adapter's own tests in kotlin-wasm (`./gradlew check` there). The fixture
`rtok-wasm/src/nativeTest/fixtures/wasm-demo.wasm` is the Rust crate `rtok-wasm-demo-guest`. Rebuild:
```sh
cd ../rtok && CARGO_TARGET_DIR=/tmp/wasm-demo cargo build --locked -p rtok-wasm-demo-guest \
  --target wasm32-unknown-unknown --release
cp /tmp/wasm-demo/wasm32-unknown-unknown/release/rtok_wasm_demo_guest.wasm ../rtok-kotlin/rtok-wasm/src/nativeTest/fixtures/wasm-demo.wasm
```

## Limitations
- Chasm is an interpreter; it is slower than wasmi (see the measurements in port-plan.md).
- Like Rust, the tool name and arguments are not passed to the guest yet.
- WAT text modules are not accepted (wasmi accepts them); ship binary `.wasm`.

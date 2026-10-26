// WASM plugin host (port of src/plugins/wasm.rs): our own `WasmRuntime` interface here; the Chasm
// adapter (approved, Apache-2.0) plugs in behind it. Its tests run on their own: `./gradlew wasmTest`.
tasks.register("wasmTest") {
    group = "verification"; description = "Runs the WASM plugin host tests (:rtok-wasm)."
    dependsOn("macosArm64Test")
}

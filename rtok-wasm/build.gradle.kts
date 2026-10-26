// WASM plugin host (port of src/plugins/wasm.rs) over rtok.wasm (:wasm). Tests: `./gradlew wasmTest`.
dependencies { "commonMainApi"(project(":wasm")) }
tasks.register("wasmTest") {
    group = "verification"; description = "Runs the WASM plugin host tests (:rtok-wasm)."
    dependsOn("macosArm64Test")
}

// WASM plugin host (port of src/plugins/wasm.rs) over the engine-neutral rtok.wasm interface (:wasm).
// The engine adapter (:wasm-chasm) is chosen by the binary (:rtok); tests use it too.
// Fixture wasm-demo.wasm is the Rust crate rtok-wasm-demo-guest built for wasm32-unknown-unknown
// (docs/wasm-plugins.md shows how to rebuild it). Tests: `./gradlew wasmTest -Prtok.wasm=true`.
// The module exists in the build only with that flag (settings.gradle.kts).
configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies {
        api("pyrlyn.wasm:wasm")
        api(project(":rtok-plugin-sdk"))
        implementation(project(":rtok-log"))
        implementation(project(":rtok-sys"))
    }
    sourceSets.commonTest.dependencies { implementation("pyrlyn.wasm:wasm-chasm") }
}
val fixtures =
    layout.projectDirectory
        .dir("src/nativeTest/fixtures")
        .asFile.absolutePath
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach { environment("RTOK_WASM_FIXTURES", fixtures) }
tasks.register("wasmTest") {
    group = "verification"
    description = "Runs the WASM plugin host tests (:rtok-wasm)."
    dependsOn("macosArm64Test")
}

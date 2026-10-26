// Adapter: Chasm (Kotlin Multiplatform WebAssembly interpreter, Apache-2.0) behind rtok.wasm.
configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies { api(project(":wasm")); implementation("io.github.charlietap.chasm:chasm:2.3.0") }
}

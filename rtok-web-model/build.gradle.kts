import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Pure UI logic shared by the web frontends (snapshot parsing, page models, drill/compare/fix state):
// no DOM, no Compose. Tests run on macosArm64 (the gate); the web targets only compile it for the UIs.
configure<KotlinMultiplatformExtension> {
    js { browser { testTask { enabled = false } } }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { enabled = false } } }
}

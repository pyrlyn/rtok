// posix_spawn comes from the system <spawn.h>; see src/nativeInterop/cinterop/spawn.def.
extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("spawn")
    }
}

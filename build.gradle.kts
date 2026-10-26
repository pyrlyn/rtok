plugins {
    kotlin("multiplatform") version "2.2.20" apply false; kotlin("plugin.serialization") version "2.2.20" apply false
    // API docs: `./gradlew dokkaGenerate` only; never wired into build/test (docs/api-docs.md).
    id("org.jetbrains.dokka") version "2.2.0"
}

// One aggregated HTML site over every module, declared once here.
dependencies { subprojects.forEach { add("dokka", it) } }
dokka { moduleName.set("rtok-kotlin") }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jetbrains.dokka")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        macosArm64()
        if (providers.gradleProperty("rtok.allTargets").orNull == "true") { linuxX64(); linuxArm64() }
        sourceSets.commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    }
}

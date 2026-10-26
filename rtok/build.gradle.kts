import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
configure<KotlinMultiplatformExtension> {
    targets.withType<KotlinNativeTarget>().configureEach { binaries.executable { entryPoint = "rtok.main"; baseName = "rtok" } }
    sourceSets.commonMain.dependencies {
        implementation(project(":rtok-plugin-sdk")); implementation(project(":rtok-config"))
        implementation("com.github.ajalt.clikt:clikt:5.0.3")
    }
}
val fixtures = layout.projectDirectory.dir("src/commonTest/fixtures").asFile.absolutePath
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach { environment("RTOK_FIXTURES", fixtures) }

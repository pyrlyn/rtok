import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

configure<KotlinMultiplatformExtension> { sourceSets.commonMain.dependencies { api(project(":json-strict")) } }

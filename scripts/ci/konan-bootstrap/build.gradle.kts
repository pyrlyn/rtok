plugins { kotlin("multiplatform") }

// Every Linux target that -Prtok.allTargets=true configures, since CrossCc looks up each one's toolchain.
kotlin {
    linuxX64 { binaries.executable { entryPoint = "main" } }
    linuxArm64 { binaries.executable { entryPoint = "main" } }
}

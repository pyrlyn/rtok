// posix_spawn comes from the system <spawn.h>; see src/nativeInterop/cinterop/spawn.def.
// The rtok_* C helpers are compiled once into librtok_spawn.a and linked via cinterop `staticLibraries`
// (docs/experiments/binary-split.md); the build logic lives in ci-diffuser (`ci.diffuser.cstatic`).
plugins { id("ci.diffuser.cstatic") }
configure<ci.diffuser.CStaticExtension> { library(name = "rtok_spawn", cinterop = "spawn") }

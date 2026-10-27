package rtok.cli

import io.kotest.core.NamedTag

/** Tag of every Kotest CLI spec: `cliTest` runs only these (KOTEST_TAGS=Cli), `macosArm64Test` skips them (KOTEST_TAGS=!Cli). */
val Cli = NamedTag("Cli")

/** Tag of the Kotest agent specs: `agentTest` runs only these (KOTEST_TAGS=Agents), `noAgentTest` skips them. */
val Agents = NamedTag("Agents")

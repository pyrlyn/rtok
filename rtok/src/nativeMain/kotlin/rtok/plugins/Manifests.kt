// Ports of src/plugins/measure and src/plugins/proxy: manifest and page only; their work runs in
// the CLI (stats) and the proxy.
package rtok.plugins

import rtok.plugin.sdk.DashboardPage
import rtok.plugin.sdk.Manifest
import rtok.plugin.sdk.Plugin
import rtok.plugin.sdk.Surface

object MeasurePlugin : Plugin {
    override fun manifest() = Manifest("measure", listOf(Surface.Cli, Surface.Proxy), true)

    override fun dashboardPage() = DashboardPage("Measure", "Only Measurement rows count as savings. Stats from transcripts and proxy usage.", false)
}

object ProxyPlugin : Plugin {
    override fun manifest() = Manifest("proxy", listOf(Surface.Proxy), true)

    override fun dashboardPage() = DashboardPage("Proxy usage", "Record provider usage; compress mode runs plugin proxy_filter.", true)
}

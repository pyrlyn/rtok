// Entry point: mounts the Compose app on the whole page.
package rtok.web.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val state = AppState()
    state.start()
    ComposeViewport { App(state) }
}

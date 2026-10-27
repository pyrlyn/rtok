// Port of src/model/mod.rs `config_page_text` (T228): `rtok config show --sources` rows as the Config page.
package rtok.model

import rtok.config.Config
import rtok.config.Layers

object ConfigPage {
    /** `key = value (source)` per effective key, secrets redacted. Read-only: never creates the user file. */
    fun text(cfg: Config): String? =
        runCatching {
            Layers.entries(Layers.figment(cfg.home, cfg.loadedFrom, null)).joinToString("") { (k, v, s) -> "$k = $v ($s)\n" }
        }.getOrNull()
}

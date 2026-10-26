// Port of crates/rtok-config/src/estimator.rs
package rtok.config

import kotlinx.serialization.Serializable

/** `[estimator]` — chars per token per class, rewritten by `stats --calibrate`. */
@Serializable
data class Estimator(
    val code: Float = 3.5f,
    val prose: Float = 4.2f,
    val json: Float = 3.0f,
    val cjk: Float = 1.0f,
)

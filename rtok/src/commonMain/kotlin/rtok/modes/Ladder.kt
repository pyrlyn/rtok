// Port of src/modes/ladder.rs — structured YAGNI ladder.
package rtok.modes

data class LadderContext(
    val speculative: Boolean = false,
    val existingHelper: Boolean = false,
    val stdlib: Boolean = false,
    val native: Boolean = false,
    val installedDep: Boolean = false,
    val oneLiner: Boolean = false,
    val mustNotSimplify: Boolean = false,
)

enum class LadderDecision { YagniSkip, Reuse, Stdlib, NativePlatform, InstalledDep, OneLiner, Minimum }

fun evaluateLadder(ctx: LadderContext): LadderDecision =
    when {
        ctx.mustNotSimplify -> LadderDecision.Minimum
        ctx.speculative -> LadderDecision.YagniSkip
        ctx.existingHelper -> LadderDecision.Reuse
        ctx.stdlib -> LadderDecision.Stdlib
        ctx.native -> LadderDecision.NativePlatform
        ctx.installedDep -> LadderDecision.InstalledDep
        ctx.oneLiner -> LadderDecision.OneLiner
        else -> LadderDecision.Minimum
    }

fun naiveAlwaysMinimum(
    @Suppress("UNUSED_PARAMETER") ctx: LadderContext,
) = LadderDecision.Minimum

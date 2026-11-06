package app.lararium.hearth.rails

/** Tiny seam so flavor-dependent logic is testable in every flavored test source set. */
fun interface DistChannelProvider {
    fun channel(): String
}

/** Production provider backed by the build flavor. */
object BuildConfigChannel : DistChannelProvider {
    override fun channel(): String = app.lararium.BuildConfig.DIST_CHANNEL
}

/**
 * Single flavor-aware source of truth for which rails this build declares to
 * the daemon (and shows in the UI). The Play build must not claim rails it
 * cannot service: dialer and sms (action executors) and calllog (READ_CALL_LOG).
 */
object DeclaredRails {
    const val CHANNEL_PLAY = "play"

    /** Rails that exist only in the GitHub build. */
    val GITHUB_ONLY: Set<String> = setOf("dialer", "sms", "calllog")

    fun names(provider: DistChannelProvider = BuildConfigChannel): Set<String> =
        if (provider.channel() == CHANNEL_PLAY) {
            RailsWire.RAIL_NAMES - GITHUB_ONLY
        } else {
            RailsWire.RAIL_NAMES
        }

    fun build(provider: DistChannelProvider = BuildConfigChannel): List<DeclaredRail> =
        names(provider).map { name ->
            DeclaredRail(
                name = name,
                readOnly = name in RailsWire.READ_RAILS,
                enabled = true
            )
        }
}

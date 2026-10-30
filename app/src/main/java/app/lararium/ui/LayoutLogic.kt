package app.lararium.ui

/** Window-size classes the app lays itself out for. */
enum class LayoutMode { PHONE, TABLET }

/**
 * Pure (JVM-testable) layout decisions. No Android or Compose dependencies.
 */
object LayoutLogic {
    /** Width (dp) at or above which the tablet layout is used. */
    const val TABLET_MIN_WIDTH_DP = 600f

    /** Width <= 0, NaN or infinite-negative falls back to [LayoutMode.PHONE]. */
    fun modeForWidth(widthDp: Float): LayoutMode =
        if (!widthDp.isNaN() && widthDp >= TABLET_MIN_WIDTH_DP) LayoutMode.TABLET else LayoutMode.PHONE

    fun chatLayoutForWidth(widthDp: Float): ChatLayout = ChatLayout(modeForWidth(widthDp))
}

/** Layout parameters for the chat surface. */
data class ChatLayout(
    val mode: LayoutMode,
    val railWidthDp: Float = 80f,
    val contentMaxWidthDp: Float = 840f
) {
    /** Tablets replace the modal drawer with a navigation rail. */
    val useNavigationRail: Boolean get() = mode == LayoutMode.TABLET

    /** Chat content is width-capped and centered only on tablets. */
    val capContentWidth: Boolean get() = mode == LayoutMode.TABLET
}

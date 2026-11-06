package app.lararium.service

/**
 * #93 hard gate: microphone capture may only start from user-initiated entry
 * points (QS tile / assist intent / notification action → overlay, and in-app
 * mic taps). Any other caller is refused before any capture object is touched.
 */
enum class VoiceCaptureOrigin(val userInitiated: Boolean) {
    /** Mic button tap in the overlay / chat UI. */
    UI_TAP(true),

    /** Overlay auto-start, only reachable after a user-initiated activation. */
    OVERLAY_AUTOSTART(true),

    /** ACTION_START_VOICE intent carrying EXTRA_USER_INITIATED=true (e.g. notification action). */
    INTENT_USER_INITIATED(true),

    /** ACTION_START_VOICE intent without the user-initiated marker (automation, broadcast relay). */
    INTENT_UNATTRIBUTED(false)
}

class VoiceCaptureGate(
    private val userInitiatedProvider: (VoiceCaptureOrigin) -> Boolean = { it.userInitiated },
    private val log: (String) -> Unit = {}
) {
    /** Runs [startCapture] only if [origin] is user-initiated. Returns whether it ran. */
    fun guard(origin: VoiceCaptureOrigin, startCapture: () -> Unit): Boolean {
        if (!userInitiatedProvider(origin)) {
            log("Refused voice capture from non-user-initiated origin: $origin")
            return false
        }
        startCapture()
        return true
    }
}

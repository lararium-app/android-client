package app.lararium.auth

/**
 * Pure Kotlin policy governing app lock and background re-lock transitions (R2).
 * Pure logic — zero Android dependencies for pure JVM unit testability.
 */
class AppLockPolicy(
    private val relockTimeoutMs: Long = LOCK_RELOCK_BACKGROUND_MS
) {
    companion object {
        const val LOCK_RELOCK_BACKGROUND_MS = 300_000L
    }

    /**
     * All app entry paths that can reach hearth or device-rails state (T6 A.1).
     */
    enum class EntryPath {
        LAUNCHER_MAIN,
        ASSIST_GESTURE,
        NOTIFICATION_CARD_REVIEW,
        NOTIFICATION_SESSION_REPLY,
        DEEP_LINK_HEARTH_PAIR,
        DEEP_LINK_DEVICE_PAIR,
        DEVICES_ACTIVITY,
        ASSISTANT_OVERLAY,
        RECENT_APPS_RESUME
    }

    /**
     * Whether entering the app via [path] requires unlocking before any hearth call or screen render.
     * Every entry path is gated behind [isUnlocked] (cold start + resume past [relockTimeoutMs]).
     */
    fun requiresUnlockForEntry(@Suppress("UNUSED_PARAMETER") path: EntryPath): Boolean {
        return !isUnlocked
    }

    /**
     * Whether background/foreground components are permitted to issue hearth calls right now.
     */
    fun canReachHearth(): Boolean {
        return isUnlocked
    }

    /**
     * Whether the app is currently unlocked. Initial state is false (LOCKED).
     */
    var isUnlocked: Boolean = false
        private set

    /**
     * Timestamp (ms) when the app was most recently backgrounded, or null if foregrounded.
     */
    var lastBackgroundMs: Long? = null
        private set

    /**
     * Tracks whether the app has transitioned through the background since last unlock.
     * Used by scary-verb gates to require re-authentication.
     */
    var hasBeenBackgrounded: Boolean = false
        private set

    /**
     * Unlocks the app and clears background timing and flags.
     */
    fun unlock() {
        isUnlocked = true
        lastBackgroundMs = null
        hasBeenBackgrounded = false
    }

    /**
     * Locks the app immediately.
     */
    fun lock() {
        isUnlocked = false
        lastBackgroundMs = null
        hasBeenBackgrounded = false
    }

    /**
     * Record transition to background.
     */
    fun onBackground(nowMs: Long) {
        if (isUnlocked) {
            lastBackgroundMs = nowMs
            hasBeenBackgrounded = true
        }
    }

    /**
     * Record transition to foreground. Re-locks if backgrounded longer than [relockTimeoutMs].
     * Resets background timer so subsequent checks start fresh.
     */
    fun onForeground(nowMs: Long) {
        val bgTime = lastBackgroundMs
        if (bgTime != null) {
            val elapsed = nowMs - bgTime
            if (elapsed > relockTimeoutMs) {
                isUnlocked = false
                hasBeenBackgrounded = false
            }
            lastBackgroundMs = null
        }
    }

    /**
     * Clears the backgrounded flag once fresh confirmation has completed.
     */
    fun clearBackgroundFlag() {
        hasBeenBackgrounded = false
    }
}

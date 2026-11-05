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

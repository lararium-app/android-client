package app.lararium.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory lock-state holder (R2).
 *
 * Initial state is LOCKED. Tracks background/foreground transitions and automatically
 * re-locks after [LOCK_RELOCK_BACKGROUND_MS] continuously in the background.
 * Exposes pure transition methods [onForeground], [onBackground], and [isUnlocked].
 */
object AppLock {
    const val LOCK_RELOCK_BACKGROUND_MS = AppLockPolicy.LOCK_RELOCK_BACKGROUND_MS

    val policy = AppLockPolicy(LOCK_RELOCK_BACKGROUND_MS)

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlockedState: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    val isUnlocked: Boolean
        get() = _isUnlocked.value

    var authenticator: Authenticator = BiometricPromptAuthenticator()

    @Synchronized
    fun unlock() {
        policy.unlock()
        _isUnlocked.value = policy.isUnlocked
    }

    @Synchronized
    fun lock() {
        policy.lock()
        _isUnlocked.value = policy.isUnlocked
    }

    @Synchronized
    fun onBackground(nowMs: Long = System.currentTimeMillis()) {
        policy.onBackground(nowMs)
        _isUnlocked.value = policy.isUnlocked
    }

    @Synchronized
    fun onForeground(nowMs: Long = System.currentTimeMillis()) {
        policy.onForeground(nowMs)
        _isUnlocked.value = policy.isUnlocked
    }

    @Synchronized
    fun resetForTesting() {
        policy.lock()
        _isUnlocked.value = false
        authenticator = BiometricPromptAuthenticator()
    }
}

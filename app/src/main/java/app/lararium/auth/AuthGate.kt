package app.lararium.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pure logic runner for scary-verb authentication gates (R3).
 *
 * Checks whether the app is currently locked, the action is destructive, or the app
 * has been backgrounded since last unlock. If authentication is needed, prompts the
 * [authenticator]. Only on success does it execute [block].
 */
class AuthGateLogic(
    private val appLockPolicy: AppLockPolicy,
    private val authenticator: Authenticator
) {
    suspend fun execute(
        activity: Any?,
        reasonTitle: String,
        reasonText: String = "",
        destructive: Boolean,
        block: suspend () -> Unit
    ): Boolean {
        val needsAuth = !appLockPolicy.isUnlocked || destructive || appLockPolicy.hasBeenBackgrounded

        if (!needsAuth) {
            block()
            return true
        }

        val success = suspendCancellableCoroutine<Boolean> { cont ->
            authenticator.authenticate(
                activity = activity,
                reasonTitle = reasonTitle,
                reasonText = reasonText
            ) { result ->
                if (cont.isActive) {
                    cont.resume(result)
                }
            }
        }

        if (success) {
            appLockPolicy.unlock()
            block()
            return true
        } else {
            return false
        }
    }
}

/**
 * Helper to resolve the hosting [FragmentActivity] from an Android [Context].
 */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Scary-verb gate helper (R3).
 *
 * Routes scary actions through biometric/credential authentication when the app
 * has been backgrounded OR the verb is destructive (connector delete, device unpair).
 *
 * On deny/failure: prompt shows its own error UX, does NOT execute [block], leaves app state unchanged.
 */
suspend fun authGate(
    context: Context,
    reasonTitle: String,
    destructive: Boolean = false,
    block: suspend () -> Unit
) {
    val activity = context.findFragmentActivity()
    val runner = AuthGateLogic(
        appLockPolicy = AppLock.policy,
        authenticator = AppLock.authenticator
    )
    runner.execute(
        activity = activity,
        reasonTitle = reasonTitle,
        reasonText = "Confirm your identity to proceed",
        destructive = destructive,
        block = block
    )
}

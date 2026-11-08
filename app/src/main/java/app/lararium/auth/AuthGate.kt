package app.lararium.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Enumerates every scary verb in the app (T6 A.2).
 */
enum class ScaryVerb(val defaultTitle: String, val alwaysDestructive: Boolean) {
    CONNECTOR_ADD("Save Connector", false),
    CONNECTOR_EDIT("Save Connector", false),
    CONNECTOR_DELETE("Delete Connector", true),
    CONNECTOR_REAUTH("Re-authenticate Connector", false),
    APPROVAL_RESOLVE("Confirm Approval", false),
    DEVICE_ACTION_APPROVE("Approve Device Action", false),
    HEARTH_PAIR_WRITE("Pair Hearth", false),
    HEARTH_UNPAIR_CLEAR("Unpair Hearth", true),
    DEVICE_RAILS_PAIR_WRITE("Pair Device Rails", false),
    DEVICE_RAILS_REPAIR_CLEAR("Re-pair Device Rails", true),
    DEVICE_RAILS_UNPAIR_CLEAR("Forget Hearth", true)
}

/**
 * Enumerates every secret-write / secret-clear target across Keystore & Hearth vault (T6 A.2).
 */
enum class SecretWriteTarget {
    HEARTH_ENDPOINT_SET,
    HEARTH_ENDPOINT_CLEAR,
    PAIRING_CODE_SET,
    DEVICE_RAILS_ENDPOINT_SAVE,
    DEVICE_RAILS_ENDPOINT_CLEAR,
    CONNECTOR_SECRET_SUBMIT,
    CONNECTOR_REAUTH
}

/**
 * Pure logic runner for scary-verb authentication gates (R3, T6 A.2).
 *
 * Checks whether the app is currently locked, the action is destructive, or the app
 * has been backgrounded since last unlock. If authentication is needed, prompts the
 * [authenticator]. Only on success does it execute [block].
 */
class AuthGateLogic(
    private val appLockPolicy: AppLockPolicy,
    private val authenticator: Authenticator
) {
    companion object {
        /**
         * Pure decision: every secret write or destructive secret clear requires passing through [AuthGateLogic].
         */
        fun requiresGateForSecretWrite(@Suppress("UNUSED_PARAMETER") target: SecretWriteTarget): Boolean = true

        /**
         * Pure decision: whether a scary verb is treated as destructive (always prompts, even in-session).
         * Replacing an existing secret (e.g. re-pairing over an existing hearth endpoint) is destructive.
         */
        fun isDestructiveVerb(verb: ScaryVerb, replacingExistingSecret: Boolean = false): Boolean {
            return verb.alwaysDestructive || replacingExistingSecret
        }

        /**
         * Pure decision: whether [execute] must prompt the user via [Authenticator] right now.
         */
        fun shouldAuthenticate(
            isUnlocked: Boolean,
            destructive: Boolean,
            hasBeenBackgrounded: Boolean
        ): Boolean {
            return !isUnlocked || destructive || hasBeenBackgrounded
        }
    }

    suspend fun execute(
        activity: Any?,
        reasonTitle: String,
        reasonText: String = "",
        destructive: Boolean,
        block: suspend () -> Unit
    ): Boolean {
        val needsAuth = shouldAuthenticate(
            isUnlocked = appLockPolicy.isUnlocked,
            destructive = destructive,
            hasBeenBackgrounded = appLockPolicy.hasBeenBackgrounded
        )

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
 * has been backgrounded OR the verb is destructive (connector delete, device unpair, secret overwrite).
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
    val succeeded = runner.execute(
        activity = activity,
        reasonTitle = reasonTitle,
        reasonText = "Confirm your identity to proceed",
        destructive = destructive,
        block = block
    )
    if (succeeded) {
        AppLock.unlock()
    }
}

/**
 * Typed scary-verb overload routing [ScaryVerb] through [authGate].
 */
suspend fun authGate(
    context: Context,
    verb: ScaryVerb,
    replacingExistingSecret: Boolean = false,
    reasonTitle: String = verb.defaultTitle,
    block: suspend () -> Unit
) {
    authGate(
        context = context,
        reasonTitle = reasonTitle,
        destructive = AuthGateLogic.isDestructiveVerb(verb, replacingExistingSecret),
        block = block
    )
}

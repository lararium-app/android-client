package app.lararium.auth

/**
 * Pluggable authenticator interface for biometric and device-credential authentication (R2).
 */
interface Authenticator {
    /**
     * Request authentication from the user.
     *
     * @param activity The hosting activity (must be a FragmentActivity on Android; Any? for pure JVM testing)
     * @param reasonTitle Short headline explaining the authentication prompt
     * @param reasonText Subtitle or details explaining the prompt
     * @param onResult Callback invoked with true on success, false on failure or cancellation
     */
    fun authenticate(
        activity: Any?,
        reasonTitle: String,
        reasonText: String,
        onResult: (Boolean) -> Unit
    )
}

package app.lararium.hearth.rails.service

import android.content.Context

/**
 * The local action rails (dial, SMS), compiled into every build. When the
 * backing permission is missing or revoked each executor returns a degraded
 * `permission_revoked` result instead of throwing.
 */
object ActionExecutors {
    fun forContext(context: Context): List<RailToolExecutor> =
        listOf(DialerToolExecutor(context), SmsToolExecutor(context))
}

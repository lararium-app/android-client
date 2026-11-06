package app.lararium.hearth.rails.service

import android.content.Context

/**
 * Play flavor: no local action executors. DialerToolExecutor and SmsToolExecutor
 * live only in the github source set, so the Play build can neither dial nor
 * send SMS and the compiler/R8 prove the exclusion.
 */
object ActionExecutors {
    @Suppress("UNUSED_PARAMETER")
    fun forContext(context: Context): List<RailToolExecutor> = emptyList()
}

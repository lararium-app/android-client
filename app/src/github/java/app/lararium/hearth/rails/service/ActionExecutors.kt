package app.lararium.hearth.rails.service

import android.content.Context

/**
 * GitHub (sideload) flavor: the local action rails (dial, SMS) are compiled in.
 * The play flavor has a same-named object that returns an empty list, so the
 * executor classes do not exist in the Play build at all.
 */
object ActionExecutors {
    fun forContext(context: Context): List<RailToolExecutor> =
        listOf(DialerToolExecutor(context), SmsToolExecutor(context))
}

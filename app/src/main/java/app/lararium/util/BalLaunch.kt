package app.lararium.util

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Background-activity-launch (BAL) safe activity starter.
 *
 * Since Android 14, stock OEM builds silently DROP [Context.startActivity] when the
 * calling UID has no visible window or recently-foreground task — for example after the
 * user swipes the app away from Recents while its foreground service keeps running. No
 * exception is thrown; the activity simply never appears. The sanctioned route is a
 * self-created [PendingIntent] sent with an explicit opt-in via
 * [ActivityOptions.setPendingIntentBackgroundActivityLaunchAllowed]. Devices below API 34
 * keep the legacy permissive rules and use the direct start.
 */
object BalLaunch {
    private const val TAG = "BalLaunch"
    internal const val MIN_SDK_BAL_OPT_IN = Build.VERSION_CODES.UPSIDE_DOWN_CAKE // 34

    /** Pure strategy switch, JVM-testable without framework classes. */
    fun usesPendingIntentPath(sdkInt: Int): Boolean = sdkInt >= MIN_SDK_BAL_OPT_IN

    /** Returns true when a launch was dispatched; false when every path failed. */
    @SuppressLint("NewApi") // guarded by usesPendingIntentPath / SDK_INT check below
    fun launch(context: Context, intent: Intent, requestCode: Int): Boolean {
        if (!usesPendingIntentPath(Build.VERSION.SDK_INT)) {
            return try {
                context.startActivity(intent)
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "direct startActivity failed", e)
                false
            }
        }
        return try {
            // API 34+ fluent mode; the legacy LaunchAllowed setter returns void.
            val options = ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).send(context, 0, null, null, null, null, options.toBundle())
            true
        } catch (e: Exception) {
            Log.w(TAG, "BAL-safe send failed; falling back to direct start", e)
            try {
                context.startActivity(intent)
                true
            } catch (e2: RuntimeException) {
                Log.e(TAG, "fallback direct start failed too", e2)
                false
            }
        }
    }
}

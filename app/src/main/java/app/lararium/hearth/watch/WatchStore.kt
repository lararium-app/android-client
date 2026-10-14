package app.lararium.hearth.watch

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences storage for Hearth Watch state: cursors, notified cards,
 * and rate-limited alert timestamps.
 */
class WatchStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        private const val PREFS_NAME = "hearth_watch"
        private const val KEY_NOTIFIED_CARDS = "notified_cards"
        private const val PREFIX_CURSOR = "cursor_"
        private const val PREFIX_ALERT = "alert_"
        private const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L
    }

    /**
     * Returns the last-seen event sequence number for [sessionId], or null if
     * never seen before.
     */
    fun getCursor(sessionId: String): Int? {
        val key = "$PREFIX_CURSOR$sessionId"
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    /**
     * Persists the last-seen event sequence number for [sessionId].
     */
    fun setCursor(sessionId: String, seq: Int) {
        prefs.edit().putInt("$PREFIX_CURSOR$sessionId", seq).apply()
    }

    /**
     * Returns the set of custody card IDs that have already been notified.
     */
    fun getNotifiedCards(): Set<String> {
        return prefs.getStringSet(KEY_NOTIFIED_CARDS, emptySet())?.toSet() ?: emptySet()
    }

    /**
     * Replaces the set of notified custody card IDs with [ids].
     */
    fun replaceNotifiedCards(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_NOTIFIED_CARDS, ids.toSet()).apply()
    }

    /**
     * Returns true if an alert for [reasonKey] has not been sent within the
     * last 6 hours, and records the current timestamp.
     */
    fun shouldAlertToken(reasonKey: String): Boolean {
        val key = "$PREFIX_ALERT$reasonKey"
        val now = System.currentTimeMillis()
        val lastAlert = prefs.getLong(key, 0L)
        return if (lastAlert == 0L || (now - lastAlert) >= SIX_HOURS_MS) {
            prefs.edit().putLong(key, now).apply()
            true
        } else {
            false
        }
    }
}

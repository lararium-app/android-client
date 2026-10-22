package app.lararium.ui

import android.content.pm.ActivityInfo

/**
 * Pure helper logic for mapping orientation-lock setting to Android ActivityInfo orientation flags.
 * Zero android framework dependencies beyond ActivityInfo integer constants for fast pure-JVM unit testing.
 */
object OrientationLogic {
    const val DEFAULT_LOCKED: Boolean = true

    /**
     * Returns the requested orientation constant for a given locked setting.
     * When locked (default) -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
     * When unlocked -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
     */
    fun orientationForSetting(locked: Boolean = DEFAULT_LOCKED): Int {
        return if (locked) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

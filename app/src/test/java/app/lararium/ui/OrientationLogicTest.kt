package app.lararium.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for OrientationLogic and SettingsManager orientation-lock settings.
 * Verifies orientation setting mapping and default behavior without Robolectric or external dependencies.
 */
class OrientationLogicTest {

    @Test
    fun defaultLocked_returnsPortrait() {
        // Default parameter locked -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationLogic.orientationForSetting())

        // Explicit locked = true -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationLogic.orientationForSetting(locked = true))

        // Default setting flag is locked (true)
        assertTrue(OrientationLogic.DEFAULT_LOCKED)
    }

    @Test
    fun unlocked_returnsUnspecified() {
        // Explicit locked = false -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, OrientationLogic.orientationForSetting(locked = false))
    }

    @Test
    fun settingsManager_pinsOrientationLockKey() {
        assertTrue(SettingsManager.DEFAULT_LOCK_ORIENTATION)
        assertEquals("lock_orientation", SettingsManager.KEY_LOCK_ORIENTATION)
    }
}

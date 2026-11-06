package app.lararium.hearth.rails.service

import android.content.Context
import app.lararium.hearth.rails.DeclaredRails
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayFlavorGatingTest {

    @Test
    fun factoryReturnsNoActionExecutorsInPlayVariant() {
        assertTrue(ActionExecutors.forContext(mockk<Context>(relaxed = true)).isEmpty())
    }

    @Test
    fun buildConfigChannelIsPlayAndDeclaredRailsExcludeGated() {
        assertEquals("play", app.lararium.BuildConfig.DIST_CHANNEL)
        val names = DeclaredRails.names()
        assertFalse("dialer" in names)
        assertFalse("sms" in names)
        assertFalse("calllog" in names)
        assertEquals(setOf("calendar", "contacts", "notifications", "health"), names)
    }
}

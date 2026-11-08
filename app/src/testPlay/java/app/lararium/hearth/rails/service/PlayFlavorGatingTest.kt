package app.lararium.hearth.rails.service

import android.content.Context
import app.lararium.hearth.rails.DeclaredRails
import app.lararium.hearth.rails.RailsWire
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Flavors are build-identity stubs: the play variant compiles identical rail code. */
class PlayFlavorGatingTest {

    @Test
    fun factoryReturnsDialerAndSms() {
        val executors = ActionExecutors.forContext(mockk<Context>(relaxed = true))
        assertEquals(1, executors.count { it.canHandle("device.dialer.call") })
        assertEquals(2, executors.size)
    }

    @Test
    fun playChannelDeclaresEveryRail() {
        assertEquals("play", app.lararium.BuildConfig.DIST_CHANNEL)
        assertEquals(RailsWire.RAIL_NAMES, DeclaredRails.names())
    }
}

package app.lararium.hearth.rails.service

import android.content.Context
import app.lararium.hearth.rails.DeclaredRails
import app.lararium.hearth.rails.RailsWire
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class GithubFlavorGatingTest {

    @Test
    fun factoryReturnsDialerAndSmsInGithubVariant() {
        val executors = ActionExecutors.forContext(mockk<Context>(relaxed = true))
        assertEquals(1, executors.count { it.canHandle("device.dialer.call") })
        assertEquals(2, executors.size)
    }

    @Test
    fun githubDeclaresEveryRail() {
        assertEquals("github", app.lararium.BuildConfig.DIST_CHANNEL)
        assertEquals(RailsWire.RAIL_NAMES, DeclaredRails.names())
    }
}

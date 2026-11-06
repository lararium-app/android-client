package app.lararium.hearth.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclaredRailsTest {

    @Test
    fun play_excludesDialerSmsCalllog() {
        val names = DeclaredRails.names { "play" }
        assertFalse("dialer" in names)
        assertFalse("sms" in names)
        assertFalse("calllog" in names)
        assertEquals(setOf("calendar", "contacts", "notifications", "health"), names)
    }

    @Test
    fun play_buildKeepsReadOnlySemanticsAndExcludesGatedRails() {
        val rails = DeclaredRails.build { "play" }
        assertEquals(setOf("calendar", "contacts", "notifications", "health"), rails.map { it.name }.toSet())
        assertTrue(rails.all { it.readOnly })
        assertTrue(rails.filter { it.name != "health" }.all { it.enabled })
    }

    @Test
    fun github_declaresEveryRail() {
        assertEquals(RailsWire.RAIL_NAMES, DeclaredRails.names { "github" })
        val dialer = DeclaredRails.build { "github" }.first { it.name == "dialer" }
        assertFalse(dialer.readOnly)
    }

    @Test
    fun health_isDeclaredButDisabledInEveryChannel() {
        for (channel in listOf("play", "github")) {
            val rails = DeclaredRails.build { channel }
            assertTrue(rails.filter { it.name != "health" }.all { it.enabled })
            val health = rails.single { it.name == "health" }
            assertFalse(health.enabled)
        }
    }
}

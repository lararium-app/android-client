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
        assertTrue(rails.all { it.readOnly && it.enabled })
    }

    @Test
    fun github_declaresEveryRail() {
        assertEquals(RailsWire.RAIL_NAMES, DeclaredRails.names { "github" })
        val dialer = DeclaredRails.build { "github" }.first { it.name == "dialer" }
        assertFalse(dialer.readOnly)
    }
}

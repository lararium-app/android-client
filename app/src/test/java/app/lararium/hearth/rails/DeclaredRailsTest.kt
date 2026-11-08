package app.lararium.hearth.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclaredRailsTest {

    @Test
    fun declaresEveryRail_inEveryBuild() {
        assertEquals(RailsWire.RAIL_NAMES, DeclaredRails.names())
        assertEquals(RailsWire.RAIL_NAMES, DeclaredRails.build().map { it.name }.toSet())
    }

    @Test
    fun dialerSmsCalllogAreDeclared() {
        val names = DeclaredRails.names()
        assertTrue("dialer" in names)
        assertTrue("sms" in names)
        assertTrue("calllog" in names)
    }

    @Test
    fun actionRailsAreNotReadOnly() {
        val dialer = DeclaredRails.build().first { it.name == "dialer" }
        assertFalse(dialer.readOnly)
    }

    @Test
    fun health_isDeclaredButDisabled() {
        val rails = DeclaredRails.build()
        assertTrue(rails.filter { it.name != "health" }.all { it.enabled })
        assertFalse(rails.single { it.name == "health" }.enabled)
    }
}

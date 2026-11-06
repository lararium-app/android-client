package app.lararium.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureGateTest {

    @Test
    fun nonUserInitiated_neverTouchesCapture() {
        var captureCalls = 0
        val logs = mutableListOf<String>()
        val gate = VoiceCaptureGate(userInitiatedProvider = { false }, log = { logs += it })

        val ran = gate.guard(VoiceCaptureOrigin.INTENT_UNATTRIBUTED) { captureCalls++ }

        assertFalse(ran)
        assertEquals(0, captureCalls)
        assertEquals(1, logs.size)
    }

    @Test
    fun userInitiated_startsCapture() {
        var captureCalls = 0
        val gate = VoiceCaptureGate(userInitiatedProvider = { true })

        assertTrue(gate.guard(VoiceCaptureOrigin.UI_TAP) { captureCalls++ })
        assertEquals(1, captureCalls)
    }

    @Test
    fun defaultPolicy_followsOrigin() {
        var captureCalls = 0
        val gate = VoiceCaptureGate()
        assertFalse(gate.guard(VoiceCaptureOrigin.INTENT_UNATTRIBUTED) { captureCalls++ })
        assertTrue(gate.guard(VoiceCaptureOrigin.INTENT_USER_INITIATED) { captureCalls++ })
        assertTrue(gate.guard(VoiceCaptureOrigin.OVERLAY_AUTOSTART) { captureCalls++ })
        assertEquals(2, captureCalls)
    }
}

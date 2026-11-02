package app.lararium.hearth.rails.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.lararium.hearth.rails.DispatchedAction
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RailToolExecutor implementations (F4, spec A6).
 *
 * Verifies:
 * - Dialer executor emits ACTION_CALL with correct phone number
 * - Dialer catches SecurityException and returns ("permission_revoked")
 * - SMS executor formats recipient and message content correctly
 * - SMS catches SecurityException and returns ("permission_revoked")
 */
class RailToolExecutorTest {

    @Test
    fun dialerExecutor_emitsActionCallWithCorrectNumber() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        var capturedIntent: Intent? = null
        var emittedAction: String? = null
        var emittedUri: String? = null

        val mockIntent = mockk<Intent>()
        every { mockIntent.action } returns Intent.ACTION_CALL
        every { mockIntent.dataString } returns "tel:555-0199"

        val executor = DialerToolExecutor(
            context = context,
            intentFactory = { action, phoneUri ->
                emittedAction = action
                emittedUri = phoneUri
                mockIntent
            },
            callLauncher = { intent -> capturedIntent = intent }
        )

        assertTrue(executor.canHandle("device.dialer.call"))
        assertFalse(executor.canHandle("device.sms.send"))

        val action = DispatchedAction(
            actionId = "act-call-1",
            tool = "device.dialer.call",
            args = JSONObject().apply { put("phone", "555-0199") }
        )

        val (ok, result) = executor.execute(action)
        assertTrue(ok)
        assertEquals("call_placed", result)

        assertEquals(Intent.ACTION_CALL, emittedAction)
        assertEquals("tel:555-0199", emittedUri)
        assertNotNull(capturedIntent)
        assertEquals(Intent.ACTION_CALL, capturedIntent!!.action)
        assertEquals("tel:555-0199", capturedIntent!!.dataString)
    }

    @Test
    fun dialerExecutor_securityException_returnsPermissionRevoked() = runBlocking {
        val context = mockk<Context>(relaxed = true)

        val executor = DialerToolExecutor(
            context = context,
            callLauncher = { throw SecurityException("CALL_PHONE denied by OS") }
        )

        val action = DispatchedAction(
            actionId = "act-call-denied",
            tool = "device.dialer.call",
            args = JSONObject().apply { put("phone", "555-0199") }
        )

        val (ok, error) = executor.execute(action)
        assertFalse(ok)
        assertEquals("permission_revoked", error)
    }

    @Test
    fun smsExecutor_formatsRecipientAndMessage() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "app.lararium"

        var capturedDest: String? = null
        var capturedText: String? = null

        val executor = SmsToolExecutor(
            context = context,
            smsSender = { dest, _, text, _, _ ->
                capturedDest = dest
                capturedText = text
            }
        )

        assertTrue(executor.canHandle("device.sms.send"))
        assertFalse(executor.canHandle("device.dialer.call"))

        val action = DispatchedAction(
            actionId = "act-sms-1",
            tool = "device.sms.send",
            args = JSONObject().apply {
                put("phone", "+15551234567")
                put("text", "Hello from Lararium test")
            }
        )

        val (ok, result) = executor.execute(action)
        assertTrue(ok)
        assertEquals("sms_dispatched", result)

        assertEquals("+15551234567", capturedDest)
        assertEquals("Hello from Lararium test", capturedText)
    }

    @Test
    fun smsExecutor_securityException_returnsPermissionRevoked() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "app.lararium"

        val executor = SmsToolExecutor(
            context = context,
            smsSender = { _, _, _, _, _ ->
                throw SecurityException("SEND_SMS denied")
            }
        )

        val action = DispatchedAction(
            actionId = "act-sms-denied",
            tool = "device.sms.send",
            args = JSONObject().apply {
                put("phone", "555-1234")
                put("text", "Test msg")
            }
        )

        val (ok, error) = executor.execute(action)
        assertFalse(ok)
        assertEquals("permission_revoked", error)
    }
}

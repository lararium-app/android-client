package app.lararium.hearth.rails.service

import android.content.ContentResolver
import android.content.Context
import app.lararium.hearth.rails.DispatchedAction
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With CALL_PHONE / SEND_SMS / READ_CALL_LOG absent or revoked (the role is
 * additive, never required), every rail degrades to a result and never throws
 * to its caller.
 */
class RailDegradeTest {

    private fun action(tool: String, args: JSONObject) =
        DispatchedAction(actionId = "act-degrade", tool = tool, args = args)

    @Test
    fun dialer_permissionAbsent_degradesToPermissionRevoked() = runBlocking {
        val executor = DialerToolExecutor(
            context = mockk<Context>(relaxed = true),
            callLauncher = { throw SecurityException("CALL_PHONE not granted") },
            defaultDialerPackage = { "com.test.dialer" }
        )
        val (ok, result) = executor.execute(action("device.dialer.call", JSONObject().put("phone", "555-0199")))
        assertFalse(ok)
        assertEquals("permission_revoked", result)
    }

    @Test
    fun dialer_intentBuildingFailure_neverThrows() = runBlocking {
        val executor = DialerToolExecutor(
            context = mockk<Context>(relaxed = true),
            intentFactory = { _, _, _ -> throw SecurityException("denied while building") },
            callLauncher = { },
            defaultDialerPackage = { "com.test.dialer" }
        )
        val (ok, result) = executor.execute(action("device.dialer.call", JSONObject().put("phone", "555-0199")))
        assertFalse(ok)
        assertEquals("permission_revoked", result)
    }

    @Test
    fun dialer_unexpectedFailure_returnsErrorResult() = runBlocking {
        val executor = DialerToolExecutor(
            context = mockk<Context>(relaxed = true),
            callLauncher = { throw IllegalStateException("boom") },
            defaultDialerPackage = { "com.test.dialer" }
        )
        val (ok, result) = executor.execute(action("device.dialer.call", JSONObject().put("phone", "555-0199")))
        assertFalse(ok)
        assertEquals("boom", result)
    }

    @Test
    fun sms_permissionAbsent_realSendPath_degradesToPermissionRevoked() = runBlocking {
        // No injected sender: exercises the SmsManager lookup path with a context
        // whose service lookup is denied.
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "app.lararium"
        every { context.getSystemService(any<String>()) } throws SecurityException("SEND_SMS not granted")
        every { context.getSystemService(any<Class<*>>()) } throws SecurityException("SEND_SMS not granted")

        val executor = SmsToolExecutor(context = context)
        val (ok, result) = executor.execute(
            action("device.sms.send", JSONObject().put("phone", "+15551234567").put("text", "hi"))
        )
        assertFalse(ok)
        assertEquals("permission_revoked", result)
    }

    @Test
    fun sms_unexpectedFailure_returnsErrorResult() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "app.lararium"
        val executor = SmsToolExecutor(
            context = context,
            smsSender = { _, _, _, _, _ -> throw IllegalStateException("radio off") }
        )
        val (ok, result) = executor.execute(
            action("device.sms.send", JSONObject().put("phone", "555").put("text", "hi"))
        )
        assertFalse(ok)
        assertEquals("radio off", result)
    }

    @Test
    fun callLogSnapshot_permissionAbsent_returnsEmptyNotThrow() = runBlocking {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any<String>()) } throws
            SecurityException("READ_CALL_LOG not granted")
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver

        val rows = CallLogDataSource(context).snapshot()
        assertTrue(rows.isEmpty())
    }
}

package app.lararium.hearth.rails

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ActionSheetLogic double-gated execution lifecycle and timeout boundary (A6).
 */
class ActionSheetLogicTest {

    private fun makeAction(actionId: String): DispatchedAction {
        return DispatchedAction(
            actionId = actionId,
            tool = "device.dialer.call",
            args = JSONObject().apply { put("phone", "555-0199") }
        )
    }

    @Test
    fun approveLifecycle_terminalAndReceiptShape() {
        var currentTime = 1_000_000L
        val sheet = ActionSheetLogic(clock = { currentTime })
        val action = makeAction("act-approve-1")

        sheet.present(action)
        assertTrue(sheet.state("act-approve-1") is ActionState.Presented)
        assertFalse(sheet.isTerminal("act-approve-1"))

        sheet.approve("act-approve-1")
        assertEquals(ActionState.Approved, sheet.state("act-approve-1"))
        assertTrue(sheet.isTerminal("act-approve-1"))

        // Receipt body exact shape: {"ok":true,"result":"approved_local"}
        val receipt = sheet.receiptBody("act-approve-1")
        assertTrue(receipt.getBoolean("ok"))
        assertEquals("approved_local", receipt.getString("result"))
        assertFalse(receipt.has("error"))

        // Post-terminal user input is strictly ignored
        sheet.deny("act-approve-1", "user_denied")
        assertEquals(ActionState.Approved, sheet.state("act-approve-1"))
    }

    @Test
    fun denyLifecycle_defaultReasonAndCustomReason() {
        val sheet = ActionSheetLogic()

        // Default deny reason
        val action1 = makeAction("act-deny-1")
        sheet.present(action1)
        sheet.deny("act-deny-1")
        val receipt1 = sheet.receiptBody("act-deny-1")
        assertFalse(receipt1.getBoolean("ok"))
        assertEquals("denied_local", receipt1.getString("error"))
        assertFalse(receipt1.has("result"))

        // Custom deny reason
        val action2 = makeAction("act-deny-2")
        sheet.present(action2)
        sheet.deny("act-deny-2", "user_rejected_number")
        val receipt2 = sheet.receiptBody("act-deny-2")
        assertFalse(receipt2.getBoolean("ok"))
        assertEquals("user_rejected_number", receipt2.getString("error"))

        // Post-terminal approve ignored
        sheet.approve("act-deny-1")
        val postTermState = sheet.state("act-deny-1") as ActionState.Denied
        assertEquals("denied_local", postTermState.reason)
    }

    @Test
    fun timeoutBoundary_59999AliveAnd60000TimedOut() {
        var currentTime = 1_000_000L
        val sheet = ActionSheetLogic(clock = { currentTime })
        val action = makeAction("act-timeout-1")

        sheet.present(action)

        // Advance 59_999 ms: alive
        currentTime += 59_999L
        sheet.tick()
        assertTrue(sheet.state("act-timeout-1") is ActionState.Presented)
        assertFalse(sheet.isTerminal("act-timeout-1"))

        // Advance 1 ms to exactly 60_000 ms: timedOut
        currentTime += 1L
        sheet.tick()
        assertTrue(sheet.state("act-timeout-1") is ActionState.TimedOut)
        assertTrue(sheet.isTerminal("act-timeout-1"))

        // Receipt body exact shape for timedOut
        val receipt = sheet.receiptBody("act-timeout-1")
        assertFalse(receipt.getBoolean("ok"))
        assertEquals("denied_local", receipt.getString("error"))

        // Post-terminal input ignored
        sheet.approve("act-timeout-1")
        assertTrue(sheet.state("act-timeout-1") is ActionState.TimedOut)

        sheet.deny("act-timeout-1", "late_deny")
        assertTrue(sheet.state("act-timeout-1") is ActionState.TimedOut)
    }

    @Test
    fun timeoutConstantValue() {
        assertEquals(60_000L, RAIL_TIMEOUT_MS)
    }

    @Test
    fun duplicatePresentIgnored() {
        var currentTime = 1_000_000L
        val sheet = ActionSheetLogic(clock = { currentTime })
        val action = makeAction("act-1")

        sheet.present(action)

        currentTime += 30_000L
        // Duplicate present at later time does NOT reset timer
        sheet.present(action)

        // Advance remaining 30_000 ms (total 60_000 ms from initial present)
        currentTime += 30_000L
        sheet.tick()
        assertTrue("Timer should be measured from first present", sheet.isTerminal("act-1"))
    }
}

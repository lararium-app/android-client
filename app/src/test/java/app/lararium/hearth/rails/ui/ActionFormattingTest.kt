package app.lararium.hearth.rails.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionFormattingTest {

    @Test
    fun testDialerCallHappyPath() {
        val args = JSONObject().apply {
            put("phone", "+15551234567")
        }
        val label = ActionFormatting.humanLabel("device.dialer.call", args)
        assertEquals("Call +15551234567", label)
    }

    @Test
    fun testDialerCallWithNumberFallback() {
        val args = JSONObject().apply {
            put("number", "555-0199")
        }
        val label = ActionFormatting.humanLabel("device.dialer.call", args)
        assertEquals("Call 555-0199", label)
    }

    @Test
    fun testDialerDialTool() {
        val args = JSONObject().apply {
            put("recipient", "+15559876543")
        }
        val label = ActionFormatting.humanLabel("device.dialer.dial", args)
        assertEquals("Call +15559876543", label)
    }

    @Test
    fun testDialerMissingPhone() {
        val args = JSONObject()
        val label = ActionFormatting.humanLabel("device.dialer.call", args)
        assertEquals("Call", label)
    }

    @Test
    fun testSmsSendHappyPath() {
        val args = JSONObject().apply {
            put("phone", "+15551234567")
            put("text", "Hello, are you available?")
        }
        val label = ActionFormatting.humanLabel("device.sms.send", args)
        assertEquals("Send SMS to +15551234567: Hello, are you available?", label)
    }

    @Test
    fun testSmsSendWithAlternativeKeys() {
        val args = JSONObject().apply {
            put("recipient", "+15557654321")
            put("message", "Test message")
        }
        val label = ActionFormatting.humanLabel("device.sms.send", args)
        assertEquals("Send SMS to +15557654321: Test message", label)
    }

    @Test
    fun testSmsDraftTool() {
        val args = JSONObject().apply {
            put("to", "+15550001111")
            put("body", "Drafting an SMS")
        }
        val label = ActionFormatting.humanLabel("device.sms.draft", args)
        assertEquals("Send SMS to +15550001111: Drafting an SMS", label)
    }

    @Test
    fun testSmsMissingMessage() {
        val args = JSONObject().apply {
            put("phone", "+15551234567")
        }
        val label = ActionFormatting.humanLabel("device.sms.send", args)
        assertEquals("Send SMS to +15551234567", label)
    }

    @Test
    fun testSmsMissingPhone() {
        val args = JSONObject().apply {
            put("text", "Message without phone")
        }
        val label = ActionFormatting.humanLabel("device.sms.send", args)
        assertEquals("Send SMS: Message without phone", label)
    }

    @Test
    fun testSmsEmptyArgs() {
        val args = JSONObject()
        val label = ActionFormatting.humanLabel("device.sms.send", args)
        assertEquals("Send SMS", label)
    }

    @Test
    fun testMessageWithQuotesAndSpecialCharacters() {
        val args = JSONObject().apply {
            put("phone", "+15551234567")
            put("text", "Meeting at 5:00 \"quote\" & special <chars> \n newline")
        }
        val label = ActionFormatting.humanLabel("device.sms.send", args)
        assertEquals("Send SMS to +15551234567: Meeting at 5:00 \"quote\" & special <chars> \n newline", label)
    }

    @Test
    fun testUnknownToolFallback() {
        val args = JSONObject().apply {
            put("some_key", "some_value")
        }
        val label = ActionFormatting.humanLabel("device.camera.snap", args)
        assertEquals("Run device action device.camera.snap", label)
    }

    @Test
    fun testCountdownLabelTransitions() {
        assertEquals("60s", ActionFormatting.countdownLabel(60_000L))
        assertEquals("60s", ActionFormatting.countdownLabel(65_000L))
        assertEquals("60s", ActionFormatting.countdownLabel(59_001L))
        assertEquals("59s", ActionFormatting.countdownLabel(59_000L))
        assertEquals("59s", ActionFormatting.countdownLabel(58_001L))
        assertEquals("2s", ActionFormatting.countdownLabel(1_001L))
        assertEquals("1s", ActionFormatting.countdownLabel(1_000L))
        assertEquals("1s", ActionFormatting.countdownLabel(500L))
        assertEquals("1s", ActionFormatting.countdownLabel(1L))
        assertEquals("Blocked", ActionFormatting.countdownLabel(0L))
        assertEquals("Blocked", ActionFormatting.countdownLabel(-1L))
        assertEquals("Blocked", ActionFormatting.countdownLabel(-5_000L))
    }
}

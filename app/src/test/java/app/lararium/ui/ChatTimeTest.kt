package app.lararium.ui

import app.lararium.llm.ChatSession.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Unit tests for pure-Kotlin [ChatTime] logic (timestamp decision and formatting).
 */
class ChatTimeTest {

    // -----------------------------------------------------------------------
    // shouldShowTimestamp
    // -----------------------------------------------------------------------

    @Test
    fun shouldShowTimestamp_firstMessage_returnsTrue() {
        val messages = listOf(
            Message(role = "user", content = "First message", timestamp = 1_000_000L),
            Message(role = "user", content = "Second message", timestamp = 1_000_000L + 60_000L)
        )
        assertTrue(shouldShowTimestamp(messages, 0))
    }

    @Test
    fun shouldShowTimestamp_singleElementList_returnsTrue() {
        val single = listOf(
            Message(role = "assistant", content = "Solo message", timestamp = 1_000_000L)
        )
        assertTrue(shouldShowTimestamp(single, 0))
    }

    @Test
    fun shouldShowTimestamp_sameRoleWithinTwoMinutes_returnsFalse() {
        val t0 = 1_000_000L
        val messages = listOf(
            Message(role = "user", content = "Hello", timestamp = t0),
            Message(role = "user", content = "Follow-up", timestamp = t0 + 2 * 60_000L)
        )
        assertFalse(shouldShowTimestamp(messages, 1))
    }

    @Test
    fun shouldShowTimestamp_sameRoleAfterSixMinutes_returnsTrue() {
        val t0 = 1_000_000L
        val messages = listOf(
            Message(role = "user", content = "Hello", timestamp = t0),
            Message(role = "user", content = "Much later question", timestamp = t0 + 6 * 60_000L)
        )
        assertTrue(shouldShowTimestamp(messages, 1))
    }

    @Test
    fun shouldShowTimestamp_roleSwitch_returnsTrueRegardlessOfTimeGap() {
        val t0 = 1_000_000L
        val immediateReply = listOf(
            Message(role = "user", content = "Ping", timestamp = t0),
            Message(role = "assistant", content = "Pong", timestamp = t0 + 100L)
        )
        assertTrue(shouldShowTimestamp(immediateReply, 1))

        val reverseSwitch = listOf(
            Message(role = "assistant", content = "Here is the answer", timestamp = t0),
            Message(role = "user", content = "Thanks", timestamp = t0 + 500L)
        )
        assertTrue(shouldShowTimestamp(reverseSwitch, 1))
    }

    @Test
    fun shouldShowTimestamp_zeroTimestamp_returnsFalseAtAnyIndex() {
        val messages = listOf(
            Message(role = "user", content = "Restored user", timestamp = 0L),
            Message(role = "user", content = "Restored user follow-up", timestamp = 0L),
            Message(role = "assistant", content = "Restored assistant", timestamp = 0L)
        )
        assertFalse(shouldShowTimestamp(messages, 0))
        assertFalse(shouldShowTimestamp(messages, 1))
        assertFalse(shouldShowTimestamp(messages, 2))
    }

    @Test
    fun shouldShowTimestamp_negativeTimestamp_returnsFalse() {
        val messages = listOf(
            Message(role = "user", content = "Invalid timestamp", timestamp = -1L)
        )
        assertFalse(shouldShowTimestamp(messages, 0))
    }

    // -----------------------------------------------------------------------
    // formatTimestampLabel
    // -----------------------------------------------------------------------

    private fun createFixedNow(): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun formatTimestampLabel_today_returnsShortTimeOnlyNoDatePart() {
        val nowCal = createFixedNow()
        val msgCal = (nowCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 15)
        }

        val label = formatTimestampLabel(msgCal.timeInMillis, nowCal.timeInMillis, "Yesterday")
        assertEquals("10:15", label)
        assertFalse(label.contains("Oct"))
        assertFalse(label.contains("2026"))
        assertFalse(label.contains("Yesterday"))
    }

    @Test
    fun formatTimestampLabel_yesterday_returnsYesterdayPrefixWithShortTime() {
        val nowCal = createFixedNow()
        val msgCal = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 16)
            set(Calendar.MINUTE, 45)
        }

        val label = formatTimestampLabel(msgCal.timeInMillis, nowCal.timeInMillis, "Yesterday")
        assertEquals("Yesterday 16:45", label)

        val customLabel = formatTimestampLabel(msgCal.timeInMillis, nowCal.timeInMillis, "Hier")
        assertEquals("Hier 16:45", customLabel)
    }

    @Test
    fun formatTimestampLabel_withinLastSixDays_startsWithThreeLetterWeekday() {
        val nowCal = createFixedNow() // Thursday, Oct 15, 2026
        val threeDaysAgoCal = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -3) // Monday, Oct 12, 2026
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
        }

        val label = formatTimestampLabel(threeDaysAgoCal.timeInMillis, nowCal.timeInMillis, "Yesterday")
        val expectedWeekday = SimpleDateFormat("EEE", Locale.getDefault()).format(threeDaysAgoCal.time)
        assertEquals(expectedWeekday, label)
        assertTrue(label.matches(Regex("^[A-Za-z]{3}.*")))
        assertFalse(label.contains("Oct"))
        assertFalse(label.contains(":"))
    }

    @Test
    fun formatTimestampLabel_moreThanSixDaysAgo_includesMonthAndDay() {
        val nowCal = createFixedNow() // Oct 15, 2026
        val eightDaysAgoCal = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -8) // Oct 7, 2026
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
        }

        val label = formatTimestampLabel(eightDaysAgoCal.timeInMillis, nowCal.timeInMillis, "Yesterday")
        val expected = SimpleDateFormat("MMM d", Locale.getDefault()).format(eightDaysAgoCal.time)
        assertEquals(expected, label)
        assertTrue(label.contains("Oct"))
        assertTrue(label.contains("7"))
        assertFalse(label.contains("2026"))
    }

    @Test
    fun formatTimestampLabel_differentYear_includesYear() {
        val nowCal = createFixedNow() // Oct 15, 2026
        val lastYearCal = (nowCal.clone() as Calendar).apply {
            set(Calendar.YEAR, 2025)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 30)
        }

        val label = formatTimestampLabel(lastYearCal.timeInMillis, nowCal.timeInMillis, "Yesterday")
        val expected = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(lastYearCal.time)
        assertEquals(expected, label)
        assertTrue(label.contains("2025"))
    }
}

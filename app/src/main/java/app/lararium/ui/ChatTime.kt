package app.lararium.ui

import app.lararium.llm.ChatSession.Message
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Decides whether a timestamp caption should be rendered above the message at [index].
 *
 * A timestamp is shown for the initial message, whenever the role alternates between consecutive
 * messages, or when the time elapsed between adjacent messages of the same role exceeds [gapMinutes].
 * Messages with a non-positive timestamp (<= 0L), such as those restored without an authoritative
 * wall clock, never display a timestamp label.
 *
 * @param messages The list of conversation messages.
 * @param index The index of the message to evaluate.
 * @param gapMinutes The threshold in minutes after which consecutive messages of the same role show a timestamp.
 * @return True if a timestamp caption should be displayed, false otherwise.
 */
fun shouldShowTimestamp(
    messages: List<Message>,
    index: Int,
    gapMinutes: Long = 5
): Boolean {
    if (index !in messages.indices) return false
    val current = messages[index]
    if (current.timestamp <= 0L) return false
    if (index == 0) return true

    val prev = messages[index - 1]
    if (current.role != prev.role) return true

    val diff = current.timestamp - prev.timestamp
    return diff > gapMinutes * 60_000L
}

/**
 * Formats an epoch timestamp in milliseconds into a discreet chat time or date label relative to [now].
 *
 * Formatting rules:
 * - Same calendar day: "HH:mm" (or "h:mm a" when [is24Hour] is false) short time format.
 * - Yesterday: [yesterdayWord] followed by a space and "HH:mm" (e.g. "Yesterday 14:30").
 * - Within the last 6 days (excluding today and yesterday): 3-letter weekday short name ("EEE", e.g. "Mon").
 * - Older within the same calendar year: "MMM d" (e.g. "Oct 24").
 * - Older in a different calendar year: "MMM d, yyyy" (e.g. "Oct 24, 2025").
 *
 * @param epochMillis The wall-clock epoch timestamp in milliseconds of the message.
 * @param now The reference timestamp in milliseconds representing the current time.
 * @param yesterdayWord The localized word for "Yesterday" (defaults to "Yesterday").
 * @param is24Hour True for "HH:mm" time pattern, false for "h:mm a" (resolved by the UI call site).
 * @return Formatted timestamp label string.
 */
fun formatTimestampLabel(
    epochMillis: Long,
    now: Long,
    yesterdayWord: String = "Yesterday",
    is24Hour: Boolean = true
): String {
    val timePattern = if (is24Hour) "HH:mm" else "h:mm a"
    val msgCal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }

    val isSameDay = msgCal.get(Calendar.ERA) == nowCal.get(Calendar.ERA) &&
        msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
        msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

    if (isSameDay) {
        return SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(epochMillis))
    }

    val yesterdayCal = (nowCal.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = msgCal.get(Calendar.ERA) == yesterdayCal.get(Calendar.ERA) &&
        msgCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        msgCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)

    if (isYesterday) {
        val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(epochMillis))
        return "$yesterdayWord $timeStr"
    }

    val startOfSixDaysAgo = (nowCal.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -6)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    if (epochMillis >= startOfSixDaysAgo.timeInMillis && epochMillis <= now) {
        return SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMillis))
    }

    val isSameYear = msgCal.get(Calendar.ERA) == nowCal.get(Calendar.ERA) &&
        msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)

    return if (isSameYear) {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
    } else {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))
    }
}

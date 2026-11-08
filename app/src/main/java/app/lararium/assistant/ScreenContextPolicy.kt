package app.lararium.assistant

data class NotificationSummary(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTimeMillis: Long
)

data class ScreenContextSettings(
    val enabled: Boolean = false,
    val windowMinutes: Int = 30,
    val maxItems: Int = 5,
    val excludedPackages: Set<String> = emptySet()
)

/**
 * Pure JVM policy for opt-in notification context (T4). No android.* imports.
 * Everything here is inert unless [ScreenContextSettings.enabled] is true.
 */
object ScreenContextPolicy {
    const val SHARE_LIMIT = 2000
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val BLOCK_HEADER = "Context from recent notifications:"

    fun shouldOffer(settings: ScreenContextSettings, nowMillis: Long): Boolean = settings.enabled

    fun select(
        summaries: List<NotificationSummary>,
        settings: ScreenContextSettings,
        nowMillis: Long
    ): List<NotificationSummary> {
        if (!shouldOffer(settings, nowMillis)) return emptyList()
        val windowMillis = settings.windowMinutes.coerceAtLeast(0).toLong() * MILLIS_PER_MINUTE
        return summaries
            .asSequence()
            .filter { it.packageName !in settings.excludedPackages }
            .filter { nowMillis - it.postTimeMillis < windowMillis }
            .sortedWith(compareByDescending<NotificationSummary> { it.postTimeMillis }.thenBy { it.id })
            .take(settings.maxItems.coerceAtLeast(0))
            .toList()
    }

    /** Title, or text when the title is blank. */
    fun displayLine(summary: NotificationSummary): String =
        summary.title.ifBlank { summary.text }

    /** Outgoing block for the checked items; empty string when there are none. */
    fun buildContextBlock(items: List<NotificationSummary>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(separator = "\n", prefix = "$BLOCK_HEADER\n") {
            "- ${displayLine(it)} (${it.packageName})"
        }
    }

    /** Prepends the context block to the user's message; unchanged when no items. */
    fun prependContext(items: List<NotificationSummary>, message: String): String {
        val block = buildContextBlock(items)
        return if (block.isEmpty()) message else "$block\n\n$message"
    }

    /** Trim, cap to [limit] characters, empty-safe. */
    fun sanitizeShared(text: String, limit: Int = SHARE_LIMIT): String =
        text.trim().take(limit.coerceAtLeast(0))
}

package app.lararium.assistant

import app.lararium.hearth.rails.service.NotificationsListener
import app.lararium.ui.SettingsManager

object ScreenContextProvider {
    /** Current opt-in notification context; empty on any failure or when disabled. */
    fun snapshot(
        manager: SettingsManager,
        nowMillis: Long = System.currentTimeMillis()
    ): List<NotificationSummary> = runCatching {
        val settings = ScreenContextSettings(
            enabled = manager.screenContextEnabled,
            excludedPackages = manager.screenContextExcludedPackages
        )
        if (!ScreenContextPolicy.shouldOffer(settings, nowMillis)) {
            emptyList()
        } else {
            val summaries = NotificationsListener.ring.snapshot().mapNotNull { row ->
                runCatching {
                    val d = row.data
                    NotificationSummary(
                        id = d.optString("id", row.id).ifBlank { row.id },
                        packageName = d.optString("package", ""),
                        title = d.optString("title", ""),
                        text = d.optString("text", ""),
                        postTimeMillis = d.optLong("post_time", 0L)
                    )
                }.getOrNull()
            }
            ScreenContextPolicy.select(summaries, settings, nowMillis)
        }
    }.getOrDefault(emptyList())
}

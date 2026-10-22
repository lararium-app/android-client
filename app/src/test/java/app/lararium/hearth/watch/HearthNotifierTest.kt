package app.lararium.hearth.watch

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for approval card notification lock-screen visibility (issue #8).
 *
 * Approval prompts are security-sensitive and must not leak tool names,
 * destinations, or allow/deny actions on a secured lock screen.
 */
class HearthNotifierTest {

    @Test
    fun cardVisibility_isSecret() {
        // Approval cards never render on a secured lock screen (issue #8).
        assertEquals(NotificationCompat.VISIBILITY_SECRET, HearthNotifier.cardVisibility())
        assertEquals(NotificationCompat.VISIBILITY_SECRET, cardVisibility())
        assertEquals(-1, HearthNotifier.cardVisibility())
    }

    @Test
    fun cardVisibility_notPrivateOrPublic() {
        assertNotEquals(NotificationCompat.VISIBILITY_PRIVATE, HearthNotifier.cardVisibility())
        assertNotEquals(NotificationCompat.VISIBILITY_PUBLIC, HearthNotifier.cardVisibility())
    }
}

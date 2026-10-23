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
    }

    @Test
    fun cardVisibility_notPrivateOrPublic() {
        assertNotEquals(NotificationCompat.VISIBILITY_PRIVATE, HearthNotifier.cardVisibility())
        assertNotEquals(NotificationCompat.VISIBILITY_PUBLIC, HearthNotifier.cardVisibility())
    }

    @Test
    fun replyVisibility_isPrivate() {
        // Agent reply notifications must use VISIBILITY_PRIVATE (finding L-11).
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, HearthNotifier.replyVisibility())
    }

    @Test
    fun replyVisibility_notSecretOrPublic() {
        // SECRET can silently suppress on some OEMs; PUBLIC leaks content (finding L-11).
        assertNotEquals(NotificationCompat.VISIBILITY_SECRET, HearthNotifier.replyVisibility())
        assertNotEquals(NotificationCompat.VISIBILITY_PUBLIC, HearthNotifier.replyVisibility())
    }

    @Test
    fun replyPublicText_matchesLaw() {
        // Redacted public text leaks nothing beyond "Lararium: new message" (finding L-11).
        assertEquals("Lararium: new message", HearthNotifier.REPLY_PUBLIC_TEXT)
    }
}

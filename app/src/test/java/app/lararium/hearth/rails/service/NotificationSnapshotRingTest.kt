package app.lararium.hearth.rails.service

import app.lararium.hearth.rails.Row
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NotificationSnapshotRing buffer (F5).
 *
 * Verifies:
 * - Cap 50 enforcement with FIFO eviction
 * - Rejection of ongoing notifications (isOngoing = true)
 * - Rejection of foreground service indicator notifications (isForeground = true)
 * - Removal by ID
 * - Thread-safe snapshot output
 */
class NotificationSnapshotRingTest {

    private fun makeRow(id: String, text: String): Row {
        return Row(id, JSONObject().apply {
            put("id", id)
            put("text", text)
        })
    }

    @Test
    fun rejectsOngoingAndForegroundNotifications() {
        val ring = NotificationSnapshotRing(capacity = 50)

        // Normal notification -> accepted
        val normalAdded = ring.add("n1", makeRow("n1", "Normal message"), isOngoing = false, isForeground = false)
        assertTrue(normalAdded)
        assertEquals(1, ring.size())

        // Ongoing notification -> rejected
        val ongoingAdded = ring.add("n2", makeRow("n2", "Music player"), isOngoing = true, isForeground = false)
        assertFalse(ongoingAdded)
        assertEquals(1, ring.size())

        // Foreground service indicator -> rejected
        val fgAdded = ring.add("n3", makeRow("n3", "Sync service"), isOngoing = false, isForeground = true)
        assertFalse(fgAdded)
        assertEquals(1, ring.size())

        // Both -> rejected
        val bothAdded = ring.add("n4", makeRow("n4", "Service"), isOngoing = true, isForeground = true)
        assertFalse(bothAdded)
        assertEquals(1, ring.size())
    }

    @Test
    fun cap50FifoEviction() {
        val ring = NotificationSnapshotRing(capacity = 50)

        for (i in 1..60) {
            val id = "notif-$i"
            val added = ring.add(id, makeRow(id, "Content $i"))
            assertTrue(added)
        }

        assertEquals(50, ring.size())
        val snapshot = ring.snapshot()
        assertEquals(50, snapshot.size)

        // First 10 (notif-1 to notif-10) should have been evicted FIFO
        assertEquals("notif-11", snapshot.first().id)
        assertEquals("notif-60", snapshot.last().id)
    }

    @Test
    fun removeExistingAndNonExisting() {
        val ring = NotificationSnapshotRing(capacity = 10)
        ring.add("n1", makeRow("n1", "First"))
        ring.add("n2", makeRow("n2", "Second"))

        assertEquals(2, ring.size())
        assertTrue(ring.remove("n1"))
        assertEquals(1, ring.size())
        assertEquals("n2", ring.snapshot().first().id)

        assertFalse("Removing non-existing key returns false", ring.remove("n999"))
        assertEquals(1, ring.size())
    }

    @Test
    fun updateExistingEntryDoesNotIncreaseCount() {
        val ring = NotificationSnapshotRing(capacity = 10)
        ring.add("n1", makeRow("n1", "Initial text"))
        assertEquals(1, ring.size())

        ring.add("n1", makeRow("n1", "Updated text"))
        assertEquals(1, ring.size())
        assertEquals("Updated text", ring.snapshot().first().data.getString("text"))
    }
}

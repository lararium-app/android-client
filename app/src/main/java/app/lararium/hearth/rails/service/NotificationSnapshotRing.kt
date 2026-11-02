package app.lararium.hearth.rails.service

import app.lararium.hearth.rails.Row

/**
 * Thread-safe ring buffer maintaining the last 50 non-mutable notifications (F5).
 * Ongoing and foreground service indicator notifications are strictly rejected.
 */
class NotificationSnapshotRing(val capacity: Int = 50) {

    private val entries = LinkedHashMap<String, Row>()

    @Synchronized
    fun add(id: String, row: Row, isOngoing: Boolean = false, isForeground: Boolean = false): Boolean {
        if (isOngoing || isForeground) {
            // Reject mutable / ongoing service indicators per spec A5
            return false
        }

        if (entries.containsKey(id)) {
            entries[id] = row
            return true
        }

        if (entries.size >= capacity) {
            val oldestKey = entries.keys.firstOrNull()
            if (oldestKey != null) {
                entries.remove(oldestKey)
            }
        }

        entries[id] = row
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        return entries.remove(id) != null
    }

    @Synchronized
    fun snapshot(): List<Row> {
        return entries.values.toList()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

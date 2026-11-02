package app.lararium.hearth.rails

import org.json.JSONObject

/**
 * Pure JVM bounded, deduplicated, retry-aware receipt queue (A6).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */

data class PendingReceipt(val actionId: String, val body: JSONObject, val attempts: Int)

class ReceiptQueue(val capacity: Int = CAPACITY) {
    companion object {
        const val CAPACITY = 1000
    }

    private data class Entry(
        val actionId: String,
        val body: JSONObject,
        var attempts: Int = 0,
        var delivered: Boolean = false
    )

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Enqueue a receipt body for an action.
     * Duplicate actionId is a no-op (one terminal fact, recorded once).
     *
     * Eviction behavior:
     * When capacity (1000) is reached, evicts the OLDEST acked receipt (delivered = true).
     * Receipts are never acked-pending-then-evicted: an entry is dropped only when queue is full
     * and delivered = true. If all entries are undelivered, enqueue still succeeds by evicting
     * the oldest undelivered entry.
     * Trade-off: Bounded memory beats unbounded memory; daemon timeout backstops undelivered items.
     */
    @Synchronized
    fun enqueue(actionId: String, body: JSONObject) {
        if (entries.containsKey(actionId)) {
            return
        }

        if (entries.size >= capacity) {
            val ackedKey = entries.entries.firstOrNull { it.value.delivered }?.key
            if (ackedKey != null) {
                entries.remove(ackedKey)
            } else {
                val oldestKey = entries.keys.firstOrNull()
                if (oldestKey != null) {
                    entries.remove(oldestKey)
                }
            }
        }

        entries[actionId] = Entry(actionId = actionId, body = body)
    }

    /**
     * Returns all currently pending (undelivered) receipts in FIFO order.
     */
    @Synchronized
    fun pending(): List<PendingReceipt> {
        return entries.values
            .filter { !it.delivered }
            .map { PendingReceipt(it.actionId, it.body, it.attempts) }
    }

    @Synchronized
    fun markDelivered(actionId: String) {
        entries[actionId]?.delivered = true
    }

    @Synchronized
    fun recordAttempt(actionId: String) {
        entries[actionId]?.let { it.attempts++ }
    }

    /**
     * Retry delay ladder: 1s * 2 -> 30s.
     * attempts 1 -> 1000ms
     * attempts 2 -> 2000ms
     * attempts 3 -> 4000ms
     * attempts 4 -> 8000ms
     * attempts 5 -> 16000ms
     * attempts >= 6 -> 30000ms
     */
    fun retryDelayMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceAtLeast(0).coerceAtMost(30)
        return minOf(30_000L, 1_000L * (1L shl shift))
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun isDelivered(actionId: String): Boolean = entries[actionId]?.delivered == true
}

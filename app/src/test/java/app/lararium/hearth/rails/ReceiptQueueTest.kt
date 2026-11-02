package app.lararium.hearth.rails

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ReceiptQueue terminal-receipt guarantee, deduplication, and bounded eviction (A6).
 */
class ReceiptQueueTest {

    @Test
    fun enqueueOnceAndPendingInOrder() {
        val queue = ReceiptQueue()
        val body1 = JSONObject().apply { put("ok", true); put("result", "call_placed") }
        val body2 = JSONObject().apply { put("ok", false); put("error", "denied_local") }

        queue.enqueue("act-1", body1)
        queue.enqueue("act-2", body2)

        val pending = queue.pending()
        assertEquals(2, pending.size)
        assertEquals("act-1", pending[0].actionId)
        assertEquals("call_placed", pending[0].body.getString("result"))
        assertEquals(0, pending[0].attempts)

        assertEquals("act-2", pending[1].actionId)
        assertEquals("denied_local", pending[1].body.getString("error"))
        assertEquals(0, pending[1].attempts)
    }

    @Test
    fun duplicateEnqueueIgnored() {
        val queue = ReceiptQueue()
        val body1 = JSONObject().apply { put("ok", true); put("result", "original") }
        val body2 = JSONObject().apply { put("ok", false); put("error", "duplicate_ignored") }

        queue.enqueue("act-dup", body1)
        queue.enqueue("act-dup", body2)

        val pending = queue.pending()
        assertEquals(1, pending.size)
        assertEquals("original", pending[0].body.getString("result"))
    }

    @Test
    fun markDeliveredRemovesFromPending() {
        val queue = ReceiptQueue()
        val body = JSONObject().apply { put("ok", true) }

        queue.enqueue("act-1", body)
        queue.enqueue("act-2", body)

        assertEquals(2, queue.pending().size)
        assertFalse(queue.isDelivered("act-1"))

        queue.markDelivered("act-1")
        assertTrue(queue.isDelivered("act-1"))

        val pending = queue.pending()
        assertEquals(1, pending.size)
        assertEquals("act-2", pending[0].actionId)
    }

    @Test
    fun recordAttemptIncrementsPendingAttempts() {
        val queue = ReceiptQueue()
        val body = JSONObject().apply { put("ok", true) }

        queue.enqueue("act-retry", body)
        assertEquals(0, queue.pending()[0].attempts)

        queue.recordAttempt("act-retry")
        assertEquals(1, queue.pending()[0].attempts)

        queue.recordAttempt("act-retry")
        assertEquals(2, queue.pending()[0].attempts)
    }

    @Test
    fun retryDelayLadder() {
        val queue = ReceiptQueue()

        // 1s * 2 -> 30s ladder
        assertEquals(1000L, queue.retryDelayMs(0))
        assertEquals(1000L, queue.retryDelayMs(1))
        assertEquals(2000L, queue.retryDelayMs(2))
        assertEquals(4000L, queue.retryDelayMs(3))
        assertEquals(8000L, queue.retryDelayMs(4))
        assertEquals(16000L, queue.retryDelayMs(5))
        assertEquals(30000L, queue.retryDelayMs(6))
        assertEquals(30000L, queue.retryDelayMs(7))
        assertEquals(30000L, queue.retryDelayMs(100))
    }

    @Test
    fun evictionAt1000_dropsOldestAckedWhenAckedExists() {
        val queue = ReceiptQueue(capacity = 1000)
        val dummyBody = JSONObject().apply { put("ok", true) }

        // Fill queue to capacity: act-0 through act-999
        for (i in 0 until 1000) {
            queue.enqueue("act-$i", dummyBody)
        }
        assertEquals(1000, queue.size())

        // Mark act-2 delivered
        queue.markDelivered("act-2")
        assertTrue(queue.isDelivered("act-2"))

        // Enqueue act-1000 (queue is full)
        queue.enqueue("act-1000", dummyBody)
        assertEquals(1000, queue.size())

        // Oldest acked (act-2) was evicted!
        assertFalse("act-2 should have been evicted", queue.isDelivered("act-2"))
        // act-0 (which was undelivered) must NOT have been evicted
        val pendingIds = queue.pending().map { it.actionId }.toSet()
        assertTrue("act-0 must remain pending", pendingIds.contains("act-0"))
        assertTrue("act-1 must remain pending", pendingIds.contains("act-1"))
        assertTrue("act-1000 must be in queue", pendingIds.contains("act-1000"))
    }

    @Test
    fun evictionAt1000_allUndeliveredEvictsOldestUndelivered() {
        val queue = ReceiptQueue(capacity = 1000)
        val dummyBody = JSONObject().apply { put("ok", true) }

        // Fill queue to capacity with zero delivered receipts
        for (i in 0 until 1000) {
            queue.enqueue("act-$i", dummyBody)
        }
        assertEquals(1000, queue.size())

        // Enqueue act-1000 when all 1000 are undelivered
        queue.enqueue("act-1000", dummyBody)
        assertEquals(1000, queue.size())

        val pending = queue.pending()
        assertEquals(1000, pending.size)

        // Oldest undelivered (act-0) was evicted to bound memory
        assertEquals("act-1", pending.first().actionId)
        assertEquals("act-1000", pending.last().actionId)
    }
}

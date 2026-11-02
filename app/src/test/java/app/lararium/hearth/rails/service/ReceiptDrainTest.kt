package app.lararium.hearth.rails.service

import app.lararium.hearth.HttpResponse
import app.lararium.hearth.rails.ReceiptQueue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for ReceiptDrain worker (F4, spec A6).
 *
 * Verifies:
 * - Success marks action receipt delivered in ReceiptQueue
 * - Failure increments attempt counter and follows retry backoff ladder
 * - Enqueue-once invariant preserved across drain cycles
 */
class ReceiptDrainTest {

    private val endpoint = DeviceEndpoint("https://example.com:7717", "lar1_test_token", "test-dev")

    @Test
    fun drainSuccess_marksDelivered() = runBlocking {
        val queue = ReceiptQueue()
        val postedUrls = mutableListOf<String>()

        val drain = ReceiptDrain(
            receiptQueue = queue,
            endpointProvider = { endpoint },
            httpPoster = { url, _, _ ->
                postedUrls.add(url)
                HttpResponse(200, "{\"ok\":true}")
            }
        )

        val receiptBody = JSONObject().apply {
            put("ok", true)
            put("result", "call_placed")
        }
        queue.enqueue("act-101", receiptBody)
        assertEquals(1, queue.pending().size)

        val deliveredCount = drain.drainOnce()
        assertEquals(1, deliveredCount)
        assertTrue(queue.isDelivered("act-101"))
        assertEquals(0, queue.pending().size)
        assertEquals("https://example.com:7717/v1/devices/test-dev/actions/act-101/receipt", postedUrls.first())
    }

    @Test
    fun drainFailure_incrementsAttemptAndFollowsRetryLadder() = runBlocking {
        val queue = ReceiptQueue()
        var returnError = true

        val drain = ReceiptDrain(
            receiptQueue = queue,
            endpointProvider = { endpoint },
            httpPoster = { _, _, _ ->
                if (returnError) {
                    HttpResponse(500, "{\"error\":\"server_error\"}")
                } else {
                    HttpResponse(200, "{\"ok\":true}")
                }
            }
        )

        val body = JSONObject().apply { put("ok", true) }
        queue.enqueue("act-500", body)

        // Attempt 1 fails
        val count1 = drain.drainOnce()
        assertEquals(0, count1)
        assertFalse(queue.isDelivered("act-500"))
        var pending = queue.pending()
        assertEquals(1, pending.size)
        assertEquals(1, pending.first().attempts)
        assertEquals(1000L, queue.retryDelayMs(pending.first().attempts))

        // Attempt 2 fails
        drain.drainOnce()
        pending = queue.pending()
        assertEquals(2, pending.first().attempts)
        assertEquals(2000L, queue.retryDelayMs(pending.first().attempts))

        // Attempt 3 fails via network exception
        val throwingDrain = ReceiptDrain(
            receiptQueue = queue,
            endpointProvider = { endpoint },
            httpPoster = { _, _, _ -> throw IOException("Network drop") }
        )
        throwingDrain.drainOnce()
        pending = queue.pending()
        assertEquals(3, pending.first().attempts)
        assertEquals(4000L, queue.retryDelayMs(pending.first().attempts))

        // Subsequent success delivers
        returnError = false
        val countSuccess = drain.drainOnce()
        assertEquals(1, countSuccess)
        assertTrue(queue.isDelivered("act-500"))
        assertEquals(0, queue.pending().size)
    }

    @Test
    fun enqueueOncePreservedAcrossDrains() = runBlocking {
        val queue = ReceiptQueue()
        val drain = ReceiptDrain(
            receiptQueue = queue,
            endpointProvider = { endpoint },
            httpPoster = { _, _, _ -> HttpResponse(200, "{\"ok\":true}") }
        )

        val body1 = JSONObject().apply { put("ok", true) }
        val body2 = JSONObject().apply { put("ok", false) }

        queue.enqueue("act-once", body1)
        queue.enqueue("act-once", body2) // Duplicate enqueue is a no-op

        assertEquals(1, queue.pending().size)
        drain.drainOnce()

        assertTrue(queue.isDelivered("act-once"))
        assertEquals(0, queue.pending().size)
    }
}

package app.lararium.hearth.rails.service

import app.lararium.hearth.HttpResponse
import app.lararium.hearth.Net
import app.lararium.hearth.rails.ReceiptQueue

/**
 * Drains pending action execution receipts to the Hearth daemon (F4, spec A6).
 *
 * Resolves each pending receipt by posting to /local/device/v1/actions/{id}/receipt.
 * 2xx responses mark the item delivered. Non-2xx or network errors increment attempt count
 * and follow the exponential backoff ladder from ReceiptQueue.
 */
class ReceiptDrain(
    val receiptQueue: ReceiptQueue,
    private val endpointProvider: () -> DeviceEndpoint?,
    private val httpPoster: suspend (url: String, body: String, headers: Map<String, String>) -> HttpResponse = { url, body, headers ->
        Net.post(url, body, headers)
    }
) {
    suspend fun drainOnce(): Int {
        val endpoint = endpointProvider() ?: return 0
        val pending = receiptQueue.pending()
        var deliveredCount = 0

        for (item in pending) {
            val url = "${endpoint.baseUrl.trimEnd('/')}/local/device/v1/actions/${item.actionId}/receipt"
            val headers = mapOf(
                "Authorization" to "Bearer ${endpoint.token}",
                "Content-Type" to "application/json"
            )

            val response = try {
                httpPoster(url, item.body.toString(), headers)
            } catch (_: Exception) {
                receiptQueue.recordAttempt(item.actionId)
                continue
            }

            if (response.status in 200..299) {
                receiptQueue.markDelivered(item.actionId)
                deliveredCount++
            } else {
                receiptQueue.recordAttempt(item.actionId)
            }
        }

        return deliveredCount
    }
}

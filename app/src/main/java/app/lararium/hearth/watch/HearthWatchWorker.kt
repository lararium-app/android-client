package app.lararium.hearth.watch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.lararium.hearth.Endpoint
import app.lararium.hearth.LarariumClient
import app.lararium.security.SecureKeyManager
import app.lararium.service.AssistantService
import java.io.IOException

/**
 * Background worker polling the paired hearth for pending custody card approvals
 * and new assistant replies.
 */
class HearthWatchWorker(
    app: Context,
    params: WorkerParameters,
) : CoroutineWorker(app, params) {

    override suspend fun doWork(): Result {
        return try {
            val keyManager = SecureKeyManager(applicationContext)
            val epTriple = keyManager.getHearthEndpoint() ?: return Result.success()
            val endpoint = Endpoint(
                baseUrl = epTriple.first,
                token = epTriple.second,
                name = epTriple.third,
            )
            val client = LarariumClient(endpoint)
            val store = WatchStore(applicationContext)

            // 1. Check custody cards for pending approvals
            val cardsBody = client.custosCards()
            val pendingCards = WatchLogic.parseCards(cardsBody)
            val notifiedCards = store.getNotifiedCards()
            val diff = WatchLogic.diffCards(pendingCards, notifiedCards)

            for (card in diff.notify) {
                HearthNotifier.notifyCard(applicationContext, card)
            }
            val updatedNotified = (notifiedCards - diff.prune) + diff.notify.map { it.id }.toSet()
            store.replaceNotifiedCards(updatedNotified)

            // 2. Check sessions for assistant replies
            val sessionsArray = client.listSessions()
            val sessionsToProcess = mutableListOf<Pair<String, String>>()
            for (i in 0 until sessionsArray.length()) {
                if (sessionsToProcess.size >= 10) break
                val obj = sessionsArray.optJSONObject(i) ?: continue
                if (obj.isNull("id")) continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                val title = if (obj.isNull("title")) "" else obj.optString("title", "")
                sessionsToProcess.add(id to title)
            }

            for ((sessionId, sessionTitle) in sessionsToProcess) {
                val cursor = store.getCursor(sessionId)
                val eventsObj = client.events(sessionId, afterSeq = cursor ?: 0)
                val scanResult = WatchLogic.scanEvents(eventsObj.toString(), cursor)
                store.setCursor(sessionId, scanResult.newCursor)

                if (scanResult.replies.isNotEmpty()) {
                    if (WatchLogic.shouldWriteThrough(sessionId, AssistantService.activeSessionId, scanResult.replies)) {
                        AssistantService.writeThroughReplies(sessionId, scanResult.replies)
                    }
                    val lastReply = scanResult.replies.last()
                    HearthNotifier.notifyReply(applicationContext, sessionId, sessionTitle, lastReply)
                }
            }

            Result.success()
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            if (msg.contains("returned 401") || msg.contains("returned 403")) {
                val store = WatchStore(applicationContext)
                if (store.shouldAlertToken("auth")) {
                    HearthNotifier.notifyAuthError(applicationContext)
                }
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Throwable) {
            Result.retry()
        }
    }
}

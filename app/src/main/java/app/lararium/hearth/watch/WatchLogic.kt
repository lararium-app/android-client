package app.lararium.hearth.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Custody card approval request payload from hearthd.
 */
data class CardInfo(
    val id: String,
    val cell: String,
    val cred: String,
    val dest: String,
    val tool: String,
    val review: String,
)

/**
 * Result of diffing pending cards against already-notified cards.
 */
data class CardDiff(
    val notify: List<CardInfo>,
    val prune: Set<String>,
)

/**
 * Summary of a new assistant reply extracted from session events.
 */
data class ReplyPreview(
    val seq: Int,
    val text: String,
)

/**
 * Result of scanning session events for new assistant replies.
 */
data class ScanResult(
    val newCursor: Int,
    val seenBefore: Boolean,
    val replies: List<ReplyPreview>,
)

object WatchLogic {

    /**
     * Parses the response of GET /v1/custos/cards ({"cards":[{id, cell, cred, dest, tool, review, age_s, expires_in_s}, ...]}).
     * Missing fields become empty strings; non-objects and entries lacking a non-blank id are silently skipped.
     * A body that fails to parse yields the empty list (never throw).
     */
    fun parseCards(eventsJsonArrayBody: String): List<CardInfo> {
        return try {
            val trimmed = eventsJsonArrayBody.trim()
            val cardsArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONArray("cards") ?: return emptyList()
            }
            val result = mutableListOf<CardInfo>()
            for (i in 0 until cardsArray.length()) {
                val obj = cardsArray.optJSONObject(i) ?: continue
                if (obj.isNull("id")) continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                val cell = if (obj.isNull("cell")) "" else obj.optString("cell", "")
                val cred = if (obj.isNull("cred")) "" else obj.optString("cred", "")
                val dest = if (obj.isNull("dest")) "" else obj.optString("dest", "")
                val tool = if (obj.isNull("tool")) "" else obj.optString("tool", "")
                val review = if (obj.isNull("review")) "" else obj.optString("review", "")
                result.add(
                    CardInfo(
                        id = id,
                        cell = cell,
                        cred = cred,
                        dest = dest,
                        tool = tool,
                        review = review,
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Computes which pending cards require notification and which previously notified
     * cards are no longer pending and should be pruned.
     */
    fun diffCards(pending: List<CardInfo>, notified: Set<String>): CardDiff {
        val pendingIds = pending.map { it.id }.toSet()
        val notify = pending.filter { it.id !in notified }
        val prune = notified.filter { it !in pendingIds }.toSet()
        return CardDiff(notify = notify, prune = prune)
    }

    /**
     * Scans session events for new assistant messages past [cursor].
     * Parses {"events":[...], "in_flight":bool}.
     * When cursor is null, baselines silently and returns replies = emptyList().
     */
    fun scanEvents(eventsJsonBody: String, cursor: Int?): ScanResult {
        val seenBefore = cursor != null
        val initialCursor = cursor ?: 0
        return try {
            val root = JSONObject(eventsJsonBody)
            val eventsArray = root.optJSONArray("events")
                ?: return ScanResult(initialCursor, seenBefore, emptyList())

            var maxNumericSeq: Int? = null
            val replies = mutableListOf<ReplyPreview>()

            for (i in 0 until eventsArray.length()) {
                val obj = eventsArray.optJSONObject(i) ?: continue

                val seq = if (obj.has("seq") && !obj.isNull("seq")) {
                    val seqRaw = obj.opt("seq")
                    when (seqRaw) {
                        is Number -> seqRaw.toInt()
                        is String -> seqRaw.toIntOrNull()
                        else -> null
                    }
                } else {
                    null
                }

                if (seq != null) {
                    maxNumericSeq = if (maxNumericSeq == null) seq else maxOf(maxNumericSeq, seq)
                }

                if (seenBefore && cursor != null && seq != null && seq > cursor) {
                    val t = if (obj.isNull("t")) "" else obj.optString("t", "")
                    val role = if (obj.isNull("role")) "" else obj.optString("role", "")
                    if (t == "msg" && role == "assistant") {
                        val text = if (obj.isNull("text")) "" else obj.optString("text", "")
                        replies.add(ReplyPreview(seq = seq, text = text))
                    }
                }
            }

            val newCursor = maxOf(initialCursor, maxNumericSeq ?: initialCursor)
            ScanResult(
                newCursor = newCursor,
                seenBefore = seenBefore,
                replies = if (seenBefore) replies else emptyList(),
            )
        } catch (_: Exception) {
            ScanResult(initialCursor, seenBefore, emptyList())
        }
    }

    /**
     * Collapses newlines and tabs to single spaces, trims, and cuts to [max] chars
     * appending '…' only when it actually cut.
     */
    fun truncate(s: String, max: Int = 200): String {
        val collapsed = s.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return if (collapsed.length > max) {
            collapsed.substring(0, max) + "…"
        } else {
            collapsed
        }
    }
}

/** Top-level shortcut delegating to [WatchLogic.parseCards]. */
fun parseCards(eventsJsonArrayBody: String): List<CardInfo> =
    WatchLogic.parseCards(eventsJsonArrayBody)

/** Top-level shortcut delegating to [WatchLogic.diffCards]. */
fun diffCards(pending: List<CardInfo>, notified: Set<String>): CardDiff =
    WatchLogic.diffCards(pending, notified)

/** Top-level shortcut delegating to [WatchLogic.scanEvents]. */
fun scanEvents(eventsJsonBody: String, cursor: Int?): ScanResult =
    WatchLogic.scanEvents(eventsJsonBody, cursor)

/** Top-level shortcut delegating to [WatchLogic.truncate]. */
fun truncate(s: String, max: Int = 200): String =
    WatchLogic.truncate(s, max)


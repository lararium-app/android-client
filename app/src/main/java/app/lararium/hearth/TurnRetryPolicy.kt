package app.lararium.hearth

import org.json.JSONObject

/**
 * Pure JVM decision logic for chat turn send retries, event-log deduplication before
 * resend, and first-token watchdog timeouts (issues #45, #63).
 *
 * Contains ZERO android.* imports so it can be unit-tested directly on the JVM.
 */
class TurnRetryPolicy(
    val watchdogTimeoutMs: Long = WATCHDOG_TIMEOUT_MS,
    val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    val commitWaitDeadlineMs: Long = DEFAULT_COMMIT_WAIT_MS,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {

    companion object {
        const val WATCHDOG_TIMEOUT_MS = 45_000L
        const val DEFAULT_RETRY_DELAY_MS = 1_500L
        const val DEFAULT_COMMIT_WAIT_MS = 150_000L
        const val DEFAULT_MAX_RETRIES = 1

        /**
         * Inspects a session events JSON payload (`{"events":[...], "in_flight":bool}`)
         * and returns true if a user message matching [sentText] already exists in the log.
         */
        fun hasUserMessageInLog(eventsJson: String?, sentText: String): Boolean {
            if (eventsJson.isNullOrBlank() || sentText.isBlank()) return false
            return try {
                val root = JSONObject(eventsJson)
                val array = root.optJSONArray("events") ?: return false
                val target = sentText.trim()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optString("t", "") == "msg" &&
                        obj.optString("role", "") == "user" &&
                        obj.optString("text", "").trim() == target
                    ) {
                        return true
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Extracts the committed assistant reply following the last occurrence of
         * [sentText] in [eventsJson], if already present.
         */
        fun committedReplyInLog(eventsJson: String?, sentText: String): String? {
            if (eventsJson.isNullOrBlank() || sentText.isBlank()) return null
            return try {
                val root = JSONObject(eventsJson)
                val array = root.optJSONArray("events") ?: return null
                val target = sentText.trim()
                var lastUserIdx = -1
                val entries = mutableListOf<Pair<String, String>>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optString("t", "") == "msg") {
                        val role = obj.optString("role", "")
                        val text = obj.optString("text", "")
                        if (text.isNotBlank() && (role == "user" || role == "assistant")) {
                            if (role == "user" && text.trim() == target) {
                                lastUserIdx = entries.size
                            }
                            entries.add(role to text)
                        }
                    }
                }
                if (lastUserIdx == -1) return null
                entries.subList(lastUserIdx + 1, entries.size)
                    .firstOrNull { it.first == "assistant" }
                    ?.second
            } catch (_: Exception) {
                null
            }
        }
    }

    enum class Phase {
        IDLE,
        SENDING_INITIAL,
        AWAITING_EVENT_LOG,
        WAITING_FOR_COMMIT,
        SENDING_RETRY,
        COMPLETED,
        FAILED,
    }

    sealed class Action {
        /** No action required (e.g. first token arrived before watchdog, or already terminal). */
        object NoOp : Action()

        /** Wait [delayMs] and fetch session events to check if user message was logged before resending. */
        data class CheckEventLogBeforeRetry(val delayMs: Long) : Action()

        /** User message already exists in the event log; do NOT resend — wait for turn commit. */
        data class WaitForCommit(val deadlineMs: Long) : Action()

        /** User message is not in the event log; resend the turn once. */
        object Resend : Action()

        /** Turn succeeded with [responseText]; clear any failure state and deliver. */
        data class Complete(val responseText: String) : Action()

        /** Retry budget exhausted (or wait-for-commit timed out); surface failedSend. */
        data class Fail(val reason: String? = null) : Action()
    }

    var phase: Phase = Phase.IDLE
        private set

    var retriesUsed: Int = 0
        private set

    var receivedFirstToken: Boolean = false
        private set

    var committed: Boolean = false
        private set

    /**
     * Marks the start of a send attempt (initial or retry).
     */
    fun onSendStart(): Phase {
        phase = if (retriesUsed == 0) Phase.SENDING_INITIAL else Phase.SENDING_RETRY
        receivedFirstToken = false
        committed = false
        return phase
    }

    /**
     * Records that at least one streaming token has arrived, disarming the 45 s watchdog.
     */
    fun onFirstToken(): Action {
        receivedFirstToken = true
        return Action.NoOp
    }

    /**
     * Records normal completion of the turn.
     */
    fun onTurnDone(responseText: String): Action {
        committed = true
        phase = Phase.COMPLETED
        return Action.Complete(responseText)
    }

    /**
     * Handles a send failure (network error, exception, or 0-token close).
     * On initial attempt with no tokens received, schedules a single retry check;
     * if retry was already used, transitions to [Phase.FAILED] and emits [Action.Fail].
     */
    fun onFailure(reason: String? = null): Action {
        if (committed || phase == Phase.COMPLETED) return Action.NoOp
        if (receivedFirstToken) {
            phase = Phase.FAILED
            return Action.Fail(reason)
        }
        return if (retriesUsed < maxRetries) {
            retriesUsed++
            phase = Phase.AWAITING_EVENT_LOG
            Action.CheckEventLogBeforeRetry(retryDelayMs)
        } else {
            phase = Phase.FAILED
            Action.Fail(reason)
        }
    }

    /**
     * Handles a stream closing cleanly with 0 tokens and no committed reply.
     */
    fun onZeroTokenClose(): Action = onFailure("Connection closed with no tokens")

    /**
     * Evaluates the watchdog after [elapsedMs] from send start.
     * If [elapsedMs] >= [watchdogTimeoutMs] with no first token and no commit,
     * treats the attempt as failed (retry once, then show failure).
     */
    fun onWatchdogTick(
        elapsedMs: Long,
        eventsJson: String? = null,
        sentText: String = "",
    ): Action {
        if (phase == Phase.COMPLETED || phase == Phase.FAILED) return Action.NoOp
        if (receivedFirstToken || committed) return Action.NoOp
        if (elapsedMs < watchdogTimeoutMs) return Action.NoOp

        val alreadyCommitted = if (eventsJson != null && sentText.isNotBlank()) {
            committedReplyInLog(eventsJson, sentText)
        } else null

        if (alreadyCommitted != null) {
            committed = true
            phase = Phase.COMPLETED
            return Action.Complete(alreadyCommitted)
        }

        return onFailure("Turn watchdog timed out after ${watchdogTimeoutMs}ms")
    }

    /**
     * Decides the retry action after fetching the session event log before resending:
     * if the user message already exists in the log, returns [Action.WaitForCommit] (DO NOT resend);
     * otherwise returns [Action.Resend].
     */
    fun onEventLogFetched(eventsJson: String?, sentText: String): Action {
        return if (hasUserMessageInLog(eventsJson, sentText)) {
            phase = Phase.WAITING_FOR_COMMIT
            Action.WaitForCommit(commitWaitDeadlineMs)
        } else {
            phase = Phase.SENDING_RETRY
            receivedFirstToken = false
            Action.Resend
        }
    }

    /**
     * Resolves the outcome of waiting for a turn commit via `waitForTurnCommit`.
     */
    fun onCommitWaitResult(committedText: String?, failureReason: String? = null): Action {
        return if (!committedText.isNullOrBlank()) {
            committed = true
            phase = Phase.COMPLETED
            Action.Complete(committedText)
        } else {
            phase = Phase.FAILED
            Action.Fail(failureReason)
        }
    }
}

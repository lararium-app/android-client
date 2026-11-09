package app.lararium.hearth.rails

/**
 * Pure JVM monotonic sequence tracker per rail (A5).
 * Tracks independent strictly increasing sequence numbers across rails.
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */
class SeqTracker {
    private val seqs = mutableMapOf<String, Long>()

    @Synchronized
    fun acceptedSeq(rail: String): Long = seqs[rail] ?: 0L

    /**
     * Accepts a new sequence number if it is strictly greater than the current accepted sequence.
     * Equal or lower sequence numbers are rejected without modifying state.
     */
    @Synchronized
    fun accept(rail: String, seq: Long): Boolean {
        val current = acceptedSeq(rail)
        if (seq > current) {
            seqs[rail] = seq
            return true
        }
        return false
    }

    /**
     * Rebases the tracked sequence for [rail] onto the server's counter after a 409 stale_seq.
     * The next push will use `serverCurrent + 1`.
     *
     * Unlike [accept], this is deliberately NOT monotonic: it mirrors the server, so the
     * last writer wins and a lower value overwrites a higher one (rebase(5) then rebase(3)
     * leaves 3). The server is the source of truth for its own counter.
     */
    @Synchronized
    fun rebase(rail: String, serverCurrent: Long) {
        seqs[rail] = serverCurrent
    }
}

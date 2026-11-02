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
     * Resets the tracked sequence number for the specified rail to 0.
     * Called after a 409 stale_seq round-trip so the next sequence restarts at 1 with a fresh snapshot.
     */
    @Synchronized
    fun reset(rail: String) {
        seqs[rail] = 0L
    }
}

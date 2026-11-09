package app.lararium.hearth.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SeqTracker monotonic sequence custody per rail (A5).
 */
class SeqTrackerTest {

    @Test
    fun initialSequenceIsZero() {
        val tracker = SeqTracker()
        assertEquals(0L, tracker.acceptedSeq("calendar"))
        assertEquals(0L, tracker.acceptedSeq("contacts"))
    }

    @Test
    fun strictlyIncreasingSequenceAccepted() {
        val tracker = SeqTracker()

        assertTrue(tracker.accept("calendar", 1L))
        assertEquals(1L, tracker.acceptedSeq("calendar"))

        assertTrue(tracker.accept("calendar", 2L))
        assertEquals(2L, tracker.acceptedSeq("calendar"))

        assertTrue(tracker.accept("calendar", 100L))
        assertEquals(100L, tracker.acceptedSeq("calendar"))
    }

    @Test
    fun equalOrLowerSequenceRejectedWithoutStateChange() {
        val tracker = SeqTracker()

        assertTrue(tracker.accept("contacts", 50L))
        assertEquals(50L, tracker.acceptedSeq("contacts"))

        // Equal sequence rejected
        assertFalse("Equal sequence must be rejected", tracker.accept("contacts", 50L))
        assertEquals(50L, tracker.acceptedSeq("contacts"))

        // Lower sequence rejected
        assertFalse("Lower sequence must be rejected", tracker.accept("contacts", 49L))
        assertEquals(50L, tracker.acceptedSeq("contacts"))

        assertFalse("Lower sequence must be rejected", tracker.accept("contacts", 1L))
        assertEquals(50L, tracker.acceptedSeq("contacts"))
    }

    @Test
    fun rebaseSetsFloorSoNextPushIsCurrentPlusOne() {
        val tracker = SeqTracker()

        tracker.rebase("calllog", 42L)
        assertEquals(42L, tracker.acceptedSeq("calllog"))

        // Equal to the rebased floor is stale; floor + 1 is accepted.
        assertFalse(tracker.accept("calllog", 42L))
        assertTrue(tracker.accept("calllog", 43L))
        assertEquals(43L, tracker.acceptedSeq("calllog"))
    }

    @Test
    fun rebaseIsLastWriterWinsUnlikeAccept() {
        val tracker = SeqTracker()

        tracker.rebase("calendar", 5L)
        tracker.rebase("calendar", 3L)
        assertEquals("rebase mirrors the server, so it may move downward", 3L, tracker.acceptedSeq("calendar"))

        tracker.accept("calendar", 10L)
        tracker.rebase("calendar", 7L)
        assertEquals(7L, tracker.acceptedSeq("calendar"))
    }

    @Test
    fun railsAreTrackedIndependently() {
        val tracker = SeqTracker()

        assertTrue(tracker.accept("calendar", 10L))
        assertTrue(tracker.accept("contacts", 20L))
        assertTrue(tracker.accept("sms", 1L))

        assertEquals(10L, tracker.acceptedSeq("calendar"))
        assertEquals(20L, tracker.acceptedSeq("contacts"))
        assertEquals(1L, tracker.acceptedSeq("sms"))

        // Rebasing calendar leaves contacts and sms intact
        tracker.rebase("calendar", 0L)
        assertEquals(0L, tracker.acceptedSeq("calendar"))
        assertEquals(20L, tracker.acceptedSeq("contacts"))
        assertEquals(1L, tracker.acceptedSeq("sms"))

        // Updating contacts doesn't affect sms or calendar
        assertTrue(tracker.accept("contacts", 25L))
        assertEquals(0L, tracker.acceptedSeq("calendar"))
        assertEquals(25L, tracker.acceptedSeq("contacts"))
        assertEquals(1L, tracker.acceptedSeq("sms"))
    }
}

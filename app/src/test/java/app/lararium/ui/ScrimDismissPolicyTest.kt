package app.lararium.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrimDismissPolicyTest {

    @Test
    fun debounceConstant_is300ms() {
        assertEquals(300L, ScrimDismissPolicy.DEBOUNCE_MS)
    }

    @Test
    fun exactlyAtBoundary_shouldDismiss() {
        // exactly-at-boundary true: nowMs - shownAtMs == DEBOUNCE_MS
        val shownAtMs = 1_000L
        val nowMs = shownAtMs + ScrimDismissPolicy.DEBOUNCE_MS
        assertTrue(ScrimDismissPolicy.shouldDismiss(shownAtMs = shownAtMs, nowMs = nowMs))
    }

    @Test
    fun oneMsEarly_shouldNotDismiss() {
        // 1ms-early false: nowMs - shownAtMs == DEBOUNCE_MS - 1
        val shownAtMs = 1_000L
        val nowMs = shownAtMs + ScrimDismissPolicy.DEBOUNCE_MS - 1L
        assertFalse(ScrimDismissPolicy.shouldDismiss(shownAtMs = shownAtMs, nowMs = nowMs))
    }

    @Test
    fun negativeElapsed_shouldNotDismiss() {
        // negative elapsed false: nowMs < shownAtMs
        val shownAtMs = 1_000L
        val nowMs = 999L
        assertFalse(ScrimDismissPolicy.shouldDismiss(shownAtMs = shownAtMs, nowMs = nowMs))
    }

    @Test
    fun afterDebounceWindow_shouldDismiss() {
        val shownAtMs = 1_000L
        val nowMs = shownAtMs + ScrimDismissPolicy.DEBOUNCE_MS + 200L
        assertTrue(ScrimDismissPolicy.shouldDismiss(shownAtMs = shownAtMs, nowMs = nowMs))
    }
}

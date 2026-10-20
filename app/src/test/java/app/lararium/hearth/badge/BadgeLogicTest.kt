package app.lararium.hearth.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for BadgeLogic (card count parsing, badge text, plural selection).
 */
class BadgeLogicTest {

    private fun cardsJson(n: Int): String {
        val items = (1..n).joinToString(",") { """{"id":"c$it","cell":"x"}""" }
        return """{"cards":[$items]}"""
    }

    @Test
    fun countPending_zero() {
        assertEquals(0, BadgeLogic.countPending(cardsJson(0)))
    }

    @Test
    fun countPending_one() {
        assertEquals(1, BadgeLogic.countPending(cardsJson(1)))
    }

    @Test
    fun countPending_five() {
        assertEquals(5, BadgeLogic.countPending(cardsJson(5)))
    }

    @Test
    fun countPending_twelve() {
        assertEquals(12, BadgeLogic.countPending(cardsJson(12)))
    }

    @Test
    fun countPending_garbageIsUnknown() {
        assertNull(BadgeLogic.countPending("<html>oops</html>"))
        assertNull(BadgeLogic.countPending("""{"error":"x"}"""))
        assertNull(BadgeLogic.countPending(""))
    }

    @Test
    fun badgeText_values() {
        assertNull(BadgeLogic.badgeText(BadgeLogic.NO_BADGE))
        assertNull(BadgeLogic.badgeText(0))
        assertEquals("1", BadgeLogic.badgeText(1))
        assertEquals("5", BadgeLogic.badgeText(5))
        assertEquals("9", BadgeLogic.badgeText(9))
        assertEquals("9+", BadgeLogic.badgeText(12))
    }

    @Test
    fun badgeText_fromParsedPayloads() {
        assertEquals("1", BadgeLogic.badgeText(BadgeLogic.countPending(cardsJson(1))!!))
        assertEquals("5", BadgeLogic.badgeText(BadgeLogic.countPending(cardsJson(5))!!))
        assertEquals("9+", BadgeLogic.badgeText(BadgeLogic.countPending(cardsJson(12))!!))
    }

    @Test
    fun pluralCategory_selection() {
        assertEquals("other", BadgeLogic.pluralCategory(0))
        assertEquals("one", BadgeLogic.pluralCategory(1))
        assertEquals("other", BadgeLogic.pluralCategory(2))
        assertEquals("other", BadgeLogic.pluralCategory(12))
    }
}

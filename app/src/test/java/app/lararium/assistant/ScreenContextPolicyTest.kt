package app.lararium.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextPolicyTest {
    private val now = 10_000_000L
    private val on = ScreenContextSettings(enabled = true)

    private fun item(id: String, ageMin: Long, pkg: String = "pkg.a", title: String = "T", text: String = "x") =
        NotificationSummary(id, pkg, title, text, now - ageMin * 60_000L)

    @Test fun defaultSettingsSelectNothing() {
        assertTrue(ScreenContextPolicy.select(listOf(item("1", 1)), ScreenContextSettings(), now).isEmpty())
    }

    @Test fun enabledFreshItemSelected() {
        assertEquals(listOf("1"), ScreenContextPolicy.select(listOf(item("1", 1)), on, now).map { it.id })
    }

    @Test fun excludedPackageDropped() {
        val s = on.copy(excludedPackages = setOf("pkg.b"))
        val r = ScreenContextPolicy.select(listOf(item("1", 1, "pkg.b"), item("2", 1)), s, now)
        assertEquals(listOf("2"), r.map { it.id })
    }

    @Test fun olderThanWindowDropped() {
        assertTrue(ScreenContextPolicy.select(listOf(item("1", 31)), on, now).isEmpty())
    }

    @Test fun exactlyWindowOldIsExcluded() {
        assertTrue(ScreenContextPolicy.select(listOf(item("1", 30)), on, now).isEmpty())
    }

    @Test fun capAtMaxItems() {
        val items = (1..9).map { item("i$it", it.toLong()) }
        assertEquals(5, ScreenContextPolicy.select(items, on, now).size)
    }

    @Test fun newestFirstWithIdTieBreak() {
        val items = listOf(item("b", 5), item("c", 2), item("a", 5))
        assertEquals(listOf("c", "a", "b"), ScreenContextPolicy.select(items, on, now).map { it.id })
    }

    @Test fun blankTitleFallsBackToText() {
        assertEquals("body", ScreenContextPolicy.displayLine(item("1", 1, title = "  ", text = "body")))
        assertEquals("T", ScreenContextPolicy.displayLine(item("1", 1)))
    }

    @Test fun contextBlockAndPrepend() {
        val items = listOf(item("1", 1, "pkg.a", "Hi"))
        assertEquals("Context from recent notifications:\n- Hi (pkg.a)", ScreenContextPolicy.buildContextBlock(items))
        assertEquals("Context from recent notifications:\n- Hi (pkg.a)\n\nq", ScreenContextPolicy.prependContext(items, "q"))
        assertEquals("q", ScreenContextPolicy.prependContext(emptyList(), "q"))
    }

    @Test fun sanitizeSharedTrimsCapsAndIsEmptySafe() {
        assertEquals("", ScreenContextPolicy.sanitizeShared("   "))
        assertEquals("abc", ScreenContextPolicy.sanitizeShared("  abc \n"))
        assertEquals(2000, ScreenContextPolicy.sanitizeShared("a".repeat(5000)).length)
        assertEquals("ab", ScreenContextPolicy.sanitizeShared("abcdef", 2))
    }
}

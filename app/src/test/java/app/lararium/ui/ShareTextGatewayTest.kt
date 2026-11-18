package app.lararium.ui

import app.lararium.assistant.ScreenContextPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTextGatewayTest {
    @Test fun normalTextPassesThroughSanitized() {
        assertEquals("hello world", sanitizeProcessText("  hello world \n"))
    }

    @Test fun oversizeTruncatedToPolicyLimit() {
        val out = sanitizeProcessText("a".repeat(ScreenContextPolicy.SHARE_LIMIT + 500))
        assertEquals(ScreenContextPolicy.SHARE_LIMIT, out.length)
    }

    @Test fun nullBecomesEmpty() {
        assertEquals("", sanitizeProcessText(null))
    }

    @Test fun whitespaceOnlyBecomesEmpty() {
        assertEquals("", sanitizeProcessText(" \t\n  "))
    }
}

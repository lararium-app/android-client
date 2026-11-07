package app.lararium.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunCopyTest {
    @Test
    fun installGuideUrlIsCanonical() {
        assertEquals(
            "https://github.com/lararium-app/lararium/blob/main/docs/INSTALL-GUIDE.md",
            FirstRunCopy.INSTALL_GUIDE_URL
        )
    }

    @Test
    fun explainerPresentWhenUnpairedNullWhenPaired() {
        assertNotNull(FirstRunCopy.hearthExplainer(true))
        assertNull(FirstRunCopy.hearthExplainer(false))
    }

    @Test
    fun explainerMentionsHearthAndNamesNoProvider() {
        val text = FirstRunCopy.hearthExplainer(true)!!.lowercase()
        assertTrue(text.contains("hearth"))
        for (banned in listOf("api key", "openrouter", "openai", "anthropic", "google", "gemini", "claude", "chatgpt")) {
            assertFalse(banned, text.contains(banned))
        }
    }
}

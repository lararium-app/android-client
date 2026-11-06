package app.lararium

import org.junit.Assert.*
import org.junit.Test

class AssistIntentLogicTest {
    @Test fun `assist routes to main`() =
        assertEquals("main", AssistIntentLogic.routeFor("android.intent.action.ASSIST"))

    @Test fun `voice command routes to main`() =
        assertEquals("main", AssistIntentLogic.routeFor("android.intent.action.VOICE_COMMAND"))

    @Test fun `null action has no route`() = assertNull(AssistIntentLogic.routeFor(null))

    @Test fun `main action has no route`() =
        assertNull(AssistIntentLogic.routeFor("android.intent.action.MAIN"))

    @Test fun `view action has no route`() =
        assertNull(AssistIntentLogic.routeFor("android.intent.action.VIEW"))

    @Test fun `action match is case sensitive`() {
        assertNull(AssistIntentLogic.routeFor("android.intent.action.assist"))
        assertFalse(AssistIntentLogic.isAssistantLaunch("android.intent.action.assist"))
    }
}

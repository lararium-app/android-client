package app.lararium.hearth

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLaneTest {

    @Test
    fun `paired yields Ready`() {
        val result = chatLaneFor(hasEndpoint = true)
        assertEquals(ChatLane.Ready, result)
    }

    @Test
    fun `unpaired yields NotPaired`() {
        val result = chatLaneFor(hasEndpoint = false)
        assertEquals(ChatLane.NotPaired, result)
    }
}

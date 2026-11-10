package app.lararium.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class VoiceInteractionMetadataTest {

    @Test
    fun voiceInteractionServiceMetadataDeclaresRequiredServices() {
        val candidates = listOf(
            File("src/main/res/xml/voice_interaction_service.xml"),
            File("app/src/main/res/xml/voice_interaction_service.xml")
        )
        val xmlFile = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("voice_interaction_service.xml not found")

        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = documentBuilder.parse(xmlFile)
        val root = document.documentElement

        assertEquals("voice-interaction-service", root.nodeName)

        assertTrue(
            "android:recognitionService attribute must be present",
            root.hasAttribute("android:recognitionService")
        )
        val recognitionService = root.getAttribute("android:recognitionService")
        assertTrue(
            "android:recognitionService must end with LarariumRecognitionService",
            recognitionService.endsWith("LarariumRecognitionService")
        )

        assertTrue(
            "android:sessionService attribute must be present",
            root.hasAttribute("android:sessionService")
        )
        val sessionService = root.getAttribute("android:sessionService")
        assertTrue(
            "android:sessionService must end with LarariumVoiceSessionService",
            sessionService.endsWith("LarariumVoiceSessionService")
        )
    }
}

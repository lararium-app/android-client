package app.lararium

import app.lararium.ui.DrawerLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrandingUnitTest {

    @Test
    fun appName_inStringsXml_isLararium() {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
            File("../app/src/main/res/values/strings.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("strings.xml must exist", file)
        val content = file!!.readText()

        val appNameMatch = Regex("""<string\s+name="app_name">([^<]+)</string>""").find(content)
        assertNotNull("app_name entry must be present in strings.xml", appNameMatch)
        assertEquals("Lararium", appNameMatch!!.groupValues[1])

        val taglineMatch = Regex("""<string\s+name="app_tagline">([^<]+)</string>""").find(content)
        assertNotNull("app_tagline entry must be present in strings.xml", taglineMatch)
        assertEquals("Your keys, your models, your home.", taglineMatch!!.groupValues[1])

        // Ensure legacy assistant_tile_label is removed in favor of single source of truth app_name
        val tileLabelMatch = Regex("""<string\s+name="assistant_tile_label">""").find(content)
        assertNull("assistant_tile_label should be removed in favor of single app_name source of truth", tileLabelMatch)
    }

    @Test
    fun defaultTopBarTitle_isLararium() {
        assertEquals("Lararium", DrawerLogic.formatTopBarTitle(null))
    }

    @Test
    fun noForbiddenProviderLiteralsInMainSource() {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main"),
            File("../app/src/main")
        )
        val mainDir = candidates.firstOrNull { it.isDirectory }
        assertNotNull("app/src/main must exist", mainDir)

        val forbidden = listOf(
            "hermes_api_key",
            "openrouter",
            "groq",
            "api.openai.com",
            "brave.com",
            "exa.ai",
            "langsearch"
        )

        val violations = mutableListOf<String>()

        mainDir!!.walkTopDown().filter { file ->
            file.isFile && (file.extension == "kt" || file.canonicalPath.contains("/res/"))
        }.forEach { file ->
            val text = file.readText().lowercase()
            for (pattern in forbidden) {
                if (text.contains(pattern.lowercase())) {
                    violations.add("${file.path} contains forbidden literal '$pattern'")
                }
            }
        }

        assertTrue(
            "Found forbidden provider literals in main source:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}

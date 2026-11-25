package app.lararium.hearth

import android.os.Build
import app.lararium.BuildConfig

/**
 * Builds and sanitizes the shared identifying User-Agent sent on all outbound
 * HTTP and streaming requests.
 *
 * Format:
 *   `lararium-android/<versionName> (Android <Build.VERSION.RELEASE>; <Build.BRAND> <Build.MODEL>)`
 */
object UserAgent {

    private val MULTI_SPACE = Regex(" +")

    /**
     * Sanitizes a single User-Agent component so it never contains characters
     * illegal in HTTP header values:
     * - strips CR, LF, NUL, other control characters, and non-ASCII bytes
     * - replaces commas with `+`
     * - collapses whitespace runs to a single space and trims
     */
    fun sanitizeComponent(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw
            .replace("\r", "")
            .replace("\n", "")
            .replace(',', '+')
            .replace('\t', ' ')
            .filter { it.code in 0x20..0x7E }
            .replace(MULTI_SPACE, " ")
            .trim()
    }

    /**
     * Formats the User-Agent header value from explicit fields.
     */
    fun build(
        versionName: String?,
        androidRelease: String?,
        brand: String?,
        model: String?,
    ): String {
        val ver = sanitizeComponent(versionName).replace(' ', '-').ifEmpty { "unknown" }
        val rel = sanitizeComponent(androidRelease).ifEmpty { "unknown" }
        val cleanBrand = sanitizeComponent(brand)
        val cleanModel = sanitizeComponent(model)
        val device = listOf(cleanBrand, cleanModel)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "unknown" }
        return "lararium-android/$ver (Android $rel; $device)"
    }

    /**
     * Builds the User-Agent for the current runtime device and app build.
     */
    fun forCurrentDevice(): String = build(
        versionName = BuildConfig.VERSION_NAME,
        androidRelease = Build.VERSION.RELEASE,
        brand = Build.BRAND,
        model = Build.MODEL,
    )
}

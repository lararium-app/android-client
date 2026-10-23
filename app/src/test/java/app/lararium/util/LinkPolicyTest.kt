package app.lararium.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H9: only http(s) may leave through a link click; everything else fails closed. */
class LinkPolicyTest {

    @Test
    fun httpAndHttpsAllowed() {
        assertTrue(LinkPolicy.isSafeExternal("https://example.com/path?a=b#f"))
        assertTrue(LinkPolicy.isSafeExternal("http://127.0.0.1:7717/"))
        assertTrue(LinkPolicy.isSafeExternal("  HTTPS://Example.COM  "))
    }

    @Test
    fun everyOtherSchemeRefused() {
        for (u in listOf(
            "javascript:alert(1)",
            "content://sms/inbox",
            "file:///data/data/app.lararium/databases/chat.db",
            "intent://scan/#Intent;scheme=x;end",
            "tel:5551234",
            "mailto:x@y.z",
            "android-app://androidx.settings",
            "jar:https://evil.com!/x",
            "data:text/html;base64,PHNjcmlwdD4=",
        )) {
            assertFalse("must refuse $u", LinkPolicy.isSafeExternal(u))
        }
    }

    @Test
    fun schemelessAndGarbageRefused() {
        assertFalse(LinkPolicy.isSafeExternal("example.com/x"))
        assertFalse(LinkPolicy.isSafeExternal("//example.com"))
        assertFalse(LinkPolicy.isSafeExternal(""))
        assertFalse(LinkPolicy.isSafeExternal("https:/example.com")) // one slash: no authority
    }
}

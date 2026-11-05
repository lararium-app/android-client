package app.lararium.hearth.watch

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [WatchStore] (R5, R7).
 * Verifies non-secret storage semantics for cursors, notified card sets, and rate-limited alert timestamps.
 */
class WatchStoreTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) changes[key] = values?.toMutableSet()
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) changes[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in changes) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun cursorStorageAndAdvancement() {
        val prefs = FakeSharedPreferences()
        val store = WatchStore(prefs)

        assertNull(store.getCursor("sess-1"))

        store.setCursor("sess-1", 5)
        assertEquals(5, store.getCursor("sess-1"))

        // markSeen advances monotonically
        store.markSeen("sess-1", 10)
        assertEquals(10, store.getCursor("sess-1"))

        // Earlier sequence number does not rewind cursor
        store.markSeen("sess-1", 8)
        assertEquals(10, store.getCursor("sess-1"))
    }

    @Test
    fun notifiedCardsStorage() {
        val prefs = FakeSharedPreferences()
        val store = WatchStore(prefs)

        assertTrue(store.getNotifiedCards().isEmpty())

        val cards = setOf("card-1", "card-2")
        store.replaceNotifiedCards(cards)
        assertEquals(cards, store.getNotifiedCards())

        val updated = setOf("card-2", "card-3")
        store.replaceNotifiedCards(updated)
        assertEquals(updated, store.getNotifiedCards())
    }

    @Test
    fun alertRateLimitingCooldown() {
        val prefs = FakeSharedPreferences()
        val store = WatchStore(prefs)

        // First alert triggers immediately
        assertTrue(store.shouldAlertToken("auth"))

        // Subsequent immediate call is throttled within 6 hours
        assertFalse(store.shouldAlertToken("auth"))

        // Different reason key is tracked separately
        assertTrue(store.shouldAlertToken("other_reason"))
    }
}

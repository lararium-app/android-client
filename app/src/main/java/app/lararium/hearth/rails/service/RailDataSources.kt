package app.lararium.hearth.rails.service

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import app.lararium.hearth.rails.Row
import org.json.JSONObject

interface RailDataSource {
    val authority: String
    suspend fun snapshot(): List<Row>
    fun registerObserver(observer: Runnable)
    fun unregisterObserver() {}
}

/**
 * Calendar rail data source querying CalendarContract.Instances for the next 30 days (F5).
 */
class CalendarDataSource(private val context: Context) : RailDataSource {
    override val authority: String = CalendarContract.AUTHORITY

    private var contentObserver: ContentObserver? = null

    override fun registerObserver(observer: Runnable) {
        unregisterObserver()
        val handler = Handler(Looper.getMainLooper())
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                observer.run()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (_: Exception) {}
    }

    override fun unregisterObserver() {
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        contentObserver = null
    }

    override suspend fun snapshot(): List<Row> {
        val now = System.currentTimeMillis()
        val end = now + 30L * 24 * 60 * 60 * 1000
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, end)
        val uri = builder.build()

        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION
        )

        val rows = mutableListOf<Row>()
        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(CalendarContract.Instances._ID)
                val eventIdIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIdx = c.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = c.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = c.getColumnIndex(CalendarContract.Instances.END)
                val allDayIdx = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val locIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val descIdx = c.getColumnIndex(CalendarContract.Instances.DESCRIPTION)

                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                    val eventId = if (eventIdIdx >= 0) c.getString(eventIdIdx) ?: "" else ""
                    val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
                    val begin = if (beginIdx >= 0) c.getLong(beginIdx) else 0L
                    val eventEnd = if (endIdx >= 0) c.getLong(endIdx) else 0L
                    val allDay = if (allDayIdx >= 0) c.getInt(allDayIdx) == 1 else false
                    val location = if (locIdx >= 0) c.getString(locIdx) ?: "" else ""
                    val description = if (descIdx >= 0) c.getString(descIdx) ?: "" else ""

                    val data = JSONObject().apply {
                        put("id", id)
                        put("event_id", eventId)
                        put("title", title)
                        put("begin", begin)
                        put("end", eventEnd)
                        put("all_day", allDay)
                        put("location", location)
                        put("description", description)
                    }
                    rows.add(Row(id = id, data = data))
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return rows
    }
}

/**
 * Contacts rail data source querying ContactsContract.CommonDataKinds.Phone (F5).
 */
class ContactsDataSource(private val context: Context) : RailDataSource {
    override val authority: String = ContactsContract.AUTHORITY

    private var contentObserver: ContentObserver? = null

    override fun registerObserver(observer: Runnable) {
        unregisterObserver()
        val handler = Handler(Looper.getMainLooper())
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                observer.run()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (_: Exception) {}
    }

    override fun unregisterObserver() {
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        contentObserver = null
    }

    override suspend fun snapshot(): List<Row> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        val rows = mutableListOf<Row>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
                val contactIdIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                    val contactId = if (contactIdIdx >= 0) c.getString(contactIdIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                    val number = if (numberIdx >= 0) c.getString(numberIdx) ?: "" else ""
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0

                    val data = JSONObject().apply {
                        put("id", id)
                        put("contact_id", contactId)
                        put("name", name)
                        put("number", number)
                        put("type", type)
                    }
                    rows.add(Row(id = id, data = data))
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return rows
    }
}

/**
 * Call log rail data source querying CallLog.Calls (last 200) (F5).
 */
class CallLogDataSource(private val context: Context) : RailDataSource {
    override val authority: String = CallLog.AUTHORITY

    private var contentObserver: ContentObserver? = null

    override fun registerObserver(observer: Runnable) {
        unregisterObserver()
        val handler = Handler(Looper.getMainLooper())
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                observer.run()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (_: Exception) {}
    }

    override fun unregisterObserver() {
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        contentObserver = null
    }

    override suspend fun snapshot(): List<Row> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.CACHED_NAME
        )

        val rows = mutableListOf<Row>()
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 200"
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)

                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                    val number = if (numberIdx >= 0) c.getString(numberIdx) ?: "" else ""
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val duration = if (durationIdx >= 0) c.getLong(durationIdx) else 0L
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""

                    val data = JSONObject().apply {
                        put("id", id)
                        put("number", number)
                        put("date", date)
                        put("duration", duration)
                        put("type", type)
                        put("name", name)
                    }
                    rows.add(Row(id = id, data = data))
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return rows
    }
}

/**
 * Notifications rail data source backed by NotificationsListener (F5).
 */
class NotificationsDataSource(private val context: Context) : RailDataSource {
    override val authority: String = "notifications"

    override fun registerObserver(observer: Runnable) {
        NotificationsListener.observer = observer
    }

    override fun unregisterObserver() {
        NotificationsListener.observer = null
    }

    override suspend fun snapshot(): List<Row> {
        return NotificationsListener.ring.snapshot()
    }

    fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat != null && flat.contains(context.packageName)
    }
}

/**
 * Health rail data source placeholder (F5).
 * Health Connect is not a dependency per spec; returns empty list in degraded state.
 */
class HealthDataSource(private val context: Context) : RailDataSource {
    override val authority: String = "health"

    override fun registerObserver(observer: Runnable) {}

    override fun unregisterObserver() {}

    override suspend fun snapshot(): List<Row> = emptyList()
}

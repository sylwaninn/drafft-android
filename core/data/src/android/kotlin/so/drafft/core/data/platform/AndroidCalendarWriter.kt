package so.drafft.core.data.platform

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CalendarWriter] on `CalendarContract`: the events go to the person's primary writable calendar.
 * Each event carries its session's marker URL twice: in `CUSTOM_APP_URI` (the provider's link back to
 * the app) and on the last line of its description, so the event is still recognised on a calendar
 * account whose sync drops the custom columns.
 *
 * Its [permission] is re-read each time the app comes back to the front ([AndroidForeground]).
 */
class AndroidCalendarWriter(
    private val context: Context,
    private val defaults: KeyValueStore,
) : CalendarWriter {
    override var permission: PermissionStatus by mutableStateOf(read())
        private set

    init {
        AndroidForeground.observe { permission = read() }
    }

    private val granted: Boolean
        get() = PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun read(): PermissionStatus = when {
        granted -> PermissionStatus.ALLOWED
        defaults.getBoolean(DENIED_KEY) == true -> PermissionStatus.DENIED
        else -> PermissionStatus.NOT_ASKED
    }

    override suspend fun requestPermission(): Boolean {
        when (PermissionPrompter.request(PERMISSIONS)) {
            PermissionPrompter.Result.GRANTED -> defaults.remove(DENIED_KEY)
            PermissionPrompter.Result.DENIED_FOR_GOOD -> defaults.putBoolean(DENIED_KEY, true)
            PermissionPrompter.Result.DENIED -> Unit
        }
        permission = read()
        return permission == PermissionStatus.ALLOWED
    }

    override fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun insert(
        title: String,
        start: Instant,
        end: Instant,
        notes: String,
        url: String,
        alarmMinutesBefore: Int,
    ): String? = io {
        val calendar = primaryCalendar() ?: return@io null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.DESCRIPTION, "$notes\n\n$url")
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(CalendarContract.Events.CUSTOM_APP_URI, url)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return@io null
        val id = ContentUris.parseId(uri)
        context.contentResolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, alarmMinutesBefore)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
        id.toString()
    }

    override suspend fun event(id: String): CalendarWriter.Event? = io {
        val eventID = id.toLongOrNull() ?: return@io null
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CUSTOM_APP_URI,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DELETED,
        )
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventID), projection, null, null, null,
        )?.use { c ->
            if (!c.moveToFirst() || c.getInt(5) != 0) return@io null
            val start = c.getLong(1)
            val end = if (c.isNull(2)) start else c.getLong(2)
            val url = c.getString(3) ?: c.getString(4)?.let { MARKER.find(it)?.value }
            CalendarWriter.Event(id, c.getString(0).orEmpty(), Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), url)
        }
    }

    override suspend fun update(id: String, title: String, start: Instant, end: Instant): Boolean = ioOrFalse {
        val eventID = id.toLongOrNull() ?: return@ioOrFalse false
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toEpochMilli())
        }
        context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventID), values, null, null) > 0
    }

    override suspend fun remove(id: String): Boolean = ioOrFalse {
        val eventID = id.toLongOrNull() ?: return@ioOrFalse false
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventID), null, null) > 0
    }

    override fun open(id: String) {
        val eventID = id.toLongOrNull() ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventID))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** The primary calendar the person can write to, else the first writable visible one. */
    private fun primaryCalendar(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!c.isNull(1) && c.getInt(1) == 1) return id
                if (first == null) first = id
            }
            return first
        }
        return null
    }

    /** Provider calls off the main thread; without access (revoked meanwhile) nothing happens. */
    private suspend fun <T> io(block: () -> T?): T? = withContext(Dispatchers.IO) {
        if (!granted) return@withContext null
        try {
            block()
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun ioOrFalse(block: () -> Boolean): Boolean = io(block) ?: false

    private companion object {
        val PERMISSIONS = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        const val DENIED_KEY = "permission.calendar.deniedForGood"
        val MARKER = Regex("drafft://session/[0-9a-f-]{36}")
    }
}

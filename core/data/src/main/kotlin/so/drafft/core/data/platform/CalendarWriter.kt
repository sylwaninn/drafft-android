package so.drafft.core.data.platform

import java.time.Instant

/**
 * The phone's calendar, for the events drafft adds for a confirmed session.
 * Android: `CalendarContract` on the primary writable calendar (`AndroidCalendarWriter`), with the
 * READ_CALENDAR and WRITE_CALENDAR runtime permissions ([SystemPermission]).
 *
 * An event carries its session's marker URL (`drafft://session/<id>`) so drafft only ever changes the
 * events it added.
 */
interface CalendarWriter : SystemPermission {
    data class Event(
        val id: String,
        val title: String,
        val start: Instant,
        val end: Instant,
        /** The marker URL drafft wrote, if the event has one. */
        val url: String?,
    )

    /** Adds an event to the person's default calendar. Its id, or null when it couldn't be added. */
    suspend fun insert(
        title: String,
        start: Instant,
        end: Instant,
        notes: String,
        url: String,
        alarmMinutesBefore: Int,
    ): String?

    /** The event with this id, or null (deleted, or no access). */
    suspend fun event(id: String): Event?

    /** Saves a new title and time. Whether it worked. */
    suspend fun update(id: String, title: String, start: Instant, end: Instant): Boolean

    /** Whether it's gone now. */
    suspend fun remove(id: String): Boolean

    /** Shows the event in the calendar app (to review or edit it). */
    fun open(id: String)
}

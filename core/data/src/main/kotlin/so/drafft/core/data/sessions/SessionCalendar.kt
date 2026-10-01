package so.drafft.core.data.sessions

import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.CalendarWriter
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.platform.PermissionStatus
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.model.Sport

/**
 * Ports Drafft/Services/SessionCalendar.swift.
 *
 * The calendar events added from "Add to calendar" follow their session: moved when its time or title
 * changes, removed when it's cancelled, declined, replaced by other times, or gone. Each session keeps
 * the link to its event (saved on the phone), and only events drafft added are ever changed:
 *
 * - an event is found by the identifier saved when it was added, and must still carry the session's
 *   `drafft://session/<id>` URL;
 * - a field the person edited in their calendar (the title, the time) is left as they set it: only a
 *   value still equal to the one drafft wrote is updated;
 * - an event the person deleted, or that no longer carries the URL, is let go, never recreated.
 *
 * Changes come from the person's own changes once the server took them ([SessionStore]), from the
 * Realtime `session` event (`UserChannel`), and from a re-read on foreground and on each reconnection.
 * Following needs calendar access, asked when adding the event; refused, a banner says so and opens
 * Settings (`CalendarAccessNotice`, core:ui / app).
 *
 * Android has no system "New Event" sheet that hands back the saved event, so [add] writes the event
 * itself (same title, time, 90 minutes, note, reminder an hour before) and [CalendarWriter.open] can
 * show it afterwards for the person to review.
 */
class SessionCalendar(
    private val backend: Backend,
    val writer: CalendarWriter,
    private val defaults: KeyValueStore,
) {
    /** What drafft wrote in an event, to know what the person has edited since. */
    @Serializable
    data class Link(
        val eventID: String,
        val externalID: String? = null,
        /** The conversation (sample ones are never looked up on the server). */
        val chatID: String,
        /** The other person's name, in the title. */
        val partner: String,
        val title: String,
        /** Epoch milliseconds. */
        val start: Long,
    )

    /** Where a session stands, for its event. */
    sealed interface State {
        data class Scheduled(val start: Instant, val title: String) : State
        data object Gone : State
    }

    /** What the app may do with the calendar when the person adds a session. */
    enum class Access {
        /** Full access: the event is added in the app and follows the session. */
        FULL,

        /** Add-only access (the iPhone's write-only): not a case Android has, kept for parity. */
        ADD_ONLY,

        /** Refused: nothing is added, a banner explains it (`CalendarAccessNotice`). */
        REFUSED,
    }

    var links: Map<UUID, Link> = load()
        private set

    private val canFollow: Boolean get() = writer.permission == PermissionStatus.ALLOWED

    /** Asked before the event is added: access is requested the first time. */
    suspend fun requestAccess(): Access {
        if (writer.permission == PermissionStatus.NOT_ASKED) writer.requestPermission()
        return if (writer.permission == PermissionStatus.ALLOWED) Access.FULL else Access.REFUSED
    }

    /**
     * Adds a confirmed session to the calendar ("Add to calendar") and remembers the link, with what
     * drafft wrote in it. The event's id, or null when it couldn't be added (no access).
     */
    suspend fun add(session: SessionProposal, chatID: String, partner: String): String? {
        val title = title(session.displayTitle, partner)
        val start = session.date
        val id = writer.insert(
            title = title,
            start = start,
            end = start.plusSeconds(90 * 60),
            notes = L("%s session, confirmed on drafft.", session.sport.displayName),
            url = marker(session.id),
            alarmMinutesBefore = 60,
        ) ?: return null
        added(id, session = session.id, chatID = chatID, partner = partner, title = title, start = start)
        return id
    }

    /** The person saved the event: remembered, with what drafft wrote in it. */
    fun added(eventID: String, session: UUID, chatID: String, partner: String, title: String, start: Instant) {
        if (eventID.isEmpty()) return
        links = links + (session to Link(eventID, null, chatID, partner, title, start.toEpochMilli()))
        save()
    }

    // Following the session

    /**
     * A session changed: the Realtime `session` event, or the person's own change once the server took
     * it ([SessionStore]). A final status removes the event; any other change is read from the server,
     * which holds the time and title.
     */
    suspend fun sessionChanged(id: UUID, status: String?) {
        if (links[id] == null) return
        when (status) {
            "cancelled", "declined", "countered" -> apply(State.Gone, id)
            else -> refresh(only = setOf(id))
        }
    }

    /**
     * Every followed session read again from the server: on foreground and on reconnect. Only a
     * successful read changes anything; a session the server no longer returns is gone.
     */
    suspend fun refresh(only: Set<UUID>? = null) {
        val followed = links.filterKeys { only?.contains(it) ?: true }
        if (!canFollow || followed.isEmpty()) return
        val list = followed.keys.joinToString(",") { it.toString().lowercase() }
        val rows = try {
            val data = backend.select("sessions?id=in.($list)&select=id,status,chosen_at,options,title,sport_id")
            json.parseToJsonElement(data.decodeToString()).jsonArray.map { Row.decode(it.jsonObject) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val byID = rows.groupBy { it.id }.mapValues { it.value.first() }
        for ((id, link) in followed) {
            val row = byID[id]
            if (row == null || (row.status != "accepted" && row.status != "pending")) {
                apply(State.Gone, id)
                continue
            }
            if (row.status != "accepted") continue
            val start = row.chosenAt ?: row.options.firstOrNull() ?: continue
            val name = if (row.title.isEmpty()) Sport.fromId(row.sportID)?.let { L("%s session", it.displayName) } else row.title
            name ?: continue
            apply(State.Scheduled(start, title(name, link.partner)), id)
        }
    }

    /** Nothing follows another account's sessions on this phone (the events themselves stay). */
    fun forgetAll() {
        links = emptyMap()
        save()
    }

    // Events

    private suspend fun apply(state: State, id: UUID) {
        val link = links[id] ?: return
        // Without access nothing can be read or changed: kept for when it's given.
        if (!canFollow) return
        val event = event(id, link)
        if (event == null) {
            // Deleted by the person, or no longer drafft's: let go.
            links = links - id
            save()
            return
        }
        when (state) {
            State.Gone -> {
                // Not removed (the store failed): kept, the next refresh tries again.
                if (!writer.remove(event.id)) return
                links = links - id
            }
            is State.Scheduled -> {
                val linkStart = Instant.ofEpochMilli(link.start)
                var start = event.start
                var end = event.end
                var title = event.title
                var changed = false
                if (event.start == linkStart && state.start != linkStart) {
                    val length = java.time.Duration.between(event.start, event.end)
                    start = state.start
                    end = state.start.plus(length)
                    changed = true
                }
                if (event.title == link.title && state.title != link.title && state.title.isNotEmpty()) {
                    title = state.title
                    changed = true
                }
                if (changed && !writer.update(event.id, title, start, end)) return
                links = links + (id to link.copy(start = state.start.toEpochMilli(), title = state.title))
            }
        }
        save()
    }

    /** The linked event, only if it's still the one drafft added for this session. */
    private suspend fun event(id: UUID, link: Link): CalendarWriter.Event? {
        val found = writer.event(link.eventID) ?: return null
        return found.takeIf { it.url == marker(id) }
    }

    private fun load(): Map<UUID, Link> = runCatching {
        val text = defaults.getString(KEY) ?: return@runCatching emptyMap()
        json.decodeFromString(serializer, text).mapKeys { UUID.fromString(it.key) }
    }.getOrDefault(emptyMap())

    private fun save() {
        defaults.putString(KEY, json.encodeToString(serializer, links.mapKeys { it.key.toString() }))
    }

    // Server rows

    private data class Row(
        val id: UUID,
        val status: String,
        val chosenAt: Instant?,
        val options: List<Instant>,
        val title: String,
        val sportID: String,
    ) {
        companion object {
            fun decode(o: JsonObject): Row {
                fun string(key: String) = (o[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
                return Row(
                    id = UUID.fromString(string("id")),
                    status = string("status") ?: throw IOException("status missing"),
                    chosenAt = string("chosen_at")?.let(ServerDate::parse),
                    options = (o["options"] as JsonArray).map { ServerDate.parse(it.jsonPrimitive.content) },
                    title = string("title") ?: throw IOException("title missing"),
                    sportID = string("sport_id") ?: throw IOException("sport_id missing"),
                )
            }
        }
    }

    companion object {
        private const val KEY = "sessionCalendarLinks"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), Link.serializer())

        fun marker(session: UUID): String = "drafft://session/${session.toString().lowercase()}"

        /** The event's title for a session: "Sunrise run with Maya", like when it was added. */
        fun title(session: String, partner: String): String = L("%s with %s", session, partner)
    }
}

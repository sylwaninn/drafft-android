package so.drafft.core.data.sessions

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A `public.sessions` row as the server sends it (`upcoming_sessions`, the session RPCs, a read by id),
 * with the other person when `upcoming_sessions` adds them (`with`).
 */
data class SessionRecord(
    val id: UUID,
    val matchID: UUID,
    val proposerID: UUID,
    val sportID: String,
    val options: List<Instant>,
    val chosenAt: Instant? = null,
    val title: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    val discovery: String? = null,
    val status: Status = Status.PENDING,
    val replacesID: UUID? = null,
    /** The row's version: a reply older than what's already known never overwrites it. */
    val updatedAt: Instant,
    val partner: Partner? = null,
) {
    enum class Status(val rawValue: String) {
        PENDING("pending"), ACCEPTED("accepted"), DECLINED("declined"), COUNTERED("countered"), CANCELLED("cancelled");

        companion object {
            fun from(raw: String): Status =
                entries.firstOrNull { it.rawValue == raw } ?: throw IllegalArgumentException("session status $raw")
        }
    }

    /** The other person, as the Sessions tab shows them. */
    data class Partner(
        val id: UUID,
        val name: String,
        /** Signed link to their first photo (or its poster), when they have one. */
        val photo: String?,
    )

    /** The agreed time, or the first option while it's still being decided. */
    val date: Instant get() = chosenAt ?: options.firstOrNull() ?: Instant.MIN

    /**
     * Still ahead, as the Sessions tab counts it (`upcoming_sessions`): pending or accepted, and its time
     * (the chosen one, or the last option) less than 2 hours ago.
     */
    fun isUpcoming(at: Instant): Boolean {
        if (status != Status.PENDING && status != Status.ACCEPTED) return false
        val last = chosenAt ?: options.lastOrNull() ?: Instant.MIN
        return last > at.minusSeconds(2 * 3600)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** One row. Throws when a required field is missing or malformed, like `Decodable`. */
        fun decode(element: JsonElement): SessionRecord {
            val o = element.jsonObject
            fun string(key: String): String? = (o[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
            fun required(key: String): String = string(key) ?: throw IllegalArgumentException("session row: $key missing")
            val with = (o["with"] as? JsonObject)?.let { w ->
                val photo = w["photo"] as? JsonObject
                fun photoField(key: String) = (photo?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
                Partner(
                    id = UUID.fromString(w["id"]!!.jsonPrimitive.content),
                    name = (w["name"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull ?: "",
                    photo = photoField("url") ?: photoField("posterUrl") ?: photoField("poster_url"),
                )
            }
            return SessionRecord(
                id = UUID.fromString(required("id")),
                matchID = UUID.fromString(required("match_id")),
                proposerID = UUID.fromString(required("proposer_id")),
                sportID = required("sport_id"),
                options = o["options"]!!.jsonArray.map { ServerDate.parse(it.jsonPrimitive.content) },
                chosenAt = string("chosen_at")?.let(ServerDate::parse),
                title = string("title") ?: "",
                note = string("note") ?: "",
                tags = (o["tags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                discovery = string("discovery"),
                status = Status.from(required("status")),
                replacesID = string("replaces_id")?.let(UUID::fromString),
                updatedAt = ServerDate.parse(required("updated_at")),
                partner = with,
            )
        }

        /** A JSON array of rows (`upcoming_sessions`, a read by id). */
        fun decodeList(text: String): List<SessionRecord> = json.parseToJsonElement(text).jsonArray.map(::decode)

        /** One row (the session RPCs). */
        fun decode(text: String): SessionRecord = decode(json.parseToJsonElement(text))
    }
}

/**
 * Postgres timestamps in JSON: ISO 8601 with an offset, with or without fractional seconds (cut to
 * milliseconds).
 */
object ServerDate {
    class Invalid(val text: String) : IllegalArgumentException("Invalid server date: $text")

    fun parse(text: String): Instant {
        val parsed = runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
            .recoverCatching { OffsetDateTime.parse(text.replace(Regex("([+-]\\d{2})$"), "$1:00")).toInstant() }
            .getOrNull() ?: throw Invalid(text)
        return parsed.truncatedTo(ChronoUnit.MILLIS)
    }

    /** What the RPCs are sent: UTC, to the second (the options come back exactly as sent). */
    fun string(date: Instant): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(date.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC))
            .replace("+00:00", "Z")
}

/**
 * The sessions this phone knows: the server's rows, with the person's own changes still on their way
 * laid over them. The usual optimistic-update pattern (a pending-mutation queue over confirmed state):
 *
 * - a change shows at once ([begin]), whatever the network;
 * - once the server answers, the change is dropped and its returned rows merged ([settle]);
 * - if it refuses or can't be reached, the change is dropped alone ([fail]): the screen goes back to the
 *   server's state as it is now, including anything that arrived meanwhile, never to a stale snapshot;
 * - rows are merged by version (`updated_at`): an old reply never overwrites a newer Realtime read.
 *
 * An immutable value: [SessionStore] edits a [copy] and publishes it, so screens observe it.
 */
class SessionLedger private constructor(
    private val confirmedRows: LinkedHashMap<UUID, SessionRecord>,
    private val pendingChanges: ArrayList<Pending>,
) {
    constructor() : this(LinkedHashMap(), ArrayList())

    sealed interface Change {
        data class Propose(val row: SessionRecord) : Change
        data class Respond(val id: UUID, val accept: Boolean, val pick: Instant?) : Change
        data class Counter(val id: UUID, val row: SessionRecord) : Change
        data class Cancel(val id: UUID) : Change
    }

    data class Pending(val token: UUID, val change: Change)

    val confirmed: Map<UUID, SessionRecord> get() = confirmedRows
    val pending: List<Pending> get() = pendingChanges

    fun copy(): SessionLedger = SessionLedger(LinkedHashMap(confirmedRows), ArrayList(pendingChanges))

    /** What the screens show: the server's rows with the pending changes applied, in order. */
    val visible: Map<UUID, SessionRecord>
        get() {
            val rows = LinkedHashMap(confirmedRows)
            for (p in pendingChanges) {
                when (val change = p.change) {
                    is Change.Propose -> rows[change.row.id] = change.row
                    is Change.Respond -> {
                        val row = rows[change.id] ?: continue
                        if (row.status != SessionRecord.Status.PENDING) continue
                        rows[change.id] = row.copy(
                            status = if (change.accept) SessionRecord.Status.ACCEPTED else SessionRecord.Status.DECLINED,
                            chosenAt = if (change.accept) change.pick ?: row.options.firstOrNull() else null,
                        )
                    }
                    is Change.Counter -> {
                        val old = rows[change.id]
                        if (old != null && old.status == SessionRecord.Status.PENDING) {
                            rows[change.id] = old.copy(status = SessionRecord.Status.COUNTERED)
                        }
                        rows[change.row.id] = change.row
                    }
                    is Change.Cancel -> {
                        val row = rows[change.id] ?: continue
                        if (row.status != SessionRecord.Status.PENDING && row.status != SessionRecord.Status.ACCEPTED) continue
                        rows[change.id] = row.copy(status = SessionRecord.Status.CANCELLED)
                    }
                }
            }
            return rows
        }

    /** Pending and accepted sessions still ahead, soonest first (the Sessions tab). */
    fun upcoming(at: Instant): List<SessionRecord> =
        visible.values.filter { it.isUpcoming(at) }
            .sortedWith(compareBy<SessionRecord> { it.date }.thenBy { it.id.toString() })

    /** Whether a change on this session is still on its way (its buttons wait). */
    fun isBusy(id: UUID): Boolean = pendingChanges.any { p ->
        when (val c = p.change) {
            is Change.Propose -> c.row.id == id
            is Change.Respond -> c.id == id
            is Change.Cancel -> c.id == id
            is Change.Counter -> c.id == id || c.row.id == id
        }
    }

    /** Server rows, merged by version. The other person is kept when a row comes without them. */
    fun merge(rows: List<SessionRecord>) {
        for (incoming in rows) {
            var row = incoming
            val known = confirmedRows[row.id]
            if (known != null) {
                if (row.updatedAt < known.updatedAt) continue
                row = row.copy(partner = row.partner ?: known.partner)
            }
            confirmedRows[row.id] = row
        }
    }

    /** A session the server no longer returns (its match or an account is gone). */
    fun remove(ids: Set<UUID>) {
        for (id in ids) confirmedRows.remove(id)
    }

    /** Starts a change: shown at once. Returns its token for [settle] or [fail]. */
    fun begin(change: Change, token: UUID = UUID.randomUUID()): UUID {
        pendingChanges.add(Pending(token, change))
        return token
    }

    /** The server accepted the change: it's replaced by the rows it returned. */
    fun settle(token: UUID, with: List<SessionRecord>) {
        pendingChanges.removeAll { it.token == token }
        merge(with)
    }

    /** The server refused it or couldn't be reached: undone, back to the server's state. */
    fun fail(token: UUID) {
        pendingChanges.removeAll { it.token == token }
    }

    /** Signed out: nothing of this account stays. */
    fun reset() {
        confirmedRows.clear()
        pendingChanges.clear()
    }

    override fun equals(other: Any?): Boolean =
        other is SessionLedger && other.confirmedRows == confirmedRows && other.pendingChanges == pendingChanges

    override fun hashCode(): Int = confirmedRows.hashCode() * 31 + pendingChanges.hashCode()
}

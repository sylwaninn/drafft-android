package so.drafft.core.data.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import so.drafft.core.data.platform.KeyValueStore

/**
 * What needs the person among their sessions, and what they have already looked at, kept per account on
 * the phone ([KeyValueStore]):
 *
 * - when each session was last opened as it stands (`sessions.seen.<account>`), so a change the other
 *   person made since (confirmed, declined, cancelled) counts as news until the page is opened again;
 * - a baseline (`sessions.seen.<account>.baseline`): nothing that happened before this phone first
 *   knew about "seen" counts as news, so an update never lights up every old session at once;
 * - the invites whose "Meet safely" was already shown when they arrived (`sessions.safety.<account>`),
 *   shown once each.
 *
 * [seenAt] is snapshot state: the Sessions tab's badge and the rows' dots redraw when it changes.
 */
class SessionAttention(private val defaults: KeyValueStore) {
    var seenAt: Map<UUID, Instant> by mutableStateOf(emptyMap())
        private set

    private var account: UUID? = null
    private var baseline: Instant? = null
    private var safetyShown: Set<UUID> = emptySet()

    /** Reads the account's state once (again only for another account). */
    fun load(me: UUID, now: Instant = Instant.now()) {
        if (account == me) return
        account = me
        seenAt = decodeSeen(defaults.getString(seenKey(me)))
        safetyShown = decodeIDs(defaults.getString(safetyKey(me)))
        val stored = defaults.getString(baselineKey(me))?.toLongOrNull()
        baseline = if (stored != null) {
            Instant.ofEpochMilli(stored)
        } else {
            defaults.putString(baselineKey(me), now.toEpochMilli().toString())
            now
        }
    }

    /**
     * An answer is expected from the person ([mine] false, still pending and ahead), or the other person
     * changed something not looked at yet (confirmed, declined, cancelled). Countered ones are told by
     * the new invite that replaces them.
     */
    fun needsAttention(row: SessionRecord, mine: Boolean, now: Instant = Instant.now()): Boolean {
        if (account == null) return false
        return when (row.status) {
            SessionRecord.Status.PENDING -> !mine && row.isUpcoming(now)
            SessionRecord.Status.COUNTERED -> false
            SessionRecord.Status.ACCEPTED, SessionRecord.Status.DECLINED, SessionRecord.Status.CANCELLED -> {
                val since = baseline ?: return false
                row.updatedAt > since && row.updatedAt > (seenAt[row.id] ?: Instant.MIN)
            }
        }
    }

    /** Opened as it stands: no longer news. */
    fun markSeen(row: SessionRecord, now: Instant = Instant.now()) {
        val me = account ?: return
        seenAt = seenAt + (row.id to maxOf(now, row.updatedAt))
        defaults.putString(seenKey(me), encodeSeen(seenAt))
    }

    /** Whether "Meet safely" was already shown for this invite. */
    fun hasShownSafety(id: UUID): Boolean = id in safetyShown

    fun markSafetyShown(id: UUID) {
        val me = account ?: return
        safetyShown = safetyShown + id
        defaults.putString(safetyKey(me), JsonArray(safetyShown.map { JsonPrimitive(it.toString()) }).toString())
    }

    /** Signed out: the next account reads its own. What was stored stays with the account it belongs to. */
    fun reset() {
        account = null
        baseline = null
        seenAt = emptyMap()
        safetyShown = emptySet()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun seenKey(me: UUID) = "sessions.seen.$me"
        fun baselineKey(me: UUID) = "sessions.seen.$me.baseline"
        fun safetyKey(me: UUID) = "sessions.safety.$me"

        fun encodeSeen(seen: Map<UUID, Instant>): String =
            JsonObject(seen.entries.associate { (id, at) -> id.toString() to JsonPrimitive(at.toEpochMilli()) }).toString()

        /** A damaged value reads as nothing seen: at worst a session shows as news once more. */
        fun decodeSeen(text: String?): Map<UUID, Instant> {
            if (text == null) return emptyMap()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
            return obj.entries.mapNotNull { (key, value) ->
                val id = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
                val millis = (value as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                id to Instant.ofEpochMilli(millis)
            }.toMap()
        }

        fun decodeIDs(text: String?): Set<UUID> {
            if (text == null) return emptySet()
            val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptySet()
            return array.mapNotNull { runCatching { UUID.fromString(it.jsonPrimitive.content) }.getOrNull() }.toSet()
        }
    }
}

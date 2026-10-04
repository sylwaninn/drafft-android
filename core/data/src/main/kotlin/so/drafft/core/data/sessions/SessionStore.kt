package so.drafft.core.data.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.SessionProposal
import so.drafft.core.model.Sport

/**
 * Sessions, on the server (`public.sessions`), the one source for the Sessions tab and the chat cards.
 *
 * - Reads: `upcoming_sessions` for the tab, a read by id for a card the tab doesn't list (declined,
 *   countered, cancelled, past). Again on each Realtime `session` event (`UserChannel`), on each
 *   (re)connection and on foreground, so a change made while away is never missed.
 * - Writes: `propose_session`, `counter_session`, `respond_session`, `cancel_session`, optimistic
 *   ([SessionLedger]): the card changes at once, is put back if the server refuses, and the refusal is
 *   said in the app's words ([SessionFailureNotice]).
 * - Around it: the calendar event added for a session follows it ([SessionCalendar]); reminders are the
 *   server's pushes (`notify_session_*`), so a cancelled session never leaves one behind on a phone.
 * - What needs the person (the Sessions tab's badge, a row's dot) and what they have looked at, kept per
 *   account on the phone ([SessionAttention]).
 *
 * A voluntary pause doesn't stop any of this (only discovery pauses); a hold does, server side
 * (`moderated`). Every call runs on the main thread (the scope's dispatcher), like `@MainActor`.
 */
class SessionStore(
    private val backend: Backend,
    private val calendar: SessionCalendar,
    val failureNotice: SessionFailureNotice,
    defaults: KeyValueStore,
    private val scope: CoroutineScope,
) {
    var ledger: SessionLedger by mutableStateOf(SessionLedger())
        private set

    /** A proposal's id on this phone to the server's, once it's saved: a card shown before the server
     *  answered keeps following it. */
    private val aliases = mutableMapOf<UUID, UUID>()

    /** Ids already asked for, so a card on screen doesn't read its row on every redraw. */
    private val requested = mutableSetOf<UUID>()

    /** The signed-in person (`proposer_id` tells whose invite a card is). */
    var me: UUID? = null
        private set

    private val attention = SessionAttention(defaults)

    /** Edits a copy of the ledger and publishes it, so the screens observing [ledger] redraw. */
    private inline fun <T> edit(block: SessionLedger.() -> T): T {
        val next = ledger.copy()
        val result = next.block()
        ledger = next
        return result
    }

    // Reading

    /** A session as it stands now (server row with the person's changes on their way). */
    fun record(id: UUID): SessionRecord? {
        val rows = ledger.visible
        return rows[id] ?: aliases[id]?.let { rows[it] }
    }

    /** Pending and accepted sessions still ahead, soonest first. */
    val upcoming: List<SessionRecord> get() = ledger.upcoming(Instant.now())

    fun isBusy(id: UUID): Boolean = ledger.isBusy(aliases[id] ?: id)

    fun isMine(row: SessionRecord): Boolean = row.proposerID == me

    /**
     * The pending sessions of a chat still ahead, the one waiting on the person first: what the chat's
     * banner shows. A confirmed session is settled and has no banner.
     */
    fun pending(inMatch: UUID): List<SessionRecord> {
        val now = Instant.now()
        return ledger.visible.values
            .filter { it.matchID == inMatch && it.status == SessionRecord.Status.PENDING && it.isUpcoming(now) }
            .sortedWith(compareBy<SessionRecord> { isMine(it) }.thenBy { it.date })
    }

    // What needs the person

    /**
     * An answer is expected from the person, or the other person changed something not looked at yet
     * (confirmed, declined, cancelled).
     */
    fun needsAttention(row: SessionRecord): Boolean = me != null && attention.needsAttention(row, mine = isMine(row))

    /** The count on the Sessions tab. */
    val attentionCount: Int get() = ledger.visible.values.count(::needsAttention)

    /** Opened as it stands: no longer news. */
    fun markSeen(id: UUID) {
        if (me == null) return
        record(id)?.let { attention.markSeen(it) }
    }

    /** Whether "Meet safely" was already shown for this invite. */
    fun hasShownSafety(id: UUID): Boolean = attention.hasShownSafety(aliases[id] ?: id)

    fun markSafetyShown(id: UUID) {
        if (me == null) return
        attention.markSafetyShown(aliases[id] ?: id)
    }

    /**
     * Everything read again: the upcoming list, then the other sessions this phone shows, by id.
     * Only a successful read changes anything.
     */
    suspend fun refresh() {
        val me = backend.userID ?: return
        this.me = me
        attention.load(me)
        val rows = attempt { SessionRecord.decodeList(backend.rpc("upcoming_sessions", JsonObject(emptyMap())).decodeToString()) }
            ?: return
        val returned = rows.map { it.id }.toSet()
        edit { merge(rows) }
        // Listed before and not now: closed since (cancelled, declined, passed) or gone with its match.
        val others = ledger.confirmed.keys.filterNot { it in returned }.toSet()
        load(others, dropMissing = true)
        calendar.refresh()
    }

    /**
     * Rows read by id (a chat card, a Realtime event). With [dropMissing], an id the server no longer
     * returns is forgotten (its match and chat are gone).
     */
    suspend fun load(ids: Set<UUID>, dropMissing: Boolean = false) {
        if (ids.isEmpty()) return
        if (me == null) me = backend.userID
        val list = ids.map { it.toString().lowercase() }.sorted().joinToString(",")
        val rows = attempt { SessionRecord.decodeList(backend.select("sessions?id=in.($list)&select=*").decodeToString()) }
            ?: return
        edit {
            merge(rows)
            if (dropMissing) remove(ids - rows.map { it.id }.toSet())
        }
    }

    /** A card on screen whose row isn't known yet: read once. */
    fun need(id: UUID) {
        if (record(id) != null || id in requested) return
        requested += id
        scope.launch { load(setOf(id)) }
    }

    /**
     * The Realtime `session` event: the row (and the one it replaced) read again, then its calendar
     * event follows.
     */
    suspend fun changed(id: UUID, replaces: UUID?, status: String?) {
        load(setOfNotNull(id, replaces))
        calendar.sessionChanged(id, status)
    }

    // Writing

    /** A new invite in a match ([matchID] is the chat's id). Shown at once as the person's own. */
    suspend fun propose(proposal: SessionProposal, matchID: String): Boolean {
        val match = runCatching { UUID.fromString(matchID) }.getOrNull()
        val me = backend.userID
        if (match == null || me == null) {
            failureNotice.show(null)
            return false
        }
        this.me = me
        val local = draft(proposal, match = match, proposer = me)
        val token = edit { begin(SessionLedger.Change.Propose(local)) }
        return try {
            val row = call("propose_session", params("p_match" to JsonPrimitive(matchID.lowercase()), "p_proposal" to body(proposal)))
            aliases[local.id] = row.id
            edit { settle(token, with = listOf(row)) }
            true
        } catch (e: CancellationException) {
            edit { fail(token) }
            throw e
        } catch (e: Exception) {
            fail(token, e, action = "propose")
            false
        }
    }

    /** Accept with one of the options, or decline. */
    suspend fun respond(id: UUID, accept: Boolean, pick: Instant?): Boolean {
        val sessionID = aliases[id] ?: id
        val token = edit { begin(SessionLedger.Change.Respond(sessionID, accept, pick)) }
        val args = buildMap {
            put("p_session", JsonPrimitive(sessionID.toString().lowercase()))
            put("p_accept", JsonPrimitive(accept))
            if (accept && pick != null) put("p_pick", JsonPrimitive(ServerDate.string(pick)))
        }
        return try {
            val row = call("respond_session", JsonObject(args))
            edit { settle(token, with = listOf(row)) }
            calendar.sessionChanged(sessionID, row.status.rawValue)
            true
        } catch (e: CancellationException) {
            edit { fail(token) }
            throw e
        } catch (e: Exception) {
            fail(token, e, action = if (accept) "accept" else "decline")
            false
        }
    }

    /** Other times for an invite: it becomes `countered`, the new one is the person's own. */
    suspend fun counter(id: UUID, proposal: SessionProposal): Boolean {
        val sessionID = aliases[id] ?: id
        val old = record(sessionID) ?: return false
        val me = backend.userID ?: return false
        this.me = me
        val local = draft(proposal, match = old.matchID, proposer = me, replaces = sessionID)
        val token = edit { begin(SessionLedger.Change.Counter(sessionID, local)) }
        return try {
            val row = call(
                "counter_session",
                params("p_session" to JsonPrimitive(sessionID.toString().lowercase()), "p_proposal" to body(proposal)),
            )
            aliases[local.id] = row.id
            // Both rows changed in the same transaction: the old one is countered, as of the new one.
            val countered = old.copy(status = SessionRecord.Status.COUNTERED, updatedAt = row.updatedAt)
            edit { settle(token, with = listOf(countered, row)) }
            calendar.sessionChanged(sessionID, countered.status.rawValue)
            true
        } catch (e: CancellationException) {
            edit { fail(token) }
            throw e
        } catch (e: Exception) {
            fail(token, e, action = "counter")
            false
        }
    }

    /** Either person calls it off (pending or accepted). */
    suspend fun cancel(id: UUID): Boolean {
        val sessionID = aliases[id] ?: id
        val token = edit { begin(SessionLedger.Change.Cancel(sessionID)) }
        return try {
            val row = call("cancel_session", params("p_session" to JsonPrimitive(sessionID.toString().lowercase())))
            edit { settle(token, with = listOf(row)) }
            calendar.sessionChanged(sessionID, row.status.rawValue)
            true
        } catch (e: CancellationException) {
            edit { fail(token) }
            throw e
        } catch (e: Exception) {
            fail(token, e, action = "cancel")
            false
        }
    }

    /** Signed out: nothing of this account stays. */
    fun reset() {
        edit { reset() }
        aliases.clear()
        requested.clear()
        me = null
        attention.reset()
    }

    // Helpers

    private suspend fun call(rpc: String, params: JsonObject): SessionRecord =
        SessionRecord.decode(backend.rpc(rpc, params).decodeToString())

    /** A read that either works or changes nothing (cancellation still propagates). */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Telemetry.unexpected(e, "sessions", "read")
        null
    }

    /**
     * Refused or unreachable: the change is undone and the reason said. A refusal also means this
     * phone's copy may be behind (answered elsewhere, cancelled by the other person): read again.
     */
    private fun fail(token: UUID, error: Throwable, action: String) {
        Telemetry.track(AnalyticsEvent.SessionActionFailed(action, Telemetry.reason(error)))
        Telemetry.unexpected(error, "sessions", action)
        edit { fail(token) }
        Haptics.warning()
        failureNotice.show(error)
        if (error !is IOException) scope.launch { refresh() }
    }

    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    private fun body(p: SessionProposal): JsonObject = buildJsonObject {
        put("sport", JsonPrimitive(p.sport.id))
        put("options", JsonArray(p.options.map { JsonPrimitive(ServerDate.string(it)) }))
        put("title", JsonPrimitive(p.title))
        put("note", JsonPrimitive(p.note))
        put("tags", JsonArray(p.tags.map(::JsonPrimitive)))
        p.discovery?.let { put("discovery", JsonPrimitive(it.serverValue)) }
    }

    private fun draft(p: SessionProposal, match: UUID, proposer: UUID, replaces: UUID? = null) = SessionRecord(
        id = p.id, matchID = match, proposerID = proposer, sportID = p.sport.id,
        options = p.options.sorted(), title = p.title, note = p.note, tags = p.tags,
        discovery = p.discovery?.serverValue, replacesID = replaces, updatedAt = Instant.now(),
    )
}

/** `public.session_discovery`. */
val SessionProposal.Discovery.serverValue: String
    get() = if (this == SessionProposal.Discovery.I_TEACH) "iTeach" else "theyTeach"

fun sessionDiscovery(server: String?): SessionProposal.Discovery? = when (server) {
    "iTeach" -> SessionProposal.Discovery.I_TEACH
    "theyTeach" -> SessionProposal.Discovery.THEY_TEACH
    else -> null
}

/** A server row's status as the chat card and the Sessions tab show it. */
val SessionRecord.Status.proposalStatus: SessionProposal.Status
    get() = when (this) {
        SessionRecord.Status.PENDING -> SessionProposal.Status.PENDING
        SessionRecord.Status.ACCEPTED -> SessionProposal.Status.ACCEPTED
        SessionRecord.Status.DECLINED -> SessionProposal.Status.DECLINED
        SessionRecord.Status.COUNTERED -> SessionProposal.Status.COUNTERED
        SessionRecord.Status.CANCELLED -> SessionProposal.Status.CANCELLED
    }

/** A server row as the chat card and the Sessions tab show it. */
fun SessionProposal.Companion.from(row: SessionRecord): SessionProposal? {
    val sport = Sport.fromId(row.sportID) ?: return null
    return SessionProposal(
        id = row.id, sport = sport, options = row.options, chosen = row.chosenAt, title = row.title,
        note = row.note, tags = row.tags, discovery = sessionDiscovery(row.discovery), status = row.status.proposalStatus,
    )
}

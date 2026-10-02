package so.drafft.core.data.backend

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.Icebreaker
import so.drafft.core.model.Profile
import so.drafft.core.model.Vitals

// Ports Drafft/Services/Backend/Safety.swift.

/** Reports and blocks, on the server. Profiles from the demo data (not server ids) stay on the device. */
class Safety(private val backend: Backend) {
    /**
     * The report reaches the safety team (who may hold the account at once, see reports_events), and
     * blocks them: `report_user` does both. [reason] is the report reason's raw value.
     */
    suspend fun report(person: Profile, reason: String, details: String) {
        if (uuidOrNull(person.id) == null) return
        try {
            backend.rpc(
                "report_user",
                jsonOf("p_target" to person.id, "p_reason" to reason, "p_details" to details.trim().take(1000)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.track(AnalyticsEvent.ReportFailed(Telemetry.reason(e)))
            Telemetry.unexpected(e, "safety", "report")
            throw e
        }
        // The category only: the details are the person's own words, for the safety team alone.
        Telemetry.track(AnalyticsEvent.UserReported(reason.lowercase()))
    }

    /** `block_user` / `unblock_user`. Both are idempotent: sending one again is harmless. */
    suspend fun send(action: SafetyOutbox.Action, id: String) {
        if (uuidOrNull(id) == null) return
        backend.rpc(if (action == SafetyOutbox.Action.BLOCK) "block_user" else "unblock_user", jsonOf("p_target" to id))
    }

    /**
     * The people you blocked, as the server has them (`blocked_users`), newest first. Their photo isn't
     * signed for you any more: the list shows names.
     */
    suspend fun blockedPeople(): List<Profile> =
        backend.rpc("blocked_users").parseJsonOrNull().asArray.orEmpty().mapNotNull { row ->
            val id = row.asObject?.optString("id") ?: return@mapNotNull null
            blockedProfile(id, row.asObject?.optString("name") ?: "")
        }

    companion object {
        /**
         * The server turned it down for good (yourself, someone who doesn't exist): sending it again can't
         * help. A network error, a server error or a session to refresh can.
         */
        fun isFinal(error: Throwable): Boolean =
            error is Backend.BackendError.Http && error.status in 400..499 && error.status !in setOf(401, 408, 429)

        /** Someone in your blocked list as the server lists them: an id and a name. */
        fun blockedProfile(id: String, name: String) = Profile(
            id = id, name = name, age = 18, neighborhood = "", distanceKm = 0.0, portrait = "", photos = emptyList(),
            sports = emptyList(), voiceIntro = null, voiceDuration = 0.0, icebreaker = Icebreaker.Kind.TWO_TRUTHS.blank,
            favoriteSpot = "", bio = "", goal = "", vitalsOverride = Vitals.blank, promptsOverride = emptyList(),
        )
    }
}

/**
 * Blocks and unblocks not on the server yet, kept on this phone until they are: one made offline still
 * reaches the server, after a relaunch too, and the person stays hidden meanwhile. Per account; the last
 * action on a person wins.
 */
class SafetyOutbox(private val store: KeyValueStore) {
    @Serializable
    enum class Action { BLOCK, UNBLOCK }

    @Serializable
    data class Entry(val action: Action, val name: String)

    private val serializer = MapSerializer(String.serializer(), Entry.serializer())

    private fun key(user: UUID) = "safety.pending.${user.toString().lowercase()}"

    fun pending(user: UUID): Map<String, Entry> = store.getString(key(user))
        ?.let { attemptOrNull { DrafftJson.decodeFromString(serializer, it) } }
        .orEmpty()

    fun add(entry: Entry, id: String, user: UUID) = save(pending(user) + (id to entry), user)

    /** Done (or turned down for good): forgotten, unless another action on them came in meanwhile. */
    fun remove(entry: Entry, id: String, user: UUID) {
        val all = pending(user)
        if (all[id] != entry) return
        save(all - id, user)
    }

    private fun save(all: Map<String, Entry>, user: UUID) {
        if (all.isEmpty()) store.remove(key(user)) else store.putString(key(user), DrafftJson.encodeToString(serializer, all))
    }
}

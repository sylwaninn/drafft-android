package so.drafft.core.data

import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.asArray
import so.drafft.core.data.backend.asBoolean
import so.drafft.core.data.backend.asObject
import so.drafft.core.data.backend.asString
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.backend.uuidOrNull
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.store.PurchaseCredit

// Ports Drafft/Services/UserChannel.swift.

/**
 * The person's own Realtime topic, `user:<id>` (private): the database broadcasts on it when
 * something of theirs changes. One channel per account, whatever listens:
 *
 * - `moderation`: the hold (`AccountModeration`);
 * - `wallet`: drafft tempo, boosts and super likes (`AppModel.loadWallet`), and who liked you, blurred
 *   or not (`AppModel.loadLikes`);
 * - `media`: a photo was approved or refused, by the automatic check or by the team
 *   (`PhotoModeration`);
 * - `session_revoked`: Auth sessions ended on the server; this device signs out at once if its own is
 *   one of them (`AppModel.sessionsRevoked`);
 * - `profile`: the person's own profile changed, on this device, another one or by the team, with the
 *   columns that changed: the row is read again with its hold, pause, settings, language and card
 *   (`AppModel.profileChanged`, `AppModel.refreshAccount`);
 * - `session`: a session of theirs was proposed, answered, countered or cancelled: its row (and the one
 *   it replaced) is read again for the Sessions tab and the chat cards, and the calendar event added for
 *   it follows (`SessionStore`, `SessionCalendar`);
 * - `like`: someone liked them (Likes, and the deck for a super like);
 * - `match`, `match_ended`: a match was made or ended, either side (`AppModel.loadMatches`).
 *
 * A payload only says a change happened (a photo decision carries its media and status): the row
 * is the truth, read again on each event and on each (re)connection, so a change made while the
 * socket was down isn't missed.
 */
class UserChannel(
    private val backend: Backend,
    private val photoModeration: PhotoModeration,
    private val sessionStore: SessionStore,
    private val purchaseCredit: PurchaseCredit,
) {
    /**
     * While signed in. A channel that fails to join, is closed by the server or stays down is
     * replaced, after a pause that grows while it keeps failing. Ends when the coroutine is cancelled
     * (sign-out). Runs on the main thread, like every change to the app's state.
     */
    suspend fun watch(app: AppModel) {
        val id = backend.userID ?: return
        val topic = "user:${id.toString().lowercase()}"
        var pause: Duration = 2.seconds
        while (currentCoroutineContext().isActive) {
            val joined = listen(topic, app)
            pause = if (joined) 2.seconds else minOf(pause * 2, 60.seconds)
            delay(pause)
        }
    }

    /**
     * One channel on the topic, until it's gone (true if it was joined at some point) or the
     * coroutine is cancelled.
     */
    private suspend fun listen(topic: String, app: AppModel): Boolean {
        val client = backend.client
        val channel = client.channel(topic) { isPrivate = true }
        val joined = AtomicBoolean(false)
        try {
            val stayed = coroutineScope {
                // Every stream is listened to before the join (undispatched: registered right away).
                fun <T> on(flow: Flow<T>, handle: suspend (T) -> Unit) =
                    launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { handle(it) } }

                on(channel.broadcastFlow<JsonElement>("moderation")) { app.refreshAccount(force = true) }
                on(channel.broadcastFlow<JsonElement>("wallet")) {
                    // drafft tempo starting or ending changes what Likes may show.
                    app.loadWallet()
                    app.loadLikes()
                }
                on(channel.broadcastFlow<JsonElement>("media")) { payload ->
                    val o = payload.asObject ?: return@on
                    val mediaID = o["mediaId"].asString ?: return@on
                    val state = o["status"].asString ?: return@on
                    photoModeration.apply(mediaID = mediaID, status = state)
                }
                on(channel.broadcastFlow<JsonElement>("session_revoked")) { payload ->
                    val ids = payload.asObject?.get("sessions").asArray?.mapNotNull { it.asString } ?: emptyList()
                    app.sessionsRevoked(ids.map { it.lowercase() })
                }
                on(channel.broadcastFlow<JsonElement>("profile")) { payload ->
                    val fields = payload.asObject?.get("fields").asArray?.mapNotNull { it.asString }
                    app.profileChanged(fields?.toSet())
                }
                on(channel.broadcastFlow<JsonElement>("session")) { payload ->
                    val o = payload.asObject ?: return@on
                    val id = uuidOrNull(o["sessionId"].asString) ?: return@on
                    sessionStore.changed(id, replaces = uuidOrNull(o["replacesId"].asString), status = o["status"].asString)
                }
                // `like`, `match` and `match_ended`: Likes on a like, the matches on a match or an ended one.
                on(channel.broadcastFlow<JsonElement>("like")) { payload ->
                    app.likeReceived(superLike = payload.asObject?.get("superLike").asBoolean ?: false)
                }
                on(channel.broadcastFlow<JsonElement>("match")) { app.loadMatches() }
                on(channel.broadcastFlow<JsonElement>("match_ended")) { payload ->
                    val id = payload.asObject?.get("matchId").asString ?: return@on
                    app.matchEnded(id)
                }
                on(channel.status) { s ->
                    if (s != RealtimeChannel.Status.SUBSCRIBED) return@on
                    joined.set(true)
                    // Joined (again): photo verdicts given while it was down are read, not waited for.
                    photoModeration.recheck()
                    app.refreshAccount(force = true)
                    app.loadWallet()
                    purchaseCredit.resume(app)
                    sessionStore.refresh()
                    // Discovery missed nothing while the socket was down.
                    app.refreshDiscovery(DiscoveryFreshness.Moment.RECONNECTED)
                }
                // The streams only end when cancelled: the channel failing to join or being gone ends
                // this one, and the others with it.
                val result = stayJoined(channel, client.realtime)
                coroutineContext.cancelChildren()
                result
            }
            return stayed || joined.get()
        } finally {
            withContext(NonCancellable) { attempt { client.realtime.removeChannel(channel) } }
        }
    }

    /**
     * Joins, then checks the channel is still up: after a reconnect the SDK joins it again by
     * itself, so only a channel down for a while (closed by the server, rejoin given up, socket not
     * reconnecting) ends this one. False if it never joined.
     */
    private suspend fun stayJoined(channel: RealtimeChannel, realtime: Realtime): Boolean {
        withTimeoutOrNull(JOIN_TIMEOUT) { attempt { channel.subscribe(blockUntilSubscribed = true) } } ?: return false
        var downSince: TimeSource.Monotonic.ValueTimeMark? = null
        while (currentCoroutineContext().isActive) {
            delay(5.seconds)
            val down = channel.status.value == RealtimeChannel.Status.UNSUBSCRIBED ||
                realtime.status.value == Realtime.Status.DISCONNECTED
            val since = downSince
            if (!down) {
                downSince = null
            } else if (since != null && since.elapsedNow() >= 15.seconds) {
                return true
            } else if (since == null) {
                downSince = TimeSource.Monotonic.markNow()
            }
        }
        return true
    }

    private companion object {
        /** How long a join may take before it counts as failed (the Swift SDK's default timeout). */
        val JOIN_TIMEOUT = 10.seconds
    }
}

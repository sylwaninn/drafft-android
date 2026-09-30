package so.drafft.core.data.moderation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.DrafftJson
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.backend.jsonArray
import so.drafft.core.data.backend.optString
import so.drafft.core.data.backend.requireObject
import so.drafft.core.data.backend.string
import so.drafft.core.data.media.EdgeFunctionTicketProvider
import so.drafft.core.data.media.MediaUploads
import so.drafft.core.data.platform.AppLifecycle
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.model.L

/**
 * Ports Drafft/Services/Backend/PhotoModeration.swift.
 *
 * Where a profile photo stands on the server: sent, then judged by moderation (AWS Rekognition in the
 * backend's db-events function). Keyed by the photo's local path, as the photo grids show it.
 */
class PhotoModeration(
    private val backend: Backend,
    private val defaults: KeyValueStore,
    private val lifecycle: AppLifecycle,
    /** `NotificationService.syncPushToken` (resolved late: the notification service needs this one). */
    private val syncPushToken: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Uploading : State
        data object Checking : State
        data object Approved : State
        data object Refused : State

        /** Borderline for Rekognition, too large for it, or a second review asked: a person decides. */
        data object InReview : State
        data class Failed(val message: String) : State

        /** Still on its way: the tile is dimmed with a small loader. */
        val isWorking: Boolean get() = this == Uploading || this == Checking
    }

    /** A refused photo, for its banner and its explanation. */
    data class Refusal(val path: String) {
        val id: String get() = path
    }

    /** Each picked photo's state, by path (observed by the grids). */
    val states: MutableMap<String, State> = mutableStateMapOf()

    /**
     * Server id of each photo, once registered. Kept on the device, so a push tapped after the app was
     * closed still finds its photo.
     */
    private val mediaIDs: MutableMap<String, String> = mutableStateMapOf<String, String>().apply { putAll(loadIDs()) }

    /** The failed photo whose reason is on screen (tap on its badge). */
    var shownFailure: String? by mutableStateOf(null)

    /** In-app banner: a photo was just refused (the push does it when the app is closed). */
    var refusalBanner: Refusal? by mutableStateOf(null)

    /** A photo to take off the profile (from the refusal sheet): the grid showing it removes it. */
    var removeRequest: String? by mutableStateOf(null)

    /**
     * The refusal explanation to present above whatever is on screen (a tapped push): the root shows it
     * as a sheet and sets it back to null when closed (the iPhone's `PhotoRefusalPresenter`).
     */
    var presentedRefusal: Refusal? by mutableStateOf(null)

    /** The server id of a picked photo, once registered. */
    fun id(path: String): String? = mediaIDs[path]

    /** Waits for a picked photo to be uploaded and registered (moderation may still be running). Null if it failed. */
    suspend fun waitForID(path: String): String? {
        if (states[path] == null && mediaIDs[path] == null) submit(path)
        repeat(600) {
            mediaIDs[path]?.let { return it }
            if (states[path] is State.Failed) return null
            delay(100.milliseconds)
        }
        return null
    }

    /** Clears a failed attempt and sends the photo again. */
    fun retry(path: String) {
        states.remove(path)
        submit(path)
    }

    /** The failure reason for a photo, in plain words when we know the cause. */
    fun reason(path: String): String = (states[path] as? State.Failed)?.message ?: ""

    /**
     * Compress, upload to the media bucket, register it (add_profile_media), then follow its moderation
     * status until it's decided.
     */
    fun submit(path: String) {
        if (states[path] != null) return
        states[path] = State.Uploading
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { File(path).readBytes() }
                val tickets = EdgeFunctionTicketProvider(
                    backend.config.functionsURL,
                    onAccountHeld = { backend.post(Backend.Event.ACCOUNT_HELD_BY_SERVER) },
                ) { backend.accessToken() }
                val uploaded = MediaUploads.photo(data, tickets = tickets)
                // The session exists now: this device can receive the "refused" push.
                syncPushToken()
                states[path] = State.Checking
                val args = buildMap {
                    put("p_key", JsonPrimitive(uploaded.key))
                    put("p_width", JsonPrimitive(uploaded.width))
                    put("p_height", JsonPrimitive(uploaded.height))
                    uploaded.thumbHash?.let { put("p_thumbhash", JsonPrimitive(it)) }
                }
                val row = backend.rpc("add_profile_media", JsonObject(args))
                val id = DrafftJson.parseToJsonElement(row.decodeToString()).requireObject().string("id")
                mediaIDs[path] = id
                saveIDs()
                val verdict = verdict(id)
                // A live `media` event may have settled it already (apply): only a newer word counts.
                if (states[path] == State.Checking) settle(path, verdict)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                states[path] = State.Failed(failure(e))
            }
        }
    }

    /** Asks a person to look at a refused photo again. It stays off the profile meanwhile. */
    suspend fun requestReview(path: String) {
        // Not uploaded (yet): nothing the team could look at, so never say it was sent.
        val id = mediaIDs[path] ?: throw Backend.BackendError.Http(404, "photo not on the server")
        backend.rpc("request_media_review", JsonObject(mapOf("p_media" to JsonPrimitive(id))))
        states[path] = State.InReview
    }

    /** Takes a refused photo off the profile: from the grid, and from the server. */
    fun remove(path: String) {
        removeRequest = path
        mediaIDs[path]?.let { id ->
            scope.launch { attempt { backend.rpc("delete_media", JsonObject(mapOf("p_id" to JsonPrimitive(id)))) } }
        }
        states.remove(path)
        mediaIDs.remove(path)
        saveIDs()
    }

    /**
     * A `media` event from the person's Realtime topic (UserChannel): the automatic check or the team
     * decided on one of their photos. The tile follows at once; a refusal gets its banner, with the second
     * look offered by its explanation. A photo this device doesn't know (added from another phone) is
     * left alone.
     */
    fun apply(mediaID: String, status: String) {
        val path = mediaIDs.entries.firstOrNull { it.value == mediaID }?.key ?: return
        when {
            status == "approved" -> settle(path, State.Approved)
            status == "rejected" -> settle(path, State.Refused)
            // Back to pending: a second look was asked (here or on another device).
            status == "pending" && states[path] == State.Refused -> settle(path, State.InReview)
        }
    }

    /** Sets a photo's verdict; announces a refusal once, when it becomes one. */
    private fun settle(path: String, state: State) {
        val was = states[path]
        states[path] = state
        if (state == State.Refused && was != State.Refused) announceRefusal(path)
    }

    /**
     * From the push: shows the explanation for that photo, once the app is on screen (a tap on a push can
     * launch it: its first screen takes a moment to exist).
     */
    fun openRefusal(mediaID: String) {
        val path = mediaIDs.entries.firstOrNull { it.value == mediaID }?.key ?: return
        states[path] = State.Refused
        refusalBanner = null
        scope.launch {
            repeat(30) {
                if (lifecycle.isActive()) {
                    // Let the launch or the return from the background finish its transition.
                    delay(400.milliseconds)
                    presentedRefusal = Refusal(path)
                    return@launch
                }
                delay(100.milliseconds)
            }
        }
    }

    /** Open app: a banner. Closed or in the background: the server's push says it. */
    private fun announceRefusal(path: String) {
        if (!lifecycle.isActive()) return
        Haptics.warning()
        refusalBanner = Refusal(path)
    }

    /**
     * Polls the status (every second, up to 30 s). Still pending after that: a person decides, and their
     * decision arrives as a `media` event ([apply]).
     */
    private suspend fun verdict(id: String): State {
        repeat(30) {
            delay(1.seconds)
            val data = backend.select("profile_media?id=eq.$id&select=status")
            when (data.jsonArray().firstOrNull()?.requireObject()?.optString("status")) {
                "approved" -> return State.Approved
                "rejected" -> return State.Refused
            }
        }
        return State.InReview
    }

    private fun loadIDs(): Map<String, String> = runCatching {
        defaults.getString(IDS_KEY)?.let { DrafftJson.decodeFromString(idsSerializer, it) } ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun saveIDs() {
        defaults.putString(IDS_KEY, DrafftJson.encodeToString(idsSerializer, mediaIDs.toMap()))
    }

    private companion object {
        const val IDS_KEY = "photoModeration.mediaIDs"
        val idsSerializer = MapSerializer(String.serializer(), String.serializer())

        /**
         * Why a photo couldn't be sent, as its tile explains it: a refusal by the server in its own words
         * (photo limit, file too large...), the connection only when that's the likely cause.
         */
        fun failure(error: Throwable): String {
            if (error is Backend.BackendError.SignedOut) return L("Log in again to send your photos.")
            ServerMessage.text(of = error)?.let { return it }
            if (ServerMessage.code(of = error) != null) return ServerMessage.generic
            return L("It couldn't be sent. Check your connection and try again.")
        }
    }
}

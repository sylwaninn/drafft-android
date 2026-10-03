package so.drafft.core.data.moderation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
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
import so.drafft.core.data.backend.jsonArray
import so.drafft.core.data.backend.optString
import so.drafft.core.data.backend.requireObject
import so.drafft.core.data.backend.string
import so.drafft.core.data.media.EdgeFunctionTicketProvider
import so.drafft.core.data.media.MediaURL
import so.drafft.core.data.media.MediaUploads
import so.drafft.core.data.platform.AppLifecycle
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.platform.NetworkMonitor
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.ScreenTracker
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.L
import so.drafft.core.model.Profile

/**
 * Where a profile photo stands on the server: sent, then judged by moderation (AWS Rekognition in the
 * backend's db-events function). Known by its local path (picked) or its object key (on the server).
 */
class PhotoModeration(
    private val backend: Backend,
    private val defaults: KeyValueStore,
    private val lifecycle: AppLifecycle,
    network: NetworkMonitor,
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

        /** The automatic check has spoken (approved, refused, or handed to a person). */
        val isJudged: Boolean get() = this == Approved || this == Refused || this == InReview
    }

    /** A refused photo, for its banner and its explanation. */
    data class Refusal(val path: String) {
        val id: String get() = path
    }

    /** Each photo's state (observed by the grids). Keyed by [slot]: a photo on the server keeps its state when its signed link changes. */
    private val states: MutableMap<String, State> = mutableStateMapOf()

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
     * as a sheet and sets it back to null when closed.
     */
    var presentedRefusal: Refusal? by mutableStateOf(null)

    /**
     * Photos read back from the server, by [slot]: their id, so they can be looked at again or removed,
     * and a later verdict still reaches them. Not kept on the device: every read of the account gives
     * them again.
     */
    private val serverIDs: MutableMap<String, String> = mutableStateMapOf()

    /** The latest link of each photo read back from the server, by [slot]: what a banner shows. */
    private val links: MutableMap<String, String> = mutableStateMapOf()

    /** Photos read back from the server on which it found no face, by [slot]. */
    private val faceless: MutableMap<String, Boolean> = mutableStateMapOf()

    /** Photos picked, then left without being saved: deleted from the server as soon as they're on it. */
    private val discarded: MutableSet<String> = mutableSetOf()

    /**
     * Picked photos on the server whose verdict couldn't be read (no connection): still being checked,
     * never taken as approved, read again as soon as the connection is back ([recheck]).
     */
    private val unresolved: MutableSet<String> = mutableSetOf()

    init {
        // Back online: the verdicts missed meanwhile are read again (on the main thread, like every change here).
        network.onAvailable { scope.launch { recheck() } }
    }

    /**
     * What a photo is known by: a picked file by its path, a photo on the server by its object key (its
     * signed link changes every few minutes, the photo doesn't).
     */
    private fun slot(path: String): String {
        if (!path.startsWith("http")) return path
        return MediaURL.key(path) ?: MediaURL.canonical(path)
    }

    /** The server id of a photo, once registered. */
    fun id(path: String): String? = mediaIDs[path] ?: serverIDs[slot(path)]

    /**
     * Where a photo stands, as the grid and the profile read it. Null: nothing to say (a photo on the
     * server moderation approved, or one never sent).
     */
    fun state(path: String): State? = states[slot(path)]

    /** Whether the server found no face on a photo read back from it (never checked: false). */
    fun isFaceless(path: String): Boolean = faceless[slot(path)] == true

    /**
     * Whether a photo may show as the profile, to the person themselves included: only once approved. A
     * photo read back from the server with no state was approved (the others are tracked below).
     */
    fun isShown(path: String): Boolean {
        val state = state(path) ?: return !path.startsWith("/")
        return state == State.Approved
    }

    /** The same profile with only the photos that may show ([isShown]), in order: the first one is the portrait. */
    fun showingApprovedPhotos(profile: Profile): Profile {
        val shown = profile.allPhotos.filter { it.isNotEmpty() && isShown(it) }
        return profile.copy(portrait = shown.firstOrNull() ?: "", photos = shown.drop(1))
    }

    /**
     * The account's photos as the server has them: a refused one stays on the person's own grid (to ask
     * for a second look or remove it) and one still pending shows as in review. Neither is ever on the
     * profile.
     */
    fun track(photos: List<ProfileSync.OwnPhoto>) {
        for (photo in photos) {
            val key = slot(photo.link)
            // Its id stays known once approved: a later refusal (the team, a second look) must still reach it.
            serverIDs[key] = photo.id
            links[key] = photo.link
            if (photo.faceless) faceless[key] = true else faceless.remove(key)
            when (photo.status) {
                "approved" -> states.remove(key)
                "rejected" -> states[key] = State.Refused
                else -> states[key] = State.InReview
            }
        }
    }

    /**
     * A picked photo whose state this launch doesn't know (a sign-up resumed after the app was closed):
     * its verdict read again, or the photo sent again if the server never got it.
     */
    fun ensureChecked(path: String) {
        if (!path.startsWith("/") || states[slot(path)] != null) return
        val id = mediaIDs[path] ?: return submit(path)
        states[slot(path)] = State.Checking
        scope.launch { follow(path, id) }
    }

    /**
     * Reads again the verdict of every picked photo still waiting for one: back online, back in the app.
     * One the server has settled meanwhile takes its word; the others keep waiting.
     */
    fun recheck() {
        val waiting = mediaIDs.filter { (path, _) ->
            path in unresolved || states[slot(path)] == State.InReview
        }
        for ((path, id) in waiting) {
            val state = states[slot(path)]
            if (state == State.Refused || state == State.Approved) continue
            unresolved.remove(path)
            states[slot(path)] = if (state == State.InReview) State.InReview else State.Checking
            scope.launch { follow(path, id) }
        }
    }

    /**
     * Follows a registered photo's verdict. Only the server's word settles it: without a connection it
     * stays being checked (never approved by default) until [recheck] reads it again.
     */
    private suspend fun follow(path: String, id: String) {
        val verdict = try {
            verdict(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.unexpected(e, "photos", "verdict")
            unresolved.add(path)
            return
        }
        if (verdict != null) {
            // A live `media` event may have settled it already (apply): only a newer word counts.
            val state = states[slot(path)]
            if (state == State.Checking || state == State.InReview) settle(path, verdict)
        } else {
            // Gone from the server: sent again.
            mediaIDs.remove(path)
            saveIDs()
            states.remove(slot(path))
            submit(path)
        }
    }

    /** Waits for a picked photo to be uploaded and registered (moderation may still be running). Null if it failed. */
    suspend fun waitForID(path: String): String? {
        if (states[slot(path)] == null && mediaIDs[path] == null) submit(path)
        repeat(600) {
            mediaIDs[path]?.let { return it }
            if (states[slot(path)] is State.Failed) return null
            delay(100.milliseconds)
        }
        return null
    }

    /** Photos whose upload started in this launch: another start is a retry. */
    private val attempted = mutableSetOf<String>()

    /** Clears a failed attempt and sends the photo again. */
    fun retry(path: String) {
        states.remove(slot(path))
        // Already on the server (only its verdict couldn't be read): read it again, never a second upload.
        if (mediaIDs[path] != null) ensureChecked(path) else submit(path)
    }

    /** The failure reason for a photo, in plain words when we know the cause. */
    fun reason(path: String): String = (states[slot(path)] as? State.Failed)?.message ?: ""

    /**
     * Compress, upload to the media bucket, register it (add_profile_media), then follow its moderation
     * status until it's decided.
     */
    fun submit(path: String) {
        if (states[slot(path)] != null) return
        states[slot(path)] = State.Uploading
        Telemetry.track(AnalyticsEvent.PhotoUploadStarted(where = ScreenTracker.currentID, retry = path in attempted))
        attempted += path
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { File(path).readBytes() }
                val tickets = EdgeFunctionTicketProvider(
                    backend.config.functionsURL,
                    onAccountHeld = { backend.post(Backend.Event.ACCOUNT_HELD_BY_SERVER) },
                ) { backend.accessToken() }
                val uploaded = Telemetry.trace("media.upload", "profile photo") { span ->
                    span.setData("bytes", data.size)
                    MediaUploads.photo(data, tickets = tickets)
                }
                // The session exists now: this device can receive the "refused" push.
                syncPushToken()
                states[slot(path)] = State.Checking
                // A draft: on the profile only once the person saves (save_profile_media).
                val args = buildMap {
                    put("p_key", JsonPrimitive(uploaded.key))
                    put("p_width", JsonPrimitive(uploaded.width))
                    put("p_height", JsonPrimitive(uploaded.height))
                    uploaded.thumbHash?.let { put("p_thumbhash", JsonPrimitive(it)) }
                    put("p_draft", JsonPrimitive(true))
                }
                val row = backend.rpc("add_profile_media", JsonObject(args))
                val id = DrafftJson.parseToJsonElement(row.decodeToString()).requireObject().string("id")
                // Left without saving while it was on its way: it goes at once.
                if (discarded.remove(path)) {
                    states.remove(slot(path))
                    scope.launch { deleteOnServer(id) }
                    return@launch
                }
                mediaIDs[path] = id
                saveIDs()
                follow(path, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.track(AnalyticsEvent.PhotoUploadFailed(Telemetry.reason(e)))
                Telemetry.unexpected(e, "photos", "upload")
                states[slot(path)] = State.Failed(failure(e))
            }
        }
    }

    /**
     * Deletes a photo the person took off (refused, or a draft never saved). The screen already let it go,
     * so a failure isn't put back on it: tried again a few times (offline, a server hiccup) so it doesn't
     * come back with the next read. A refusal with a code is the server's answer, never tried again:
     * already gone (`not_found`) is done, and one it won't delete (`portrait_required`) stays refused.
     * Drafts missed here are deleted by the server after a few days.
     */
    private suspend fun deleteOnServer(id: String) {
        for (attempt in 0 until 4) {
            try {
                backend.rpc("delete_media", JsonObject(mapOf("p_id" to JsonPrimitive(id))))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ServerMessage.code(e) != null || e == Backend.BackendError.SignedOut) return
                if (attempt < 3) delay((2L shl attempt).seconds)
            }
        }
    }

    /** Asks a person to look at a refused photo again. It stays off the profile meanwhile. */
    suspend fun requestReview(path: String) {
        // Not uploaded (yet): nothing the team could look at, so never say it was sent.
        val id = id(path) ?: throw Backend.BackendError.Http(404, "not_found")
        backend.rpc("request_media_review", JsonObject(mapOf("p_media" to JsonPrimitive(id))))
        Telemetry.track(AnalyticsEvent.PhotoReviewRequested())
        states[slot(path)] = State.InReview
    }

    /**
     * Takes a refused photo off the profile: from the grid, and from the server. Its state stays refused
     * while a screen still holds it (a photo that's gone never passes for an approved one).
     */
    fun remove(path: String) {
        Telemetry.track(AnalyticsEvent.PhotoRemoved())
        removeRequest = path
        id(path)?.let { id ->
            scope.launch { deleteOnServer(id) }
        }
        mediaIDs.remove(path)
        serverIDs.remove(slot(path))
        saveIDs()
    }

    /**
     * Photos picked but not saved (the person left, or took them off the grid): drafts, never on the
     * profile, deleted from the server now, or as soon as their upload registers them. The server deletes
     * the ones this misses after a few days.
     */
    fun discard(paths: List<String>) {
        for (path in paths) {
            if (!path.startsWith("/")) continue
            val id = mediaIDs[path]
            when {
                id != null -> {
                    scope.launch { deleteOnServer(id) }
                    mediaIDs.remove(path)
                    saveIDs()
                    states.remove(slot(path))
                }
                states[slot(path)]?.isWorking == true -> discarded.add(path)
                else -> states.remove(slot(path))
            }
        }
    }

    /**
     * A `media` event from the person's Realtime topic (UserChannel): the automatic check or the team
     * decided on one of their photos. The tile follows at once; a refusal gets its banner, with the second
     * look offered by its explanation. A photo this device doesn't know (added from another phone) is
     * left alone.
     */
    fun apply(mediaID: String, status: String) {
        // The same photo can be known by its local path and by its server link: both follow.
        val paths = paths(mediaID)
        for (path in paths) {
            when {
                status == "approved" -> settle(path, State.Approved)
                status == "rejected" -> settle(path, State.Refused, announce = path == paths.first())
                // Back to pending: a second look was asked (here or on another device).
                status == "pending" && states[slot(path)] == State.Refused -> settle(path, State.InReview)
            }
        }
    }

    /** Every path a server id is known by: picked on this device, and read back from the server. */
    private fun paths(mediaID: String): List<String> =
        mediaIDs.filterValues { it == mediaID }.keys.toList() +
            serverIDs.filterValues { it == mediaID }.keys.mapNotNull { links[it] }

    /** Sets a photo's verdict; announces a refusal once, when it becomes one. */
    private fun settle(path: String, state: State, announce: Boolean = true) {
        val was = states[slot(path)]
        states[slot(path)] = state
        if (was != state) {
            when (state) {
                State.Approved -> Telemetry.track(AnalyticsEvent.PhotoModerated("approved"))
                State.Refused -> Telemetry.track(AnalyticsEvent.PhotoModerated("refused"))
                State.InReview -> Telemetry.track(AnalyticsEvent.PhotoModerated("in_review"))
                else -> Unit
            }
        }
        if (state == State.Refused && was != State.Refused && announce) announceRefusal(path)
    }

    /**
     * From the push: shows the explanation for that photo, once the app is on screen (a tap on a push can
     * launch it: its first screen takes a moment to exist). Returns false when the app knows no photo by
     * that id (nothing to explain).
     */
    fun openRefusal(mediaID: String): Boolean {
        val paths = paths(mediaID)
        val path = paths.firstOrNull() ?: return false
        paths.forEach { states[slot(it)] = State.Refused }
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
        return true
    }

    /** Open app: a banner. Closed or in the background: the server's push says it. */
    private fun announceRefusal(path: String) {
        if (!lifecycle.isActive()) return
        Haptics.warning()
        refusalBanner = Refusal(path)
    }

    /**
     * Polls the status (every second, up to 30 s). Still pending after that: a person decides, and their
     * decision arrives as a `media` event ([apply]) or on the next [recheck]. Null: the photo is no longer
     * on the server. Throws when the server can't be reached: nothing is decided then.
     */
    private suspend fun verdict(id: String): State? {
        repeat(30) { round ->
            if (round > 0) delay(1.seconds)
            val data = backend.select("profile_media?id=eq.$id&select=status")
            val row = data.jsonArray().firstOrNull() ?: return null
            when (row.requireObject().optString("status")) {
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

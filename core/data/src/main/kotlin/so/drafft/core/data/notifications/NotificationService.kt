package so.drafft.core.data.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.chat.ChatService
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.platform.LocalNotifications
import so.drafft.core.data.platform.PermissionStatus
import so.drafft.core.data.platform.SystemPermission
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.Localization

/**
 * Ports Drafft/Services/Notifications.swift.
 *
 * Notifications: permission, the per-type preferences, and the push plumbing (the FCM token). Session
 * reminders are the server's pushes (`session.reminder`, following `notify_session_*`): checked against
 * the session when they're sent, so a cancelled or changed one never reminds anyone. Tapping a
 * notification opens its chat ([openChatID]), Discover for the weekly boost ([openBoost]), or the
 * Sessions tab ([openSessions]); screens read those and reset them once handled.
 */
class NotificationService(
    private val backend: Backend,
    private val platform: LocalNotifications,
    private val defaults: KeyValueStore,
    private val appInfo: AppInfo,
    private val sessions: SessionStore,
    private val photoModeration: PhotoModeration,
    /** The chat service, resolved when a token arrives (it registers the same token with Stream). */
    private val chat: () -> ChatService?,
    private val scope: CoroutineScope,
) : SystemPermission {
    override var permission: PermissionStatus by mutableStateOf(PermissionStatus.NOT_ASKED)
        private set

    /** The FCM token, to send to the server. */
    var deviceToken: String? by mutableStateOf(null)
        private set

    /** A chat to open, set when a notification is tapped. */
    var openChatID: String? by mutableStateOf(null)

    /** Set when the weekly-boost notification is tapped: Discover opens, where the boost is used. */
    var openBoost: Boolean by mutableStateOf(false)

    /** Set when a session's cancellation without a chat is tapped: the Sessions tab opens. */
    var openSessions: Boolean by mutableStateOf(false)

    // Preferences. Saved on the profile (the server's pushes follow them, and they come back on a new
    // device) and on the phone (right at launch, offline too). See `applyServer`.
    private var _language by mutableStateOf(Localization.language)
    private var _matches by mutableStateOf(true)
    private var _messages by mutableStateOf(true)
    private var _messagePreviews by mutableStateOf(false)
    private var _reactions by mutableStateOf(true)
    private var _likes by mutableStateOf(true)
    private var _sessionEvening by mutableStateOf(true)
    private var _sessionHourBefore by mutableStateOf(true)
    private var _weeklyBoost by mutableStateOf(true)

    /** Language of the notification texts: the app's language (set by AppModel). */
    var language: AppLanguage
        get() = _language
        set(value) { _language = value; changed() }
    var matches: Boolean
        get() = _matches
        set(value) { _matches = value; changed() }
    var messages: Boolean
        get() = _messages
        set(value) { _messages = value; changed() }
    var messagePreviews: Boolean
        get() = _messagePreviews
        set(value) { _messagePreviews = value; changed() }
    var reactions: Boolean
        get() = _reactions
        set(value) { _reactions = value; changed() }
    var likes: Boolean
        get() = _likes
        set(value) { _likes = value; changed() }

    /** Session reminders, sent by the server: the evening before (20:00 in the person's time zone). */
    var sessionEvening: Boolean
        get() = _sessionEvening
        set(value) { _sessionEvening = value; changed() }

    /** Session reminders, sent by the server: an hour before. */
    var sessionHourBefore: Boolean
        get() = _sessionHourBefore
        set(value) { _sessionHourBefore = value; changed() }

    /** drafft tempo's weekly boost: pushed by the server when it credits it (`notify_weekly_boost`). */
    var weeklyBoost: Boolean
        get() = _weeklyBoost
        set(value) { _weeklyBoost = value; changed() }

    val isAllowed: Boolean get() = permission == PermissionStatus.ALLOWED
    val isDenied: Boolean get() = permission == PermissionStatus.DENIED

    private val registration = PushTokenRegistration(defaults)

    /** Set while settings from the phone or the server are applied: nothing is saved or sent back. */
    private var applying = false

    init {
        // Always current: every return to the app (from Settings too) re-reads the permission, so no
        // screen has to refresh it.
        platform.onForeground { scope.launch { refresh() } }
        defaults.getString(SETTINGS_KEY)?.let { text ->
            runCatching { json.decodeFromString(NotificationSettings.serializer(), text) }.getOrNull()?.let {
                applying = true
                apply(it)
                applying = false
            }
        }
        permission = platform.permission()
    }

    suspend fun refresh() {
        permission = platform.permission()
        if (isAllowed) platform.pushToken()?.let(::didRegister)
    }

    /** The system prompt. Returns whether notifications are on. */
    override suspend fun requestPermission(): Boolean {
        val granted = platform.requestPermission()
        refresh()
        return granted
    }

    override fun openSettings() = platform.openSettings()

    /** A token from FCM (at launch, or renewed: `DrafftMessagingService.onNewToken`). */
    fun didRegister(token: String) {
        deviceToken = token
        scope.launch { syncPushToken() }
        // Messages are pushed by Stream: the chat registers the same token there.
        chat()?.registerDevice(token)
    }

    /**
     * Registers this device with the backend so it can push (photo refused, match, session...).
     * Only once a backend session exists: it never creates an account by itself.
     * Sent only when the token, the account or the environment changed (or a day went by): every
     * return to the app hands the same token back.
     */
    suspend fun syncPushToken() {
        val token = deviceToken ?: return
        val account = backend.userID ?: return
        val environment = pushEnvironment
        if (!registration.needsSending(token, account, environment)) return
        // Not remembered when it fails: the next return to the app sends it again.
        attempt {
            backend.rpc(
                "register_push_token",
                JsonObject(
                    mapOf(
                        "p_token" to JsonPrimitive(token),
                        "p_environment" to JsonPrimitive(environment),
                        // The server sends FCM tokens through FCM, APNs ones through APNs.
                        "p_platform" to JsonPrimitive("android"),
                    ),
                ),
            )
            registration.markSent(token, account, environment)
        }
    }

    /**
     * Signed out: this device stops getting the account's pushes. The next account (or the same one,
     * signed in again) registers the token afresh.
     */
    suspend fun unregisterPushToken() {
        forgetPushTokenRegistration()
        val token = deviceToken ?: return
        attempt { backend.rpc("unregister_push_token", JsonObject(mapOf("p_token" to JsonPrimitive(token)))) }
    }

    fun forgetPushTokenRegistration() = registration.forget()

    /**
     * Which push environment this build registers (the iPhone's APNs `sandbox` for development builds,
     * `production` otherwise). FCM has a single one; the value keeps the server's contract.
     */
    private val pushEnvironment: String get() = if (appInfo.isDebugBuild) "sandbox" else "production"

    // Settings

    private val current: NotificationSettings
        get() = NotificationSettings(
            language = language.code, matches = matches, likes = likes, messages = messages,
            messagePreviews = messagePreviews, reactions = reactions, sessionEvening = sessionEvening,
            sessionHourBefore = sessionHourBefore, weeklyBoost = weeklyBoost,
        )

    private fun apply(s: NotificationSettings) {
        val lang = AppLanguage.fromCode(s.language) ?: language
        if (Localization.language != lang) Localization.use(lang)
        language = lang
        matches = s.matches
        likes = s.likes
        messages = s.messages
        messagePreviews = s.messagePreviews
        reactions = s.reactions
        sessionEvening = s.sessionEvening
        sessionHourBefore = s.sessionHourBefore
        weeklyBoost = s.weeklyBoost
    }

    /**
     * A setting (or the language) changed: saved on the phone, sent to the profile. Only real changes
     * are sent, never the defaults at launch.
     */
    private fun changed() {
        if (applying) return
        trackChanges(from = defaults.getString(SETTINGS_KEY), to = current)
        defaults.putString(SETTINGS_KEY, json.encodeToString(NotificationSettings.serializer(), current))
        val account = backend.userID?.toString() ?: return
        unsentFor = account
        send(current, account)
    }

    /** Each switch the person flipped (`notify_matches` off...); the language has its own event. */
    private fun trackChanges(from: String?, to: NotificationSettings) {
        val before = from?.let { runCatching { json.decodeFromString(NotificationSettings.serializer(), it) }.getOrNull() }?.fields ?: return
        for ((key, value) in to.fields) {
            if (key == "language" || before[key] == value) continue
            val enabled = (value as? JsonPrimitive)?.booleanOrNull ?: continue
            Telemetry.track(AnalyticsEvent.NotificationSettingChanged(key, enabled))
        }
    }

    /**
     * The account whose settings changed here and haven't reached the server yet (offline, a failed
     * save): kept over the server's copy, and sent again at each account read until one goes through.
     */
    private var unsentFor: String?
        get() = defaults.getString(UNSENT_KEY)
        set(value) = if (value == null) defaults.remove(UNSENT_KEY) else defaults.putString(UNSENT_KEY, value)

    /** Bumped by each send: only the latest one clears [unsentFor]. */
    private var sends = 0

    /**
     * The switch stays as the person set it: a save that fails is sent again at the next account read
     * (return to the app), never undone by the server's older copy.
     */
    private fun send(settings: NotificationSettings, account: String) {
        sends += 1
        val send = sends
        scope.launch {
            // Still marked unsent on failure: `applyServer` sends it again.
            attempt { backend.updateMyProfile(settings.fields) } ?: return@launch
            if (send == sends && unsentFor == account) unsentFor = null
        }
    }

    /**
     * The settings saved on the profile (another device, a reinstall) replace the phone's, unless this
     * phone has a change the server hasn't got yet: that one is sent again instead. Read with the rest
     * of the profile row (`AppModel.refreshAccount`).
     */
    fun applyServer(remote: NotificationSettings) {
        val account = backend.userID?.toString()
        if (account != null && unsentFor == account) {
            send(current, account)
            return
        }
        // Another account's leftover: this one's server copy wins.
        unsentFor = null
        applying = true
        apply(remote)
        applying = false
        defaults.putString(SETTINGS_KEY, json.encodeToString(NotificationSettings.serializer(), remote))
    }

    // From a person

    /**
     * A notification about someone, shown right away: their name as the title (the event for an
     * anonymous like), one sentence as the body ("Nouveau message.", or the text with previews on), their
     * photo as the picture. Away from the app these come as pushes (Stream for messages, db-events for
     * the rest); while the chat is connected (the app open, or just left), Stream doesn't push, so a
     * message from another chat is posted here, under the same settings. [preview] is the message text,
     * used when message previews are on.
     */
    fun notify(
        kind: NotificationText.Kind,
        name: String,
        photo: String?,
        chatID: String,
        muted: Boolean,
        preview: String? = null,
    ) {
        if (!isAllowed || muted) return
        val channel = when (kind) {
            NotificationText.Kind.Message, is NotificationText.Kind.SessionProposed, is NotificationText.Kind.SessionAccepted,
            is NotificationText.Kind.SessionDeclined, is NotificationText.Kind.SessionCancelled -> {
                if (!messages) return
                if (kind == NotificationText.Kind.Message) LocalNotifications.Channel.MESSAGES else LocalNotifications.Channel.SESSIONS
            }
            NotificationText.Kind.Like, NotificationText.Kind.SuperLike -> {
                if (!likes) return
                LocalNotifications.Channel.LIKES
            }
            NotificationText.Kind.Match -> {
                if (!matches) return
                LocalNotifications.Channel.MATCHES
            }
            is NotificationText.Kind.Reaction -> {
                if (!messages || !reactions) return
                LocalNotifications.Channel.MESSAGES
            }
        }
        val body = when {
            kind == NotificationText.Kind.Message && messagePreviews && preview != null ->
                NotificationText.preview(preview, name, language)
            // Previews off: which message it was stays in the app.
            kind is NotificationText.Kind.Reaction && !messagePreviews ->
                NotificationText.body(NotificationText.Kind.Reaction(kind.emoji, null), name, language)
            else -> NotificationText.body(kind, name, language)
        }
        platform.post(
            LocalNotifications.Notification(
                title = NotificationText.title(kind, name, language),
                body = body,
                channel = channel,
                threadID = chatID,
                info = mapOf("chatID" to chatID),
                photo = photo,
            ),
        )
    }

    // Pushes and taps

    /**
     * A push arrived while the app is running (`DrafftMessagingService.onMessageReceived`, the iPhone's
     * `willPresent`). Whether the system banner should show; with true, [present] posts it.
     */
    suspend fun willPresent(info: Map<String, String>): Boolean {
        val kind = info["kind"]
        Telemetry.track(AnalyticsEvent.PushReceived(pushKind(info), inForeground = true))
        // A refused photo while the app is open: its own banner says it, not the system's (shown once,
        // whether the push or the live `media` event comes first).
        if (kind == "photo_refused") {
            info["media"]?.let { photoModeration.apply(mediaID = it, status = "rejected") }
            return false
        }
        // Moderation news (a hold lifted, a selfie asked for): the open app's screen already changed
        // live (Realtime `moderation`), so the system banner would say it twice.
        if (kind == "moderation") return false
        // A session changed while the app is open: the push says it, the cards follow (the Realtime event
        // usually got there first; this read catches a missed one).
        if (kind == "session_cancelled" || kind == "session_reminder") sessions.refresh()
        return true
    }

    /**
     * Shows a push as a notification (Android only shows a push's notification by itself while the app
     * is in the background). [title] and [body] are the push's own words, already in the person's
     * language (the server's `texts.ts`).
     */
    fun present(title: String, body: String, info: Map<String, String>) {
        if (!isAllowed) return
        platform.post(
            LocalNotifications.Notification(
                title = title,
                body = body,
                channel = channel(info),
                threadID = chatID(info),
                info = info,
            ),
        )
    }

    /** A notification was tapped (from `MainActivity`, with the notification's data). */
    fun didReceive(info: Map<String, String>) {
        val kind = info["kind"]
        Telemetry.track(AnalyticsEvent.PushOpened(pushKind(info)))
        if (kind == "photo_refused") {
            info["media"]?.let { photoModeration.openRefusal(mediaID = it) }
            return
        }
        // Opening the app is enough: it shows the screen of the account's current state.
        if (kind == "moderation") return
        if (kind == "weekly_boost") {
            openBoost = true
            return
        }
        val chatID = chatID(info)
        if (kind == "session_cancelled") {
            // Cancelled with its match (no chat any more): the Sessions tab, read again.
            scope.launch { sessions.refresh() }
            if (chatID == null) {
                openSessions = true
                return
            }
        }
        openChatID = chatID
    }

    /** The push's kind as a code (`new_message` for Stream's chat pushes, `local` for the app's own). */
    private fun pushKind(info: Map<String, String>): String = when {
        info["kind"] != null -> info.getValue("kind").lowercase()
        info["sender"] == "stream.chat" -> "new_message"
        info["chatID"] != null -> "local"
        else -> "unknown"
    }

    /**
     * Server pushes name the match (its chat); the app's own name the chat. Stream's message pushes
     * (`sender: stream.chat`) name the channel, whose id is the match's.
     */
    private fun chatID(info: Map<String, String>): String? =
        info["chatID"] ?: info["match"]?.lowercase()
            ?: info["channel_id"]?.takeIf { info["sender"] == "stream.chat" }?.lowercase()

    private fun channel(info: Map<String, String>): LocalNotifications.Channel = when (info["kind"]) {
        "match" -> LocalNotifications.Channel.MATCHES
        "like", "super_like" -> LocalNotifications.Channel.LIKES
        "photo_refused", "moderation", "weekly_boost" -> LocalNotifications.Channel.ACCOUNT
        else -> if (info["kind"]?.startsWith("session") == true) LocalNotifications.Channel.SESSIONS else LocalNotifications.Channel.MESSAGES
    }

    private companion object {
        const val SETTINGS_KEY = "notificationSettings"
        const val UNSENT_KEY = "notificationSettingsUnsent"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The notification settings and the app's language, as saved on the profile (`profiles` columns). */
@Serializable
data class NotificationSettings(
    /** The app language's code (`en`, `fr`...). */
    val language: String,
    @SerialName("notify_matches") val matches: Boolean,
    @SerialName("notify_likes") val likes: Boolean,
    @SerialName("notify_messages") val messages: Boolean,
    @SerialName("notify_message_previews") val messagePreviews: Boolean,
    @SerialName("notify_reactions") val reactions: Boolean,
    @SerialName("notify_session_evening") val sessionEvening: Boolean,
    @SerialName("notify_session_hour_before") val sessionHourBefore: Boolean,
    @SerialName("notify_weekly_boost") val weeklyBoost: Boolean,
) {
    /** The PATCH body. */
    val fields: JsonObject
        get() = JsonObject(
            mapOf(
                "language" to JsonPrimitive(language),
                "notify_matches" to JsonPrimitive(matches),
                "notify_likes" to JsonPrimitive(likes),
                "notify_messages" to JsonPrimitive(messages),
                "notify_message_previews" to JsonPrimitive(messagePreviews),
                "notify_reactions" to JsonPrimitive(reactions),
                "notify_session_evening" to JsonPrimitive(sessionEvening),
                "notify_session_hour_before" to JsonPrimitive(sessionHourBefore),
                "notify_weekly_boost" to JsonPrimitive(weeklyBoost),
            ),
        )

    companion object {
        val columns: String = listOf(
            "language", "notify_matches", "notify_likes", "notify_messages", "notify_message_previews",
            "notify_reactions", "notify_session_evening", "notify_session_hour_before", "notify_weekly_boost",
        ).joinToString(",")
    }
}

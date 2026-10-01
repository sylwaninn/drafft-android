package so.drafft.core.data.chat

import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import so.drafft.core.data.AppModel
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.media.EdgeFunctionTicketProvider
import so.drafft.core.data.media.MediaPurpose
import so.drafft.core.data.media.MediaURL
import so.drafft.core.data.media.MediaUploads
import so.drafft.core.data.media.VideoCompressor
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.sessions.from
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.Conversation
import so.drafft.core.model.DeliveryState
import so.drafft.core.model.Message
import so.drafft.core.model.MessageContent
import so.drafft.core.model.SessionProposal

/**
 * The chats as the app shows them: the matches (`AppModel.matches`, from `my_matches`), their Stream
 * channels and messages, the sessions' rows (`SessionStore`) and the signed media links, turned into
 * `Conversation`s and `Message`s. Ports Drafft/Services/Chat/ChatService+Model.swift, plus the parts of
 * ChatService.swift that don't touch the SDK (pending invites, uploads, the link cache).
 *
 * Owned by `StreamChatService`, which fills [channels] and [history] from the SDK. Not thread-safe:
 * every call is made on the main thread, and [scope] runs on `Dispatchers.Main.immediate`.
 */
class ChatThreads(
    private val sessions: SessionStore,
    private val backend: Backend,
    private val mediaCheck: ChatMediaCheck,
    private val scope: CoroutineScope,
) {
    private val log = Logger.getLogger("so.drafft.chat")

    var app: AppModel? = null

    /** The account connected (or connecting), lowercased. */
    var userID: String? = null

    /** The Stream channels, by match. */
    val channels = mutableMapOf<String, ChatChannel>()

    /** Open chats: their whole loaded history, followed live. */
    val history = mutableMapOf<String, List<ChatMessage>>()

    /** Invites sent from this device, shown until the channel's message for them arrives (by match). */
    val pendingSessions = mutableMapOf<String, MutableList<Message>>()

    /** Session rows already asked for by a card. */
    val requestedSessions = mutableSetOf<UUID>()

    /**
     * Media being prepared and uploaded, and text written before the chat was connected: not yet a Stream
     * message (by match).
     */
    val uploads = mutableMapOf<String, MutableList<Upload>>()

    /** What this device sent (its own picture, video file, recording): shown instead of the downloaded copy. */
    val localMedia = mutableMapOf<String, MessageContent>()

    /** Signed links by object key. */
    val links = mutableMapOf<String, SignedLink>()
    private val signing = linkedSetOf<String>()
    private var signJob: Job? = null

    data class SignedLink(val url: String, val expires: Instant?)

    data class Upload(val message: Message, val matchID: String, val source: Source) {
        sealed interface Source {
            /** A text: sent by the chat service once connected (never dropped meanwhile). */
            data class Text(val text: String) : Source

            data class Photo(val data: ByteArray) : Source {
                override fun equals(other: Any?) = other is Photo && other.data.contentEquals(data)
                override fun hashCode() = data.contentHashCode()
            }

            /** A local video file (path or URI). */
            data class Video(val url: String) : Source

            /** A local recording: its file, seconds and waveform. */
            data class Voice(val url: String, val duration: Double, val levels: List<Float>) : Source
        }
    }

    /** Signed out: everything forgotten. */
    fun reset() {
        signJob?.cancel()
        signJob = null
        channels.clear()
        history.clear()
        pendingSessions.clear()
        requestedSessions.clear()
        uploads.clear()
        localMedia.clear()
        links.clear()
        signing.clear()
        userID = null
    }

    // Building the app's model

    /** The chats as the app shows them, in order: latest activity first. */
    fun publish() {
        val app = app ?: return
        val conversations = mutableListOf<Conversation>()
        val missingSessions = mutableSetOf<UUID>()
        for (match in app.matches) {
            val matchID = match.id
            val channel = channels[matchID]
            if (channel?.isFrozen == true) continue
            // One card per session: the channel's message for it (db-events posts one per proposal).
            val shown = mutableSetOf<UUID>()
            val messages = (history[matchID] ?: channel?.latestMessages ?: emptyList()).mapNotNull { m ->
                val reference = sessionReference(m)
                if (reference is ChatPayload.Kind.Session) {
                    if (!shown.add(reference.id)) return@mapNotNull null
                    if (sessions.record(reference.id) == null) missingSessions += reference.id
                }
                map(m, matchID)
            }.toMutableList()
            // An invite on its way: its card at once, until the channel's own message for it arrives.
            pendingSessions[matchID]?.removeAll { m ->
                val session = m.content as? MessageContent.Session ?: return@removeAll true
                (sessions.record(session.proposal.id)?.id ?: session.proposal.id) in shown
            }
            messages += pendingSessions[matchID].orEmpty()
            val sent = messages.mapTo(HashSet()) { it.id }
            messages += uploads[matchID].orEmpty().map { it.message }.filter { it.id !in sent }
            messages.sortBy { it.date }
            val old = app.conversation(matchID)
            val other = channel?.lastActiveMembers?.firstOrNull { it.id != userID }
            val open = app.openChatID == matchID
            conversations += Conversation(
                id = matchID,
                profile = match.profile,
                messages = messages,
                isTyping = channel?.currentlyTypingUserIDs?.any { it != userID } ?: false,
                unread = if (open) 0 else channel?.unreadMessages ?: 0,
                markedUnread = (old?.markedUnread ?: false) && !open,
                matchedAt = match.matchedAt,
                muted = channel?.isMuted ?: false,
                online = other?.isOnline ?: false,
            )
        }
        conversations.sortByDescending { it.lastMessage?.date ?: it.matchedAt }
        if (app.conversations != conversations) app.conversations = conversations
        // Cards whose row isn't known yet: read once, then shown.
        missingSessions -= requestedSessions
        if (missingSessions.isNotEmpty()) {
            requestedSessions += missingSessions
            scope.launch {
                sessions.load(missingSessions)
                publish()
            }
        }
    }

    fun sessionReference(m: ChatMessage): ChatPayload.Kind? {
        val extra = m.extra ?: return null
        return ChatPayload.kind(m.text, extra)
    }

    /** One Stream message as the thread shows it, or null (deleted, system, a session's later status). */
    fun map(m: ChatMessage, matchID: String): Message? {
        if (m.type in ChatMessage.hiddenTypes || m.deletedAt != null || m.isShadowed) return null
        val media = m.media
        val base = if (media != null) {
            Message(
                id = m.id,
                content = localMedia[m.id] ?: content(media),
                fromMe = m.isSentByCurrentUser,
                date = m.createdAt,
                mediaSize = if (media.width != null && media.height != null) {
                    Message.PixelSize(media.width.toDouble(), media.height.toDouble())
                } else null,
                poster = media.posterKey?.let(::link),
            )
        } else {
            val content = content(m, matchID) ?: return null
            Message(id = m.id, content = content, fromMe = m.isSentByCurrentUser, date = m.createdAt)
        }
        val quoted = m.quotedMessage
        return base.copy(
            state = state(m, matchID),
            reaction = m.latestReactions.firstOrNull { it.authorID != m.authorID }?.type,
            replyTo = quoted?.id ?: base.replyTo,
            replyQuote = quoted?.let {
                Message.Quote(fromMe = it.isSentByCurrentUser, text = map(it, matchID)?.previewText ?: it.text)
            },
        )
    }

    /** A message without media: text, a session card, an opener; null when it isn't shown. */
    fun content(m: ChatMessage, matchID: String): MessageContent? =
        when (val kind = ChatPayload.kind(m.text, m.extra)) {
            is ChatPayload.Kind.Text -> if (kind.text.isEmpty()) null else MessageContent.Text(kind.text)
            // The card reads the live row (`SessionStore`); this copy stands in until it redraws.
            is ChatPayload.Kind.Session ->
                sessions.record(kind.id)?.let { SessionProposal.from(it) }?.let { MessageContent.Session(it) }
            is ChatPayload.Kind.IcebreakerReply -> MessageContent.IcebreakerReply(quote = kind.quote, reply = kind.reply)
            is ChatPayload.Kind.PhotoReply -> {
                val liked = app?.matches?.firstOrNull { it.id == matchID }?.profile?.allPhotos
                    ?.firstOrNull { MediaURL.key(it) == kind.key }
                MessageContent.PhotoReply(asset = liked ?: link(kind.key) ?: "", reply = kind.reply)
            }
            ChatPayload.Kind.Hidden -> null
        }

    fun state(m: ChatMessage, matchID: String): DeliveryState {
        when (m.localState) {
            ChatMessage.LocalState.PENDING_SEND, ChatMessage.LocalState.SENDING -> return DeliveryState.SENDING
            ChatMessage.LocalState.SENDING_FAILED -> return DeliveryState.FAILED
            null -> Unit
        }
        if (!m.isSentByCurrentUser) return DeliveryState.READ
        val read = channels[matchID]?.reads?.firstOrNull { it.userID != userID }
        if (read != null && read.lastReadAt >= m.createdAt) return DeliveryState.READ
        val delivered = read?.lastDeliveredAt
        if (delivered != null && delivered >= m.createdAt) return DeliveryState.DELIVERED
        return DeliveryState.SENT
    }

    fun content(media: ChatPayload.Media): MessageContent {
        val url = link(media.key)
        return when (media.kind) {
            ChatPayload.Media.Kind.PHOTO -> MessageContent.Photo(asset = url, imageData = null)
            ChatPayload.Media.Kind.VIDEO ->
                MessageContent.Video(url = url ?: ChatService.UNSIGNED, thumbnail = null, duration = media.duration ?: 0.0)
            ChatPayload.Media.Kind.VOICE ->
                MessageContent.Voice(url = url ?: ChatService.UNSIGNED, duration = media.duration ?: 0.0, levels = media.levels ?: emptyList())
        }
    }

    /**
     * The signed link of an object, while it has more than a minute left; otherwise asked for (in one
     * request with the others missing), and the thread updates when it comes.
     */
    fun link(key: String): String? {
        val known = links[key]
        if (known != null && (known.expires ?: Instant.MAX) > Instant.now().plusSeconds(60)) return known.url
        if (signing.add(key) && signJob?.isActive != true) signJob = scope.launch { sign() }
        return known?.url
    }

    private suspend fun sign() {
        // Let the rest of this pass ask too: one request for all of them.
        yield()
        while (signing.isNotEmpty()) {
            val keys = signing.take(100)
            val signed = MediaURL.signed(keys)
            for (key in keys) {
                signing.remove(key)
                signed[key]?.let { links[key] = SignedLink(it, MediaURL.expiry(it)) }
            }
            if (signed.isNotEmpty()) publish()
        }
    }

    // Pending invites

    fun showPending(proposal: SessionProposal, matchID: String) {
        pendingSessions.getOrPut(matchID) { mutableListOf() } +=
            Message(content = MessageContent.Session(proposal), fromMe = true, state = DeliveryState.SENDING)
        publish()
    }

    fun dropPending(sessionID: UUID, matchID: String) {
        pendingSessions[matchID]?.removeAll { (it.content as? MessageContent.Session)?.proposal?.id == sessionID }
        publish()
    }

    // Uploads

    fun pendingMessage(id: String, content: MessageContent, replyTo: String?): Message {
        localMedia[id] = content
        return Message(id = id, content = content, fromMe = true, state = DeliveryState.SENDING, replyTo = replyTo)
    }

    /**
     * Prepares and uploads a photo, video or voice message to drafft's bucket, then hands its payload to
     * [send] (the Stream message with the `drafft_media` attachment, same id as the pending bubble).
     */
    fun startUpload(upload: Upload, send: suspend (Upload, ChatPayload.Media) -> Unit) {
        val list = uploads.getOrPut(upload.matchID) { mutableListOf() }
        list.removeAll { it.message.id == upload.message.id }
        val current = upload.copy(message = upload.message.copy(state = DeliveryState.SENDING))
        list += current
        publish()
        val tickets = EdgeFunctionTicketProvider(backend.config.functionsURL, accessToken = { backend.accessToken() })
        scope.launch {
            try {
                // Timed (Sentry Performance): compression and upload, the slow part of a media message.
                val media = Telemetry.trace("media.upload", "chat ${current.source.telemetryKind.name.lowercase()}") {
                    when (val source = current.source) {
                        is Upload.Source.Text -> error("a text goes through the chat service")
                        is Upload.Source.Photo -> {
                            val sent = MediaUploads.photo(source.data, purpose = MediaPurpose.CHAT_PHOTO, tickets = tickets)
                            ChatPayload.Media(
                                kind = ChatPayload.Media.Kind.PHOTO, key = sent.key, width = sent.width, height = sent.height,
                                thumbhash = sent.thumbHash,
                            )
                        }
                        is Upload.Source.Video -> {
                            val sent = MediaUploads.video(
                                source.url, purpose = MediaPurpose.CHAT_VIDEO, settings = VideoCompressor.Settings.chat,
                                posterPurpose = MediaPurpose.CHAT_PHOTO, tickets = tickets,
                            )
                            ChatPayload.Media(
                                kind = ChatPayload.Media.Kind.VIDEO, key = sent.key, width = sent.width, height = sent.height,
                                duration = sent.duration, posterKey = sent.posterKey, thumbhash = sent.thumbHash,
                            )
                        }
                        is Upload.Source.Voice -> {
                            val key = MediaUploads.voice(source.url, purpose = MediaPurpose.CHAT_VOICE, tickets = tickets)
                            ChatPayload.Media(
                                kind = ChatPayload.Media.Kind.VOICE, key = key, duration = source.duration,
                                levels = ChatService.compact(source.levels),
                            )
                        }
                    }
                }
                // Only the person's own chat objects are ever sent (keys, never links).
                val me = userID
                check(
                    me != null && ChatPayload.Media.isOwnChatKey(media.key, me) &&
                        (media.posterKey?.let { ChatPayload.Media.isOwnChatKey(it, me) } ?: true),
                ) { "not an own chat key" }
                send(current, media)
                uploads[current.matchID]?.removeAll { it.message.id == current.message.id }
                publish()
                // Photos and videos get the silent check (flagged ones are only recorded server-side).
                if (media.kind != ChatPayload.Media.Kind.VOICE) {
                    scope.launch { mediaCheck.check(media.key, media.posterKey) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "media send failed: ${e.message}")
                val kind = current.source.telemetryKind
                Telemetry.track(AnalyticsEvent.MessageFailed(kind, Telemetry.reason(e)))
                Telemetry.unexpected(e, "chat", "send_media", mapOf("kind" to kind))
                val pending = uploads[current.matchID] ?: return@launch
                val i = pending.indexOfFirst { it.message.id == current.message.id }
                if (i >= 0) {
                    pending[i] = pending[i].copy(message = pending[i].message.copy(state = DeliveryState.FAILED))
                    publish()
                }
            }
        }
    }

    /** A text's bubble, sending, until Stream's own copy (same id) shows or [textSent] says how it went. */
    fun queueText(upload: Upload) {
        val list = uploads.getOrPut(upload.matchID) { mutableListOf() }
        list.removeAll { it.message.id == upload.message.id }
        list += upload.copy(message = upload.message.copy(state = DeliveryState.SENDING))
        publish()
    }

    /** Stream has the text: its copy shows from now on. Not [sent]: Stream's failed copy, or this bubble, offers the retry. */
    fun textSent(upload: Upload, sent: Boolean) {
        val pending = uploads[upload.matchID] ?: return
        if (sent) {
            pending.removeAll { it.message.id == upload.message.id }
        } else {
            val i = pending.indexOfFirst { it.message.id == upload.message.id }
            if (i >= 0) pending[i] = pending[i].copy(message = pending[i].message.copy(state = DeliveryState.FAILED))
        }
        publish()
    }

    /** The texts written while the chat wasn't connected, in the order they were written. */
    fun waitingTexts(): List<Upload> = uploads.values.flatten().filter { it.source is Upload.Source.Text }

    /** Unsend an upload not sent yet: it just stops showing. True when [messageID] was one. */
    fun dropUpload(messageID: String, matchID: String): Boolean {
        val removed = uploads[matchID]?.removeAll { it.message.id == messageID } ?: false
        if (removed) publish()
        return removed
    }

    fun upload(messageID: String, matchID: String): Upload? = uploads[matchID]?.firstOrNull { it.message.id == messageID }
}

/** What kind of message an upload makes, for analytics. */
private val ChatThreads.Upload.Source.telemetryKind: AnalyticsEvent.MessageKind
    get() = when (this) {
        is ChatThreads.Upload.Source.Photo -> AnalyticsEvent.MessageKind.PHOTO
        is ChatThreads.Upload.Source.Video -> AnalyticsEvent.MessageKind.VIDEO
        is ChatThreads.Upload.Source.Voice -> AnalyticsEvent.MessageKind.VOICE
        is ChatThreads.Upload.Source.Text -> AnalyticsEvent.MessageKind.TEXT
    }

package so.drafft.core.data.chat

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.api.models.QueryChannelsRequest
import io.getstream.chat.android.client.channel.state.ChannelState
import io.getstream.chat.android.client.events.ConnectedEvent
import io.getstream.chat.android.client.events.NewMessageEvent
import io.getstream.chat.android.client.logger.ChatLogLevel
import io.getstream.chat.android.client.subscribeFor
import io.getstream.chat.android.client.token.TokenProvider
import io.getstream.chat.android.client.utils.observable.Disposable
import io.getstream.chat.android.models.Attachment
import io.getstream.chat.android.models.Channel
import io.getstream.chat.android.models.Device
import io.getstream.chat.android.models.Filters
import io.getstream.chat.android.models.PushProvider
import io.getstream.chat.android.models.Reaction
import io.getstream.chat.android.models.SyncStatus
import io.getstream.chat.android.models.TypingEvent
import io.getstream.chat.android.models.User
import io.getstream.chat.android.models.querysort.QuerySortByField
import io.getstream.chat.android.offline.plugin.factory.StreamOfflinePluginFactory
import io.getstream.chat.android.state.extensions.globalState
import io.getstream.chat.android.state.extensions.loadOlderMessages
import io.getstream.chat.android.state.extensions.queryChannelsAsState
import io.getstream.chat.android.state.extensions.watchChannelAsState
import io.getstream.chat.android.state.plugin.config.StatePluginConfig
import io.getstream.chat.android.state.plugin.factory.StreamStatePluginFactory
import io.getstream.result.Result
import io.getstream.result.call.Call
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import so.drafft.core.data.AppModel
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.notifications.NotificationText
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.model.MessageContent
import so.drafft.core.model.SessionProposal
import io.getstream.chat.android.models.Message as StreamMessage

/**
 * The chat, on Stream (low-level client, drafft's own screens). One connection per signed-in account.
 * Ports Drafft/Services/Chat/ChatService.swift; the model building (ChatService+Model.swift) is
 * [ChatThreads], shared and SDK-free.
 *
 * - Connection: `stream-token` gives the key and a 24 h token; the SDK asks for a new one through the
 *   token provider before it expires, reconnects by itself (network, foreground), keeps an offline copy
 *   (the last known chats show at launch) and catches up on reconnect.
 * - Chats: one per active match (`AppModel.matches`, read from `my_matches` and kept live by Realtime
 *   `match` / `match_ended`, the source of truth: an ended match, whose channel the server froze, is gone
 *   at once), joined with its Stream channel. The list and every open chat follow Stream's events live
 *   (the state plugin's StateFlows).
 * - Messages: optimistic (the SDK's local copy, then the server's). Media is uploaded to drafft's bucket
 *   first (`media-upload-url`, purpose chat) and sent as a `drafft_media` attachment carrying the object's
 *   key; links are signed on display (`media_urls`).
 * - Sessions live in Postgres (`sessions`, `SessionStore`): the chat carries one card per session, from
 *   the channel's `drafft.type: "session"` message, showing the live row.
 *
 * Everything here runs on the main thread ([scope] is `Dispatchers.Main.immediate`); SDK callbacks hop
 * onto it before touching any state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamChatService(
    context: Context,
    private val backend: Backend,
    private val sessions: SessionStore,
    mediaCheck: ChatMediaCheck,
    private val notifications: NotificationService,
) : ChatService {
    private val context = context.applicationContext
    private val log = Logger.getLogger("so.drafft.chat")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val threads = ChatThreads(sessions, backend, mediaCheck, scope)

    override var app: AppModel?
        get() = threads.app
        set(value) {
            threads.app = value
        }

    override val userID: String? get() = threads.userID

    private var client: ChatClient? = null
    private var apiKey: String? = null
    private var connecting: Job? = null
    private var connected = false

    /** Channel list, events, mutes and typing: everything followed for the account. */
    private var watching: Job? = null
    private var subscriptions: List<Disposable> = emptyList()

    /** The channel list's channels (not frozen, still a member), by match. */
    private var listed: Map<String, Channel> = emptyMap()

    /** Open chats, followed live until closed. */
    private val chats = mutableMapOf<String, OpenChat>()
    private var mutedChannels: Set<String> = emptySet()
    private var typingChannels: Map<String, TypingEvent> = emptyMap()

    /** Push token, kept until the client is connected. */
    private var deviceToken: String? = null

    private class OpenChat {
        var state: ChannelState? = null
        var job: Job? = null
        var messages: List<StreamMessage> = emptyList()
    }

    // Connection

    override suspend fun start(app: AppModel) {
        this.app = app
        val me = backend.userID?.toString()?.lowercase() ?: return
        if (threads.userID == me) {
            publish()
            return
        }
        if (threads.userID != null) stop()
        threads.userID = me
        publish()
        connecting = scope.launch { connect(me) }
    }

    override suspend fun stop() {
        connecting?.cancel()
        connecting = null
        watching?.cancel()
        watching = null
        subscriptions.forEach { it.dispose() }
        subscriptions = emptyList()
        chats.values.forEach { it.job?.cancel() }
        chats.clear()
        listed = emptyMap()
        mutedChannels = emptySet()
        typingChannels = emptyMap()
        threads.reset()
        connected = false
        publish()
        val client = client ?: return
        // This device stops getting the account's pushes, and the offline copy goes with it.
        deviceToken?.let { token ->
            runCatching { client.deleteDevice(device(token)).value() }
                .onFailure { log.log(Level.WARNING, "chat push device removal failed: ${it.message}") }
        }
        runCatching { client.disconnect(flushPersistence = true).value() }
    }

    private suspend fun connect(me: String) {
        var pause = 2.seconds
        while (currentCoroutineContext().isActive && threads.userID == me && !connected) {
            try {
                val first = fetchToken()
                val client = client(first.apiKey)
                val tokens = TokenSource(first.token)
                client.connectUser(User(id = me), tokens).value()
                if (threads.userID != me || !currentCoroutineContext().isActive) return
                connected = true
                watchChannels(client, me)
                deviceToken?.let(::addDevice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "chat connect failed: ${e.message}")
                delay(pause)
                pause = minOf(pause * 2, 60.seconds)
            }
        }
    }

    /** One client per Stream app (the key comes with the token, per environment). */
    private fun client(key: String): ChatClient {
        client?.takeIf { apiKey == key }?.let { return it }
        val offline = StreamOfflinePluginFactory(appContext = context)
        val state = StreamStatePluginFactory(
            config = StatePluginConfig(backgroundSyncEnabled = true, userPresence = true),
            appContext = context,
        )
        val made = ChatClient.Builder(key, context)
            .withPlugins(offline, state)
            .logLevel(ChatLogLevel.NOTHING)
            .build()
        client = made
        apiKey = key
        return made
    }

    @Serializable
    private data class StreamToken(val apiKey: String, val userId: String, val token: String)

    private suspend fun fetchToken(): StreamToken {
        val data = backend.function("stream-token", JsonObject(emptyMap()))
        return ChatPayload.json.decodeFromString(data.decodeToString())
    }

    /**
     * The first token (already fetched to learn the key), then a fresh one each time the SDK asks. The SDK
     * calls [loadToken] on its own background thread and waits for it.
     */
    private inner class TokenSource(first: String) : TokenProvider {
        private val first = AtomicReference<String?>(first)
        override fun loadToken(): String = first.getAndSet(null) ?: runBlocking { fetchToken().token }
    }

    private suspend fun watchChannels(client: ChatClient, me: String) {
        val request = QueryChannelsRequest(
            filter = Filters.and(Filters.`in`("members", listOf(me)), Filters.eq("frozen", false)),
            offset = 0,
            limit = PAGE,
            querySort = QuerySortByField.descByName("last_message_at"),
            // Enough of each chat for the list and the first screen of a thread.
            messageLimit = 25,
            memberLimit = 2,
        ).apply {
            watch = true
            state = true
            presence = true
        }
        val list = client.queryChannelsAsState(request = request, coroutineScope = scope)
        watching?.cancel()
        watching = scope.launch {
            launch {
                list.filterNotNull().flatMapLatest { it.channels }.collect { channels ->
                    if (channels != null) channelsChanged(channels)
                }
            }
            launch {
                val global = client.globalState
                combine(global.channelMutes, global.typingChannels) { mutes, typing -> mutes to typing }
                    .collect { (mutes, typing) ->
                        mutedChannels = mutes.mapNotNull { it.channel?.cid }.toSet()
                        typingChannels = typing
                        rebuild()
                    }
            }
        }
        subscriptions.forEach { it.dispose() }
        subscriptions = listOf(
            client.subscribeFor<NewMessageEvent> { event ->
                scope.launch { received(event.message, event.channelId) }
            },
            // Back online: matches and sessions may have changed meanwhile (Stream catches up by itself).
            client.subscribeFor<ConnectedEvent> { scope.launch { refresh() } },
        )
        // Every chat, not just the first page: a person has few enough matches.
        val state = list.filterNotNull().first()
        for (page in 1..20) {
            state.loading.first { !it }
            if (state.endOfChannels.value || (state.channels.value?.size ?: 0) < PAGE * page) break
            val next = state.nextPageRequest.value ?: break
            client.queryChannels(next).value()
        }
    }

    // Push

    override fun registerDevice(token: String) {
        deviceToken = token
        if (connected) addDevice(token)
    }

    private fun device(token: String) =
        Device(token = token, pushProvider = PushProvider.FIREBASE, providerName = ChatService.PUSH_PROVIDER)

    private fun addDevice(token: String) {
        val client = client ?: return
        scope.launch {
            runCatching { client.addDevice(device(token)).value() }
                .onFailure { log.log(Level.WARNING, "chat push device: ${it.message}") }
        }
    }

    // Matches and sessions

    override suspend fun refresh() {
        app?.loadMatches()
        sessions.refresh()
        publish()
    }

    override fun publish() = threads.publish()

    override fun showPending(proposal: SessionProposal, matchID: String) = threads.showPending(proposal, matchID)

    override fun dropPending(sessionID: UUID, matchID: String) = threads.dropPending(sessionID, matchID)

    // Open chats

    override fun open(matchID: String) {
        val client = client ?: return
        if (chats.containsKey(matchID)) return
        val flow = client.watchChannelAsState(cid = cid(matchID), messageLimit = PAGE, coroutineScope = scope)
        val chat = OpenChat()
        chats[matchID] = chat
        // Registered before it starts: on the main thread, the launch runs at once up to its first suspension.
        chat.job = scope.launch {
            val state = flow.filterNotNull().first()
            chat.state = state
            launch {
                state.loading.first { !it }
                markRead(matchID)
            }
            combine(state.messages, state.reads, state.members, state.unreadCount, state.typing) { _, _, _, _, _ -> }
                .collect {
                    chat.messages = state.messages.value
                    rebuild()
                }
        }
    }

    override fun close(matchID: String) {
        chats.remove(matchID)?.job?.cancel()
        threads.history.remove(matchID)
        val client = client
        if (client != null) {
            scope.launch { runCatching { client.channel(TYPE, matchID).stopTyping().value() } }
        }
        rebuild()
    }

    override fun loadOlder(matchID: String) {
        val client = client ?: return
        val state = chats[matchID]?.state ?: return
        if (state.endOfOlderMessages.value) return
        scope.launch { runCatching { client.loadOlderMessages(cid(matchID), PAGE).value() } }
    }

    // Actions

    override fun send(content: MessageContent, matchID: String, replyTo: String?) {
        val id = UUID.randomUUID().toString().lowercase()
        when (content) {
            is MessageContent.Text -> {
                val client = client ?: return
                val message = StreamMessage(id = id, cid = cid(matchID), text = content.text, replyMessageId = replyTo)
                scope.launch {
                    try {
                        client.channel(TYPE, matchID).sendMessage(message).value()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.log(Level.WARNING, "send failed: ${e.message}")
                    }
                }
            }
            is MessageContent.Photo -> {
                val data = content.imageData ?: return
                startUpload(ChatThreads.Upload(threads.pendingMessage(id, content, replyTo), matchID, ChatThreads.Upload.Source.Photo(data)))
            }
            is MessageContent.Video ->
                startUpload(ChatThreads.Upload(threads.pendingMessage(id, content, replyTo), matchID, ChatThreads.Upload.Source.Video(content.url)))
            is MessageContent.Voice ->
                startUpload(
                    ChatThreads.Upload(
                        threads.pendingMessage(id, content, replyTo), matchID,
                        ChatThreads.Upload.Source.Voice(content.url, content.duration, content.levels),
                    ),
                )
            is MessageContent.Session -> app?.proposeSession(content.proposal, matchID)
            else -> Unit
        }
    }

    private fun startUpload(upload: ChatThreads.Upload) {
        threads.startUpload(upload) { sent, media ->
            val client = client ?: throw IllegalStateException("chat not connected")
            val message = StreamMessage(
                id = sent.message.id,
                cid = cid(sent.matchID),
                text = "",
                // Mutable collections fit both the SDK's older `MutableList`/`MutableMap` fields and the newer read-only ones.
                attachments = mutableListOf(Attachment(type = ChatPayload.Media.TYPE, extraData = media.extraData().toMutableMap())),
                replyMessageId = sent.message.replyTo,
            )
            client.channel(TYPE, sent.matchID).sendMessage(message).value()
        }
    }

    override fun retry(messageID: String, matchID: String) {
        val upload = threads.upload(messageID, matchID)
        if (upload != null) {
            startUpload(upload)
            return
        }
        val client = client ?: return
        val message = streamMessages(matchID).firstOrNull { it.id == messageID } ?: return
        scope.launch { runCatching { client.channel(TYPE, matchID).sendMessage(message, isRetrying = true).value() } }
    }

    override fun react(emoji: String?, messageID: String, current: String?, matchID: String) {
        val client = client ?: return
        val channel = client.channel(TYPE, matchID)
        scope.launch {
            runCatching {
                if (current != null && (emoji == null || emoji == current)) {
                    channel.deleteReaction(messageID, current).value()
                } else if (emoji != null) {
                    channel.sendReaction(Reaction(messageId = messageID, type = emoji, score = 1), enforceUnique = true).value()
                }
            }
        }
    }

    override fun delete(messageID: String, matchID: String) {
        if (threads.dropUpload(messageID, matchID)) return
        val client = client ?: return
        scope.launch { runCatching { client.channel(TYPE, matchID).deleteMessage(messageID).value() } }
    }

    override suspend fun markRead(matchID: String) {
        val client = client ?: return
        val channel = threads.channels[matchID] ?: return
        if (channel.unreadMessages <= 0 && !channel.isUnread) return
        runCatching { client.channel(TYPE, matchID).markRead().value() }
    }

    override fun markUnread(matchID: String) {
        val client = client ?: return
        val last = streamMessages(matchID)
            .filter { it.user.id != threads.userID }
            .maxByOrNull { date(it) } ?: return
        scope.launch { runCatching { client.channel(TYPE, matchID).markUnread(last.id).value() } }
    }

    override fun typing(matchID: String, text: String) {
        val client = client ?: return
        if (!chats.containsKey(matchID)) return
        val channel = client.channel(TYPE, matchID)
        scope.launch {
            runCatching { if (text.isEmpty()) channel.stopTyping().value() else channel.keystroke().value() }
        }
    }

    override fun toggleMute(matchID: String) {
        val client = client ?: return
        val channel = threads.channels[matchID] ?: return
        val muted = channel.isMuted
        val channelClient = client.channel(TYPE, matchID)
        scope.launch { runCatching { if (muted) channelClient.unmute().value() else channelClient.mute().value() } }
    }

    // Events

    /**
     * A message arrived. From the other person, while that chat isn't on screen: a notification (the
     * server's own messages, openers and sessions, come with their own push).
     */
    private fun received(message: StreamMessage, matchID: String) {
        if (message.user.id == threads.userID) return
        val app = app ?: return
        val match = app.matches.firstOrNull { it.id == matchID } ?: return
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (app.openChatID == matchID && foreground) {
            Haptics.tap()
            scope.launch { markRead(matchID) }
            return
        }
        if (message.extraData["drafft"] != null) return
        val mapped = threads.map(snapshot(message), matchID) ?: return
        val profile = match.profile
        val muted = threads.channels[matchID]?.isMuted ?: false
        scope.launch {
            notifications.notify(
                NotificationText.Kind.Message, profile.firstName, profile.portrait, matchID, muted, mapped.previewText,
            )
        }
    }

    private fun channelsChanged(list: List<Channel>) {
        // Left or frozen since (match ended): not shown, even before `my_matches` says so.
        listed = list.filter { !it.frozen && it.membership != null }.associateBy { it.id }
        rebuild()
    }

    // SDK models into the shared snapshots

    /** Every channel snapshot built again (list, open chats, mutes, typing), then the chats published. */
    private fun rebuild() {
        val built = mutableMapOf<String, ChatChannel>()
        for ((id, channel) in listed) built[id] = snapshot(channel)
        for ((id, chat) in chats) {
            val state = chat.state ?: continue
            built[id] = snapshot(state.toChannel(), typing = state.typing.value.users.map { it.id }.toSet())
            threads.history[id] = chat.messages.map(::snapshot)
        }
        // An open chat keeps its channel even once it's left the list (the thread on screen stays).
        threads.channels.clear()
        threads.channels.putAll(built)
        threads.publish()
    }

    private fun snapshot(
        channel: Channel,
        typing: Set<String> = typingChannels[channel.cid]?.users.orEmpty().map { it.id }.toSet(),
    ): ChatChannel {
        val unread = channel.unreadCount ?: 0
        return ChatChannel(
            id = channel.id,
            isFrozen = channel.frozen,
            isMuted = channel.cid in mutedChannels,
            unreadMessages = unread,
            isUnread = unread > 0,
            latestMessages = channel.messages.map(::snapshot),
            lastActiveMembers = channel.members
                .sortedByDescending { it.user.lastActive?.time ?: 0L }
                .map { ChatChannel.Member(it.user.id, it.user.online) },
            currentlyTypingUserIDs = typing,
            reads = channel.read.map { read ->
                ChatChannel.Read(
                    userID = read.user.id,
                    lastReadAt = read.lastRead?.toInstant() ?: Instant.EPOCH,
                    // Stream's Android read state carries no delivery time: "delivered" isn't known here.
                    lastDeliveredAt = null,
                )
            },
        )
    }

    private fun snapshot(m: StreamMessage, depth: Int = 0): ChatMessage = ChatMessage(
        id = m.id,
        type = m.type,
        text = m.text,
        extra = ChatPayload.extra(m.extraData["drafft"]),
        media = m.attachments.firstNotNullOfOrNull { a ->
            if (a.type == ChatPayload.Media.TYPE) ChatPayload.Media.decode(a.extraData) else null
        },
        createdAt = date(m),
        authorID = m.user.id,
        isSentByCurrentUser = m.user.id == threads.userID,
        localState = when (m.syncStatus) {
            SyncStatus.SYNC_NEEDED, SyncStatus.AWAITING_ATTACHMENTS -> ChatMessage.LocalState.PENDING_SEND
            SyncStatus.IN_PROGRESS -> ChatMessage.LocalState.SENDING
            SyncStatus.FAILED_PERMANENTLY -> ChatMessage.LocalState.SENDING_FAILED
            else -> null
        },
        deletedAt = m.deletedAt?.toInstant(),
        isShadowed = m.shadowed,
        latestReactions = m.latestReactions.map { ChatMessage.Reaction(type = it.type, authorID = it.userId) },
        quotedMessage = if (depth == 0) m.replyTo?.let { snapshot(it, depth = 1) } else null,
    )

    private fun date(m: StreamMessage): Instant = (m.createdAt ?: m.createdLocallyAt)?.toInstant() ?: Instant.now()

    /** The SDK's messages of a chat: the open thread's history, or the list's copy. */
    private fun streamMessages(matchID: String): List<StreamMessage> =
        chats[matchID]?.messages?.takeIf { it.isNotEmpty() } ?: listed[matchID]?.messages.orEmpty()

    private fun cid(matchID: String) = "$TYPE:$matchID"

    private companion object {
        const val TYPE = "messaging"
        const val PAGE = 30
    }
}

/** The SDK's call, awaited: its value, or its error thrown. */
private suspend fun <T : Any> Call<T>.value(): T = when (val result = await()) {
    is Result.Success -> result.value
    is Result.Failure -> throw StreamException(result.value.message)
}

private class StreamException(message: String) : Exception(message)

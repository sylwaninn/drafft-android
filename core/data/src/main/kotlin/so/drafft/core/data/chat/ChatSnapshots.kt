package so.drafft.core.data.chat

import java.time.Instant

/**
 * A Stream message as the shared chat code reads it (the fields of the Stream SDK's `Message` that
 * [ChatThreads] uses). `StreamChatService` builds these from the SDK's models, so everything that turns
 * them into the app's `Message`s stays platform-neutral and compiles on the JVM.
 */
data class ChatMessage(
    val id: String,
    /** Stream's message type: `regular`, `reply`, `system`, `error`, `deleted`, `ephemeral`. */
    val type: String,
    val text: String,
    /** The `drafft` extra data, decoded. */
    val extra: ChatPayload.Extra?,
    /** The first `drafft_media` attachment, decoded. */
    val media: ChatPayload.Media?,
    val createdAt: Instant,
    val authorID: String,
    val isSentByCurrentUser: Boolean,
    /** Null once the server has it. */
    val localState: LocalState?,
    val deletedAt: Instant?,
    val isShadowed: Boolean,
    val latestReactions: List<Reaction>,
    val quotedMessage: ChatMessage?,
) {
    enum class LocalState { PENDING_SEND, SENDING, SENDING_FAILED }

    data class Reaction(val type: String, val authorID: String)

    companion object {
        /** Types never shown in a thread. */
        val hiddenTypes = setOf("system", "error", "deleted", "ephemeral")
    }
}

/** A Stream channel (one per match) as the shared chat code reads it. [id] is the match's id. */
data class ChatChannel(
    val id: String,
    val isFrozen: Boolean,
    val isMuted: Boolean,
    /** Unread messages, as Stream counts them for this account. */
    val unreadMessages: Int,
    /** Marked unread by hand on some device ("Mark as unread"). */
    val isUnread: Boolean,
    /** The last messages (the channel list's copy, 25 per chat). */
    val latestMessages: List<ChatMessage>,
    /** Members, most recently active first. */
    val lastActiveMembers: List<Member>,
    val currentlyTypingUserIDs: Set<String>,
    val reads: List<Read>,
) {
    data class Member(val id: String, val isOnline: Boolean)
    data class Read(val userID: String, val lastReadAt: Instant, val lastDeliveredAt: Instant?)
}

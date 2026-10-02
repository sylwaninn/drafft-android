package so.drafft.core.data.chat

import java.util.UUID
import so.drafft.core.data.AppModel
import so.drafft.core.model.MessageContent
import so.drafft.core.model.SessionProposal

/**
 * Chat on Stream: one connection for the account, a channel
 * per match (its id is the match's), messages mapped into `AppModel.conversations`. Sending is
 * optimistic: the bubble shows before the server has it. The Stream implementation lives in
 * src/android (`StreamChatService`, a Koin singleton); AppModel and
 * the screens only see this. Every call is made on the main thread.
 */
interface ChatService {
    /** The model it publishes into (set by `start`, and by AppModel once its matches are read). */
    var app: AppModel?

    /** The account connected (or connecting), lowercased. */
    val userID: String?

    /**
     * Signed in (launch, sign-in): connects once for the account, then keeps it up. Calling it again for
     * the same account only shows the chats again.
     */
    suspend fun start(app: AppModel)

    /**
     * Signed out or account deleted: disconnects, forgets this device's push registration and the offline
     * copy, so the next account on this phone starts empty.
     */
    suspend fun stop()

    /** Matches and sessions read again (Stream reconnected): the chats follow the matches. */
    suspend fun refresh()

    /** The device's push token (FCM): Stream pushes messages to it (provider `drafft-fcm`). */
    fun registerDevice(token: String)

    /** Rebuilds `app.conversations` from what the service holds. */
    fun publish()

    /** An invite sent from this device: its card shows at once (`SessionStore` has its row). */
    fun showPending(proposal: SessionProposal, matchID: String)

    /** The server refused it: its card goes. */
    fun dropPending(sessionID: UUID, matchID: String)

    /** A chat on screen: its whole history (older pages on demand), live, until [close]. */
    fun open(matchID: String)
    fun close(matchID: String)

    /** Scrolled to the top of a thread: the page before. */
    fun loadOlder(matchID: String)

    /**
     * Sends at once (the bubble shows before the server has it). Text goes straight to Stream; photos,
     * videos and voice messages are uploaded to drafft's bucket first, then sent with their key.
     */
    fun send(content: MessageContent, matchID: String, replyTo: String?)

    /** A message that couldn't be sent, sent again. */
    fun retry(messageID: String, matchID: String)

    /**
     * Your reaction on one of their messages (never your own), one per person: a new one replaces it,
     * the same one again removes it.
     */
    fun react(emoji: String?, messageID: String, current: String?, matchID: String)

    /** Unsend one of your messages (an upload not sent yet just stops). */
    fun delete(messageID: String, matchID: String)
    suspend fun markRead(matchID: String)

    /** "Mark as unread": from their last message, as Stream counts it (on every device). */
    fun markUnread(matchID: String)

    /** Typing: tells the other person (the SDK spaces the events out and stops them after a pause). */
    fun typing(matchID: String, text: String)
    fun toggleMute(matchID: String)

    companion object {
        /** A voice message's waveform, at most 60 bars (the attachment stays small). */
        fun compact(levels: List<Float>): List<Float> {
            if (levels.size <= 60) return levels
            val step = levels.size.toDouble() / 60
            return List(60) { levels[minOf(levels.size - 1, (it * step).toInt())] }
        }

        /** Stands for a link not signed yet (asked for, it replaces this in a moment). */
        const val UNSIGNED = "about:blank"

        /** Stream's push provider for this app's FCM credentials. */
        const val PUSH_PROVIDER = "drafft-fcm"

        /** Messages (and chats) read at a time: a chat opens on its latest page. */
        const val PAGE = 30
    }
}

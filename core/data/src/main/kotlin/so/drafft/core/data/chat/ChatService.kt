package so.drafft.core.data.chat

import java.util.UUID
import so.drafft.core.data.AppModel
import so.drafft.core.model.MessageContent
import so.drafft.core.model.SessionProposal

/**
 * Chat on Stream (Drafft/Services/Chat/ChatService.swift): one connection for the account, a channel
 * per match (its id is the match's), messages mapped into `AppModel.conversations`. Sending is
 * optimistic: the bubble shows before the server has it. The Stream implementation lives in
 * src/android (`StreamChatService`); AppModel and the screens only see this.
 */
interface ChatService {
    /** The model it publishes into, once started. */
    val app: AppModel?

    /** Connects for the signed-in account (a second call only reads matches again). */
    suspend fun start(app: AppModel)

    /** Disconnects and forgets everything (sign-out, account deletion). */
    suspend fun stop()

    /** Reads the channels again (back to the foreground, after a reconnection). */
    suspend fun refresh()

    /** The device's push token (FCM), registered with Stream for message pushes. */
    fun registerDevice(token: String)

    /** Rebuilds `app.conversations` from what the service holds. */
    fun publish()

    /** A session invite shown in the chat before the server has saved it. */
    fun showPending(proposal: SessionProposal, matchID: String)
    fun dropPending(sessionID: UUID, matchID: String)

    /** The chat on screen: its full history is watched until [close]. */
    fun open(matchID: String)
    fun close(matchID: String)
    fun loadOlder(matchID: String)

    fun send(content: MessageContent, matchID: String, replyTo: String?)

    /** A message that couldn't be sent, tapped: sent again. */
    fun retry(messageID: String, matchID: String)
    fun react(emoji: String?, messageID: String, current: String?, matchID: String)
    fun delete(messageID: String, matchID: String)
    suspend fun markRead(matchID: String)
    fun markUnread(matchID: String)
    fun typing(matchID: String, text: String)
    fun toggleMute(matchID: String)
}

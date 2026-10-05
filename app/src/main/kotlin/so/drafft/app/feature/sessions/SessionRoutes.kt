package so.drafft.app.feature.sessions

import androidx.compose.runtime.Composable
import java.util.UUID
import so.drafft.app.feature.chat.ChatRoute
import so.drafft.app.feature.chat.ChatView
import so.drafft.core.ui.navigation.LocalNavStack

/**
 * A session's page, pushed in the stack it was opened from (the Sessions tab, a person's list). [opensChat]:
 * opened from the Sessions tab, where the page offers "Open chat"; from a person's list Back already
 * returns to the chat. From the chat itself (a card, the banner) the page is a sheet over it instead.
 */
data class SessionRoute(val sessionID: UUID, val opensChat: Boolean = false)

/** Every session with one person, pushed from their chat's More menu. */
data class PersonSessionsRoute(val chatID: String, val name: String)

/**
 * The screens a tab's stack can hold once a chat or a session is open in it (Chats, Sessions): a chat, a
 * session's page, a person's sessions; anything else is the tab's own [root].
 */
@Composable
fun ChatStackScreen(route: Any, root: @Composable () -> Unit) {
    val stack = LocalNavStack.current
    when (route) {
        is ChatRoute -> ChatView(conversationID = route.chatID)
        is SessionRoute -> SessionDetailView(
            sessionID = route.sessionID,
            onOpenChat = if (route.opensChat) ({ chatID -> stack.push(ChatRoute(chatID)) }) else null,
        )
        is PersonSessionsRoute -> PersonSessionsView(chatID = route.chatID, name = route.name)
        else -> root()
    }
}

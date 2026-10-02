package so.drafft.core.data.notifications

/**
 * Where a tapped notification leads, read once from its data (a server push, a Stream message push, or
 * one of the app's own notifications). Every tap lands somewhere: a push with no place of its own, or
 * one missing what it needs, opens Discover.
 */
sealed interface PushRoute {
    /** Discover: the weekly boost, moderation news, and any push with no place of its own. */
    data object Discover : PushRoute

    /** The Likes tab: a like or a super like. */
    data object Likes : PushRoute

    /** The Sessions tab: a session cancelled along with its match (no chat left to open). */
    data object Sessions : PushRoute

    /** A chat, by its match id: a message, a match, a session proposed, answered, cancelled or coming up. */
    data class Chat(val chatID: String) : PushRoute

    /** The explanation of a refused photo. */
    data class PhotoRefusal(val mediaID: String) : PushRoute
}

/**
 * A tapped notification, parsed: its [kind] as a code (for `push_opened`), where it leads, and whether
 * it led to its own place ([routed] false when it fell back to Discover for want of a known kind or of
 * the id it needed).
 */
data class PushTap(val kind: String, val route: PushRoute, val routed: Boolean) {
    companion object {
        /**
         * The keys the backend puts in a push's data (`db-events`): `kind`, `match`, `session`, `media`.
         * Stream's message pushes carry `sender: stream.chat` and the channel (`channel_id`, `cid`), whose
         * id is the match's. The app's own notifications carry `chatID`.
         */
        fun parse(info: Map<String, String>): PushTap {
            val kind = kind(info)
            val chat = chatID(info)
            fun chat(): PushTap = if (chat != null) PushTap(kind, PushRoute.Chat(chat), true) else fallback(kind)
            return when (kind) {
                "like", "super_like" -> PushTap(kind, PushRoute.Likes, true)
                "match", "new_message", "local", "session_reminder" -> chat()
                // Cancelled with its match: no chat any more, so the Sessions tab.
                "session_cancelled" -> PushTap(kind, chat?.let(PushRoute::Chat) ?: PushRoute.Sessions, true)
                "photo_refused" -> info["media"]?.takeIf { it.isNotBlank() }
                    ?.let { PushTap(kind, PushRoute.PhotoRefusal(it), true) } ?: fallback(kind)
                // Opening the app is enough: it shows the account's current state (a hold covers the tabs).
                "weekly_boost", "moderation" -> PushTap(kind, PushRoute.Discover, true)
                // A session proposed, accepted or declined: no kind of its own yet, only its match and session.
                else -> if (info["session"] != null && chat != null) {
                    PushTap(kind, PushRoute.Chat(chat), true)
                } else {
                    fallback(kind)
                }
            }
        }

        private fun fallback(kind: String) = PushTap(kind, PushRoute.Discover, false)

        /**
         * The push's kind as a code: the server's `kind`, `new_message` for Stream's chat pushes, `local` for
         * the app's own message notifications, `session` for a session push without a kind, else `unknown`.
         */
        fun kind(info: Map<String, String>): String = when {
            !info["kind"].isNullOrBlank() -> info.getValue("kind").lowercase()
            info["sender"] == "stream.chat" -> "new_message"
            info["chatID"] != null -> "local"
            info["session"] != null -> "session"
            else -> "unknown"
        }

        /**
         * The chat a push names: the app's own name it (`chatID`), server pushes name the match, Stream's
         * name the channel (`channel_id`, or `cid` as `messaging:<id>`).
         */
        fun chatID(info: Map<String, String>): String? {
            val stream = info["sender"] == "stream.chat"
            val id = info["chatID"]
                ?: info["match"]
                ?: info["channel_id"]?.takeIf { stream }
                ?: info["cid"]?.takeIf { stream }?.substringAfter(':', missingDelimiterValue = "")
            return id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        }
    }
}

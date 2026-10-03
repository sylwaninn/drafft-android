package so.drafft.core.data.notifications

import java.time.Duration
import java.time.Instant

/**
 * Where a tapped notification leads, read once from its data (a server push, a Stream message push, or
 * one of the app's own notifications). Every tap lands somewhere: a push missing what it needs opens the
 * chat list or Discover.
 */
sealed interface PushRoute {
    /** Discover: the weekly boost, moderation news, and any push with no place of its own. */
    data object Discover : PushRoute

    /** The Likes tab: a like or a super like. */
    data object Likes : PushRoute

    /** The Sessions tab: a session cancelled or coming up whose chat can't be named. */
    data object Sessions : PushRoute

    /** The chat list: a chat push whose chat can't be named. */
    data object Chats : PushRoute

    /** Nowhere new: the screen already shows the account's state (a hold covers everything). */
    data object Current : PushRoute

    /** A chat, by its match id (a lowercased UUID: only the parser builds it from a payload). */
    data class Chat(val chatID: String) : PushRoute {
        init {
            require(chatID.isNotBlank()) { "A chat route needs a chat id" }
        }
    }

    /** The explanation of a refused photo, by its media id. */
    data class PhotoRefusal(val mediaID: String) : PushRoute {
        init {
            require(mediaID.isNotBlank()) { "A photo refusal route needs a media id" }
        }
    }
}

/**
 * What a notification was about, as sent in `push_opened`'s and `push_received`'s `kind`. The codes are
 * a fixed set shared with the iPhone app's telemetry and never renamed: a server kind outside it is
 * [UNKNOWN].
 */
enum class PushKind(val code: String) {
    MESSAGE("new_message"),
    REACTION("reaction"),
    LIKE("like"),
    SUPER_LIKE("super_like"),
    MATCH("match"),

    /** A session proposed, accepted or declined (the backend sends no `kind` for these). */
    SESSION("session"),
    SESSION_CANCELLED("session_cancelled"),
    SESSION_REMINDER("session_reminder"),
    PHOTO_REFUSED("photo_refused"),
    MODERATION("moderation"),
    WEEKLY_BOOST("weekly_boost"),

    /** The app's own notification (`NotificationService.notify`). */
    LOCAL("local"),
    UNKNOWN("unknown"),
}

/**
 * A tapped notification, parsed: what it was about ([kind]), where it leads ([route]), and whether the
 * route is only a stand-in ([fallsBack]: a chat push with no usable chat id shows the chat list, an
 * unknown kind with nothing to name it shows Discover). `push_opened`'s `routed` is true only when the
 * tap was followed to its own place.
 */
class PushTap private constructor(val kind: PushKind, val route: PushRoute, val fallsBack: Boolean = false) {
    override fun toString() = "PushTap($kind, $route, fallsBack=$fallsBack)"

    companion object {
        /**
         * The keys the backend puts in a push's data (`supabase/functions`: `db-events` for matches, likes,
         * sessions and moderation; `stream-webhook` for reactions): `kind`, `match`, `session`, `media`,
         * `tab`. Stream's message pushes carry `sender: stream.chat` and the channel (`channel_id`, `cid`),
         * whose id is the match's. The app's own notifications carry `chatID`.
         */
        fun parse(info: Map<String, String>): PushTap {
            val chat = chatID(info)
            val kind = info["kind"]?.lowercase()
            return if (kind != null) named(kind, chat, info) else unnamed(chat, info)
        }

        /** A payload with a `kind` (the backend's own pushes, the app's likes and matches). */
        private fun named(kind: String, chat: String?, info: Map<String, String>): PushTap = when (kind) {
            "like" -> PushTap(PushKind.LIKE, PushRoute.Likes)
            "super_like" -> PushTap(PushKind.SUPER_LIKE, PushRoute.Likes)
            "match" -> toChat(PushKind.MATCH, chat)
            // Cancelled because its match ended: no chat any more, so the Sessions tab.
            "session_cancelled" -> PushTap(PushKind.SESSION_CANCELLED, chat?.let(PushRoute::Chat) ?: PushRoute.Sessions)
            "session_reminder" -> PushTap(PushKind.SESSION_REMINDER, chat?.let(PushRoute::Chat) ?: PushRoute.Sessions)
            "photo_refused" -> PushTap(
                PushKind.PHOTO_REFUSED,
                text(info["media"])?.let(PushRoute::PhotoRefusal) ?: PushRoute.Current,
            )
            // Opening the app is enough: it shows the account's current state (a hold covers the tabs).
            "moderation" -> PushTap(PushKind.MODERATION, PushRoute.Discover)
            "weekly_boost" -> PushTap(PushKind.WEEKLY_BOOST, PushRoute.Discover)
            // A kind newer than this build: its chat if it names one, its tab if it says it.
            else -> unknown(chat, info)
        }

        /** A payload without a `kind`: Stream's messages, the app's own, session updates and reactions. */
        private fun unnamed(chat: String?, info: Map<String, String>): PushTap = when {
            info["sender"] == "stream.chat" -> toChat(PushKind.MESSAGE, chat)
            info["chatID"] != null -> toChat(PushKind.LOCAL, chat)
            chat == null -> unknown(null, info)
            // A session proposed, accepted or declined carries its session; a reaction only its match.
            else -> PushTap(if (info["session"] != null) PushKind.SESSION else PushKind.REACTION, PushRoute.Chat(chat))
        }

        private fun toChat(kind: PushKind, chat: String?) =
            if (chat != null) PushTap(kind, PushRoute.Chat(chat)) else PushTap(kind, PushRoute.Chats, fallsBack = true)

        private fun unknown(chat: String?, info: Map<String, String>): PushTap {
            if (chat != null) return PushTap(PushKind.UNKNOWN, PushRoute.Chat(chat))
            val tab = tab(info) ?: return PushTap(PushKind.UNKNOWN, PushRoute.Discover, fallsBack = true)
            return PushTap(PushKind.UNKNOWN, tab)
        }

        private fun tab(info: Map<String, String>): PushRoute? = when (text(info["tab"])?.lowercase()) {
            "likes" -> PushRoute.Likes
            "sessions" -> PushRoute.Sessions
            "chats" -> PushRoute.Chats
            "discover" -> PushRoute.Discover
            else -> null
        }

        private fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

        /** The push's kind as a code (`push_received`'s `kind`). */
        fun kind(info: Map<String, String>): String = parse(info).kind.code

        private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * The chat a push names, as a lowercased UUID (a payload is outside input: anything else is no
         * chat). The app's own notifications name it (`chatID`), server pushes name the match, Stream's
         * name the channel (`channel_id`, or `cid` as `messaging:<id>`).
         */
        fun chatID(info: Map<String, String>): String? {
            val stream = info["sender"] == "stream.chat"
            val candidates = listOf(
                info["chatID"],
                info["match"],
                info["channel_id"]?.takeIf { stream },
                info["cid"]?.takeIf { stream }?.substringAfter(':'),
            )
            return candidates.firstNotNullOfOrNull { text(it)?.lowercase()?.takeIf(uuid::matches) }
        }
    }
}

/**
 * A tap waiting for the tabs to be on screen: a cold launch (the session and the first reads still
 * coming), a sign-in, a moderation hold. A tap that waited longer than [LIFETIME] is dropped: taking the
 * person somewhere is then a surprise rather than an answer.
 */
class PendingPush(
    val tap: PushTap,
    private val tappedAt: Instant,
    /**
     * The signed-in account when the tap came; null when the session wasn't read yet (the tap that
     * launched the app), which binds to whoever signs in. Followed only for that same account.
     */
    val account: String? = null,
) {
    /** Waited longer than [LIFETIME]: exactly at it still counts. */
    fun isExpired(now: Instant = Instant.now()): Boolean = Duration.between(tappedAt, now) > LIFETIME

    companion object {
        val LIFETIME: Duration = Duration.ofMinutes(10)
    }
}

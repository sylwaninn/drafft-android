package so.drafft.core.data.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushTapTest {
    private val match = "6f1c2a7e-0b7d-4c55-9a3e-2f0d8c1b9e42"
    private val session = "0a9b8c7d-6e5f-4a3b-2c1d-0e9f8a7b6c5d"

    private fun parse(vararg pairs: Pair<String, String>) = PushTap.parse(mapOf(*pairs))

    @Test
    fun likesOpenLikes() {
        assertEquals(PushTap("like", PushRoute.Likes, true), parse("kind" to "like", "tab" to "likes"))
        assertEquals(PushTap("super_like", PushRoute.Likes, true), parse("kind" to "super_like", "tab" to "likes"))
    }

    @Test
    fun aMatchOpensItsChat() {
        assertEquals(PushTap("match", PushRoute.Chat(match), true), parse("kind" to "match", "match" to match))
    }

    @Test
    fun aMatchWithoutItsIdFallsBackToDiscover() {
        assertEquals(PushTap("match", PushRoute.Discover, false), parse("kind" to "match"))
    }

    @Test
    fun streamMessagesOpenTheirChannel() {
        val stream = arrayOf("sender" to "stream.chat", "type" to "message.new", "channel_type" to "messaging")
        assertEquals(PushTap("new_message", PushRoute.Chat(match), true), parse(*stream, "channel_id" to match))
        // Only the cid: the id after the channel type.
        assertEquals(PushTap("new_message", PushRoute.Chat(match), true), parse(*stream, "cid" to "messaging:$match"))
        // A template that names the match, like the server's pushes.
        assertEquals(PushTap("new_message", PushRoute.Chat(match), true), parse(*stream, "match" to match))
    }

    @Test
    fun aChannelIdOnlyCountsFromStream() {
        assertEquals(PushTap("unknown", PushRoute.Discover, false), parse("channel_id" to match))
    }

    @Test
    fun idsAreReadLowercase() {
        assertEquals(PushRoute.Chat(match), parse("kind" to "match", "match" to match.uppercase()).route)
    }

    @Test
    fun theAppsOwnNotificationsOpenTheirChat() {
        assertEquals(PushTap("local", PushRoute.Chat(match), true), parse("chatID" to match))
        assertEquals(PushTap("like", PushRoute.Likes, true), parse("kind" to "like", "chatID" to match))
    }

    @Test
    fun sessionPushesOpenTheChat() {
        assertEquals(
            PushTap("session_reminder", PushRoute.Chat(match), true),
            parse("kind" to "session_reminder", "match" to match, "session" to session),
        )
        // Proposed, accepted, declined: no kind, the match and the session.
        assertEquals(PushTap("session", PushRoute.Chat(match), true), parse("match" to match, "session" to session))
    }

    @Test
    fun aCancelledSessionOpensItsChatOrSessions() {
        assertEquals(
            PushTap("session_cancelled", PushRoute.Chat(match), true),
            parse("kind" to "session_cancelled", "match" to match, "session" to session),
        )
        // Cancelled with its match: no chat left.
        assertEquals(PushTap("session_cancelled", PushRoute.Sessions, true), parse("kind" to "session_cancelled", "session" to session))
    }

    @Test
    fun aRefusedPhotoOpensItsExplanation() {
        assertEquals(PushTap("photo_refused", PushRoute.PhotoRefusal("m1"), true), parse("kind" to "photo_refused", "media" to "m1"))
        assertEquals(PushTap("photo_refused", PushRoute.Discover, false), parse("kind" to "photo_refused"))
    }

    @Test
    fun accountNewsOpensDiscover() {
        assertEquals(PushTap("weekly_boost", PushRoute.Discover, true), parse("kind" to "weekly_boost"))
        assertEquals(PushTap("moderation", PushRoute.Discover, true), parse("kind" to "moderation"))
    }

    @Test
    fun anythingElseOpensDiscover() {
        assertEquals(PushTap("unknown", PushRoute.Discover, false), parse())
        assertEquals(PushTap("something_new", PushRoute.Discover, false), parse("kind" to "something_new"))
        // A new kind that still names a chat opens Discover too: its place isn't known yet.
        assertEquals(PushTap("something_new", PushRoute.Discover, false), parse("kind" to "something_new", "match" to match))
    }

    @Test
    fun blankIdsAreMissingIds() {
        assertNull(PushTap.chatID(mapOf("match" to "  ")))
        assertEquals(PushTap("match", PushRoute.Discover, false), parse("kind" to "match", "match" to ""))
    }
}

package so.drafft.core.data.notifications

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushTapTest {
    private val match = "6f1c2a7e-0b7d-4c55-9a3e-2f0d8c1b9e42"
    private val session = "0a9b8c7d-6e5f-4a3b-2c1d-0e9f8a7b6c5d"

    private fun parse(vararg pairs: Pair<String, String>) = PushTap.parse(mapOf(*pairs))

    private fun assertTap(kind: PushKind, route: PushRoute, fallsBack: Boolean, tap: PushTap) {
        assertEquals(kind, tap.kind)
        assertEquals(route, tap.route)
        assertEquals(fallsBack, tap.fallsBack)
    }

    @Test
    fun likesOpenLikes() {
        assertTap(PushKind.LIKE, PushRoute.Likes, false, parse("kind" to "like", "tab" to "likes"))
        assertTap(PushKind.SUPER_LIKE, PushRoute.Likes, false, parse("kind" to "super_like", "tab" to "likes"))
    }

    @Test
    fun aMatchOpensItsChat() {
        assertTap(PushKind.MATCH, PushRoute.Chat(match), false, parse("kind" to "match", "match" to match))
    }

    @Test
    fun aMatchWithoutItsIdOpensTheChatListAsAFallback() {
        assertTap(PushKind.MATCH, PushRoute.Chats, true, parse("kind" to "match"))
        assertTap(PushKind.MATCH, PushRoute.Chats, true, parse("kind" to "match", "match" to ""))
        assertTap(PushKind.MATCH, PushRoute.Chats, true, parse("kind" to "match", "match" to "not-a-uuid"))
    }

    @Test
    fun aReactionPushCarriesOnlyItsMatchAndOpensTheChat() {
        assertTap(PushKind.REACTION, PushRoute.Chat(match), false, parse("match" to match))
    }

    @Test
    fun streamMessagesOpenTheirChannel() {
        val stream = arrayOf("sender" to "stream.chat", "type" to "message.new", "channel_type" to "messaging")
        assertTap(PushKind.MESSAGE, PushRoute.Chat(match), false, parse(*stream, "channel_id" to match))
        // Only the cid: the id after the channel type.
        assertTap(PushKind.MESSAGE, PushRoute.Chat(match), false, parse(*stream, "cid" to "messaging:$match"))
        // A cid without the channel type is the id itself.
        assertTap(PushKind.MESSAGE, PushRoute.Chat(match), false, parse(*stream, "cid" to match))
        // A template that names the match, like the server's pushes.
        assertTap(PushKind.MESSAGE, PushRoute.Chat(match), false, parse(*stream, "match" to match))
        // No usable id: the chat list.
        assertTap(PushKind.MESSAGE, PushRoute.Chats, true, parse(*stream, "cid" to "messaging:nope"))
    }

    @Test
    fun aChannelIdOnlyCountsFromStream() {
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("channel_id" to match))
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("cid" to "messaging:$match"))
    }

    @Test
    fun idsAreReadLowercaseAndOnlyUuidsCount() {
        assertEquals(PushRoute.Chat(match), parse("kind" to "match", "match" to match.uppercase()).route)
        assertEquals(PushRoute.Chat(match), parse("kind" to "match", "match" to " $match ").route)
        assertNull(PushTap.chatID(mapOf("match" to "  ")))
        assertNull(PushTap.chatID(mapOf("match" to "1-1-1-1-1")))
        // The first usable candidate wins over an earlier unusable one.
        assertEquals(match, PushTap.chatID(mapOf("chatID" to "junk", "match" to match)))
    }

    @Test
    fun theAppsOwnNotificationsOpenTheirChat() {
        assertTap(PushKind.LOCAL, PushRoute.Chat(match), false, parse("chatID" to match))
        assertTap(PushKind.LOCAL, PushRoute.Chats, true, parse("chatID" to "x"))
        assertTap(PushKind.LIKE, PushRoute.Likes, false, parse("kind" to "like", "chatID" to match))
    }

    @Test
    fun sessionPushesOpenTheChat() {
        assertTap(
            PushKind.SESSION_REMINDER, PushRoute.Chat(match), false,
            parse("kind" to "session_reminder", "match" to match, "session" to session),
        )
        // Proposed, accepted, declined: no kind, the match and the session.
        assertTap(PushKind.SESSION, PushRoute.Chat(match), false, parse("match" to match, "session" to session))
    }

    @Test
    fun aSessionReminderWithoutItsChatOpensSessions() {
        assertTap(PushKind.SESSION_REMINDER, PushRoute.Sessions, false, parse("kind" to "session_reminder", "session" to session))
    }

    @Test
    fun aCancelledSessionOpensItsChatOrSessions() {
        assertTap(
            PushKind.SESSION_CANCELLED, PushRoute.Chat(match), false,
            parse("kind" to "session_cancelled", "match" to match, "session" to session),
        )
        // Cancelled with its match: no chat left.
        assertTap(PushKind.SESSION_CANCELLED, PushRoute.Sessions, false, parse("kind" to "session_cancelled", "session" to session))
        assertTap(
            PushKind.SESSION_CANCELLED, PushRoute.Sessions, false,
            parse("kind" to "session_cancelled", "match" to "  ", "session" to session),
        )
    }

    @Test
    fun aRefusedPhotoOpensItsExplanation() {
        assertTap(PushKind.PHOTO_REFUSED, PushRoute.PhotoRefusal("m1"), false, parse("kind" to "photo_refused", "media" to "m1"))
        // Without its media: nothing to show, the screen stays.
        assertTap(PushKind.PHOTO_REFUSED, PushRoute.Current, false, parse("kind" to "photo_refused"))
        assertTap(PushKind.PHOTO_REFUSED, PushRoute.Current, false, parse("kind" to "photo_refused", "media" to "  "))
    }

    @Test
    fun accountNewsOpensDiscover() {
        assertTap(PushKind.WEEKLY_BOOST, PushRoute.Discover, false, parse("kind" to "weekly_boost"))
        assertTap(PushKind.MODERATION, PushRoute.Discover, false, parse("kind" to "moderation"))
    }

    @Test
    fun kindsAreReadInAnyCase() {
        assertTap(PushKind.MATCH, PushRoute.Chat(match), false, parse("kind" to "MATCH", "match" to match))
        assertTap(PushKind.WEEKLY_BOOST, PushRoute.Discover, false, parse("kind" to "Weekly_Boost"))
    }

    @Test
    fun anUnrecognisedKindIsUnknownAndOpensWhatItNames() {
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse())
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("kind" to "something_new"))
        assertEquals("unknown", PushTap.kind(mapOf("kind" to "something_new")))
        // Its chat, if it names one.
        assertTap(PushKind.UNKNOWN, PushRoute.Chat(match), false, parse("kind" to "something_new", "match" to match))
        // Its tab, if it says one.
        assertTap(PushKind.UNKNOWN, PushRoute.Likes, false, parse("kind" to "something_new", "tab" to "likes"))
        assertTap(PushKind.UNKNOWN, PushRoute.Sessions, false, parse("tab" to "Sessions"))
        assertTap(PushKind.UNKNOWN, PushRoute.Chats, false, parse("tab" to "chats"))
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, false, parse("tab" to "discover"))
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("tab" to "settings"))
        // A session without a usable match has no chat to open.
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("kind" to "something_new", "session" to session))
    }

    @Test
    fun aKindOnlyTheServerHasIsNeverTakenFromAPush() {
        // `new_message`, `reaction`, `session` and `local` are derived from the payload's shape, not named.
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("kind" to "new_message"))
        assertTap(PushKind.UNKNOWN, PushRoute.Chat(match), false, parse("kind" to "local", "match" to match))
    }

    @Test
    fun aKindWithTrailingWhitespaceIsUnknown() {
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("kind" to "weekly_boost "))
        assertTap(PushKind.UNKNOWN, PushRoute.Chat(match), false, parse("kind" to "match\n", "match" to match))
    }

    @Test
    fun aBlankKindIsAnUnrecognisedOne() {
        assertTap(PushKind.UNKNOWN, PushRoute.Discover, true, parse("kind" to ""))
        assertTap(PushKind.UNKNOWN, PushRoute.Chat(match), false, parse("kind" to "  ", "match" to match))
    }

    @Test
    fun everyKindCodeIsDistinctAndFixed() {
        val codes = PushKind.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(
            setOf(
                "match", "like", "super_like", "weekly_boost", "moderation", "photo_refused", "session_cancelled",
                "session_reminder", "new_message", "reaction", "session", "local", "unknown",
            ),
            codes.toSet(),
        )
    }

    @Test
    fun routesNeedTheirIds() {
        assertFailsWith<IllegalArgumentException> { PushRoute.Chat(" ") }
        assertFailsWith<IllegalArgumentException> { PushRoute.PhotoRefusal("") }
    }

    @Test
    fun aTapExpiresAfterTenMinutes() {
        val tapped = Instant.parse("2026-01-01T10:00:00Z")
        val pending = PendingPush(parse("kind" to "weekly_boost"), tapped)
        assertFalse(pending.isExpired(tapped))
        assertFalse(pending.isExpired(tapped.plusSeconds(10 * 60)))
        assertTrue(pending.isExpired(tapped.plusSeconds(10 * 60).plusMillis(1)))
    }
}

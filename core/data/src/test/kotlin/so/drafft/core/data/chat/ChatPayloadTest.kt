package so.drafft.core.data.chat

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import so.drafft.core.data.chat.ChatPayload.Kind

class ChatPayloadTest {
    private fun extra(json: String): ChatPayload.Extra = ChatPayload.json.decodeFromString(json)

    @Test
    fun plainMessageIsText() {
        assertEquals(Kind.Text("See you at 7?"), ChatPayload.kind(text = "See you at 7?", extra = null))
    }

    @Test
    fun sessionProposalShowsItsCard() {
        val id = UUID.randomUUID()
        val proposed = extra("""{"type":"session","sessionId":"${id.toString().lowercase()}","status":"proposed"}""")
        assertEquals(Kind.Session(id), ChatPayload.kind(text = "Proposed a session", extra = proposed))
    }

    @Test
    fun sessionLaterStatusesAreHidden() {
        // The card shows the status; the server's English line never shows in the thread.
        for (status in listOf("accepted", "declined", "cancelled")) {
            val e = extra("""{"type":"session","sessionId":"${UUID.randomUUID().toString().uppercase()}","status":"$status"}""")
            assertEquals(Kind.Hidden, ChatPayload.kind(text = "Accepted the session", extra = e))
        }
        val broken = extra("""{"type":"session","sessionId":"nope"}""")
        assertEquals(Kind.Hidden, ChatPayload.kind(text = "x", extra = broken))
    }

    @Test
    fun openers() {
        val ice = extra("""{"type":"icebreakerReply","quote":"I never fell","reply":"Lie!"}""")
        assertEquals(Kind.IcebreakerReply(quote = "I never fell", reply = "Lie!"), ChatPayload.kind(text = "Lie!", extra = ice))
        val photo = extra("""{"type":"photoReply","key":"u/a/photos/1.jpg","reply":"Nice"}""")
        assertEquals(Kind.PhotoReply(key = "u/a/photos/1.jpg", reply = "Nice"), ChatPayload.kind(text = "Nice", extra = photo))
        val note = extra("""{"type":"superLikeNote"}""")
        assertEquals(Kind.Text("Run Sunday?"), ChatPayload.kind(text = "Run Sunday?", extra = note))
    }

    @Test
    fun unknownKindFallsBackToItsText() {
        val e = extra("""{"type":"poll"}""")
        assertEquals(Kind.Text("Vote"), ChatPayload.kind(text = "Vote", extra = e))
        assertEquals(Kind.Hidden, ChatPayload.kind(text = "", extra = e))
    }

    @Test
    fun mediaAttachmentCarriesTheKeyNeverALink() {
        val media = ChatPayload.Media(
            kind = ChatPayload.Media.Kind.VIDEO, key = "u/abc/chat/1.mp4", width = 720, height = 1280, duration = 12.0,
            posterKey = "u/abc/chat/2.jpg",
        )
        val json = ChatPayload.json.encodeToJsonElement(media) as JsonObject
        assertEquals(JsonPrimitive("u/abc/chat/1.mp4"), json["key"])
        assertEquals(JsonPrimitive("u/abc/chat/2.jpg"), json["poster_key"])
        assertEquals(JsonPrimitive("video"), json["kind"])
        assertFalse(json.values.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.startsWith("http") == true })
        assertEquals(media, ChatPayload.json.decodeFromJsonElement<ChatPayload.Media>(json))
        assertEquals(listOf("u/abc/chat/1.mp4", "u/abc/chat/2.jpg"), media.keys)
    }

    @Test
    fun mediaSurvivesTheChatSdkUntypedMap() {
        // The SDK hands custom fields back as a map with doubles for every number.
        val media = ChatPayload.Media(
            kind = ChatPayload.Media.Kind.VOICE, key = "u/abc/chat/3.m4a", duration = 4.5, levels = listOf(0.25f, 1f),
        )
        val untyped: Map<String, Any?> = media.extraData().mapValues { (_, v) ->
            when (v) {
                is Number -> v.toDouble()
                is List<*> -> v.map { (it as Number).toDouble() }
                else -> v
            }
        }
        assertEquals(media, ChatPayload.Media.decode(untyped))
        val photo = mapOf("kind" to "photo", "key" to "u/abc/chat/1.jpg", "width" to 720.0, "height" to 1280.0)
        assertEquals(720, ChatPayload.Media.decode(photo)?.width)
        assertEquals(null, ChatPayload.Media.decode(mapOf("kind" to "gif", "key" to "x")))
    }

    @Test
    fun extraFromTheChatSdkMap() {
        val raw = mapOf("type" to "photoReply", "key" to "u/a/photos/1.jpg", "reply" to "Nice", "extra" to 1.0)
        assertEquals(Kind.PhotoReply("u/a/photos/1.jpg", "Nice"), ChatPayload.kind("Nice", ChatPayload.extra(raw)))
        assertEquals(null, ChatPayload.extra("not an object"))
    }

    @Test
    fun ownChatKey() {
        assertTrue(ChatPayload.Media.isOwnChatKey("u/abc/chat/1.jpg", userID = "ABC"))
        assertFalse(ChatPayload.Media.isOwnChatKey("u/abc/photos/1.jpg", userID = "abc"))
        assertFalse(ChatPayload.Media.isOwnChatKey("u/xyz/chat/1.jpg", userID = "abc"))
        assertFalse(ChatPayload.Media.isOwnChatKey("u/abc/chat/../x.jpg", userID = "abc"))
    }

    @Test
    fun compactKeepsAtMostSixtyBars() {
        assertEquals(listOf(0.1f, 0.2f), ChatService.compact(listOf(0.1f, 0.2f)))
        val many = List(240) { it / 240f }
        val compact = ChatService.compact(many)
        assertEquals(60, compact.size)
        assertEquals(0f, compact.first())
        assertEquals(many[236], compact.last())
    }
}

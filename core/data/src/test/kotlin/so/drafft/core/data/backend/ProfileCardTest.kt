package so.drafft.core.data.backend

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import so.drafft.core.data.media.MediaPreviews

// The cards `discover`, `liked_me`, `my_matches` and `get_cards` send, decoded as the server shapes
// them (`private.rebuild_card` and `private.sign_card` in drafft-backend).
class ProfileCardTest {
    private val id = "0B7C1E2A-6D3F-4C55-9E7A-1F2B3C4D5E6F"

    private fun card(extra: String = ""): String = """
        {"id": "$id", "name": "Maya", "gender": "woman", "pronouns": "she/her", "neighborhood": "Belleville",
         "bio": "Slow long runs.", "goal": "First 50k", "favoriteSpot": "Buttes-Chaumont",
         "vitals": {"drinks": "Socially", "smokes": "Never", "diet": "", "chronotype": "Early bird"},
         "icebreaker": {"kind": "joke", "setup": "Why?", "punchline": "Because."},
         "voiceIntro": {"key": "u/${id.lowercase()}/voice/a.m4a", "url": "https://media.example/u/v?exp=1&sig=x",
                        "duration": 12.5, "levels": [0.1, 0.5]},
         "media": [
           {"id": "m1", "kind": "photo", "key": "u/x/photos/1.jpg", "url": "https://media.example/u/x/photos/1.jpg?exp=1&sig=a",
            "width": 1080, "height": 1440, "thumbhash": null, "duration": null, "posterKey": null, "posterUrl": null},
           {"id": "m2", "kind": "video", "key": "u/x/videos/2.mp4", "url": null},
           {"id": "m3", "kind": "photo", "key": "u/x/photos/3.jpg"}
         ],
         "sports": [{"sport": "trail", "perWeek": 2}, {"sport": "yoga", "perWeek": 1}],
         "prompts": [{"question": "My ideal Sunday session", "answer": "25k, then brunch."}],
         "age": 29, "cardVersion": 7$extra}
    """.trimIndent()

    private fun bytes(text: String) = text.encodeToByteArray()

    @Test
    fun decodesADiscoverCard() {
        val cards = ProfileCard.list(bytes("[${card(""", "distanceKm": 3, "superLikedMe": true, "superLikeNote": "Run?"""")}]"))
        assertEquals(1, cards.size)
        val c = assertNotNull(cards.firstOrNull())
        assertEquals(id.lowercase(), c.id, "ids are compared lowercased")
        assertEquals("Maya", c.name)
        assertEquals(29, c.age)
        assertEquals(3, c.distanceKm)
        assertTrue(c.superLikedMe)
        assertEquals("Run?", c.superLikeNote)
        assertEquals(7, c.cardVersion)
        assertEquals(listOf("trail", "yoga"), c.sports.map { it.sport })
        assertEquals("25k, then brunch.", c.prompts.firstOrNull()?.answer)
        assertEquals("joke", c.icebreaker?.kind)
        assertEquals("Because.", c.icebreaker?.punchline)
        assertEquals("Early bird", c.vitals?.chronotype)
        assertEquals(12.5, c.voiceIntro?.duration)
        assertTrue(c.isShowable)
    }

    @Test
    fun photoLinksPreferTheSignedURLAndSkipVideos() {
        val c = assertNotNull(ProfileCard.list(bytes("[${card()}]")).firstOrNull())
        assertEquals(
            listOf("https://media.example/u/x/photos/1.jpg?exp=1&sig=a", "https://media.example/u/x/photos/3.jpg"),
            c.photoLinks(base = "https://media.example"),
        )
        // Without a base (no signed link, no media URL): only the signed ones.
        assertEquals(listOf("https://media.example/u/x/photos/1.jpg?exp=1&sig=a"), c.photoLinks(base = null))
    }

    @Test
    fun photoSizeGivesItsProportions() {
        val c = assertNotNull(ProfileCard.list(bytes("[${card()}]")).firstOrNull())
        assertEquals(1080, c.media.first().width)
        assertEquals(1440, c.media.first().height)
        c.profile(mediaBase = null)
        assertEquals(0.75, MediaPreviews.aspect("https://media.example/u/x/photos/1.jpg?exp=2&sig=b"))
    }

    @Test
    fun missingOptionalFieldsHaveDefaults() {
        val c = assertNotNull(ProfileCard.list(bytes("""[{"id": "abc", "name": "Sam", "media": [], "age": null}]""")).firstOrNull())
        assertEquals("", c.bio)
        assertNull(c.age, "get_cards sends a null age for an unfinished sign-up")
        assertFalse(c.superLikedMe)
        assertEquals(0, c.cardVersion)
        assertFalse(c.isShowable, "no age and no photo: never shown")
    }

    @Test
    fun aMalformedIcebreakerOnlyHidesTheIcebreaker() {
        val c = assertNotNull(ProfileCard.list(bytes("[${card().replace("\"kind\": \"joke\"", "\"kind\": 3")}]")).firstOrNull())
        assertNull(c.icebreaker)
        assertEquals("Maya", c.name)
    }

    @Test
    fun oneBadCardNeverEmptiesTheList() {
        assertEquals(1, ProfileCard.list(bytes("[${card()}, {\"name\": \"no id\"}]")).size)
    }

    @Test
    fun decodesLikedMe() {
        val likes = LikeCard.list(bytes("[${card(""", "superLikedMe": false, "opener": {"kind": "text", "text": "Hi"}, "likedAt": "2026-09-28T10:00:00+00:00"""")}]"))
        assertEquals("Maya", likes.firstOrNull()?.card?.name)
        assertEquals("2026-09-28T10:00:00+00:00", likes.firstOrNull()?.likedAt)
    }

    @Test
    fun decodesMyMatches() {
        val rows = MatchRow.list(
            bytes(
                """[{"matchId": "AA11BB22-0000-4000-8000-000000000001", "matchedAt": "2026-09-28T10:00:00.123+00:00",
                  "profile": ${card()}}]""",
            ),
        )
        assertEquals("aa11bb22-0000-4000-8000-000000000001", rows.firstOrNull()?.matchId)
        assertEquals(id.lowercase(), rows.firstOrNull()?.profile?.id)
    }

    // Deck merge

    @Test
    fun mergeKeepsTheCardsOnScreenAndDropsStaleOnes() {
        val merged = DeckMerge.merge(current = listOf("a", "b", "c", "d", "e"), fresh = listOf("x", "b", "a", "y", "e"), keep = 4, exclude = emptySet())
        // a and b stay on top (still available), c and d are gone (not in the fresh batch), then the
        // server's order.
        assertEquals(listOf("a", "b", "x", "y", "e"), merged)
    }

    @Test
    fun mergeNeverShowsASwipeStillOnItsWay() {
        val merged = DeckMerge.merge(current = listOf("a", "b"), fresh = listOf("a", "b", "c"), keep = 4, exclude = setOf("a"))
        assertEquals(listOf("b", "c"), merged)
    }
}

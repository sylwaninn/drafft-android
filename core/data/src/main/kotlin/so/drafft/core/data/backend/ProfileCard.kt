package so.drafft.core.data.backend

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Ports Drafft/Services/Backend/ProfileCard.swift.

/**
 * Someone else's profile as the server sends it: a `profile_cards` row (`private.rebuild_card`)
 * plus what each function adds (`discover`, `liked_me`, `my_matches`, `get_cards`). Plain Kotlin, so
 * the decoding is tested on its own (ProfileCardTest).
 *
 * Media links are signed by the server for the viewer and expire after about an hour: a card is
 * never kept longer than that (`DeckCache.MAX_AGE`), and images are cached by object, not by link.
 */
data class ProfileCard(
    val id: String,
    val name: String,
    val gender: String?,
    val pronouns: String?,
    val neighborhood: String,
    val bio: String,
    val goal: String,
    val favoriteSpot: String,
    val vitals: Lifestyle?,
    val icebreaker: IcebreakerRow?,
    val voiceIntro: Voice?,
    val media: List<Media>,
    val sports: List<SportRow>,
    val prompts: List<Prompt>,
    /** Null from `get_cards` for someone who liked the viewer before finishing sign-up. */
    val age: Int?,
    /** `discover` only: at least 1. */
    val distanceKm: Int?,
    val superLikedMe: Boolean,
    val superLikeNote: String?,
    /** Bumped by the server each time the card changes. */
    val cardVersion: Long,
) {
    data class Lifestyle(val drinks: String?, val smokes: String?, val diet: String?, val chronotype: String?)

    /** `profiles.icebreaker`: `{ "kind": "joke", "setup": ..., "punchline": ... }`, fields by kind. */
    data class IcebreakerRow(
        val kind: String,
        val statements: List<String>? = null,
        val lieIndex: Int? = null,
        val setup: String? = null,
        val punchline: String? = null,
        val text: String? = null,
        val question: String? = null,
        val options: List<String>? = null,
        val pick: Int? = null,
        val answer: Int? = null,
    )

    data class Voice(val key: String, val url: String?, val duration: Double?, val levels: List<Float>?)

    data class Media(
        val id: String,
        val kind: String,
        val key: String,
        /** Signed for this viewer; null when the server may not sign it (then nothing can open it). */
        val url: String?,
        /** Blurred preview shown while the photo loads (`MediaPreviews`). */
        val thumbhash: String?,
    )

    data class SportRow(val sport: String, val perWeek: Int)

    data class Prompt(val question: String, val answer: String)

    /** Photo links in the profile's order (videos aside). The signed link when there is one, else
     * `base` + key (a backend from before signed links). */
    fun photoLinks(base: String?): List<String> =
        media.filter { it.kind == "photo" }.mapNotNull { m -> m.url ?: base?.let { appendingPath(it, m.key) } }

    /** Whether it can be shown: a name, an age and at least one photo. */
    val isShowable: Boolean get() = name.isNotEmpty() && age != null && media.any { it.kind == "photo" }

    companion object {
        /** One card from its JSON object; throws where Swift's decoding would. */
        fun decode(o: JsonObject): ProfileCard = ProfileCard(
            // Ids are compared as the app prints them.
            id = o.string("id").lowercase(),
            name = o.optString("name") ?: "",
            gender = o.optString("gender"),
            pronouns = o.optString("pronouns"),
            neighborhood = o.optString("neighborhood") ?: "",
            bio = o.optString("bio") ?: "",
            goal = o.optString("goal") ?: "",
            favoriteSpot = o.optString("favoriteSpot") ?: "",
            vitals = attemptOrNull {
                o.optObject("vitals")?.let {
                    Lifestyle(it.optString("drinks"), it.optString("smokes"), it.optString("diet"), it.optString("chronotype"))
                }
            },
            // A malformed icebreaker hides the icebreaker, never the whole card.
            icebreaker = attemptOrNull { o.optObject("icebreaker")?.let(::decodeIcebreaker) },
            voiceIntro = attemptOrNull {
                o.optObject("voiceIntro")?.let {
                    Voice(
                        key = it.string("key"),
                        url = it.optString("url"),
                        duration = it.optDouble("duration"),
                        levels = it.optList("levels") { e -> (e.asDouble ?: throw JsonShapeException("level")).toFloat() },
                    )
                }
            },
            media = o.optList("media") { e ->
                val m = e.requireObject()
                Media(m.string("id"), m.string("kind"), m.string("key"), m.optString("url"), m.optString("thumbhash"))
            } ?: emptyList(),
            sports = o.optList("sports") { e -> e.requireObject().let { SportRow(it.string("sport"), it.int("perWeek")) } } ?: emptyList(),
            prompts = o.optList("prompts") { e -> e.requireObject().let { Prompt(it.string("question"), it.string("answer")) } } ?: emptyList(),
            age = o.optInt("age"),
            distanceKm = o.optInt("distanceKm"),
            superLikedMe = o.optBoolean("superLikedMe") ?: false,
            superLikeNote = o.optString("superLikeNote"),
            cardVersion = o.optLong("cardVersion") ?: 0,
        )

        private fun decodeIcebreaker(o: JsonObject) = IcebreakerRow(
            kind = o.string("kind"),
            statements = o.optList("statements") { it.requireString() },
            lieIndex = o.optInt("lieIndex"),
            setup = o.optString("setup"),
            punchline = o.optString("punchline"),
            text = o.optString("text"),
            question = o.optString("question"),
            options = o.optList("options") { it.requireString() },
            pick = o.optInt("pick"),
            answer = o.optInt("answer"),
        )

        /** Decodes a list, skipping any element that doesn't decode (one odd card never empties a deck). */
        fun list(from: ByteArray): List<ProfileCard> = lenient(from.jsonArray()) { decode(it.requireObject()) }

        fun list(from: JsonElement): List<ProfileCard> =
            lenient(from.asArray ?: throw JsonShapeException("not an array")) { decode(it.requireObject()) }
    }
}

/** `liked_me`: a card plus the like itself. */
data class LikeCard(val card: ProfileCard, val likedAt: String?) {
    companion object {
        fun decode(o: JsonObject) = LikeCard(ProfileCard.decode(o), o.optString("likedAt"))

        fun list(from: ByteArray): List<LikeCard> = lenient(from.jsonArray()) { decode(it.requireObject()) }
    }
}

/** `my_matches`: `{ matchId, matchedAt, profile }`. */
data class MatchRow(val matchId: String, val matchedAt: String, val profile: ProfileCard) {
    companion object {
        fun decode(o: JsonObject) = MatchRow(
            matchId = o.string("matchId").lowercase(),
            matchedAt = o.string("matchedAt"),
            profile = ProfileCard.decode(o.obj("profile")),
        )

        fun list(from: ByteArray): List<MatchRow> = lenient(from.jsonArray()) { decode(it.requireObject()) }
    }
}

/** Each element that decodes; one that doesn't is left out instead of failing the whole array. */
private fun <T> lenient(array: List<JsonElement>, decode: (JsonElement) -> T): List<T> =
    array.mapNotNull { attemptOrNull { decode(it) } }

/** Merging a fresh batch into the deck on screen. */
object DeckMerge {
    /**
     * The server's fresh order, with the first `keep` cards on screen left in place when they're still
     * in it (the stack doesn't reshuffle under the person's thumb), and without anything swiped
     * meanwhile (`exclude`: swipes still on their way to the server). A card on screen that the fresh
     * batch no longer has is dropped: paused, blocked, swiped on another device, or no longer
     * eligible. No duplicates.
     */
    fun <ID> merge(current: List<ID>, fresh: List<ID>, keep: Int, exclude: Set<ID>): List<ID> {
        val available = fresh.toSet()
        val seen = exclude.toMutableSet()
        val out = mutableListOf<ID>()
        for (id in current.take(keep)) if (id in available && seen.add(id)) out += id
        for (id in fresh) if (seen.add(id)) out += id
        return out
    }
}

/** `base` + "/" + `key`, like `URL.appendingPathComponent`. */
internal fun appendingPath(base: String, key: String): String = base.trimEnd('/') + "/" + key.trimStart('/')

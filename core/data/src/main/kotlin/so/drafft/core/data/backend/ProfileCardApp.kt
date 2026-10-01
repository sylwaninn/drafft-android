package so.drafft.core.data.backend

import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import so.drafft.core.data.media.MediaPreviews
import so.drafft.core.data.media.MediaURL
import so.drafft.core.model.Audience
import so.drafft.core.model.DiscoverFilters
import so.drafft.core.model.Icebreaker
import so.drafft.core.model.MessageContent
import so.drafft.core.model.Profile
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.model.SessionProposal
import so.drafft.core.model.Sport
import so.drafft.core.model.SportEntry
import so.drafft.core.model.Vitals

// Ports Drafft/Services/Backend/ProfileCard+App.swift: cards from the server as the app's `Profile`,
// and what the discovery calls send.

/** The profile the screens show. `base`: the media base for a backend from before signed links. */
fun ProfileCard.profile(mediaBase: String?): Profile {
    // Each photo's blurred preview, shown while it loads.
    for (m in media) MediaPreviews.register(m.thumbhash, key = m.key, width = m.width, height = m.height)
    val photos = photoLinks(mediaBase)
    var lifestyle = Vitals(
        drinks = vitals?.drinks ?: "", smokes = vitals?.smokes ?: "",
        diet = vitals?.diet ?: "", chronotype = vitals?.chronotype ?: "",
    )
    if (!lifestyle.hasLifestyle) lifestyle = Vitals.blank
    return Profile(
        id = id,
        name = name,
        age = age ?: 18,
        pronouns = pronouns,
        gender = Audience.fromAnswer(gender),
        neighborhood = neighborhood,
        distanceKm = (distanceKm ?: 0).toDouble(),
        portrait = photos.firstOrNull() ?: "",
        photos = photos.drop(1),
        sports = sports.mapNotNull { s -> Sport.fromId(s.sport)?.let { SportEntry(it, s.perWeek) } },
        voiceIntro = voiceIntro?.let { v -> v.url ?: mediaBase?.let { appendingPath(it, v.key) } },
        voiceDuration = voiceIntro?.duration ?: 0.0,
        icebreaker = icebreaker?.icebreaker ?: Icebreaker.Kind.TWO_TRUTHS.blank,
        favoriteSpot = favoriteSpot,
        bio = bio,
        goal = goal,
        superLikedMe = superLikedMe,
        superLikeNote = superLikeNote?.ifEmpty { null },
        vitalsOverride = lifestyle,
        promptsOverride = prompts.map { ProfilePrompt(question = it.question, answer = it.answer) },
    )
}

/** Some lifestyle answer is filled in (the iPhone's `Vitals.hasLifestyle`). */
val Vitals.hasLifestyle: Boolean
    get() = listOf(drinks, smokes, diet, chronotype).any { it.isNotEmpty() }

/** The app's icebreaker; null for a kind this version doesn't know. */
val ProfileCard.IcebreakerRow.icebreaker: Icebreaker?
    get() = when (kind) {
        "twoTruths" -> Icebreaker.TwoTruths(statements ?: emptyList(), lieIndex ?: -1)
        "joke" -> Icebreaker.Joke(setup ?: "", punchline ?: "")
        "hotTake" -> Icebreaker.HotTake(text ?: "")
        "thisOrThat" -> Icebreaker.ThisOrThat(question ?: "", options ?: emptyList(), pick ?: -1)
        "guess" -> Icebreaker.Guess(question ?: "", options ?: emptyList(), answer ?: -1)
        else -> null
    }

/**
 * `discover`'s `p_filters` (docs/matching.md, Filters): the last stops of the sliders ("50+ km",
 * "60+") and Everyone send nothing, so the server applies no limit or the person's own preferences.
 */
val DiscoverFilters.serverFilters: JsonObject
    get() {
        val f = linkedMapOf<String, Any?>("minAge" to ages.first)
        if (!anyDistance) f["maxDistanceKm"] = maxDistanceKm.toInt()
        if (ages.last < DiscoverFilters.ageBounds.last) f["maxAge"] = ages.last
        val genders = when (audience) {
            Audience.WOMEN -> listOf("woman")
            Audience.MEN -> listOf("man")
            Audience.NON_BINARY -> listOf("nonbinary")
            Audience.EVERYONE -> emptyList()
        }
        if (genders.isNotEmpty()) f["audience"] = genders
        if (sports.isNotEmpty()) f["sports"] = sports.map { it.id }.sorted()
        if (sharedSportsOnly) f["sharedSportsOnly"] = true
        return f.toJsonElement() as JsonObject
    }

/**
 * The first message a like carries (`swipes.opener`), posted in the chat if it becomes a match. A
 * session becomes a real session proposal. Null for content a like can't carry.
 */
val MessageContent.opener: JsonObject?
    get() = when (this) {
        is MessageContent.Text -> jsonOf("kind" to "text", "text" to text)
        is MessageContent.IcebreakerReply -> jsonOf("kind" to "icebreakerReply", "quote" to quote, "reply" to reply)
        is MessageContent.PhotoReply -> {
            // The photo's object key, never its signed link (it expires).
            val o = linkedMapOf<String, Any?>("kind" to "photoReply", "reply" to reply)
            MediaURL.key(asset)?.let { o["key"] = it }
            o.toJsonElement() as JsonObject
        }
        is MessageContent.Session -> {
            val s = proposal
            val o = linkedMapOf<String, Any?>(
                "kind" to "session", "sport" to s.sport.id,
                "options" to s.options.map { DateTimeFormatter.ISO_INSTANT.format(it.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)) },
                "title" to s.title, "note" to s.note, "tags" to s.tags,
            )
            when (s.discovery) {
                SessionProposal.Discovery.I_TEACH -> o["discovery"] = "iTeach"
                SessionProposal.Discovery.THEY_TEACH -> o["discovery"] = "theyTeach"
                null -> Unit
            }
            o.toJsonElement() as JsonObject
        }
        is MessageContent.Photo, is MessageContent.Video, is MessageContent.Voice, is MessageContent.File -> null
    }

package so.drafft.core.data.backend

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import so.drafft.core.data.AccountHold
import so.drafft.core.data.media.EdgeFunctionTicketProvider
import so.drafft.core.data.media.MediaPreviews
import so.drafft.core.data.media.MediaURL
import so.drafft.core.data.media.MediaUploads
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.notifications.NotificationSettings
import so.drafft.core.data.platform.Coordinate
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.Audience
import so.drafft.core.model.Icebreaker
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.model.Sport
import so.drafft.core.model.SportEntry
import so.drafft.core.model.Vitals

// Ports Drafft/Services/Backend/ProfileSync.swift (MediaURL, also defined there on the iPhone, is in
// so.drafft.core.data.media).

/**
 * The signed-in person's own profile on the server: sign-up sends everything it collected and
 * opens the profile (`complete_onboarding`), Edit profile saves changes, and launch reads it back.
 * Photos go through `PhotoModeration` (upload, register, moderation) as soon as they're picked;
 * this only waits for them and puts them in order.
 */
class ProfileSync(
    private val backend: Backend,
    private val photoModeration: PhotoModeration,
) {
    sealed class SyncError : Exception() {
        /** A photo couldn't be sent (its tile says why). */
        object PhotoUpload : SyncError() {
            private fun readResolve(): Any = PhotoUpload
        }

        /** The server refused: its hint code (`underage`, `photo_required`...). */
        class Refused(val code: String) : SyncError()

        /** The profile on the server wasn't read in this session: saving could overwrite it. */
        object NotLoaded : SyncError() {
            private fun readResolve(): Any = NotLoaded
        }

        override val message: String
            get() = when (this) {
                PhotoUpload -> L("A photo couldn't be sent. Tap it to see why, then try again.")
                NotLoaded -> L("Your profile hasn't loaded, so nothing was saved. Close and try again.")
                is Refused -> message(code)
            }

        companion object {
            fun message(code: String): String = ServerMessage.text(forCode = code) ?: ServerMessage.generic
        }
    }

    /**
     * The account whose server profile was read (or sent by sign-up) in this session. Edit profile
     * only saves over that one: never over a profile it didn't see.
     */
    @Volatile
    var loadedAccount: UUID? = null

    /** A recorded voice intro: the .m4a file, its length in seconds and its waveform. */
    data class Voice(val url: String, val duration: Double, val levels: List<Float>)

    // Sign-up

    data class SignUp(
        val name: String,
        val birthday: Instant,
        val gender: String?,
        val interestedIn: Set<String>,
        val neighborhood: String,
        val location: Coordinate?,
        val bio: String,
        val lifestyle: Vitals,
        val icebreaker: Icebreaker?,
        val sports: List<SportEntry>,
        val prompts: List<ProfilePrompt>,
        val photos: List<String>,
        val voice: Voice?,
        val language: AppLanguage,
    )

    /** Everything sign-up collected, then `complete_onboarding`, which checks the essentials. */
    suspend fun finish(s: SignUp) {
        val fields = linkedMapOf<String, Any?>(
            "name" to s.name,
            "birthdate" to day(s.birthday),
            "interested_in" to if ("Everyone" in s.interestedIn) emptyList() else s.interestedIn.mapNotNull(::genderValue),
            "neighborhood" to s.neighborhood,
            "bio" to s.bio,
            "language" to s.language.code,
        )
        s.gender?.let(::genderValue)?.let { fields["gender"] = it }
        fields.putAll(vitalsFields(s.lifestyle))
        s.icebreaker?.let { fields["icebreaker"] = it.json }
        s.voice?.let { fields.putAll(uploadVoice(it)) }
        // Independent writes go out together (one round trip of waiting, not five), then
        // `complete_onboarding` checks the result.
        coroutineScope {
            listOf(
                async { updateProfile(fields.toJsonElement() as JsonObject) },
                async { setSports(s.sports) },
                async { setPrompts(s.prompts) },
                async { syncPhotos(s.photos) },
                async { setLocation(s.location) },
            ).awaitAll()
        }
        try {
            backend.rpc("complete_onboarding")
        } catch (e: Exception) {
            throw refused(e)
        }
        loadedAccount = backend.userID
    }

    // Edit profile

    /** Saves Edit profile's changes (the birthday stays as set at sign-up). */
    suspend fun save(p: Profile, previous: Profile, voice: Voice?) {
        requireLoaded()
        val fields = linkedMapOf<String, Any?>(
            "name" to p.name,
            "bio" to p.bio,
            "goal" to p.goal,
            "favorite_spot" to p.favoriteSpot,
            "icebreaker" to if (p.icebreaker.isComplete) p.icebreaker.json else JsonNull,
        )
        fields.putAll(vitalsFields(p.vitals ?: Vitals.blank))
        voice?.let { fields.putAll(uploadVoice(it)) }
        // Only what changed, all at once.
        val sportsChanged = p.sports != previous.sports
        val promptsChanged = p.prompts != previous.prompts
        val photosChanged = p.allPhotos != previous.allPhotos
        coroutineScope {
            listOf(
                async { updateProfile(fields.toJsonElement() as JsonObject) },
                async { if (sportsChanged) setSports(p.sports) },
                async { if (promptsChanged) setPrompts(p.prompts) },
                async { if (photosChanged) syncPhotos(p.allPhotos, loadedFirst = true) },
            ).awaitAll()
        }
    }

    /** Throws unless this session read the signed-in account's profile from the server. */
    private fun requireLoaded() {
        val id = backend.userID
        if (id == null || loadedAccount != id) throw SyncError.NotLoaded
    }

    // Read back

    /**
     * Everything the app shows of the account's own profile row, read in one request: the
     * profile (with its sports, prompts and photos), the pause, a moderation hold, whether sign-up
     * is finished, and the notification settings. One read, shared by every screen that needs it
     * (instead of one read per piece); it's also what the local cache keeps.
     */
    data class Account(
        val profile: Profile,
        val paused: Boolean,
        val hold: AccountHold?,
        val onboarded: Boolean,
        val notifications: NotificationSettings?,
    )

    /**
     * The account as saved on the server (a new device, a reinstall, another device's changes), in
     * one request with its sports, prompts and media embedded. Photos are the approved and pending
     * ones (their own), as signed links (the bucket is private). Also returns the raw bytes, for the
     * local cache: they hold keys only, never a link, so a cached copy never carries an expired one.
     */
    suspend fun loadAccount(): Pair<Account, ByteArray>? {
        val id = backend.userID ?: return null
        val data = backend.select(
            "profiles?id=eq.$id&select=$accountColumns" +
                "&profile_sports.order=position&profile_prompts.order=position&profile_media.order=position",
        )
        val keys = mediaKeys(data)
        val signed = MediaURL.signed(keys)
        // A backend from before signed links: the public base URL + key.
        val base = if (signed.size == keys.size) MediaURL.saved else MediaURL.base()
        val account = decodeAccount(data, mediaBase = base, signed = signed) ?: return null
        loadedAccount = id
        return account to data
    }

    companion object {
        private val accountColumns = listOf(
            "name", "birthdate", "pronouns", "gender", "neighborhood", "bio", "goal", "favorite_spot",
            "drinks", "smokes", "diet", "chronotype", "icebreaker", "voice_intro_key", "voice_duration",
            "paused", "moderation", "onboarded_at", NotificationSettings.columns,
            "profile_sports(sport_id,per_week)", "profile_prompts(question,answer)", "profile_media(id,key,kind,status,thumbhash)",
        ).joinToString(",")

        private class MediaRow(val id: String, val key: String, val kind: String, val status: String, val thumbhash: String?)

        /** The row as the server sends it; the local cache stores these bytes as they are. */
        private class AccountRow(o: JsonObject) {
            val name = o.string("name")
            val birthdate = o.optString("birthdate")
            val pronouns = o.optString("pronouns")
            val gender = o.optString("gender")
            val neighborhood = o.string("neighborhood")
            val bio = o.string("bio")
            val goal = o.string("goal")
            val favoriteSpot = o.string("favorite_spot")
            val drinks = o.string("drinks")
            val smokes = o.string("smokes")
            val diet = o.string("diet")
            val chronotype = o.string("chronotype")
            val icebreaker: JsonElement? = o["icebreaker"]?.takeIf { it !is JsonNull }
            val voiceIntroKey = o.optString("voice_intro_key")
            val voiceDuration = o.optDouble("voice_duration")
            val paused = o.boolean("paused")
            // An unknown hold fails the row, as the iPhone's decoding does.
            val moderation = o.optString("moderation")?.let { raw ->
                AccountHold.entries.firstOrNull { it.rawValue == raw } ?: throw JsonShapeException("moderation")
            }
            val onboardedAt = o.optString("onboarded_at")
            val sports = o.optList("profile_sports") { e -> e.requireObject().let { it.string("sport_id") to it.int("per_week") } }
            val prompts = o.optList("profile_prompts") { e -> e.requireObject().let { ProfilePrompt(it.string("question"), it.string("answer")) } }
            val media = o.optList("profile_media") { e ->
                e.requireObject().let { MediaRow(it.string("id"), it.string("key"), it.string("kind"), it.string("status"), it.optString("thumbhash")) }
            }
        }

        private fun row(data: ByteArray): Pair<AccountRow, JsonObject>? = attemptOrNull {
            val o = data.jsonArray().firstOrNull()?.requireObject() ?: return@attemptOrNull null
            AccountRow(o) to o
        }

        /** The media keys a row shows (photos not refused, the voice intro), to sign in one request. */
        private fun mediaKeys(data: ByteArray): List<String> {
            val (row) = row(data) ?: return emptyList()
            return (row.media ?: emptyList()).filter { it.kind == "photo" && it.status != "rejected" }.map { it.key } +
                listOfNotNull(row.voiceIntroKey)
        }

        /**
         * The account from the server's bytes (a fresh read, or the copy in the local cache). Media links:
         * the signed one for a key when there is one, else the base URL + key (the cached copy at launch:
         * photos then come from the image cache, keyed by object, until the fresh read signs them).
         */
        fun decodeAccount(data: ByteArray, mediaBase: String?, signed: Map<String, String> = emptyMap()): Account? {
            val (row, obj) = row(data) ?: return null
            fun link(key: String): String? = signed[key] ?: mediaBase?.let { appendingPath(it, key) }
            // Each photo's blurred preview, shown while it loads.
            for (m in row.media ?: emptyList()) MediaPreviews.register(m.thumbhash, key = m.key)
            val photos = (row.media ?: emptyList()).filter { it.kind == "photo" && it.status != "rejected" }.map { it.key }
                .mapNotNull(::link)
            val birthday = row.birthdate?.let(::parseDay)
            val age = birthday?.let {
                Period.between(it.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()).years
            } ?: 18
            var vitals = Vitals(drinks = row.drinks, smokes = row.smokes, diet = row.diet, chronotype = row.chronotype)
            if (!vitals.hasLifestyle) vitals = Vitals.blank
            val profile = Profile(
                id = "me",
                name = row.name,
                age = age,
                pronouns = row.pronouns,
                birthday = birthday,
                gender = Audience.fromAnswer(row.gender),
                neighborhood = row.neighborhood,
                distanceKm = 0.0,
                portrait = photos.firstOrNull() ?: "",
                photos = photos.drop(1),
                sports = (row.sports ?: emptyList()).mapNotNull { (id, perWeek) -> Sport.fromId(id)?.let { SportEntry(it, perWeek) } },
                voiceIntro = row.voiceIntroKey?.let(::link),
                voiceDuration = row.voiceDuration ?: 0.0,
                icebreaker = row.icebreaker?.let(::icebreakerFromJson) ?: Icebreaker.Kind.TWO_TRUTHS.blank,
                favoriteSpot = row.favoriteSpot,
                bio = row.bio,
                goal = row.goal,
                vitalsOverride = vitals,
                promptsOverride = row.prompts ?: emptyList(),
            )
            return Account(
                profile = profile, paused = row.paused, hold = row.moderation, onboarded = row.onboardedAt != null,
                notifications = attemptOrNull { DrafftJson.decodeFromJsonElement(NotificationSettings.serializer(), obj) },
            )
        }

        private fun vitalsFields(v: Vitals): Map<String, Any?> =
            mapOf("drinks" to v.drinks, "smokes" to v.smokes, "diet" to v.diet, "chronotype" to v.chronotype)

        /** Sign-up's gender answers (kept in English) as the server's values. */
        private fun genderValue(answer: String): String? = when (answer) {
            "Woman", "Women" -> "woman"
            "Man", "Men" -> "man"
            "Non-binary", "Non-binary people" -> "nonbinary"
            else -> null
        }

        /** Server errors carry a stable code in `hint`: turned into the message people see. */
        private fun refused(error: Exception): Exception = ServerMessage.code(error)?.let { SyncError.Refused(it) } ?: error

        /** yyyy-MM-dd, in UTC (a birthday is midnight UTC). */
        private fun day(date: Instant): String = date.atOffset(ZoneOffset.UTC).toLocalDate().toString()

        private fun parseDay(text: String): Instant? =
            attemptOrNull { LocalDate.parse(text.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }
    }

    // Pieces

    private suspend fun updateProfile(fields: JsonObject) {
        try {
            backend.updateMyProfile(fields)
        } catch (e: Exception) {
            throw refused(e)
        }
    }

    /** Best effort: the area can be set again later from the app. */
    private suspend fun setLocation(c: Coordinate?) {
        c ?: return
        attempt { backend.rpc("set_location", jsonOf("p_lat" to c.latitude, "p_lng" to c.longitude)) }
    }

    private suspend fun setSports(sports: List<SportEntry>) {
        val list = sports.map { mapOf("sport" to it.sport.id, "perWeek" to it.perWeek) }
        try {
            backend.rpc("set_sports", jsonOf("p_sports" to list))
        } catch (e: Exception) {
            throw refused(e)
        }
    }

    private suspend fun setPrompts(prompts: List<ProfilePrompt>) {
        val list = prompts.filter { it.answer.trim().isNotEmpty() }.take(3).map { mapOf("question" to it.question, "answer" to it.answer) }
        try {
            backend.rpc("set_prompts", jsonOf("p_prompts" to list))
        } catch (e: Exception) {
            throw refused(e)
        }
    }

    /**
     * Waits for the picked photos to reach the server, drops the ones taken off the profile, and
     * puts the rest in the profile's order. Photos already on the server (URLs) keep their id.
     * From Edit profile (`loadedFirst`), only once the server's profile was read in this session:
     * otherwise the list could be another profile's, and every photo missing from it would go.
     */
    private suspend fun syncPhotos(photos: List<String>, loadedFirst: Boolean = false) {
        if (loadedFirst) requireLoaded()
        // Only picked files and server URLs: anything else isn't a photo of this account.
        if (!photos.all { it.isEmpty() || it.startsWith("/") || it.startsWith("http") }) throw SyncError.NotLoaded
        for (path in photos) if (path.startsWith("/")) {
            photoModeration.waitForID(path) ?: throw SyncError.PhotoUpload
        }
        val me = backend.userID ?: return
        val onServer = backend.select("profile_media?user_id=eq.$me&select=id,key&order=position").jsonArray()
            .map { e -> e.requireObject().let { it.string("id") to it.string("key") } }
        // Photos loaded from the server are URLs (signed: the key is their path): matched by their key.
        val order = photos.mapNotNull { path ->
            if (path.startsWith("http")) {
                val key = MediaURL.key(path)
                onServer.firstOrNull { it.second == key }?.first
            } else {
                photoModeration.id(path)
            }
        }
        // Photos taken off the profile: removed together rather than one after the other.
        coroutineScope {
            for ((id) in onServer) if (id !in order) {
                launch { attempt { backend.rpc("delete_media", jsonOf("p_id" to id)) } }
            }
        }
        if (order.size > 1) {
            try {
                backend.rpc("reorder_media", jsonOf("p_ids" to order))
            } catch (e: Exception) {
                throw refused(e)
            }
        }
    }

    /** Uploads a voice intro (.m4a) and returns the profile columns that point to it. */
    private suspend fun uploadVoice(voice: Voice): Map<String, Any?> {
        val key = MediaUploads.voice(voice.url, tickets = tickets)
        val fields = linkedMapOf<String, Any?>("voice_intro_key" to key, "voice_duration" to minOf(60.0, voice.duration))
        if (voice.levels.isNotEmpty()) fields["voice_levels"] = voice.levels.take(200).map { it.toDouble() }
        return fields
    }

    private val tickets: EdgeFunctionTicketProvider
        get() = EdgeFunctionTicketProvider(functionsURL = backend.config.functionsURL, accessToken = { backend.accessToken() })
}

// Icebreaker on the server

/** The `profiles.icebreaker` shape: `{ "kind": "joke", "setup": ..., "punchline": ... }`. */
val Icebreaker.json: JsonObject
    get() = when (this) {
        is Icebreaker.TwoTruths -> jsonOf("kind" to "twoTruths", "statements" to statements, "lieIndex" to lieIndex)
        is Icebreaker.Joke -> jsonOf("kind" to "joke", "setup" to setup, "punchline" to punchline)
        is Icebreaker.HotTake -> jsonOf("kind" to "hotTake", "text" to text)
        is Icebreaker.ThisOrThat -> jsonOf("kind" to "thisOrThat", "question" to question, "options" to options, "pick" to pick)
        is Icebreaker.Guess -> jsonOf("kind" to "guess", "question" to question, "options" to options, "answer" to answer)
    }

/** The icebreaker from its `profiles.icebreaker` JSON, or null (no kind, or one this version doesn't know). */
fun icebreakerFromJson(value: JsonElement): Icebreaker? {
    val o = value as? JsonObject ?: return null
    val kind = o["kind"].asString ?: return null
    fun str(k: String): String = o[k].asString ?: ""
    fun int(k: String): Int = o[k].asDouble?.toInt() ?: -1
    fun strings(k: String): List<String> = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
    return when (kind) {
        "twoTruths" -> Icebreaker.TwoTruths(statements = strings("statements"), lieIndex = int("lieIndex"))
        "joke" -> Icebreaker.Joke(setup = str("setup"), punchline = str("punchline"))
        "hotTake" -> Icebreaker.HotTake(str("text"))
        "thisOrThat" -> Icebreaker.ThisOrThat(question = str("question"), options = strings("options"), pick = int("pick"))
        "guess" -> Icebreaker.Guess(question = str("question"), options = strings("options"), answer = int("answer"))
        else -> null
    }
}

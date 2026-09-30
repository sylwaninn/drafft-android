package so.drafft.core.data

import java.io.File
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.DrafftJson
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.KeyValueStore

// Ports Drafft/Services/OnboardingStore.swift.

/**
 * Sign-up progress saved as it goes, so an unfinished sign-up resumes where it stopped. Kept on
 * this device, under the account it belongs to: another account signing in here never sees it.
 */
@Serializable
data class OnboardingProgress(
    val name: String = "",
    val language: String? = null,
    @Serializable(with = InstantSerializer::class) val birthday: Instant? = null,
    /**
     * The terms version recorded on the server with the consent (`TermsConsent`), null until then.
     * It alone says the consent was given: progress saved before it (with an `acceptedTerms` flag,
     * now ignored) still decodes, and the rules step asks again.
     */
    val termsVersion: String? = null,
    val verifiedPhone: String? = null,
    val identity: String? = null,
    val interestedIn: List<String> = emptyList(),
    val area: String? = null,
    val sports: List<SavedSport> = emptyList(),
    val photos: List<String> = emptyList(),
    val voicePath: String? = null,
    val voiceDuration: Double = 0.0,
    val prompts: List<SavedPrompt> = emptyList(),
    val bio: String = "",
    /** Rhythm, food, drinking, smoking ("" when not answered). */
    val lifestyle: List<String> = emptyList(),
    /** Furthest step reached. */
    val furthest: Int = 0,
) {
    @Serializable
    data class SavedSport(val sport: String, val perWeek: Int)

    @Serializable
    data class SavedPrompt(val question: String, val answer: String)
}

/** An instant as ISO-8601 text. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

class OnboardingStore(
    private val defaults: KeyValueStore,
    private val backend: Backend,
    private val info: AppInfo,
) {
    /** One entry per account id; nothing without a signed-in account. */
    private val key: String?
        get() {
            // The old device-wide entry could belong to anyone: dropped, never handed to an account.
            defaults.remove("onboarding.progress.v2")
            return backend.userID?.let { "onboarding.progress.v3.${it.toString().lowercase()}" }
        }

    fun load(): OnboardingProgress? {
        val text = key?.let(defaults::getString) ?: return null
        return runCatching { DrafftJson.decodeFromString(OnboardingProgress.serializer(), text) }.getOrNull()
    }

    fun save(p: OnboardingProgress) {
        val key = key ?: return
        defaults.putString(key, DrafftJson.encodeToString(OnboardingProgress.serializer(), p))
    }

    /** Clears the signed-in account's progress. */
    fun clear() {
        key?.let(defaults::remove)
    }

    val hasUnfinished: Boolean get() = load() != null

    /** Photos picked during sign-up live in temp; keep them somewhere that survives relaunches. */
    fun persist(photo: String): String {
        val temp = info.cacheDir.absolutePath
        if (!photo.startsWith(temp)) return photo
        val dir = File(info.filesDir, "SignUpPhotos")
        if (!dir.isDirectory && !dir.mkdirs()) return photo
        val dest = File(dir, File(photo).name)
        if (!dest.exists()) runCatching { File(photo).copyTo(dest) }
        return dest.path
    }
}

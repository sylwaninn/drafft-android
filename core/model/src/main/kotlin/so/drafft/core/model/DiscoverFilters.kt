package so.drafft.core.model

import kotlinx.serialization.Serializable

/** Who to meet. `rawValue` is the stable identity (saved, compared); [title] is shown. */
@Serializable
enum class Audience(val rawValue: String) {
    WOMEN("Women"), MEN("Men"), NON_BINARY("Non-binary people"), EVERYONE("Everyone");

    /** Shown in the app's language. */
    val title: String
        get() = when (this) {
            WOMEN -> L("Women")
            MEN -> L("Men")
            NON_BINARY -> L("Non-binary people")
            EVERYONE -> L("Everyone")
        }

    companion object {
        /** A gender as sign-up asks it ("Woman") or the server stores it ("woman"). */
        fun fromAnswer(answer: String?): Audience? = when (answer?.lowercase()) {
            "woman", "women" -> WOMEN
            "man", "men" -> MEN
            "non-binary", "nonbinary", "non-binary people" -> NON_BINARY
            else -> null
        }

        fun fromRaw(raw: String?): Audience? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Who to show in Discover. Empty sets mean "any". Only the sports and "In common" are filters (the
 * badge, "Clear filters"); distance, age and who you want to meet are the search itself. Kept on the
 * phone between launches ([encode] / [decode], stored by AppModel).
 */
@Serializable
data class DiscoverFilters(
    val maxDistanceKm: Double = 10.0,
    val minAge: Int = 25,
    val maxAge: Int = 40,
    val audience: Audience = Audience.EVERYONE,
    /** Sport ids. */
    val sportIds: Set<String> = emptySet(),
    val sharedSportsOnly: Boolean = false,
) {
    val ages: IntRange get() = minAge..maxAge
    val sports: Set<Sport> get() = sportIds.mapNotNull(Sport::fromId).toSet()

    fun withAges(range: IntRange) = copy(minAge = range.first, maxAge = range.last)
    fun withSports(sports: Set<Sport>) = copy(sportIds = sports.map { it.id }.toSet())

    val anyDistance: Boolean get() = maxDistanceKm >= ANY_DISTANCE

    /** "Up to 10 km", or "Any distance" at the last stop. */
    val distanceLabel: String get() = if (anyDistance) L("Any distance") else L("Up to %d km", maxDistanceKm.toInt())

    /** Short form for pills: "10 km" or "50+ km". */
    val distanceShort: String get() = if (anyDistance) L("50+ km") else L("%d km", maxDistanceKm.toInt())

    /** Clears the filters (sports, "In common"), keeping distance, age and who you want to meet. */
    fun cleared() = copy(sportIds = emptySet(), sharedSportsOnly = false)

    /** Filters in use, for the badge: the sports and "In common" only. */
    val activeCount: Int get() = listOf(sportIds.isNotEmpty(), sharedSportsOnly).count { it }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val ageBounds = 18..60
        val distanceBounds = 1.0..50.0

        /** The slider's last stop: "50+", meaning no distance limit. */
        const val ANY_DISTANCE = 51.0

        const val STORAGE_KEY = "discoverFilters"
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** The saved search, or the defaults. */
        fun decode(saved: String?): DiscoverFilters =
            saved?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: DiscoverFilters()

        /** The person's own gender; without one (an unfinished sign-up), their pronouns. */
        fun audienceOf(p: Profile): Audience = p.gender ?: when (p.pronouns) {
            "she/her" -> Audience.WOMEN
            "he/him" -> Audience.MEN
            else -> Audience.NON_BINARY
        }
    }
}

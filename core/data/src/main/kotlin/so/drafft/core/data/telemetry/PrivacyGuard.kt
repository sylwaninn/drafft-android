package so.drafft.core.data.telemetry

/**
 * The last check before anything goes to Sentry or PostHog. drafft holds sensitive data (gender, who
 * someone wants to meet, lifestyle answers: they can reveal orientation, health or beliefs, GDPR
 * article 9) and private conversations: none of it is ever a property, a tag or a log line.
 *
 * - Property names on the [forbidden] list are dropped, whatever the event.
 * - Values are numbers, booleans, or short codes (`like`, `so.drafft.app.boost.5`, `discover`): a
 *   string with spaces, capitals or punctuation is something a person typed and is dropped.
 * - Free text that must go (a log line, an error message) is [scrub]bed of emails, phone numbers,
 *   ids, tokens and exact coordinates.
 *
 * A dropped property is logged (and so reaches Sentry's logs); in unit tests it throws ([strict]), so
 * the mistake shows where it's made.
 */
object PrivacyGuard {
    /** Set by unit tests: a forbidden property is a bug to fix, not to hide. */
    @Volatile
    var strict = false

    private val log = java.util.logging.Logger.getLogger("so.drafft.telemetry")

    /** Never sent, whatever the event: the person's identity, sensitive data, content, location. */
    val forbidden: Set<String> = setOf(
        // Identity and contact
        "name", "first_name", "last_name", "email", "phone", "phone_number", "birthday", "birthdate", "age",
        "address", "ip",
        // Sensitive data (article 9): gender and who someone wants to meet reveal orientation
        "gender", "identity", "interested_in", "show_me", "orientation", "pronouns",
        "lifestyle", "diet", "drinks", "smokes", "chronotype", "religion", "health",
        // What people write or record
        "bio", "message", "text", "note", "opener", "prompt", "answer", "caption", "report_text", "body",
        // Location
        "location", "latitude", "longitude", "lat", "lng", "lon", "coordinates", "neighborhood", "area", "city",
        // Another person
        "target", "target_id", "profile_id", "other_user", "match_id", "chat_id",
        // Secrets
        "token", "password", "code", "otp", "access_token", "refresh_token",
    )

    private val slug = Regex("^[a-z0-9][a-z0-9_.:\\-]{0,79}$")

    /** The properties that may go: allowed names, safe values. [event] names the offender in strict mode. */
    fun properties(event: String, properties: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(properties.size)
        for ((key, raw) in properties) {
            val value = raw ?: continue
            val safe = allowed(value)
            val problem = when {
                key.lowercase() in forbidden -> "forbidden property"
                !slug.matches(key) -> "property name isn't snake_case"
                safe == null -> "value isn't a number, a boolean or a short code"
                else -> null
            }
            if (problem == null && safe != null) {
                out[key] = safe
            } else if (strict) {
                throw IllegalArgumentException("$event.$key: $problem")
            } else {
                log.warning("$event.$key dropped: $problem")
            }
        }
        return out
    }

    /** A short code (`daily_like_limit`), not words. */
    fun isCode(text: String): Boolean = slug.matches(text)

    private fun allowed(value: Any): Any? = when (value) {
        is Boolean, is Int, is Long, is Short, is Byte -> value
        is Double -> value.takeIf { it.isFinite() }
        is Float -> value.toDouble().takeIf { it.isFinite() }
        is Enum<*> -> value.name.lowercase()
        is CharSequence -> value.toString().takeIf(slug::matches)
        is Collection<*> -> value.mapNotNull { it?.let(::allowed) }.takeIf { it.size == value.size && it.size <= 20 }
        else -> null
    }

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    // International (+33 6 12 34 56 78) and national (06 12 34 56 78) forms; not timestamps or ids.
    private val phone = Regex("\\+\\d[\\d .()-]{6,18}\\d|\\b0\\d(?:[ .-]?\\d{2}){4}\\b")
    private val bearer = Regex("(?i)(bearer\\s+|apikey[=:]\\s*|token[=:]\\s*)[A-Za-z0-9._-]+")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val coordinates = Regex("-?\\d{1,3}\\.\\d{3,}\\s*,\\s*-?\\d{1,3}\\.\\d{3,}")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val query = Regex("\\?[^\\s]*")

    /** Free text with what identifies someone taken out. Query strings go too (they carry ids and filters). */
    fun scrub(text: String): String = text
        .replace(jwt, "[token]")
        .replace(bearer) { it.groupValues[1] + "[token]" }
        .replace(email, "[email]")
        // Another person's id is theirs: the account's own goes with the report as its user.
        .replace(uuid, "[id]")
        .replace(coordinates, "[coordinates]")
        .replace(phone, "[phone]")
        .let { if (it.length > 2000) it.take(2000) else it }

    /** A URL or path without its query (`rest/v1/profiles?id=eq.<id>` → `rest/v1/profiles`). */
    fun path(url: String): String = url.replace(query, "")
}

package so.drafft.core.data.chat

import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull

/**
 * What drafft puts in a chat message besides its text, independent of the chat SDK (unit tested).
 *
 * - `drafft` (message extra data): the server's messages. `session` points to a row of `sessions`
 *   (the card shows its live status), `superLikeNote` is the note sent with a super like, and the
 *   openers attached to a like: `text`, `icebreakerReply` (`quote`), `photoReply` (`key`).
 * - `drafft_media` (attachment): a photo, video or voice message on drafft's media bucket. It holds the
 *   object's KEY, never a link: the bucket is private, and each device asks the backend for a signed
 *   link (`media_urls`, only between the two members of an active match) when it shows it.
 */
object ChatPayload {
    /** The shared JSON shape: absent fields for null, unknown fields ignored. */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** The `drafft` object of a message, as the backend writes it (db-events). */
    @Serializable
    data class Extra(
        val type: String,
        val sessionId: String? = null,
        val status: String? = null,
        val quote: String? = null,
        val reply: String? = null,
        val text: String? = null,
        /** photoReply: the liked photo's key. */
        val key: String? = null,
    )

    /** How a message shows in the thread. */
    sealed interface Kind {
        data class Text(val text: String) : Kind

        /** The card of a session; only its proposal is shown (its later statuses update that card). */
        data class Session(val id: UUID) : Kind
        data class IcebreakerReply(val quote: String, val reply: String) : Kind
        data class PhotoReply(val key: String, val reply: String) : Kind

        /** Not shown in the thread: a session's later status (the card says it), or something unknown. */
        data object Hidden : Kind
    }

    fun kind(text: String, extra: Extra?): Kind {
        if (extra == null) return Kind.Text(text)
        return when (extra.type) {
            "session" -> {
                val id = extra.sessionId?.let(::uuidOrNull) ?: return Kind.Hidden
                if (extra.status == null || extra.status == "proposed") Kind.Session(id) else Kind.Hidden
            }
            "icebreakerReply" -> Kind.IcebreakerReply(quote = extra.quote ?: "", reply = extra.reply ?: text)
            "photoReply" -> {
                val key = extra.key
                if (key.isNullOrEmpty()) Kind.Text(extra.reply ?: text) else Kind.PhotoReply(key = key, reply = extra.reply ?: text)
            }
            "superLikeNote", "text" -> Kind.Text(extra.text ?: text)
            // A newer kind this build doesn't know: its text, if it has one.
            else -> if (text.isEmpty()) Kind.Hidden else Kind.Text(text)
        }
    }

    /** A photo, video or voice message: the attachment's payload (type `drafft_media`). */
    @Serializable
    data class Media(
        val kind: Kind,
        /** The object's key on the media bucket (`u/<owner>/chat/<uuid>.<ext>`). */
        val key: String,
        val width: Int? = null,
        val height: Int? = null,
        /** Seconds (video, voice). */
        val duration: Double? = null,
        /** Voice: the waveform, 0...1. */
        val levels: List<Float>? = null,
        /** Video: its poster frame's key. */
        @SerialName("poster_key") val posterKey: String? = null,
        val thumbhash: String? = null,
    ) {
        @Serializable
        enum class Kind {
            @SerialName("photo") PHOTO,
            @SerialName("video") VIDEO,
            @SerialName("voice") VOICE,
        }

        /** Keys this attachment needs signed links for. */
        val keys: List<String> get() = listOfNotNull(key, posterKey)

        /** The attachment's custom fields, as the chat SDK sends them (next to `type`). */
        fun extraData(): Map<String, Any> = plain(json.encodeToJsonElement(this)) as Map<String, Any>

        companion object {
            /** The attachment type. */
            const val TYPE = "drafft_media"

            /**
             * A key the app may send: one of the person's own chat objects. Anything else is refused before
             * it's sent (and the backend only signs chat keys between members of an active match).
             */
            fun isOwnChatKey(key: String, userID: String): Boolean =
                key.startsWith("u/${userID.lowercase()}/chat/") && !key.contains("..") && key.length <= 200

            /** An attachment's custom fields (the SDK's untyped map), or null when they aren't a payload. */
            fun decode(extraData: Map<String, Any?>): Media? =
                runCatching { json.decodeFromJsonElement<Media>(toJson(extraData)) }.getOrNull()
        }
    }

    /** A message's `drafft` extra data (the SDK's untyped value), or null. */
    fun extra(raw: Any?): Extra? {
        if (raw == null) return null
        return runCatching { json.decodeFromJsonElement<Extra>(toJson(raw)) }.getOrNull()
    }

    /**
     * An untyped JSON value from the chat SDK (maps, lists, strings, numbers, booleans) as a JsonElement.
     * Numbers arrive as doubles: whole ones become integers so `width: 720.0` still reads as an Int.
     */
    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> if (value % 1.0 == 0.0 && abs(value) < 9.0e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        is Float -> toJson(value.toDouble())
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is Iterable<*> -> JsonArray(value.map(::toJson))
        is Array<*> -> JsonArray(value.map(::toJson))
        else -> JsonPrimitive(value.toString())
    }

    /** The reverse: plain maps, lists and values, for the SDK's extra data. */
    fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.entries.mapNotNull { (k, v) -> plain(v)?.let { k to it } }.toMap()
        is JsonArray -> element.map(::plain)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull
        }
    }

    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Only the canonical UUID form (Java's parser accepts "1-2-3-4-5"). */
    fun uuidOrNull(text: String): UUID? = if (uuidPattern.matches(text)) UUID.fromString(text) else null
}

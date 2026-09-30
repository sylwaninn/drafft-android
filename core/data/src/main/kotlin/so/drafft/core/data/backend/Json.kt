package so.drafft.core.data.backend

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// The JSON the app sends and reads as the signed-in person (the iPhone's `[String: Any]` and
// `JSONSerialization`), with the strictness of Swift's `Decodable` where the port needs it.

/** Lenient reader: unknown keys are fine, the server adds columns. */
val DrafftJson = Json { ignoreUnknownKeys = true }

/** A JSON object from Kotlin values (String, Number, Boolean, null, lists, maps, JSON elements). */
fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to v.toJsonElement() })

/** Any Kotlin value as JSON (the iPhone's `JSONSerialization.data(withJSONObject:)`). */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this.toDouble())
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

/** The bytes as JSON, or null if they aren't. */
fun ByteArray.parseJsonOrNull(): JsonElement? = runCatching { DrafftJson.parseToJsonElement(decodeToString()) }.getOrNull()

fun JsonElement.toBytes(): ByteArray = toString().encodeToByteArray()

val JsonElement?.asObject: JsonObject? get() = this as? JsonObject
val JsonElement?.asArray: JsonArray? get() = this as? JsonArray

/** A JSON string's text (not a number or a boolean written as text). */
val JsonElement?.asString: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
val JsonElement?.asBoolean: Boolean? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
val JsonElement?.asDouble: Double? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
val JsonElement?.asLong: Long? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
val JsonElement?.asInt: Int? get() = asLong?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

/** A value of the wrong type where Swift's `Decodable` would throw. */
class JsonShapeException(message: String) : Exception(message)

// Swift `decode` / `decodeIfPresent` on an object: missing or null gives null for the optional readers,
// a value of another type throws.

private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

fun JsonObject.optString(key: String): String? = present(key)?.let { it.asString ?: throw JsonShapeException("$key: not a string") }
fun JsonObject.optBoolean(key: String): Boolean? = present(key)?.let { it.asBoolean ?: throw JsonShapeException("$key: not a boolean") }
fun JsonObject.optDouble(key: String): Double? = present(key)?.let { it.asDouble ?: throw JsonShapeException("$key: not a number") }
fun JsonObject.optLong(key: String): Long? = present(key)?.let { it.asLong ?: throw JsonShapeException("$key: not an integer") }
fun JsonObject.optInt(key: String): Int? = present(key)?.let { it.asInt ?: throw JsonShapeException("$key: not an integer") }
fun JsonObject.optObject(key: String): JsonObject? = present(key)?.let { it.asObject ?: throw JsonShapeException("$key: not an object") }
fun <T> JsonObject.optList(key: String, element: (JsonElement) -> T): List<T>? =
    present(key)?.let { (it.asArray ?: throw JsonShapeException("$key: not an array")).map(element) }

fun JsonObject.string(key: String): String = optString(key) ?: throw JsonShapeException("$key missing")
fun JsonObject.boolean(key: String): Boolean = optBoolean(key) ?: throw JsonShapeException("$key missing")
fun JsonObject.int(key: String): Int = optInt(key) ?: throw JsonShapeException("$key missing")
fun JsonObject.obj(key: String): JsonObject = optObject(key) ?: throw JsonShapeException("$key missing")

fun JsonElement.requireObject(): JsonObject = asObject ?: throw JsonShapeException("not an object")
fun JsonElement.requireString(): String = asString ?: throw JsonShapeException("not a string")

/** An array of JSON elements, or throws (Swift decoding `[T]`). */
fun ByteArray.jsonArray(): JsonArray =
    (runCatching { DrafftJson.parseToJsonElement(decodeToString()) }.getOrNull() as? JsonArray)
        ?: throw JsonShapeException("not an array")

/**
 * Swift's `try?` for suspending work: the value, or null if it threw. Cancellation still propagates,
 * so a cancelled coroutine never carries on as if the call had merely failed.
 */
suspend inline fun <T> attempt(crossinline block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** Swift's `try?` for plain work. */
inline fun <T> attemptOrNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

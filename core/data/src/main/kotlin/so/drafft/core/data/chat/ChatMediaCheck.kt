package so.drafft.core.data.chat

import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import so.drafft.core.data.backend.Backend

/**
 * The silent moderation check of a photo or video sent in a chat, once it's on the media bucket: the
 * backend's chat-media function judges it (Rekognition; a video by its poster). Nothing changes on
 * screen; a flagged one is recorded server-side (media_flags).
 */
class ChatMediaCheck(private val backend: Backend) {
    private val log = Logger.getLogger("so.drafft.chat-media")

    @Serializable
    private data class Verdict(val flagged: Boolean)

    /** Logged: whether it was flagged, or why the check couldn't run (offline, no session). */
    suspend fun check(key: String, posterKey: String? = null) {
        try {
            val body = buildJsonObject {
                put("key", JsonPrimitive(key))
                if (posterKey != null) put("posterKey", JsonPrimitive(posterKey))
            }
            val response = backend.function("chat-media", body)
            val flagged = ChatPayload.json.decodeFromString<Verdict>(response.decodeToString()).flagged
            log.info("chat media $key: ${if (flagged) "flagged" else "clean"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(Level.WARNING, "chat media check failed: ${e.message}")
        }
    }
}

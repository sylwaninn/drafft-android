package so.drafft.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.drafft.core.data.backend.attemptOrNull
import so.drafft.core.data.backend.boolean
import so.drafft.core.data.backend.jsonArray
import so.drafft.core.data.backend.optString
import so.drafft.core.data.backend.requireObject
import so.drafft.core.data.backend.string
import so.drafft.core.model.ThumbHash

// Ports Drafft/Services/AppModel+BlurredLikes.swift.

/**
 * One like on the free plan: what the server gives without drafft tempo (`liked_me`, backend
 * 20260928000231), an opaque handle, whether it was a super like, when, and a ThumbHash of the first
 * photo. Nobody's id, name or photo reaches the phone: the blur is the server's, not a filter here.
 */
class BlurredLike(
    val id: String,
    val superLike: Boolean,
    /** About 32 x 32 px, decoded once when the list is read. Null: a sage tile stands in. */
    val preview: ThumbHash.Image?,
) {
    override fun equals(other: Any?): Boolean = other is BlurredLike && other.id == id && other.superLike == superLike
    override fun hashCode(): Int = id.hashCode() * 31 + superLike.hashCode()

    companion object {
        /**
         * `liked_me` as a free account gets it (`AppModel.loadLikes`): previews decoded off the main
         * thread. Null if it isn't that shape.
         */
        suspend fun list(from: ByteArray): List<BlurredLike>? {
            val rows = attemptOrNull {
                from.jsonArray().map { e ->
                    e.requireObject().let { Triple(it.string("likeId"), it.boolean("superLike"), it.optString("thumbhash")) }
                }
            } ?: return null
            return withContext(Dispatchers.Default) {
                rows.map { (id, superLike, hash) -> BlurredLike(id, superLike, hash?.let { ThumbHash.image(fromBase64 = it) }) }
            }
        }
    }
}

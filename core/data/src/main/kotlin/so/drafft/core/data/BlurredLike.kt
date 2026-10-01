package so.drafft.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.drafft.core.data.backend.attemptOrNull
import so.drafft.core.data.backend.boolean
import so.drafft.core.data.backend.jsonArray
import so.drafft.core.data.backend.optString
import so.drafft.core.data.backend.requireObject
import so.drafft.core.data.backend.string
import so.drafft.core.data.sessions.ServerDate
import so.drafft.core.model.ThumbHash
import so.drafft.core.model.newestFirst
import java.time.Instant

// Ports Drafft/Services/AppModel+BlurredLikes.swift.

/**
 * One like on the free plan: what the server gives without drafft tempo (`liked_me`, backend
 * 20260928000231), an opaque handle, whether it was a super like, when, a ThumbHash of the first
 * photo and, when the backend has made one, a signed link to a blurred copy of that photo
 * (`blurUrl`). Nobody's id, name or sharp photo reaches the phone: the blur is the server's, not a
 * filter here.
 */
class BlurredLike(
    val id: String,
    val superLike: Boolean,
    /** When they liked you (the server's `likedAt`). Null if it didn't send one: no age label then. */
    val likedAt: Instant?,
    /**
     * About 32 x 32 px, decoded once when the list is read. Null: a night tile stands in. Also the
     * placeholder and the fallback of [blurUrl].
     */
    val preview: ThumbHash.Image?,
    /**
     * Signed link to the server's blurred rendition of the first photo. Null from a backend that
     * doesn't send it yet, or when there is none: the ThumbHash alone.
     */
    val blurUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is BlurredLike && other.id == id && other.superLike == superLike && other.likedAt == likedAt && other.blurUrl == blurUrl
    override fun hashCode(): Int =
        ((id.hashCode() * 31 + superLike.hashCode()) * 31 + (likedAt?.hashCode() ?: 0)) * 31 + (blurUrl?.hashCode() ?: 0)

    companion object {
        /**
         * `liked_me` as a free account gets it (`AppModel.loadLikes`): previews decoded off the main
         * thread. Null if it isn't that shape.
         */
        suspend fun list(from: ByteArray): List<BlurredLike>? {
            val rows = attemptOrNull {
                from.jsonArray().map { e ->
                    e.requireObject().let { Row(
                            it.string("likeId"),
                            it.boolean("superLike"),
                            it.optString("likedAt")?.let { at -> attemptOrNull { ServerDate.parse(at) } },
                            it.optString("thumbhash"),
                            it.optString("blurUrl"),
                        ) }
                }
            } ?: return null
            return withContext(Dispatchers.Default) {
                rows.map { row ->
                    BlurredLike(
                        row.id,
                        row.superLike,
                        row.likedAt,
                        row.thumbhash?.let { ThumbHash.image(fromBase64 = it) },
                        row.blurUrl?.takeIf { it.startsWith("http") },
                    )
                }.newestFirst(date = { it.likedAt }, id = { it.id })
            }
        }
    }
}

private class Row(val id: String, val superLike: Boolean, val likedAt: Instant?, val thumbhash: String?, val blurUrl: String?)

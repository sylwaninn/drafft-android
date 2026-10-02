package so.drafft.core.data.media

// The transcoding is the platform's: Media3 Transformer in `AndroidVideoCompressor`, installed as
// [VideoCompressor.engine] at launch.

/** A video ready to upload: an MP4 file on disk, plus its poster frame (a prepared photo). */
class PreparedVideo(
    /** A file path. */
    val fileURL: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val duration: Double,
    val poster: PreparedPhoto,
) {
    val contentType: String get() = "video/mp4"
}

/**
 * Videos are transcoded on the phone before upload, so they can be preloaded whole and start instantly.
 *
 * - HEVC, 720p (long edge 1280), ~1.6 Mb/s: ~3 MB for 15 s, ~6 MB for 30 s, instead of 30–100 MB (H.264
 *   at the same bitrate on a phone without an HEVC encoder).
 * - Progressive MP4 with the index at the front: playback starts on the first bytes.
 * - HDR is tone-mapped to SDR, so it doesn't look washed out on SDR screens and in the poster.
 * - Rotation is baked into the pixels.
 */
object VideoCompressor {
    data class Settings(
        val maxLongEdge: Double = 1280.0,
        val videoBitRate: Int = 1_600_000,
        val audioBitRate: Int = 96_000,
        /** Longer sources are cut to this length (profile videos: 30 s, enforced by the backend too). */
        val maxDuration: Double? = null,
    ) {
        companion object {
            val profile = Settings(maxDuration = 30.0)
            val chat = Settings(maxDuration = 180.0)
        }
    }

    fun interface Engine {
        /** [source] is a path, or a `content://` URI. */
        suspend fun prepare(source: String, settings: Settings): PreparedVideo
    }

    @Volatile
    var engine: Engine = Engine { _, _ -> throw MediaPreparationError.UnreadableVideo }

    suspend fun prepare(source: String, settings: Settings): PreparedVideo = engine.prepare(source, settings)

    /** Even pixel sizes (encoders need them), at least 2. */
    fun even(value: Double): Int = maxOf(2, Math.round(value).toInt() and 1.inv())
}

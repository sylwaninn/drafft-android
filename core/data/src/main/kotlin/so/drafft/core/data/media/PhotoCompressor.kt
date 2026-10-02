package so.drafft.core.data.media

// The decoding and encoding is the platform's: `AndroidPhotoCompressor`, installed as
// [PhotoCompressor.engine] at launch.

/** A photo ready to upload: JPEG bytes, final pixel size and its ThumbHash. */
class PreparedPhoto(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val thumbHash: String?,
) {
    val contentType: String get() = "image/jpeg"
}

sealed class MediaPreparationError(message: String) : Exception(message) {
    data object UnreadableImage : MediaPreparationError("unreadable image") { private fun readResolve(): Any = UnreadableImage }
    data object EncodingFailed : MediaPreparationError("encoding failed") { private fun readResolve(): Any = EncodingFailed }
    data object UnreadableVideo : MediaPreparationError("unreadable video") { private fun readResolve(): Any = UnreadableVideo }
    data object NoVideoTrack : MediaPreparationError("no video track") { private fun readResolve(): Any = NoVideoTrack }
    class ExportFailed(val reason: String) : MediaPreparationError("export failed: $reason")
}

/**
 * Photos are resized and re-encoded on the phone before upload.
 *
 * - 2048 px on the long edge: sharp on a full-width 3× screen, ~0.5–1 MB instead of 3–12 MB.
 * - JPEG: decodes fastest when scrolling, and every CDN transform accepts it.
 * - Metadata is dropped (EXIF, GPS, camera): only pixels leave the phone.
 * - Orientation is applied to the pixels, so every viewer shows it upright.
 *
 * Everything runs off the main thread (the engine's job), since decoding and encoding take tens of
 * milliseconds.
 */
object PhotoCompressor {
    const val maxPixelSize = 2048
    const val quality = 0.8

    interface Engine {
        suspend fun prepare(data: ByteArray, maxPixelSize: Int, quality: Double): PreparedPhoto
        suspend fun prepare(fileAt: String, maxPixelSize: Int, quality: Double): PreparedPhoto
        suspend fun savePicked(data: ByteArray): String?
    }

    @Volatile
    var engine: Engine = object : Engine {
        override suspend fun prepare(data: ByteArray, maxPixelSize: Int, quality: Double): PreparedPhoto =
            throw MediaPreparationError.UnreadableImage
        override suspend fun prepare(fileAt: String, maxPixelSize: Int, quality: Double): PreparedPhoto =
            throw MediaPreparationError.UnreadableImage
        override suspend fun savePicked(data: ByteArray): String? = null
    }

    suspend fun prepare(data: ByteArray, maxPixelSize: Int = this.maxPixelSize, quality: Double = this.quality): PreparedPhoto =
        engine.prepare(data, maxPixelSize, quality)

    /** [fileAt] is a path, or a `content://` URI. */
    suspend fun prepare(fileAt: String, maxPixelSize: Int = this.maxPixelSize, quality: Double = this.quality): PreparedPhoto =
        engine.prepare(fileAt, maxPixelSize, quality)

    /**
     * A photo just picked, as a file on this phone: at most 2048 px, upright, without metadata.
     * Everything that shows or sends it then reads this copy, never the camera's 24–48 MP original.
     * Null if it can't be read.
     */
    suspend fun savePicked(data: ByteArray): String? = engine.savePicked(data)
}
